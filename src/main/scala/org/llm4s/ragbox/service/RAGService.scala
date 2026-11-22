package org.llm4s.ragbox.service

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.chunking.{ChunkerFactory, ChunkingConfig, DocumentChunk, DocumentChunker}
import org.llm4s.extract.TikaDocumentExtractor
import org.llm4s.llmconnect.{EmbeddingClient, LLMClient}
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{CompletionOptions, EmbeddingError, EmbeddingRequest}
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{RAG, RAGSearchResult, RAGAnswerResult}
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSchemaManager
import org.llm4s.trace.TracingLLMClient
import org.llm4s.types.Result
import org.llm4s.ragbox.config.{AppConfig, RuntimeSettings}
import org.llm4s.ragbox.service.Llm4sPipeline.{ChatTurn, PipelineSettings}
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{CollectionConfigRegistry, DocumentEntry, DocumentRegistry, PgDocumentRegistry}
import org.llm4s.ragbox.store.{ChunkStore, PgChunkStore}

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

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
 * '''Runtime settings.''' Every query, chat message and ingest reads the current
 * [[RuntimeSettings]] (topK, fusion, system prompt, temperature, chunking), so a change made
 * through the runtime config API applies to the next request without a restart. topK is passed
 * to llm4s on each call. The settings an llm4s [[RAG]] bakes in when it is built
 * ([[PipelineSettings]]: fusion, system prompt, reranking, query transform) select a pipeline
 * from a [[PipelinePool]], keyed by those settings: a change builds a new pipeline on the next
 * query, and the previous one is retired - closed once the requests still using it finish.
 *
 * '''Chunking.''' Each document is chunked once, with the chunking its collection resolves to
 * (`CollectionConfigRegistry.getEffective`: a file-type override, the collection's config, or
 * the runtime settings), and those chunks are what llm4s indexes and the chunk store keeps.
 *
 * A pipeline is built on first use and kept only once it has been built: a failure (database or
 * embedding provider unreachable at startup, say) is not cached, so a later call retries - at
 * most once per [[RAGService.RetryCooldownMillis]] - instead of the service staying broken until
 * restart. The factories try to build it eagerly and log a failure.
 *
 * @param providerRegistry  where the embedding provider is resolved; tests pass one holding a
 *                          deterministic in-process embedder
 * @param dataSource        the application's shared connection pool, for the permission-mode
 *                          schema setup in [[ensureDefaultCollection]]
 * @param runtimeSettings   the runtime-modifiable settings; `Main` passes the
 *                          `RuntimeConfigManager`. By default the configuration file's, fixed.
 * @param collectionConfigs per-collection chunking configs; without them every document is
 *                          chunked with the runtime settings
 */
class RAGService(
  config: AppConfig,
  documentRegistry: DocumentRegistry,
  chunkStore: ChunkStore,
  val searchIndex: Option[SearchIndex] = None,
  providerRegistry: ProviderRegistry = ProviderRegistry.default,
  dataSource: Option[DataSource] = None,
  runtimeSettings: Option[RuntimeSettings] = None,
  collectionConfigs: Option[CollectionConfigRegistry] = None
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

  // Model metadata bundled with llm4s: resolves context windows for LLM configs, prices tokens,
  // and is required (as a given) when building LLM clients and the RAG pipeline.
  private lazy val modelRegistry: Result[ModelRegistryService] = ModelRegistryService.default()

  /**
   * Attributes llm4s trace events (embedding and completion usage, durations, cost) to the
   * request that caused them; see [[QueryMetricsTracing]].
   */
  private val metricsTracing = new QueryMetricsTracing

  /**
   * The answer LLM, resolved through the provider registry (see [[Llm4sPipeline.llmClient]]) and
   * traced with [[metricsTracing]]. When it cannot be built the reason is logged and the service
   * runs without answer generation: search still works, answers fail with a configuration error.
   */
  private lazy val llmClient: Option[LLMClient] = {
    val built = modelRegistry.flatMap { registry =>
      given ModelRegistryService = registry
      Llm4sPipeline.llmClient(config, providerRegistry)
    }
    built match {
      case Right((client, providerConfig)) =>
        RAGService.logger.info(s"LLM '${config.llm.model}' resolved through the llm4s provider registry")
        Some(TracingLLMClient(client, metricsTracing, providerConfig))
      case Left(error) =>
        RAGService.logger.warn(s"LLM '${config.llm.model}' is not available, answer generation is disabled: ${error.formatted}")
        None
    }
  }

  /** The runtime settings requests read. */
  private val settings: RuntimeSettings = runtimeSettings.getOrElse(RuntimeSettings.fixed(config))

  /** The runtime settings in effect now. */
  def currentSettings: RuntimeConfig = settings.current

  /** Build the llm4s pipeline for `pipelineSettings` (blocking: it connects to the stores). */
  private def buildRag(pipelineSettings: PipelineSettings): IO[RAG] =
    IO.blocking {
      val effective = pipelineSettings.applyTo(config)
      scala.util.Try(
        for {
          registry <- modelRegistry
          ragConfig <- Llm4sPipeline.ragConfig(effective, searchIndex, llmClient, metricsTracing)
          rag <- {
            given ModelRegistryService = registry
            ragConfig.build(
              Llm4sPipeline.embeddingProviderConfig(config, providerRegistry),
              () => Llm4sPipeline.rerankerConfig(effective)
            )
          }
        } yield rag
      ).toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString)).flatMap(_.left.map(_.message))
    }.flatMap(result => IO.fromEither(result.left.map(new RuntimeException(_))))

  /** Pipelines by the settings they were built with (see the class description). */
  private val pipelines: PipelinePool[PipelineSettings, RAG] = PipelinePool.unsafeCreate(
    build = buildRag,
    dispose = r => IO.blocking(r.close()),
    maxEntries = RAGService.MaxPipelines,
    retryCooldown = RAGService.RetryCooldownMillis.millis,
    closedMessage = "RAG service is closed"
  )

  /** The pipeline settings the current runtime settings select. */
  def currentPipelineSettings: PipelineSettings = {
    val current = settings.current
    PipelineSettings.from(config)
      .copy(fusionStrategy = current.fusionStrategy, rrfK = current.rrfK, systemPrompt = current.systemPrompt)
      .forMode(config, hasPermissions)
  }

  // The pipeline settings the runtime settings selected last, so the pipeline they replace is retired
  private val lastCurrentSettings = new AtomicReference[Option[PipelineSettings]](None)

  /** Run `f` with the pipeline for `pipelineSettings`, which stays open until `f` completes. */
  private def withPipeline[B](pipelineSettings: PipelineSettings)(f: RAG => IO[B]): IO[B] =
    pipelines.use(pipelineSettings)(f)

  /**
   * Run `f` with the pipeline the current runtime settings select. When the runtime settings
   * have changed since the last call, the pipeline of the previous settings is retired: it takes
   * no new requests and is closed when the ones still using it finish.
   */
  private def withCurrentPipeline[B](f: RAG => IO[B]): IO[B] = {
    val current = currentPipelineSettings
    IO(lastCurrentSettings.getAndSet(Some(current)))
      .flatMap(_.filter(_ != current).traverse_(pipelines.retire)) *>
      withPipeline(current)(f)
  }

  /**
   * The pipeline for the current runtime settings, building it if it has not been built yet.
   * For checks (readiness, warm-up): the instance is no longer leased once this returns, so
   * requests use pipelines through [[withCurrentPipeline]] instead.
   */
  def rag: IO[RAG] = withCurrentPipeline(IO.pure)

  /** Whether the pipeline for the current settings has been built (without trying to build it). */
  private def ragReady: IO[Boolean] = pipelines.isBuilt(currentPipelineSettings)

  // The embedding client and model, once built (a failure is retried on next use)
  private val embedderRef = new AtomicReference[Option[(EmbeddingClient, EmbeddingModelConfig)]](None)

  /** The embedding client permission-mode ingest embeds with, traced like the pipeline's. */
  private def embedder: IO[(EmbeddingClient, EmbeddingModelConfig)] =
    embedderRef.get() match {
      case Some(built) => IO.pure(built)
      case None =>
        blockingResult(modelRegistry.flatMap { registry =>
          given ModelRegistryService = registry
          Llm4sPipeline.embedder(config, providerRegistry)
        }).map { case (client, model) =>
          val traced = (client.withTracing(metricsTracing), model)
          embedderRef.compareAndSet(None, Some(traced))
          embedderRef.get().getOrElse(traced)
        }
    }

  // Chunkers by strategy, once built (the semantic one needs the embedding provider)
  private val chunkers = TrieMap.empty[ChunkerFactory.Strategy, DocumentChunker]

  private def chunkerFor(strategyName: String): IO[DocumentChunker] =
    IO.fromEither(Llm4sPipeline.chunkerStrategy(strategyName).left.map(e => new RuntimeException(e.message))).flatMap { strategy =>
      chunkers.get(strategy) match {
        case Some(chunker) => IO.pure(chunker)
        case None =>
          blockingResult(modelRegistry.flatMap { registry =>
            given ModelRegistryService = registry
            Llm4sPipeline.chunker(strategy, config, providerRegistry)
          }).map(chunker => chunkers.putIfAbsent(strategy, chunker).getOrElse(chunker))
      }
    }

  /**
   * The chunking a document gets: its collection's file-type override or config when it has
   * one (and a collection config registry is attached), otherwise the runtime settings.
   */
  def effectiveChunking(collection: Option[String], filename: Option[String]): IO[EffectiveCollectionConfig] = {
    val current = settings.current
    val defaultMaxSize = RuntimeSettings.defaultMaxSize(current.chunkSize)
    (collection, collectionConfigs) match {
      case (Some(c), Some(registry)) =>
        registry.getEffective(c, filename, current.chunkingStrategy, current.chunkSize, defaultMaxSize, current.chunkOverlap)
      case _ =>
        IO.pure(CollectionConfigRegistry.resolveEffective(
          None, filename, current.chunkingStrategy, current.chunkSize, defaultMaxSize, current.chunkOverlap
        ))
    }
  }

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
   * Extract the text of an uploaded file (PDF, DOCX, HTML, plain text, ... - anything Apache Tika
   * reads) with llm4s's `TikaDocumentExtractor`, the extractor behind `RAG.ingestBytes`.
   *
   * ragbox extracts and then ingests the text itself rather than calling `RAG.ingestBytes`, so an
   * upload goes through the same path as any other document: chunked once into the chunk store,
   * registered with its content hash, and in permission mode put in its collection (llm4s has no
   * permission-aware `ingestBytes`).
   *
   * @return the text and the metadata worth keeping: the detected format and, when Tika found
   *         them, title and author
   */
  def extractText(content: Array[Byte], filename: String): IO[(String, Map[String, String])] =
    IO.blocking(TikaDocumentExtractor.extract(content, filename)).flatMap {
      case Left(error) =>
        IO.raiseError(InvalidRequestError(s"Could not extract text from '$filename': ${error.message}"))
      case Right(extracted) if extracted.text.trim.isEmpty =>
        IO.raiseError(InvalidRequestError(s"'$filename' contains no extractable text"))
      case Right(extracted) =>
        val kept = extracted.metadata.collect {
          case (key, value) if RAGService.ExtractedMetadataKeys.contains(key.toLowerCase) && value.trim.nonEmpty =>
            key.toLowerCase.replace("dc:", "") -> value.trim.take(500)
        }
        IO.pure((extracted.text, kept + ("format" -> extracted.format.name)))
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
        withCurrentPipeline(r => blockingResult(r.deleteDocument(documentId)))
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
      existingPath <- CollectionPath.create(collection).toOption match {
        case Some(path) => collectionExists(path).map(exists => Option.when(exists)(path))
        case None => IO.pure(None)
      }
      path = existingPath.getOrElse(CollectionPath.unsafe(DefaultCollection))
      deleted <- withCurrentPipeline(r => blockingResult(r.deleteFromCollection(path, documentId)))
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
      stats <- withCurrentPipeline(r => blockingResult(r.stats))
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
    withCurrentPipeline(r => blockingResult(r.clear())) *> chunkStore.clear() *> documentRegistry.clear()

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
   * Chunk a document once, with the chunking its collection and file name resolve to (see
   * [[effectiveChunking]]), on the blocking pool (the semantic chunker calls the embedding
   * provider).
   */
  private def chunkContent(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    collection: Option[String]
  ): IO[Seq[DocumentChunk]] =
    for {
      effective <- effectiveChunking(collection, RAGService.sourceName(documentId, metadata))
      chunker <- chunkerFor(effective.strategy)
      chunks <- IO.blocking {
        val chunks = chunker.chunk(content, RAGService.chunkingConfig(effective))
        metadata.get("filename").fold(chunks)(f => chunks.map(ch => ch.copy(metadata = ch.metadata.withSource(f))))
      }
    } yield chunks

  /**
   * Ingest a document without permissions: chunk it once and hand exactly those chunks to llm4s
   * (`RAG.ingestChunks`), which embeds and indexes them as `<documentId>-chunk-<n>` in both the
   * vector and the keyword store. The chunk store keeps the same chunks under the same ids.
   */
  private def ingestAndRegister(
    documentId: String,
    content: String,
    contentHash: String,
    metadata: Map[String, String],
    sourceId: Option[String]
  ): IO[DocumentUpsertResponse] =
    for {
      chunks <- chunkContent(documentId, content, metadata, metadata.get("collection"))
      chunkCount <- withCurrentPipeline(r => blockingResult(r.ingestChunks(documentId, chunks.map(_.content), metadata)))
      collection = metadata.get("collection")
      _ <- recordIngest(
        documentId = documentId,
        chunks = chunks.zipWithIndex.map { case (chunk, i) =>
          toChunkInfo(RAGService.chunkId(documentId, i), documentId, i, collection, chunk)
        },
        contentHash = contentHash,
        chunkCount = chunkCount,
        metadata = metadata,
        collection = collection,
        readableBy = Set.empty,
        sourceId = sourceId
      )
    } yield DocumentUpsertResponse(
      documentId = documentId,
      chunks = chunkCount,
      action = "created",
      message = s"Successfully indexed $chunkCount chunks"
    )

  /**
   * After a successful ingest: store the document's indexed chunks for visibility, track
   * ingestion time and register the document.
   */
  private def recordIngest(
    documentId: String,
    chunks: Seq[ChunkInfo],
    contentHash: String,
    chunkCount: Int,
    metadata: Map[String, String],
    collection: Option[String],
    readableBy: Set[Int],
    sourceId: Option[String]
  ): IO[Unit] = {
    val now = Instant.now()
    for {
      _ <- chunkStore.store(documentId, chunks)
      _ <- IO {
        if (firstIngestionTime.isEmpty) firstIngestionTime = Some(now)
        lastIngestionTime = Some(now)
      }
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
   * A chunk as the chunk store keeps it, under the id llm4s indexed it as. Chunks of documents
   * without a collection have no `collection` metadata (stored as NULL), which is what the
   * default-collection statistics query for.
   */
  private def toChunkInfo(
    id: String,
    documentId: String,
    index: Int,
    collection: Option[String],
    chunk: DocumentChunk
  ): ChunkInfo =
    ChunkInfo(
      id = id,
      documentId = documentId,
      index = index,
      content = chunk.content,
      contentLength = chunk.length,
      metadata = collection.map("collection" -> _).toMap,
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
    withCurrentPipeline { r =>
      blockingResult(r.query(query, Some(resolveTopK(topK)))).map { results =>
        SearchResponse(
          results = results.map(toContextItem),
          count = results.size
        )
      }
    }

  /**
   * Query with answer generation, through llm4s `RAG.queryWithAnswer`. The response carries the
   * query's per-stage latencies, token usage and cost, from what llm4s traced while answering.
   */
  def queryWithAnswer(question: String, topK: Option[Int]): IO[QueryResponse] =
    queryWithAnswer(question, topK, RAGService.SettingsOverride.Empty)

  /**
   * [[queryWithAnswer]] with settings of its own over the runtime settings - an experiment
   * group's or a request's overrides. The query runs on the pipeline built for those settings
   * (built on first use, then reused; see [[PipelinePool]]).
   */
  def queryWithAnswer(question: String, topK: Option[Int], overrides: RAGService.SettingsOverride): IO[QueryResponse] =
    withPipelineFor(overrides)(r => measured(r.queryWithAnswer(question, Some(resolveTopK(topK))))).map { case (result, collected, nanos) =>
      toQueryResponse(result, collected, nanos)
    }

  /**
   * The pipeline settings `overrides` select over the current runtime settings.
   *
   * @return the settings, or an [[InvalidRequestError]] for a value llm4s has no strategy for
   */
  def pipelineSettingsFor(overrides: RAGService.SettingsOverride): Either[InvalidRequestError, PipelineSettings] = {
    val current = currentPipelineSettings
    for {
      fusion <- overrides.fusionStrategy.fold(Right(current.fusionStrategy)) { f =>
        Either.cond(RAGService.FusionStrategies.contains(f), f, InvalidRequestError(
          s"Unknown fusion strategy '$f'. Use one of: ${RAGService.FusionStrategies.mkString(", ")}"
        ))
      }
      reranking <- overrides.reranking.fold(Right(current.reranking)) { r =>
        val settings = current.reranking.copy(strategy = r)
        settings.normalizedStrategy.map(_ => settings).left.map(InvalidRequestError(_))
      }
    } yield current
      .copy(
        fusionStrategy = fusion,
        rrfK = overrides.rrfK.getOrElse(current.rrfK),
        systemPrompt = overrides.systemPrompt.getOrElse(current.systemPrompt),
        reranking = reranking
      )
      .forMode(config, hasPermissions)
  }

  /** Run `f` with the pipeline `overrides` select (the current one when they change nothing). */
  private def withPipelineFor[B](overrides: RAGService.SettingsOverride)(f: RAG => IO[B]): IO[B] =
    IO.fromEither(pipelineSettingsFor(overrides)).flatMap { selected =>
      if (selected == currentPipelineSettings) withCurrentPipeline(f) else withPipeline(selected)(f)
    }

  // ============================================================
  // Permission-Aware Query Operations
  // ============================================================

  /**
   * Search with permission filtering.
   *
   * Permission-filtered search is vector-only in llm4s: fusion, keyword search, reranking and
   * query transforms do not apply (llm4s/llm4s#2716).
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
    parseCollectionPattern(collectionPattern).flatMap { pattern =>
      withCurrentPipeline(r => blockingResult(r.queryWithPermissions(auth, pattern, query, Some(resolveTopK(topK))))).map { results =>
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
    topK: Option[Int],
    overrides: RAGService.SettingsOverride = RAGService.SettingsOverride.Empty
  ): IO[QueryResponse] =
    parseCollectionPattern(collectionPattern).flatMap { pattern =>
      withPipelineFor(overrides)(r => measured(r.queryWithPermissionsAndAnswer(auth, pattern, question, Some(resolveTopK(topK))))).map { case (result, collected, nanos) =>
        toQueryResponse(result, collected, nanos)
      }
    }

  /**
   * Answer a chat message, with the session's earlier messages as conversation history.
   *
   * llm4s's `queryWithAnswer` takes a single question (llm4s/llm4s#2733), so ragbox composes the two llm4s steps
   * itself: retrieval through the pipeline (`RAG.query`, or `RAG.queryWithPermissions` when
   * `permissions` is given), then the answer LLM with the history, the retrieved context, and the
   * runtime system prompt and temperature (see [[Llm4sPipeline.chatConversation]]).
   *
   * @param history     earlier messages, oldest first; the caller bounds how many
   * @param permissions the user's authorization and collection pattern, in permission mode
   */
  def chatAnswer(
    question: String,
    history: Seq[ChatTurn],
    permissions: Option[(UserAuthorization, String)],
    topK: Option[Int]
  ): IO[QueryResponse] =
    for {
      client <- IO.fromOption(llmClient)(new RuntimeException(RAGService.LlmRequiredMessage))
      pattern <- permissions.traverse { case (auth, p) => parseCollectionPattern(p).map(auth -> _) }
      current = settings.current
      k = Some(topK.getOrElse(current.topK))
      measuredAnswer <- withCurrentPipeline { r =>
        measured {
          for {
            contexts <- pattern match {
              case Some((auth, p)) => r.queryWithPermissions(auth, p, question, k)
              case None => r.query(question, k)
            }
            conversation = Llm4sPipeline.chatConversation(current.systemPrompt, history, contexts, question)
            completion <- client.complete(conversation, CompletionOptions(temperature = current.llmTemperature))
          } yield RAGAnswerResult(answer = completion.content, question = question, contexts = contexts, usage = completion.usage)
        }
      }
    } yield {
      val (result, collected, nanos) = measuredAnswer
      toQueryResponse(result, collected, nanos)
    }

  /** topK for a call: the caller's, or the runtime setting. */
  private def resolveTopK(topK: Option[Int]): Int = topK.getOrElse(settings.current.topK)

  /**
   * Run a synchronous llm4s call on the blocking pool, collecting what it traced and timing it.
   */
  private def measured[A](call: => Result[A]): IO[(A, QueryMetricsTracing.Collected, Long)] =
    IO.blocking {
      val started = System.nanoTime()
      val (result, collected) = metricsTracing.collect(call)
      (result, collected, System.nanoTime() - started)
    }.flatMap { case (result, collected, nanos) =>
      IO.fromEither(result.left.map(e => new RuntimeException(e.message))).map(a => (a, collected, nanos))
    }

  private def toQueryResponse(result: RAGAnswerResult, collected: QueryMetricsTracing.Collected, nanos: Long): QueryResponse =
    QueryResponse(
      answer = result.answer,
      contexts = result.contexts.map(toContextItem),
      usage = result.usage.map(u => UsageInfo(
        promptTokens = u.promptTokens,
        completionTokens = u.completionTokens,
        totalTokens = u.totalTokens
      )),
      metrics = Some(queryMetrics(collected, nanos, result.contexts.size))
    )

  /**
   * A query's metrics from its trace events. The answer is the last completion; earlier ones
   * (query rewriting, LLM reranking) are part of retrieval. Search latency is the rest of the
   * query once embedding and answer generation are taken out, so it is known only when the
   * embedding provider reported its duration (llm4s traces it with the call's token usage).
   */
  private def queryMetrics(collected: QueryMetricsTracing.Collected, nanos: Long, contexts: Int): QueryMetrics = {
    def millis(d: scala.concurrent.duration.FiniteDuration): Int = d.toMillis.toInt
    val totalMs = (nanos / 1000000L).toInt
    val completions = collected.completions
    val llmMs = completions.lastOption.flatMap(_.duration).map(millis)
    val embeddingMs = collected.embeddingDuration.map(millis)
    val llmCost = completions.flatMap { c =>
      for {
        usage <- c.usage
        registry <- modelRegistry.toOption
        meta <- registry.lookup(c.model).toOption
        cost <- meta.pricing.estimateCost(usage.promptTokens, usage.completionTokens)
      } yield cost
    }
    val costs = llmCost ++ collected.embeddingCostUsd.toSeq
    QueryMetrics(
      embeddingLatencyMs = embeddingMs,
      searchLatencyMs = embeddingMs.map(e => math.max(0, totalMs - e - llmMs.getOrElse(0))),
      llmLatencyMs = llmMs,
      // llm4s sends every retrieved chunk to the LLM, so the two are equal
      chunksRetrieved = contexts,
      chunksUsed = contexts,
      embeddingTokens = collected.embeddingTokens,
      promptTokens = Option.when(completions.exists(_.usage.isDefined))(completions.flatMap(_.usage).map(_.promptTokens).sum),
      completionTokens = Option.when(completions.exists(_.usage.isDefined))(completions.flatMap(_.usage).map(_.completionTokens).sum),
      costUsd = Option.when(costs.nonEmpty)(costs.sum)
    )
  }

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

  /**
   * Ingest into a collection: chunk the document once (with its collection's chunking, see
   * [[chunkContent]]), embed the chunks and write them through llm4s's `SearchIndex.ingest` -
   * the call `RAG.ingestWithPermissions` makes once it has chunked and embedded. ragbox does not
   * call `RAG.ingestWithPermissions` itself, as that chunks with the pipeline's one chunking
   * config (llm4s/llm4s#2732). The chunk store keeps the same chunks under the ids llm4s gave
   * them (`coll-<collectionId>-<doc>-chunk-<n>`).
   */
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
      index <- IO.fromOption(searchIndex)(new IllegalStateException("Permission-aware ingestion needs a SearchIndex"))
      chunks <- chunkContent(documentId, content, metadata, Some(path.value))
      embeddings <- embed(chunks.map(_.content))
      chunkCount <- blockingResult(index.ingest(
        collectionPath = path,
        documentId = documentId,
        chunks = chunks.zip(embeddings).zipWithIndex.map { case ((chunk, embedding), i) =>
          ChunkWithEmbedding(content = chunk.content, embedding = embedding, chunkIndex = i, metadata = metadata)
        },
        metadata = metadata,
        readableBy = readableBy
      ))
      collectionId <- collections.traverse(store => blockingResult(store.get(path))).map(_.flatten.map(_.id))
      // The registry records documents in the default collection with no collection, which is
      // how it has always represented "default" (see PgDocumentRegistry.listIdsByCollection).
      registeredCollection = Option.when(path.value != DefaultCollection)(path.value)
      _ <- recordIngest(
        documentId = documentId,
        chunks = chunks.zipWithIndex.map { case (chunk, i) =>
          val id = collectionId.fold(RAGService.chunkId(documentId, i))(cid => s"coll-$cid-${RAGService.chunkId(documentId, i)}")
          toChunkInfo(id, documentId, i, registeredCollection, chunk)
        },
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

  /** Embed `texts` in one request, as llm4s's `RAG` embeds a document's chunks. */
  private def embed(texts: Seq[String]): IO[Seq[Array[Float]]] =
    if (texts.isEmpty) IO.pure(Seq.empty)
    else
      embedder.flatMap { case (client, model) =>
        blockingResult(client.embed(EmbeddingRequest(texts, model)).flatMap { response =>
          Either.cond(
            response.embeddings.size == texts.size,
            response.embeddings.map(_.map(_.toFloat).toArray),
            EmbeddingError(None, s"Embedding provider returned ${response.embeddings.size} embeddings for ${texts.size} texts", model.name)
          )
        })
      }

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
      deleted <- withCurrentPipeline(r => blockingResult(r.deleteFromCollection(path, documentId)))
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
   * Get current configuration info, including how the pipeline searches: in permission mode
   * llm4s's permission-filtered search is vector-only, so the configured fusion strategy and RRF k,
   * reranking and query transforms do not apply there (llm4s/llm4s#2716).
   */
  def getConfigInfo: IO[ConfigResponse] = ragReady.map { connected =>
    val current = settings.current
    ConfigResponse(
      embedding = EmbeddingConfigInfo(
        provider = config.embedding.provider,
        model = config.embedding.model,
        dimensions = config.embedding.dimensions
      ),
      llm = LLMConfigInfo(
        model = config.llm.model,
        temperature = current.llmTemperature
      ),
      rag = RAGConfigInfo(
        chunkingStrategy = current.chunkingStrategy,
        chunkSize = current.chunkSize,
        chunkOverlap = current.chunkOverlap,
        topK = current.topK,
        fusionStrategy = current.fusionStrategy,
        rrfK = current.rrfK,
        searchMode = if (hasPermissions) "vector" else "hybrid",
        fusionApplies = !hasPermissions,
        reranking = config.rag.reranking.normalizedStrategy.getOrElse(config.rag.reranking.strategy),
        queryTransform = config.rag.queryTransform.normalizedStrategy.getOrElse(config.rag.queryTransform.strategy),
        limitations = pipelineLimitations
      ),
      database = DatabaseConfigInfo(
        host = config.database.effectiveHost,
        port = config.database.effectivePort,
        database = config.database.effectiveDatabase,
        tableName = config.database.tableName,
        connected = connected
      )
    )
  }

  /** What the configured settings do not affect, with the llm4s issue that tracks each. */
  private def pipelineLimitations: Seq[String] = {
    val permissionMode =
      if (hasPermissions)
        Seq(
          "Permission mode: llm4s permission-filtered search is vector-only, so fusionStrategy and rrfK " +
            "have no effect" +
            (if (config.rag.reranking.enabled) ", nor does reranking" else "") +
            (if (config.rag.queryTransform.enabled) ", nor does the query transform" else "") +
            " (llm4s/llm4s#2716)."
        )
      else Seq.empty
    permissionMode ++ Seq(
      "The LLM temperature applies to chat answers only: llm4s RAG.queryWithAnswer generates /query answers " +
        "at temperature 0.0 in llm4s 0.6.0 (llm4s/llm4s#2715, fixed upstream for the next release).",
      "Streaming endpoints send the answer in one event once it is complete: llm4s RAG has no streaming " +
        "answer API in 0.6.0 (llm4s/llm4s#2717, added upstream for the next release)."
    )
  }

  // Live model lists by provider id, with when they were fetched
  private val modelListCache = new java.util.concurrent.ConcurrentHashMap[String, (Long, Seq[String])]()

  /**
   * Get available providers: every LLM and embedding provider registered with llm4s's
   * [[ProviderRegistry]]. A provider counts as configured when it needs no API key or ragbox has
   * one for it. Models come from the provider's live model lister when it has one and is
   * configured (cached for [[RAGService.ModelListTtlMillis]]), otherwise from llm4s's bundled model
   * metadata and the embedding descriptors' model tables.
   */
  def getProviders: IO[ProvidersResponse] =
    for {
      llmProviders <- providerRegistry.ids.toList.parTraverse(llmProviderInfo)
      embeddingProviders = providerRegistry.embeddingIds.map(embeddingProviderInfo)
    } yield ProvidersResponse(
      embeddingProviders = embeddingProviders,
      llmProviders = llmProviders,
      chunkingStrategies = org.llm4s.chunking.ChunkerFactory.Strategy.all.map(_.name),
      fusionStrategies = Seq("rrf", "weighted", "vector_only", "keyword_only")
    )

  private def bundledModels(provider: String, mode: org.llm4s.model.ModelMode): Seq[String] =
    modelRegistry.toOption
      .flatMap(_.listByProvider(provider).toOption)
      .getOrElse(Nil)
      .filter(_.mode == mode)
      .map(_.modelId)
      .sorted

  private def embeddingProviderInfo(id: String): ProviderInfo = {
    val descriptor = providerRegistry.findEmbedding(org.llm4s.types.ProviderModelTypes.ProviderId(id))
    val fromDescriptor = descriptor.map(_.modelDimensions.keys.toSeq.sorted).getOrElse(Nil)
    val models = if (fromDescriptor.nonEmpty) fromDescriptor else bundledModels(id, org.llm4s.model.ModelMode.Embedding)
    val needsKey = descriptor.exists(_.configSpec.requiresApiKey)
    ProviderInfo(id, models, configured = !needsKey || config.apiKeys.getKey(id).isDefined)
  }

  private def llmProviderInfo(id: String): IO[ProviderInfo] = {
    val providerId = org.llm4s.types.ProviderModelTypes.ProviderId(id)
    val descriptor = providerRegistry.find(providerId)
    val apiKey = config.apiKeys.getKey(id)
    val configured = descriptor.exists(d => !d.configSpec.requiresApiKey || apiKey.isDefined)
    val bundled = bundledModels(id, org.llm4s.model.ModelMode.Chat)
    val listed: IO[Option[Seq[String]]] = descriptor.flatMap(_.modelLister).filter(_ => configured) match {
      case None => IO.pure(None)
      case Some(lister) =>
        Option(modelListCache.get(id)).filter { case (at, _) =>
          System.currentTimeMillis() - at < RAGService.ModelListTtlMillis
        } match {
          case Some((_, models)) => IO.pure(Some(models))
          case None =>
            val isActive = config.llm.providerId == id
            val section = org.llm4s.config.ProvidersConfigModel.NamedProviderConfig(
              provider = providerId,
              model = org.llm4s.types.ProviderModelTypes.ModelName(if (isActive) config.llm.modelName else ""),
              baseUrl = (if (isActive) config.llm.baseUrl else None)
                .orElse(descriptor.flatMap(_.configSpec.defaultBaseUrl))
                .map(org.llm4s.types.ProviderModelTypes.BaseUrl(_)),
              apiKey = apiKey.map(k => org.llm4s.types.ProviderModelTypes.ApiKey(k.reveal))
            )
            IO.interruptible {
              val http = org.llm4s.http.Llm4sHttpClient.create(scala.concurrent.duration.FiniteDuration(3, "seconds"))
              lister.listModels(section, http).map(_.map(_.name.asString).sorted.toSeq)
            }.timeout(scala.concurrent.duration.FiniteDuration(5, "seconds")).attempt.map {
              case Right(Right(models)) if models.nonEmpty =>
                modelListCache.put(id, (System.currentTimeMillis(), models))
                Some(models)
              case Right(Left(error)) =>
                RAGService.logger.debug(s"Listing models of '$id' failed: ${error.message}")
                None
              case Left(e) =>
                RAGService.logger.debug(s"Listing models of '$id' failed: ${e.getMessage}")
                None
              case _ => None
            }
        }
    }
    listed.map(live => ProviderInfo(id, live.getOrElse(bundled), configured))
  }

  // ============================================================
  // Visibility Operations
  // ============================================================

  /**
   * Get detailed configuration with changeability annotations.
   */
  def getDetailedConfig: IO[DetailedConfigResponse] = ragReady.map { connected =>
    val current = settings.current
    DetailedConfigResponse(
      embedding = VisibilityEmbeddingConfig(
        provider = config.embedding.provider,
        model = config.embedding.model,
        dimensions = config.embedding.dimensions,
        changeability = ChangeabilityInfo.cold
      ),
      llm = VisibilityLLMConfig(
        model = config.llm.model,
        temperature = current.llmTemperature,
        changeability = ChangeabilityInfo.hot
      ),
      rag = DetailedRAGConfigInfo(
        chunkingStrategy = ConfigSetting("chunkingStrategy", current.chunkingStrategy, ChangeabilityInfo.warm),
        chunkSize = ConfigSetting("chunkSize", current.chunkSize.toString, ChangeabilityInfo.warm),
        chunkOverlap = ConfigSetting("chunkOverlap", current.chunkOverlap.toString, ChangeabilityInfo.warm),
        topK = ConfigSetting("topK", current.topK.toString, ChangeabilityInfo.hot),
        fusionStrategy = ConfigSetting("fusionStrategy", current.fusionStrategy, ChangeabilityInfo.hot),
        rrfK = ConfigSetting("rrfK", current.rrfK.toString, ChangeabilityInfo.hot),
        systemPrompt = Some(current.systemPrompt),
        searchMode = if (hasPermissions) "vector" else "hybrid",
        limitations = pipelineLimitations
      ),
      database = VisibilityDatabaseConfig(
        host = config.database.effectiveHost,
        port = config.database.effectivePort,
        database = config.database.effectiveDatabase,
        tableName = config.database.tableName,
        connected = connected
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
          chunkingConfig = currentChunkConfig
        ))
      } else {
        None
      }
    }

  /** The runtime chunking settings, as the visibility API shows them. */
  private def currentChunkConfig: ChunkConfigSnapshot = {
    val current = settings.current
    ChunkConfigSnapshot(
      strategy = current.chunkingStrategy,
      targetSize = current.chunkSize,
      maxSize = RuntimeSettings.defaultMaxSize(current.chunkSize),
      overlap = current.chunkOverlap
    )
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
        currentConfig = currentChunkConfig
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
      val chunking = currentChunkConfig
      val defaultConfig = EffectiveChunkingInfo(
        strategy = chunking.strategy,
        targetSize = chunking.targetSize,
        maxSize = chunking.maxSize,
        overlap = chunking.overlap,
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
   * Close resources: the pipelines (each once the requests using it finish), the document
   * registry and the chunk store. Every one is closed even if closing another fails; the first failure is re-raised
   * with the others attached as suppressed exceptions.
   */
  def close(): IO[Unit] =
    List(pipelines.close, documentRegistry.close(), chunkStore.close())
      .traverse(_.attempt)
      .flatMap { results =>
        results.collect { case Left(e) => e } match {
          case Nil => IO.unit
          case first :: rest => IO(rest.foreach(first.addSuppressed)) *> IO.raiseError(first)
        }
      }
}

object RAGService {

  /**
   * The collection documents go to in permission mode when they name none. It is llm4s
   * `PgSchemaManager`'s default collection: public and a leaf.
   */
  val DefaultCollection: String = "default"

  /** Keyword table the pipeline uses in both modes (llm4s `PgConfig`'s default). */
  val KeywordTableName: String = Llm4sPipeline.KeywordTableName

  /** Minimum time between attempts to build the RAG instance after a failure. */
  val RetryCooldownMillis: Long = 5000L

  /** The fusion strategies a query can be given. */
  val FusionStrategies: Seq[String] = Seq("rrf", "weighted", "vector_only", "keyword_only")

  /**
   * Settings a query runs with instead of the runtime settings' (an experiment group's or a
   * request's overrides). topK is not here: it is passed with each call.
   *
   * @param reranking `none`, `cohere` or `llm`
   */
  final case class SettingsOverride(
    fusionStrategy: Option[String] = None,
    rrfK: Option[Int] = None,
    systemPrompt: Option[String] = None,
    reranking: Option[String] = None
  )

  object SettingsOverride {
    val Empty: SettingsOverride = SettingsOverride()
  }

  /**
   * How many pipelines are kept at once: the current settings' and, while an experiment runs,
   * its variants'. Each holds its own store connections, so the least recently used one beyond
   * this is closed.
   */
  val MaxPipelines: Int = 4

  /**
   * The name a document's file-type chunking override is looked up by: its file name, else the
   * path or source llm4s's loaders record, else its id.
   */
  def sourceName(documentId: String, metadata: Map[String, String]): Option[String] =
    metadata.get("filename").orElse(metadata.get("path")).orElse(metadata.get("source")).orElse(Some(documentId))

  /**
   * The llm4s chunking config for an effective collection config. Sizes that validation rejects
   * when a config is saved can still arise later - a collection that sets only `maxSize`, after
   * the runtime chunk size grew past it - so they are brought within llm4s's bounds here rather
   * than failing the ingest.
   */
  def chunkingConfig(effective: EffectiveCollectionConfig): ChunkingConfig = {
    val target = math.max(1, effective.targetSize)
    ChunkingConfig(
      targetSize = target,
      maxSize = math.max(effective.maxSize, target),
      overlap = math.max(0, math.min(effective.overlap, target - 1))
    )
  }

  /** Tika metadata kept on an uploaded document (lower-cased keys; `dc:` is dropped). */
  private val ExtractedMetadataKeys: Set[String] = Set("dc:title", "title", "dc:creator", "author")

  /** How long a provider's live model list is cached by [[RAGService.getProviders]]. */
  val ModelListTtlMillis: Long = 10 * 60 * 1000L

  /** The error answer generation fails with when no LLM is available; routes match on it. */
  val LlmRequiredMessage: String = "LLM client required for answer generation"

  /** The id llm4s indexes chunk `index` of a document under (outside permission mode). */
  def chunkId(documentId: String, index: Int): String = s"$documentId-chunk-$index"

  /**
   * Create a new RAGService with PostgreSQL-backed document registry and chunk store over the
   * shared connection pool. Does not enable permission-based RAG.
   */
  def create(
    config: AppConfig,
    dataSource: DataSource,
    providerRegistry: ProviderRegistry = ProviderRegistry.default,
    runtimeSettings: Option[RuntimeSettings] = None,
    collectionConfigs: Option[CollectionConfigRegistry] = None
  ): IO[RAGService] =
    for {
      registry <- PgDocumentRegistry(dataSource)
      chunks <- PgChunkStore(dataSource)
      service = new RAGService(config, registry, chunks, None, providerRegistry, Some(dataSource), runtimeSettings, collectionConfigs)
      _ <- warmUp(service)
    } yield service

  /**
   * Create a new RAGService with permission-based RAG enabled.
   * Requires a SearchIndex to be created and initialized first.
   *
   * @param config Application configuration
   * @param dataSource The shared connection pool
   * @param searchIndex Pre-configured SearchIndex for permission-aware operations
   * @param runtimeSettings the runtime-modifiable settings (see the class description)
   * @param collectionConfigs per-collection chunking configs
   */
  def createWithPermissions(
    config: AppConfig,
    dataSource: DataSource,
    searchIndex: SearchIndex,
    providerRegistry: ProviderRegistry = ProviderRegistry.default,
    runtimeSettings: Option[RuntimeSettings] = None,
    collectionConfigs: Option[CollectionConfigRegistry] = None
  ): IO[RAGService] =
    for {
      registry <- PgDocumentRegistry(dataSource)
      chunks <- PgChunkStore(dataSource)
      service = new RAGService(config, registry, chunks, Some(searchIndex), providerRegistry, Some(dataSource), runtimeSettings, collectionConfigs)
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
