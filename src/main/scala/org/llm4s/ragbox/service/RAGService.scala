package org.llm4s.ragbox.service

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.chunking.{ChunkerFactory, ChunkingConfig, DocumentChunk}
import org.llm4s.llmconnect.{LLMClient, LLMConnect}
import org.llm4s.llmconnect.config.{AnthropicConfig, EmbeddingProviderConfig, OpenAIConfig, ProviderConfig}
import org.llm4s.llmconnect.spi.{EmbeddingProviderSection, ProviderRegistry}
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{RAG, RAGConfig, RAGSearchResult, RAGAnswerResult, RAGStats}
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSchemaManager
import org.llm4s.types.Result
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{DocumentEntry, DocumentRegistry, PgDocumentRegistry}
import org.llm4s.ragbox.store.{ChunkStore, PgChunkStore}

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * A request the service rejects because its input is invalid, such as a malformed collection
 * path or pattern. Routes map it to 400 Bad Request.
 */
final case class InvalidRequestError(message: String) extends RuntimeException(message)

/**
 * Service wrapping llm4s RAG functionality.
 *
 * Provides a high-level interface for document ingestion, search, and query operations.
 *
 * In permission mode (a [[SearchIndex]] is configured) every document lives in an llm4s
 * collection: queries filter on `collection_id`, so a vector written without one could never be
 * retrieved. Ingests that name no collection go to [[RAGService.DefaultCollection]], and deletes
 * go through the collection the document was registered in.
 *
 * All llm4s RAG calls are synchronous (embedding and LLM HTTP calls, JDBC), so they run on the
 * blocking pool through [[blockingResult]], never on the compute pool.
 *
 * The llm4s [[RAG]] instance is built on first use and memoised only once it has been built: a
 * failure (database or embedding provider unreachable at startup, say) is not cached, so a later
 * call retries - at most once per [[RAGService.RetryCooldownMillis]] - instead of the service
 * staying broken until restart. The factories try to build it eagerly and log a failure.
 *
 * @param providerRegistry where the embedding provider is resolved; tests pass one holding a
 *                         deterministic in-process embedder
 * @param dataSource the application's shared connection pool, for the permission-mode schema
 *                   setup in [[ensureDefaultCollection]]
 */
class RAGService(
  config: AppConfig,
  documentRegistry: DocumentRegistry,
  chunkStore: ChunkStore,
  val searchIndex: Option[SearchIndex] = None,
  providerRegistry: ProviderRegistry = ProviderRegistry.default,
  dataSource: Option[DataSource] = None
) {
  import RAGService.DefaultCollection

  private given ProviderRegistry = providerRegistry

  private val startTime: Long = System.currentTimeMillis()
  private var firstIngestionTime: Option[Instant] = None
  private var lastIngestionTime: Option[Instant] = None

  /**
   * Get PrincipalStore for user/group ID management.
   * Only available when SearchIndex is configured.
   */
  def principals: Option[PrincipalStore] = searchIndex.map(_.principals)

  /**
   * Get CollectionStore for collection hierarchy management.
   * Only available when SearchIndex is configured.
   */
  def collections: Option[CollectionStore] = searchIndex.map(_.collections)

  /**
   * Check if permission-based RAG is enabled.
   */
  def hasPermissions: Boolean = searchIndex.isDefined

  // Model metadata bundled with llm4s: resolves context windows for LLM configs and is
  // required (as a given) when building LLM clients and the RAG pipeline.
  private lazy val modelRegistry: Result[ModelRegistryService] = ModelRegistryService.default()

  // Create LLM client for answer generation
  private lazy val llmClient: Option[LLMClient] = modelRegistry.toOption.flatMap { registry =>
    given ModelRegistryService = registry

    // Parse provider/model format (e.g., "openai/gpt-4o")
    val parts = config.llm.model.split("/", 2)
    val provider = if (parts.length > 1) parts(0) else "openai"
    val model = if (parts.length > 1) parts(1) else config.llm.model

    // fromValues resolves context window / completion reserve from the model registry
    val providerConfig: Option[Result[ProviderConfig]] = provider.toLowerCase match {
      case "openai" =>
        config.apiKeys.openai.map(OpenAIConfig.fromValues(model, _, OpenAIConfig.DEFAULT_BASE_URL))
      case "anthropic" =>
        config.apiKeys.anthropic.map(AnthropicConfig.fromValues(model, _, AnthropicConfig.DEFAULT_BASE_URL))
      case _ => None
    }

    providerConfig.flatMap(_.flatMap(LLMConnect.getClient).toOption)
  }

  /**
   * Embedding provider config resolver, called by llm4s with the configured embedding
   * provider id. Base URL and model defaults come from the provider's llm4s descriptor;
   * ragbox supplies the API key and model from its own configuration.
   */
  private def resolveEmbeddingProvider(providerId: String): Result[EmbeddingProviderConfig] = {
    val registry = summon[ProviderRegistry]
    val id = registry.canonicalEmbeddingId(providerId)
    for {
      descriptor <- registry.resolveEmbedding(id)
      section = EmbeddingProviderSection(
        apiKey = config.apiKeys.getKey(id.asString),
        model = Some(config.embedding.model)
      )
      providerConfig <- descriptor.buildConfig(section, modelOverride = None)
    } yield providerConfig
  }

  // Build RAG configuration
  private lazy val ragConfig: RAGConfig = {
    val baseConfig = RAGConfig.default
      .withEmbeddings(config.embedding.providerId, config.embedding.model)
      .withChunking(
        config.rag.chunking.toChunkerStrategy,
        config.rag.chunking.size,
        config.rag.chunking.overlap
      )
      .withTopK(config.rag.search.topK)
      .withFusion(config.rag.search.toFusionStrategy)
      .withSystemPrompt(config.rag.systemPrompt)

    // Configure storage: use SearchIndex if available, otherwise pgvector directly
    val withStorage = searchIndex match {
      case Some(si) =>
        // Permission-based RAG with SearchIndex. A SearchIndex over the shared pool
        // (PgSearchIndex.fromDataSource) carries no connection settings, so the pipeline's own
        // pgvector + keyword store is pointed at the same database explicitly.
        baseConfig
          .withSearchIndex(si)
          .withPgHybrid(
            connectionString = config.database.connectionString,
            user = config.database.effectiveUser,
            password = config.database.effectivePassword,
            vectorTableName = config.database.tableName,
            keywordTableName = si.pgConfig.map(_.keywordTableName).getOrElse(RAGService.KeywordTableName)
          )
      case None =>
        // Basic pgvector without permissions
        baseConfig.withPgVector(
          connectionString = config.database.connectionString,
          user = config.database.effectiveUser,
          password = config.database.effectivePassword,
          tableName = config.database.tableName
        )
    }

    // Add LLM client if available
    llmClient.fold(withStorage)(withStorage.withLLM)
  }

  // The RAG instance once built, and the last build failure (time, message) while it is not.
  private val ragRef = new AtomicReference[Option[RAG]](None)
  private val lastRagFailure = new AtomicReference[Option[(Long, String)]](None)
  private val ragLock = new Object
  @volatile private var closed = false

  private def buildRag(): Result[RAG] =
    modelRegistry.flatMap { registry =>
      given ModelRegistryService = registry
      ragConfig.build(resolveEmbeddingProvider)
    }

  /**
   * Get the underlying RAG instance, building it if it has not been built yet.
   *
   * A build failure is not memoised: the next call after [[RAGService.RetryCooldownMillis]]
   * tries again (calls within the cooldown fail fast with the last error). Builds are
   * serialised, so concurrent callers never create two instances.
   */
  def rag: IO[RAG] =
    ragRef.get() match {
      case Some(r) => IO.pure(r)
      case None =>
        IO.blocking {
          ragLock.synchronized {
            if (closed) Left("RAG service is closed")
            else
              ragRef.get() match {
                case Some(r) => Right(r)
                case None =>
                  val now = System.currentTimeMillis()
                  lastRagFailure.get() match {
                    case Some((at, message)) if now - at < RAGService.RetryCooldownMillis => Left(message)
                    case _ =>
                      scala.util.Try(buildRag()).toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString))
                        .flatMap(_.left.map(_.message)) match {
                        case Right(r) =>
                          ragRef.set(Some(r))
                          lastRagFailure.set(None)
                          Right(r)
                        case Left(message) =>
                          lastRagFailure.set(Some(now -> message))
                          Left(message)
                      }
                  }
              }
          }
        }.flatMap(result => IO.fromEither(result.left.map(new RuntimeException(_))))
    }

  /** Whether the RAG instance has been built (without trying to build it). */
  private def ragReady: Boolean = ragRef.get().isDefined

  /** Run a synchronous llm4s call on the blocking pool and lift its `Result`. */
  private def blockingResult[A](call: => Result[A]): IO[A] =
    IO.blocking(call).flatMap(result => IO.fromEither(result.left.map(e => new RuntimeException(e.message))))

  // ============================================================
  // Document Operations
  // ============================================================

  /**
   * Ingest a document with content and metadata.
   *
   * In permission mode the document goes into its `collection` metadata value, or the default
   * collection when there is none, so that permission-filtered queries can retrieve it.
   */
  def ingestDocument(
    content: String,
    documentId: String,
    metadata: Map[String, String]
  ): IO[DocumentUploadResponse] =
    if (hasPermissions) {
      ingestWithPermissions(metadata.getOrElse("collection", DefaultCollection), documentId, content, metadata)
    } else {
      val contentHash = computeContentHash(content)
      ingestAndRegister(documentId, content, contentHash, metadata, sourceId = None).map { result =>
        DocumentUploadResponse(documentId, result.chunks, s"Successfully ingested ${result.chunks} chunks")
      }
    }

  /**
   * Delete a document by ID: its vectors, its visible chunks and its registry entry.
   *
   * In permission mode the vectors are deleted from the collection the document is registered
   * in (or the default collection). `RAG.deleteDocument` cannot be used there: it deletes by the
   * `<docId>-chunk-` id prefix, while permission ingest writes `coll-<id>-<docId>-chunk-<n>`.
   */
  def deleteDocument(documentId: String): IO[Unit] = {
    val deleteVectors: IO[Unit] =
      if (hasPermissions) {
        documentRegistry.get(documentId).flatMap { entry =>
          deleteVectorsFromCollection(entry.flatMap(_.collection).getOrElse(DefaultCollection), documentId).void
        }
      } else {
        rag.flatMap(r => blockingResult(r.deleteDocument(documentId)))
      }
    deleteVectors *> chunkStore.deleteByDocument(documentId) *> documentRegistry.remove(documentId)
  }

  /**
   * Delete a document's vectors from a collection (permission mode).
   *
   * A registered collection can be a free-text label from before permission mode that names no
   * llm4s collection. Such a document's vectors were migrated into the default collection at
   * startup, so that is where they are deleted from.
   */
  private def deleteVectorsFromCollection(collection: String, documentId: String): IO[Long] =
    for {
      r <- rag
      existingPath <- CollectionPath.create(collection).toOption match {
        case Some(path) => collectionExists(path).map(exists => Option.when(exists)(path))
        case None => IO.pure(None)
      }
      path = existingPath.getOrElse(CollectionPath.unsafe(DefaultCollection))
      deleted <- blockingResult(r.deleteFromCollection(path, documentId))
    } yield deleted

  private def collectionExists(path: CollectionPath): IO[Boolean] =
    collections match {
      case Some(store) =>
        blockingResult(store.get(path).map(_.isDefined))
      case None => IO.pure(false)
    }

  /**
   * Get RAG statistics.
   * Uses document/chunk registries for persistent counts.
   */
  def getStats: IO[StatsResponse] =
    for {
      r <- rag
      stats <- blockingResult(r.stats)
      // Use registries for accurate persistent counts
      docCount <- documentRegistry.count()
      chunkCount <- chunkStore.count()
      collectionStats <- getCollectionStats
    } yield StatsResponse(
      documentCount = docCount.toInt,
      chunkCount = chunkCount,
      vectorCount = stats.vectorCount,
      collections = collectionStats
    )

  /**
   * Clear all documents.
   */
  def clearAll: IO[Unit] =
    rag.flatMap(r => blockingResult(r.clear())) *> chunkStore.clear() *> documentRegistry.clear()

  // ============================================================
  // Upsert Operations (SDK/Sidecar Pattern)
  // ============================================================

  /**
   * Compute SHA-256 hash of content for change detection.
   */
  private def computeContentHash(content: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(content.getBytes("UTF-8"))
    hashBytes.map("%02x".format(_)).mkString
  }

  /**
   * Upsert a document (idempotent create/update).
   *
   * If the document doesn't exist or content has changed, it will be indexed.
   * If the content is unchanged, the operation returns without re-indexing.
   *
   * @param documentId Unique document identifier
   * @param content Document content
   * @param metadata Optional metadata
   * @param providedHash Optional pre-computed content hash
   * @return Upsert response with action taken
   */
  def upsertDocument(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    providedHash: Option[String]
  ): IO[DocumentUpsertResponse] =
    upsertFrom(None, documentId, content, metadata, providedHash)

  /**
   * Upsert a document on behalf of an ingestion source, recording the source as its owner.
   *
   * Change detection uses the persistent registry's content hash, so an unchanged document is
   * not re-embedded - also after a restart. The owner is what lets a source's sync remove only
   * its own documents (see [[listSourceDocumentIds]]).
   */
  def upsertSourceDocument(
    sourceId: String,
    documentId: String,
    content: String,
    metadata: Map[String, String]
  ): IO[DocumentUpsertResponse] =
    upsertFrom(Some(sourceId), documentId, content, metadata, None)

  /** IDs of the documents an ingestion source owns. */
  def listSourceDocumentIds(sourceId: String): IO[Seq[String]] =
    documentRegistry.listIdsBySource(sourceId)

  private def upsertFrom(
    sourceId: Option[String],
    documentId: String,
    content: String,
    metadata: Map[String, String],
    providedHash: Option[String]
  ): IO[DocumentUpsertResponse] =
    if (hasPermissions) {
      upsertWithPermissions(
        collectionPath = metadata.getOrElse("collection", DefaultCollection),
        documentId = documentId,
        content = content,
        metadata = metadata,
        providedHash = providedHash,
        sourceId = sourceId
      )
    } else {
      upsertWithoutPermissions(documentId, content, metadata, providedHash, sourceId)
    }

  private def upsertWithoutPermissions(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    providedHash: Option[String],
    sourceId: Option[String]
  ): IO[DocumentUpsertResponse] = {
    val contentHash = providedHash.getOrElse(computeContentHash(content))

    documentRegistry.get(documentId).flatMap {
      case Some(existing) if existing.contentHash == contentHash =>
        // Content unchanged - skip re-indexing
        claimForSource(existing, sourceId).as(DocumentUpsertResponse(
          documentId = documentId,
          chunks = existing.chunkCount,
          action = "unchanged",
          message = s"Document unchanged (hash: ${contentHash.take(8)}...)"
        ))

      case Some(_) =>
        // Content changed - delete and re-index
        for {
          _ <- deleteDocument(documentId).attempt // Ignore errors if doesn't exist
          result <- ingestAndRegister(documentId, content, contentHash, metadata, sourceId)
        } yield result.copy(action = "updated")

      case None =>
        // New document
        ingestAndRegister(documentId, content, contentHash, metadata, sourceId)
          .map(_.copy(action = "created"))
    }
  }

  /**
   * An unchanged document a source lists becomes that source's, so the source's sync removes it
   * once it is gone - without re-embedding anything. Upserts not from a source change no owner.
   */
  private def claimForSource(existing: DocumentEntry, sourceId: Option[String]): IO[Unit] =
    sourceId match {
      case Some(_) if existing.sourceId != sourceId => documentRegistry.put(existing.copy(sourceId = sourceId))
      case _ => IO.unit
    }

  /**
   * Helper to ingest and register a document.
   */
  private def ingestAndRegister(
    documentId: String,
    content: String,
    contentHash: String,
    metadata: Map[String, String],
    sourceId: Option[String]
  ): IO[DocumentUpsertResponse] =
    rag.flatMap { r =>
      blockingResult(r.ingestText(content, documentId, metadata)).flatMap { chunkCount =>
        recordIngest(documentId, content, contentHash, chunkCount, metadata, metadata.get("collection"), Set.empty, sourceId)
          .as(DocumentUpsertResponse(
            documentId = documentId,
            chunks = chunkCount,
            action = "created",
            message = s"Successfully indexed $chunkCount chunks"
          ))
      }
    }

  /**
   * After a successful ingest: store the document's chunks for visibility, track ingestion time
   * and register the document.
   */
  private def recordIngest(
    documentId: String,
    content: String,
    contentHash: String,
    chunkCount: Int,
    metadata: Map[String, String],
    collection: Option[String],
    readableBy: Set[Int],
    sourceId: Option[String]
  ): IO[Unit] = {
    val now = Instant.now()

    // Create chunks for visibility using the same chunking strategy
    val chunker = ChunkerFactory.create(config.rag.chunking.strategy).getOrElse(ChunkerFactory.default)
    val chunkingConfig = ChunkingConfig(
      targetSize = config.rag.chunking.size,
      maxSize = (config.rag.chunking.size * 1.5).toInt,
      overlap = config.rag.chunking.overlap
    )
    val filename = metadata.get("filename")
    val docChunks = filename.map(f => chunker.chunkWithSource(content, f, chunkingConfig))
      .getOrElse(chunker.chunk(content, chunkingConfig))

    // Convert to ChunkInfo for storage
    val chunkInfos = docChunks.map(toChunkInfo(documentId, collection, _))

    for {
      // Store chunks for visibility
      _ <- chunkStore.store(documentId, chunkInfos)
      // Track ingestion time
      _ <- IO {
        if (firstIngestionTime.isEmpty) firstIngestionTime = Some(now)
        lastIngestionTime = Some(now)
      }
      // Register document
      _ <- documentRegistry.put(DocumentEntry(
        documentId = documentId,
        contentHash = contentHash,
        chunkCount = chunkCount,
        metadata = metadata,
        collection = collection,
        indexedAt = now,
        updatedAt = now,
        readableBy = readableBy,
        sourceId = sourceId
      ))
    } yield ()
  }

  /**
   * Convert a DocumentChunk to ChunkInfo for storage.
   */
  private def toChunkInfo(documentId: String, collection: Option[String], chunk: DocumentChunk): ChunkInfo =
    ChunkInfo(
      id = s"$documentId-${chunk.index}",
      documentId = documentId,
      index = chunk.index,
      content = chunk.content,
      contentLength = chunk.length,
      metadata = Map("collection" -> collection.getOrElse("")),
      chunkMetadata = ChunkMetadataInfo(
        headings = chunk.metadata.headings,
        isCodeBlock = chunk.metadata.isCodeBlock,
        language = chunk.metadata.language,
        startOffset = chunk.metadata.startOffset,
        endOffset = chunk.metadata.endOffset
      )
    )

  /**
   * Prune documents not in the provided keep list.
   * Used for garbage collection after sync.
   *
   * @param keepDocumentIds Documents to keep
   * @return Number of documents deleted
   */
  def pruneDocuments(keepDocumentIds: Set[String]): IO[Int] = {
    documentRegistry.findOrphans(keepDocumentIds).flatMap { toDelete =>
      toDelete.foldLeft(IO.pure(0)) { (acc, docId) =>
        acc.flatMap { count =>
          deleteDocument(docId)
            .as(count + 1)
            .handleError(_ => count) // Continue on error
        }
      }
    }
  }

  /**
   * Get sync status.
   */
  def getSyncStatus: IO[SyncStatusResponse] =
    for {
      stats <- getStats
      syncInfo <- documentRegistry.getSyncInfo()
      docCount <- documentRegistry.count()
    } yield SyncStatusResponse(
      lastSyncTime = syncInfo.lastSyncTime,
      documentCount = docCount,
      chunkCount = stats.chunkCount,
      pendingDeletes = 0
    )

  /**
   * Mark sync as completed.
   */
  def markSyncComplete(): IO[Unit] =
    documentRegistry.markSyncComplete()

  /**
   * List all registered document IDs.
   */
  def listDocumentIds: IO[Seq[String]] =
    documentRegistry.listIds()

  /**
   * List all document entries (full details for sync).
   */
  def listDocumentEntries: IO[Seq[org.llm4s.ragbox.registry.DocumentEntry]] =
    documentRegistry.listEntries()

  /**
   * List document entries modified since a given time (for incremental sync).
   */
  def listDocumentEntriesSince(since: java.time.Instant): IO[Seq[org.llm4s.ragbox.registry.DocumentEntry]] =
    documentRegistry.listEntriesSince(since)

  /**
   * Get multiple document entries by ID (batch lookup).
   */
  def getDocumentEntries(documentIds: Seq[String]): IO[Seq[org.llm4s.ragbox.registry.DocumentEntry]] =
    documentRegistry.getMultiple(documentIds)

  /**
   * Find orphaned documents (documents not in the keep list).
   * Used for dry-run prune operations.
   */
  def findOrphanedDocuments(keepIds: Set[String]): IO[Seq[String]] =
    documentRegistry.findOrphans(keepIds)

  /**
   * List document IDs in a specific collection.
   */
  def listDocumentIdsByCollection(collection: String): IO[Seq[String]] =
    documentRegistry.listIdsByCollection(collection)

  /**
   * List all unique collection names.
   */
  def listCollections: IO[Seq[String]] =
    documentRegistry.listCollections()

  /**
   * Get collection statistics.
   */
  def getCollectionStats: IO[Seq[org.llm4s.ragbox.model.CollectionStats]] =
    for {
      collections <- documentRegistry.listCollections()
      counts <- documentRegistry.countsByCollection()
    } yield collections.map { coll =>
      // Chunk count per collection requires llm4s changes
      org.llm4s.ragbox.model.CollectionStats(name = coll, documentCount = counts.getOrElse(coll, 0), chunkCount = 0)
    }

  // ============================================================
  // Query Operations
  // ============================================================

  /**
   * Search for relevant chunks (no LLM answer generation).
   */
  def search(query: String, topK: Option[Int]): IO[SearchResponse] =
    rag.flatMap { r =>
      blockingResult(r.query(query, topK)).map { results =>
        SearchResponse(
          results = results.map(toContextItem),
          count = results.size
        )
      }
    }

  /**
   * Query with answer generation.
   */
  def queryWithAnswer(question: String, topK: Option[Int]): IO[QueryResponse] =
    rag.flatMap(r => blockingResult(r.queryWithAnswer(question, topK)).map(toQueryResponse))

  // ============================================================
  // Permission-Aware Query Operations
  // ============================================================

  /**
   * Search with permission filtering.
   *
   * @param query Search query text
   * @param auth User authorization (from UserContextMiddleware)
   * @param collectionPattern Collection pattern: "*", "exact", "parent/wildcard", "parent/deep-wildcard"
   * @param topK Number of results to return
   * @return Filtered search results
   */
  def searchWithPermissions(
    query: String,
    auth: UserAuthorization,
    collectionPattern: String,
    topK: Option[Int]
  ): IO[SearchResponse] =
    (rag, parseCollectionPattern(collectionPattern)).flatMapN { (r, pattern) =>
      blockingResult(r.queryWithPermissions(auth, pattern, query, topK)).map { results =>
        SearchResponse(
          results = results.map(toContextItem),
          count = results.size
        )
      }
    }

  /**
   * Query with answer generation and permission filtering.
   */
  def queryWithPermissionsAndAnswer(
    question: String,
    auth: UserAuthorization,
    collectionPattern: String,
    topK: Option[Int]
  ): IO[QueryResponse] =
    (rag, parseCollectionPattern(collectionPattern)).flatMapN { (r, pattern) =>
      blockingResult(r.queryWithPermissionsAndAnswer(auth, pattern, question, topK)).map(toQueryResponse)
    }

  private def toQueryResponse(result: RAGAnswerResult): QueryResponse =
    QueryResponse(
      answer = result.answer,
      contexts = result.contexts.map(toContextItem),
      usage = result.usage.map(u => UsageInfo(
        promptTokens = u.promptTokens,
        completionTokens = u.completionTokens,
        totalTokens = u.totalTokens
      ))
    )

  /**
   * Ingest a document with permission controls.
   *
   * @param collectionPath Collection path for the document; [[RAGService.DefaultCollection]] for
   *                       documents that name none
   * @param documentId Unique document identifier
   * @param content Document content
   * @param metadata Optional metadata
   * @param readableBy Set of principal IDs who can read this document (empty = inherit from collection)
   * @return Number of chunks created
   */
  def ingestWithPermissions(
    collectionPath: String,
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty
  ): IO[DocumentUploadResponse] =
    ingestIntoCollection(collectionPath, documentId, content, metadata, readableBy, computeContentHash(content), None)

  private def ingestIntoCollection(
    collectionPath: String,
    documentId: String,
    content: String,
    metadata: Map[String, String],
    readableBy: Set[PrincipalId],
    contentHash: String,
    sourceId: Option[String]
  ): IO[DocumentUploadResponse] =
    for {
      path <- parseCollectionPath(collectionPath)
      r <- rag
      chunkCount <- blockingResult(r.ingestWithPermissions(path, documentId, content, metadata, readableBy))
      // The registry records documents in the default collection with no collection, which is
      // how it has always represented "default" (see PgDocumentRegistry.listIdsByCollection).
      registeredCollection = Option.when(path.value != DefaultCollection)(path.value)
      _ <- recordIngest(
        documentId = documentId,
        content = content,
        contentHash = contentHash,
        chunkCount = chunkCount,
        metadata = metadata ++ registeredCollection.map("collection" -> _),
        collection = registeredCollection,
        readableBy = readableBy.map(_.value),
        sourceId = sourceId
      )
    } yield DocumentUploadResponse(
      documentId = documentId,
      chunks = chunkCount,
      message = s"Successfully indexed $chunkCount chunks in ${path.value}"
    )

  /**
   * Upsert a document with permission controls (idempotent create/update).
   *
   * The document is unchanged - and not re-indexed - only when its content hash, collection and
   * access list all match the registry. Otherwise its vectors are deleted from the collection it
   * is registered in (which may differ from `collectionPath`, for a move) and it is re-ingested.
   *
   * @param collectionPath Collection path for the document
   * @param documentId Unique document identifier
   * @param content Document content
   * @param metadata Optional metadata
   * @param readableBy Set of principal IDs who can read this document
   * @param providedHash Optional pre-computed content hash
   * @param sourceId The ingestion source upserting the document, recorded as its owner
   * @return Upsert response with action taken
   */
  def upsertWithPermissions(
    collectionPath: String,
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty,
    providedHash: Option[String] = None,
    sourceId: Option[String] = None
  ): IO[DocumentUpsertResponse] = {
    val contentHash = providedHash.getOrElse(computeContentHash(content))

    def ingest(action: String): IO[DocumentUpsertResponse] =
      // Register the caller's hash, so the next upsert with the same hash is unchanged
      ingestIntoCollection(collectionPath, documentId, content, metadata, readableBy, contentHash, sourceId)
        .map(result => DocumentUpsertResponse(
          documentId = result.documentId,
          chunks = result.chunks,
          action = action,
          message = result.message
        ))

    (parseCollectionPath(collectionPath), documentRegistry.get(documentId)).flatMapN {
      case (path, Some(existing))
          if existing.contentHash == contentHash &&
            existing.collection.getOrElse(DefaultCollection) == path.value &&
            existing.readableBy == readableBy.map(_.value) =>
        // Content, collection and access list unchanged - skip re-indexing
        claimForSource(existing, sourceId).as(DocumentUpsertResponse(
          documentId = documentId,
          chunks = existing.chunkCount,
          action = "unchanged",
          message = s"Document unchanged (hash: ${contentHash.take(8)}...)"
        ))

      case (_, Some(existing)) =>
        // Changed - delete from the collection it is in now (not the target one, for a move),
        // then re-index
        deleteVectorsFromCollection(existing.collection.getOrElse(DefaultCollection), documentId) *>
          chunkStore.deleteByDocument(documentId) *>
          ingest("updated")

      case (_, None) =>
        // Not registered, but it may still have vectors written outside the registry - by an
        // ingestion source before permission mode routed sources through collections - that
        // startup migrated into the default collection. Remove them so it is not indexed twice.
        deleteVectorsFromCollection(DefaultCollection, documentId) *>
          ingest("created")
    }
  }

  /**
   * Delete a document from a specific collection.
   */
  def deleteFromCollection(collectionPath: String, documentId: String): IO[Long] =
    for {
      path <- parseCollectionPath(collectionPath)
      r <- rag
      deleted <- blockingResult(r.deleteFromCollection(path, documentId))
      _ <- chunkStore.deleteByDocument(documentId)
      _ <- documentRegistry.remove(documentId)
    } yield deleted

  /**
   * Permission mode: make sure the default collection exists and move vectors that have no
   * collection into it.
   *
   * Uses llm4s's own `PgSchemaManager` definitions: the default collection is a public (empty
   * `queryable_by`) leaf named `default`. Vectors written before permission mode was enabled -
   * or by an ingest path that bypassed collections - have `collection_id = NULL`, which
   * permission-filtered queries can never return; migrating them makes them searchable again.
   *
   * @return the number of vectors migrated
   */
  def ensureDefaultCollection: IO[Long] =
    (hasPermissions, dataSource) match {
      case (false, _) => IO.pure(0L)
      case (true, None) =>
        IO.raiseError(new IllegalStateException("Permission mode needs the application's connection pool"))
      case (true, Some(ds)) =>
        ConnectionPool.withTransaction(ds) { conn =>
          for {
            defaultId <- PgSchemaManager.createDefaultCollection(conn)
            migrated <- PgSchemaManager.migrateExistingVectors(conn, config.database.tableName, defaultId)
          } yield migrated
        }.flatMap(result => IO.fromEither(result.left.map(e => new RuntimeException(e.message))))
    }

  private def parseCollectionPath(path: String): IO[CollectionPath] =
    IO.fromEither(
      CollectionPath.create(path).left.map(e => InvalidRequestError(s"Invalid collection path '$path': ${e.message}"))
    )

  private def parseCollectionPattern(pattern: String): IO[CollectionPattern] =
    IO.fromEither(
      CollectionPattern.parse(pattern).left.map(e => InvalidRequestError(s"Invalid collection pattern '$pattern': ${e.message}"))
    )

  // ============================================================
  // Configuration Operations
  // ============================================================

  /**
   * Get current configuration info.
   */
  def getConfigInfo: IO[ConfigResponse] = IO.pure {
    ConfigResponse(
      embedding = EmbeddingConfigInfo(
        provider = config.embedding.provider,
        model = config.embedding.model,
        dimensions = config.embedding.dimensions
      ),
      llm = LLMConfigInfo(
        model = config.llm.model,
        temperature = config.llm.temperature
      ),
      rag = RAGConfigInfo(
        chunkingStrategy = config.rag.chunking.strategy,
        chunkSize = config.rag.chunking.size,
        chunkOverlap = config.rag.chunking.overlap,
        topK = config.rag.search.topK,
        fusionStrategy = config.rag.search.fusionStrategy
      ),
      database = DatabaseConfigInfo(
        host = config.database.effectiveHost,
        port = config.database.effectivePort,
        database = config.database.effectiveDatabase,
        tableName = config.database.tableName,
        connected = ragReady
      )
    )
  }

  /**
   * Get available providers.
   */
  def getProviders: IO[ProvidersResponse] = IO.pure {
    ProvidersResponse(
      embeddingProviders = Seq(
        ProviderInfo("openai", Seq("text-embedding-3-small", "text-embedding-3-large", "text-embedding-ada-002"), config.apiKeys.openai.isDefined),
        ProviderInfo("voyage", Seq("voyage-3", "voyage-3-lite", "voyage-code-3"), config.apiKeys.voyage.isDefined),
        ProviderInfo("ollama", Seq("nomic-embed-text", "mxbai-embed-large"), configured = true)
      ),
      llmProviders = Seq(
        ProviderInfo("openai", Seq("gpt-4o", "gpt-4o-mini", "gpt-4-turbo"), config.apiKeys.openai.isDefined),
        ProviderInfo("anthropic", Seq("claude-3-opus", "claude-3-sonnet", "claude-3-haiku"), config.apiKeys.anthropic.isDefined)
      ),
      chunkingStrategies = Seq("simple", "sentence", "markdown", "semantic"),
      fusionStrategies = Seq("rrf", "weighted", "vector_only", "keyword_only")
    )
  }

  // ============================================================
  // Visibility Operations
  // ============================================================

  /**
   * Get detailed configuration with changeability annotations.
   */
  def getDetailedConfig: IO[DetailedConfigResponse] = IO.pure {
    DetailedConfigResponse(
      embedding = VisibilityEmbeddingConfig(
        provider = config.embedding.provider,
        model = config.embedding.model,
        dimensions = config.embedding.dimensions,
        changeability = ChangeabilityInfo.cold
      ),
      llm = VisibilityLLMConfig(
        model = config.llm.model,
        temperature = config.llm.temperature,
        changeability = ChangeabilityInfo.hot
      ),
      rag = DetailedRAGConfigInfo(
        chunkingStrategy = ConfigSetting("chunkingStrategy", config.rag.chunking.strategy, ChangeabilityInfo.warm),
        chunkSize = ConfigSetting("chunkSize", config.rag.chunking.size.toString, ChangeabilityInfo.warm),
        chunkOverlap = ConfigSetting("chunkOverlap", config.rag.chunking.overlap.toString, ChangeabilityInfo.warm),
        topK = ConfigSetting("topK", config.rag.search.topK.toString, ChangeabilityInfo.hot),
        fusionStrategy = ConfigSetting("fusionStrategy", config.rag.search.fusionStrategy, ChangeabilityInfo.hot),
        rrfK = ConfigSetting("rrfK", config.rag.search.rrfK.toString, ChangeabilityInfo.hot),
        systemPrompt = Some(config.rag.systemPrompt)
      ),
      database = VisibilityDatabaseConfig(
        host = config.database.effectiveHost,
        port = config.database.effectivePort,
        database = config.database.effectiveDatabase,
        tableName = config.database.tableName,
        connected = ragReady
      ),
      runtimeConfigurable = RuntimeConfigurableInfo(
        hotSettings = Seq("topK", "fusionStrategy", "rrfK", "systemPrompt", "llmModel", "llmTemperature"),
        warmSettings = Seq("chunkingStrategy", "chunkSize", "chunkOverlap"),
        coldSettings = Seq("embeddingProvider", "embeddingModel", "databaseHost", "databasePort")
      )
    )
  }

  /**
   * List all chunks with pagination.
   */
  def listChunks(page: Int, pageSize: Int): IO[ChunkListResponse] =
    chunkStore.listChunks(page, pageSize)

  /**
   * Get all chunks for a specific document.
   */
  def getDocumentChunks(documentId: String): IO[Option[DocumentChunksResponse]] =
    for {
      entry <- documentRegistry.get(documentId)
      chunks <- chunkStore.getChunks(documentId)
    } yield {
      if (chunks.nonEmpty || entry.isDefined) {
        Some(DocumentChunksResponse(
          documentId = documentId,
          filename = entry.flatMap(_.metadata.get("filename")),
          collection = entry.flatMap(_.collection),
          chunkCount = chunks.size,
          chunks = chunks,
          chunkingConfig = ChunkConfigSnapshot(
            strategy = config.rag.chunking.strategy,
            targetSize = config.rag.chunking.size,
            maxSize = (config.rag.chunking.size * 1.5).toInt,
            overlap = config.rag.chunking.overlap
          )
        ))
      } else {
        None
      }
    }

  /**
   * Get a specific chunk by document ID and index.
   */
  def getChunk(documentId: String, index: Int): IO[Option[ChunkInfo]] =
    chunkStore.getChunk(documentId, index)

  /**
   * Get detailed statistics.
   */
  def getDetailedStats: IO[DetailedStatsResponse] =
    for {
      basicStats <- getStats
      chunkDist <- chunkStore.getChunkSizeDistribution()
      collections <- getDetailedCollectionStats
      chunkCount <- chunkStore.count()
    } yield {
      val docCount = basicStats.documentCount
      val avgChunks = if (docCount > 0) chunkCount.toDouble / docCount else 0.0
      DetailedStatsResponse(
        documentCount = docCount,
        chunkCount = chunkCount,
        vectorCount = basicStats.vectorCount,
        collectionCount = collections.size,
        avgChunksPerDocument = avgChunks,
        collections = collections,
        chunkSizeDistribution = chunkDist,
        indexedSince = firstIngestionTime,
        lastIngestion = lastIngestionTime,
        currentConfig = ChunkConfigSnapshot(
          strategy = config.rag.chunking.strategy,
          targetSize = config.rag.chunking.size,
          maxSize = (config.rag.chunking.size * 1.5).toInt,
          overlap = config.rag.chunking.overlap
        )
      )
    }

  /**
   * Get detailed collection statistics.
   */
  private def getDetailedCollectionStats: IO[Seq[DetailedCollectionStats]] =
    for {
      collections <- documentRegistry.listCollections()
      stats <- collections.traverse { coll =>
        for {
          docCount <- documentRegistry.countByCollection(coll)
          chunkCount <- chunkStore.countByCollection(coll)
          avgSize <- chunkStore.avgChunkSizeByCollection(coll)
        } yield DetailedCollectionStats(
          name = coll,
          documentCount = docCount,
          chunkCount = chunkCount,
          avgChunksPerDoc = if (docCount > 0) chunkCount.toDouble / docCount else 0.0,
          avgChunkSize = avgSize,
          chunkingStrategy = None // Per-collection config comes in Phase 4
        )
      }
    } yield stats

  /**
   * Get collection details with chunking rules.
   */
  def getCollectionDetails: IO[CollectionsResponse] =
    for {
      detailedStats <- getDetailedCollectionStats
    } yield {
      val defaultConfig = EffectiveChunkingInfo(
        strategy = config.rag.chunking.strategy,
        targetSize = config.rag.chunking.size,
        maxSize = (config.rag.chunking.size * 1.5).toInt,
        overlap = config.rag.chunking.overlap,
        source = "default"
      )

      val collectionDetails = detailedStats.map { stats =>
        CollectionDetailInfo(
          name = stats.name,
          documentCount = stats.documentCount,
          chunkCount = stats.chunkCount,
          customConfig = None, // Per-collection config comes in Phase 4
          effectiveConfig = defaultConfig
        )
      }

      CollectionsResponse(
        collections = collectionDetails,
        defaultConfig = defaultConfig
      )
    }

  // ============================================================
  // Health Operations
  // ============================================================

  /**
   * Basic health check with system information.
   */
  def healthCheck: IO[HealthResponse] = IO {
    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val freePercent = if (maxMemory > 0) ((maxMemory - usedMemory) * 100 / maxMemory).toInt else 0

    val systemInfo = SystemInfo(
      memoryUsedMb = usedMemory,
      memoryMaxMb = maxMemory,
      memoryFreePercent = freePercent,
      cpuCount = runtime.availableProcessors(),
      javaVersion = System.getProperty("java.version", "unknown")
    )

    HealthResponse(
      status = "healthy",
      version = "0.1.0",
      uptime = System.currentTimeMillis() - startTime,
      system = Some(systemInfo)
    )
  }

  /**
   * Readiness check (verifies database connectivity with real-time query).
   */
  def readinessCheck: IO[ReadinessResponse] = {
    // Perform an actual database query to verify connectivity
    val dbCheckIO: IO[CheckStatus] = documentRegistry.count().attempt.map {
      case Right(_) => CheckStatus("ok")
      case Left(e) => CheckStatus("error", Some(s"Database unreachable: ${e.getMessage}"))
    }

    // Builds the RAG instance if an earlier attempt failed, so readiness recovers on its own
    val ragCheckIO: IO[CheckStatus] = rag.attempt.map {
      case Right(_) => CheckStatus("ok")
      case Left(e) => CheckStatus("error", Some(e.getMessage))
    }

    val apiKeyCheck = if (config.apiKeys.hasAnyKey) {
      CheckStatus("ok")
    } else {
      CheckStatus("warning", Some("No API keys configured"))
    }

    for {
      dbCheck <- dbCheckIO
      ragCheck <- ragCheckIO
      // Service is ready if both RAG initialized and DB is reachable
      isReady = ragCheck.status == "ok" && dbCheck.status == "ok"
    } yield ReadinessResponse(
      ready = isReady,
      checks = Map(
        "database" -> dbCheck,
        "rag_instance" -> ragCheck,
        "api_keys" -> apiKeyCheck
      )
    )
  }

  // ============================================================
  // Helper Methods
  // ============================================================

  private def toContextItem(result: RAGSearchResult): ContextItem = {
    val docId = result.metadata.get("docId")
    val chunkIndex = result.metadata.get("chunkIndex").flatMap(s => scala.util.Try(s.toInt).toOption)

    ContextItem(
      content = result.content,
      score = result.score,
      metadata = result.metadata,
      documentId = docId,
      chunkIndex = chunkIndex
    )
  }

  /**
   * Get the document registry for direct access.
   */
  def getDocumentRegistry: DocumentRegistry = documentRegistry

  /**
   * Close resources: the RAG instance (if it was built), the document registry and the chunk
   * store. Every one is closed even if closing another fails; the first failure is re-raised
   * with the others attached as suppressed exceptions.
   */
  def close(): IO[Unit] = {
    val closeRag = IO.blocking {
      ragLock.synchronized {
        closed = true
        ragRef.getAndSet(None).foreach(_.close())
      }
    }
    List(closeRag, documentRegistry.close(), chunkStore.close())
      .traverse(_.attempt)
      .flatMap { results =>
        results.collect { case Left(e) => e } match {
          case Nil => IO.unit
          case first :: rest => IO(rest.foreach(first.addSuppressed)) *> IO.raiseError(first)
        }
      }
  }
}

object RAGService {

  /**
   * The collection documents go to in permission mode when they name none. It is llm4s
   * `PgSchemaManager`'s default collection: public and a leaf.
   */
  val DefaultCollection: String = "default"

  /** Keyword table the permission-mode pipeline uses (llm4s `PgConfig`'s default). */
  val KeywordTableName: String = "documents"

  /** Minimum time between attempts to build the RAG instance after a failure. */
  val RetryCooldownMillis: Long = 5000L

  /**
   * Create a new RAGService with PostgreSQL-backed document registry and chunk store over the
   * shared connection pool. Does not enable permission-based RAG.
   */
  def create(
    config: AppConfig,
    dataSource: DataSource,
    providerRegistry: ProviderRegistry = ProviderRegistry.default
  ): IO[RAGService] =
    for {
      registry <- PgDocumentRegistry(dataSource)
      chunks <- PgChunkStore(dataSource)
      service = new RAGService(config, registry, chunks, None, providerRegistry, Some(dataSource))
      _ <- warmUp(service)
    } yield service

  /**
   * Create a new RAGService with permission-based RAG enabled.
   * Requires a SearchIndex to be created and initialized first.
   *
   * @param config Application configuration
   * @param dataSource The shared connection pool
   * @param searchIndex Pre-configured SearchIndex for permission-aware operations
   */
  def createWithPermissions(
    config: AppConfig,
    dataSource: DataSource,
    searchIndex: SearchIndex,
    providerRegistry: ProviderRegistry = ProviderRegistry.default
  ): IO[RAGService] =
    for {
      registry <- PgDocumentRegistry(dataSource)
      chunks <- PgChunkStore(dataSource)
      service = new RAGService(config, registry, chunks, Some(searchIndex), providerRegistry, Some(dataSource))
      // Every ingest in permission mode needs the default collection, and vectors left without
      // a collection by earlier versions are unsearchable until migrated into it.
      migrated <- service.ensureDefaultCollection
      _ <- IO.whenA(migrated > 0)(
        IO(logger.info(s"Migrated $migrated vectors without a collection into '$DefaultCollection'"))
      )
      _ <- warmUp(service)
    } yield service

  /**
   * Build the RAG instance eagerly, so a misconfiguration shows at startup. A failure is logged
   * rather than raised: the instance is retried on use (see [[RAGService.rag]]) and reported by
   * the readiness check meanwhile.
   */
  private def warmUp(service: RAGService): IO[Unit] =
    service.rag.attempt.flatMap {
      case Right(_) => IO.unit
      case Left(e) =>
        IO(logger.warn(s"RAG pipeline not available yet (will retry on use): ${e.getMessage}"))
    }

  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[RAGService])
}
