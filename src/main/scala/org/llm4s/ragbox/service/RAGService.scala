package org.llm4s.ragbox.service

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.chunking.{ChunkerFactory, ChunkingConfig, DocumentChunk}
import org.llm4s.llmconnect.{LLMClient, LLMConnect}
import org.llm4s.llmconnect.config.{EmbeddingProviderConfig, OpenAIConfig, AnthropicConfig}
import org.llm4s.rag.{EmbeddingProvider, RAG, RAGConfig, RAGSearchResult, RAGAnswerResult, RAGStats}
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.rag.permissions._
import org.llm4s.types.Result
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{DocumentEntry, DocumentRegistry, PgDocumentRegistry}
import org.llm4s.ragbox.store.{ChunkStore, PgChunkStore}

import java.security.MessageDigest
import java.time.Instant

/**
 * Service wrapping llm4s RAG functionality.
 *
 * Provides a high-level interface for document ingestion, search, and query operations.
 */

class RAGService(
  config: AppConfig,
  documentRegistry: DocumentRegistry,
  chunkStore: ChunkStore,
  val searchIndex: Option[SearchIndex] = None
) {

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

  // Create LLM client for answer generation
  private lazy val llmClient: Option[LLMClient] = {
    // Parse provider/model format (e.g., "openai/gpt-4o")
    val parts = config.llm.model.split("/", 2)
    val provider = if (parts.length > 1) parts(0) else "openai"
    val model = if (parts.length > 1) parts(1) else config.llm.model

    provider.toLowerCase match {
      case "openai" =>
        config.apiKeys.openai.flatMap { apiKey =>
          LLMConnect.getClient(OpenAIConfig(
            apiKey = apiKey,
            model = model,
            organization = None,
            baseUrl = "https://api.openai.com/v1",
            contextWindow = 128000,
            reserveCompletion = 4096
          )).toOption
        }
      case "anthropic" =>
        config.apiKeys.anthropic.flatMap { apiKey =>
          LLMConnect.getClient(AnthropicConfig(
            apiKey = apiKey,
            model = model,
            baseUrl = "https://api.anthropic.com",
            contextWindow = 200000,
            reserveCompletion = 4096
          )).toOption
        }
      case _ => None
    }
  }

  // Base URLs for embedding providers (without /v1 - provider adds it)
  private def getEmbeddingBaseUrl(provider: String): String = provider.toLowerCase match {
    case "openai" => "https://api.openai.com"
    case "voyage" => "https://api.voyageai.com"
    case "ollama" => "http://localhost:11434"
    case _ => "https://api.openai.com"
  }

  // Embedding provider config resolver
  private def resolveEmbeddingProvider(provider: String): Result[EmbeddingProviderConfig] = {
    val apiKey = config.embedding.provider.toLowerCase match {
      case "openai" => config.apiKeys.openai
      case "voyage" => config.apiKeys.voyage
      case "ollama" => Some("") // Ollama doesn't need an API key
      case _ => config.apiKeys.openai
    }

    apiKey match {
      case Some(key) =>
        Right(EmbeddingProviderConfig(
          baseUrl = getEmbeddingBaseUrl(config.embedding.provider),
          model = config.embedding.model,
          apiKey = key
        ))
      case None =>
        Left(org.llm4s.error.ConfigurationError(
          s"No API key configured for embedding provider: ${config.embedding.provider}"
        ))
    }
  }

  // Build RAG configuration
  private lazy val ragConfig: RAGConfig = {
    val baseConfig = RAGConfig.default
      .withEmbeddings(config.embedding.toEmbeddingProvider, config.embedding.model)
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
        // Permission-based RAG with SearchIndex
        baseConfig.withSearchIndex(si)
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

  // Build the RAG instance
  private lazy val ragInstance: Result[RAG] = ragConfig.build(resolveEmbeddingProvider)

  /**
   * Get the underlying RAG instance.
   */
  def rag: IO[RAG] = IO.fromEither(ragInstance.left.map(e => new RuntimeException(e.message)))

  // ============================================================
  // Document Operations
  // ============================================================

  /**
   * Ingest a document with content and metadata.
   */
  def ingestDocument(
    content: String,
    documentId: String,
    metadata: Map[String, String]
  ): IO[DocumentUploadResponse] = {
    val contentHash = computeContentHash(content)
    ingestAndRegister(documentId, content, contentHash, metadata).map { result =>
      DocumentUploadResponse(documentId, result.chunks, s"Successfully ingested ${result.chunks} chunks")
    }
  }

  /**
   * Delete a document by ID.
   */
  def deleteDocument(documentId: String): IO[Unit] =
    rag.flatMap { r =>
      IO.fromEither(
        r.deleteDocument(documentId)
          .left.map(e => new RuntimeException(e.message))
      )
    } *> chunkStore.deleteByDocument(documentId) *> documentRegistry.remove(documentId)

  /**
   * Get RAG statistics.
   * Uses document/chunk registries for persistent counts.
   */
  def getStats: IO[StatsResponse] =
    for {
      r <- rag
      stats <- IO.fromEither(r.stats.left.map(e => new RuntimeException(e.message)))
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
    rag.flatMap { r =>
      IO.fromEither(
        r.clear().left.map(e => new RuntimeException(e.message))
      )
    } *> chunkStore.clear() *> documentRegistry.clear()

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
  ): IO[DocumentUpsertResponse] = {
    val contentHash = providedHash.getOrElse(computeContentHash(content))

    documentRegistry.get(documentId).flatMap {
      case Some(existing) if existing.contentHash == contentHash =>
        // Content unchanged - skip re-indexing
        IO.pure(DocumentUpsertResponse(
          documentId = documentId,
          chunks = existing.chunkCount,
          action = "unchanged",
          message = s"Document unchanged (hash: ${contentHash.take(8)}...)"
        ))

      case Some(_) =>
        // Content changed - delete and re-index
        for {
          _ <- deleteDocument(documentId).attempt // Ignore errors if doesn't exist
          result <- ingestAndRegister(documentId, content, contentHash, metadata)
        } yield result.copy(action = "updated")

      case None =>
        // New document
        ingestAndRegister(documentId, content, contentHash, metadata)
          .map(_.copy(action = "created"))
    }
  }

  /**
   * Helper to ingest and register a document.
   */
  private def ingestAndRegister(
    documentId: String,
    content: String,
    contentHash: String,
    metadata: Map[String, String]
  ): IO[DocumentUpsertResponse] =
    rag.flatMap { r =>
      IO.fromEither(
        r.ingestText(content, documentId, metadata)
          .left.map(e => new RuntimeException(e.message))
      ).flatMap { chunkCount =>
        val now = Instant.now()
        val collection = metadata.get("collection")

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
            updatedAt = now
          ))
        } yield DocumentUpsertResponse(
          documentId = documentId,
          chunks = chunkCount,
          action = "created",
          message = s"Successfully indexed $chunkCount chunks"
        )
      }
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
      stats <- collections.traverse { coll =>
        documentRegistry.countByCollection(coll).map { count =>
          org.llm4s.ragbox.model.CollectionStats(name = coll, documentCount = count, chunkCount = 0) // Chunk count per collection requires llm4s changes
        }
      }
    } yield stats

  // ============================================================
  // Query Operations
  // ============================================================

  /**
   * Search for relevant chunks (no LLM answer generation).
   */
  def search(query: String, topK: Option[Int]): IO[SearchResponse] =
    rag.flatMap { r =>
      IO.fromEither(
        r.query(query, topK)
          .map { results =>
            SearchResponse(
              results = results.map(toContextItem),
              count = results.size
            )
          }
          .left.map(e => new RuntimeException(e.message))
      )
    }

  /**
   * Query with answer generation.
   */
  def queryWithAnswer(question: String, topK: Option[Int]): IO[QueryResponse] =
    rag.flatMap { r =>
      IO.fromEither(
        r.queryWithAnswer(question, topK)
          .map { result =>
            QueryResponse(
              answer = result.answer,
              contexts = result.contexts.map(toContextItem),
              usage = result.usage.map(u => UsageInfo(
                promptTokens = u.promptTokens,
                completionTokens = u.completionTokens,
                totalTokens = u.totalTokens
              ))
            )
          }
          .left.map(e => new RuntimeException(e.message))
      )
    }

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
    rag.flatMap { r =>
      val pattern = CollectionPattern.parse(collectionPattern)
        .getOrElse(CollectionPattern.All)

      IO.fromEither(
        r.queryWithPermissions(auth, pattern, query, topK)
          .map { results =>
            SearchResponse(
              results = results.map(toContextItem),
              count = results.size
            )
          }
          .left.map(e => new RuntimeException(e.message))
      )
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
    rag.flatMap { r =>
      val pattern = CollectionPattern.parse(collectionPattern)
        .getOrElse(CollectionPattern.All)

      IO.fromEither(
        r.queryWithPermissionsAndAnswer(auth, pattern, question, topK)
          .map { result =>
            QueryResponse(
              answer = result.answer,
              contexts = result.contexts.map(toContextItem),
              usage = result.usage.map(u => UsageInfo(
                promptTokens = u.promptTokens,
                completionTokens = u.completionTokens,
                totalTokens = u.totalTokens
              ))
            )
          }
          .left.map(e => new RuntimeException(e.message))
      )
    }

  /**
   * Ingest a document with permission controls.
   *
   * @param collectionPath Collection path for the document
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
  ): IO[DocumentUploadResponse] = {
    val path = CollectionPath.unsafe(collectionPath)

    rag.flatMap { r =>
      IO.fromEither(
        r.ingestWithPermissions(path, documentId, content, metadata, readableBy)
          .left.map(e => new RuntimeException(e.message))
      ).flatMap { chunkCount =>
        val now = Instant.now()
        val contentHash = computeContentHash(content)

        // Also register in document registry for local tracking
        documentRegistry.put(DocumentEntry(
          documentId = documentId,
          contentHash = contentHash,
          chunkCount = chunkCount,
          metadata = metadata + ("collection" -> collectionPath),
          collection = Some(collectionPath),
          indexedAt = now,
          updatedAt = now
        )).as(DocumentUploadResponse(
          documentId = documentId,
          chunks = chunkCount,
          message = s"Successfully indexed $chunkCount chunks in $collectionPath"
        ))
      }
    }
  }

  /**
   * Upsert a document with permission controls (idempotent create/update).
   *
   * Checks document registry first - if content hash matches, skips re-indexing.
   *
   * @param collectionPath Collection path for the document
   * @param documentId Unique document identifier
   * @param content Document content
   * @param metadata Optional metadata
   * @param readableBy Set of principal IDs who can read this document
   * @param providedHash Optional pre-computed content hash
   * @return Upsert response with action taken
   */
  def upsertWithPermissions(
    collectionPath: String,
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty,
    providedHash: Option[String] = None
  ): IO[DocumentUpsertResponse] = {
    val contentHash = providedHash.getOrElse(computeContentHash(content))

    documentRegistry.get(documentId).flatMap {
      case Some(existing) if existing.contentHash == contentHash =>
        // Content unchanged - skip re-indexing
        IO.pure(DocumentUpsertResponse(
          documentId = documentId,
          chunks = existing.chunkCount,
          action = "unchanged",
          message = s"Document unchanged (hash: ${contentHash.take(8)}...)"
        ))

      case Some(_) =>
        // Content changed - delete and re-index
        for {
          _ <- deleteFromCollection(collectionPath, documentId).attempt
          result <- ingestWithPermissions(collectionPath, documentId, content, metadata, readableBy)
        } yield DocumentUpsertResponse(
          documentId = result.documentId,
          chunks = result.chunks,
          action = "updated",
          message = result.message
        )

      case None =>
        // New document
        ingestWithPermissions(collectionPath, documentId, content, metadata, readableBy)
          .map(result => DocumentUpsertResponse(
            documentId = result.documentId,
            chunks = result.chunks,
            action = "created",
            message = result.message
          ))
    }
  }

  /**
   * Delete a document from a specific collection.
   */
  def deleteFromCollection(collectionPath: String, documentId: String): IO[Long] = {
    val path = CollectionPath.unsafe(collectionPath)

    rag.flatMap { r =>
      IO.fromEither(
        r.deleteFromCollection(path, documentId)
          .left.map(e => new RuntimeException(e.message))
      )
    } <* chunkStore.deleteByDocument(documentId) <* documentRegistry.remove(documentId)
  }

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
        host = config.database.host,
        port = config.database.port,
        database = config.database.database,
        tableName = config.database.tableName,
        connected = ragInstance.isRight
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
        host = config.database.host,
        port = config.database.port,
        database = config.database.database,
        tableName = config.database.tableName,
        connected = ragInstance.isRight
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
   * Readiness check (verifies database connectivity).
   */
  def readinessCheck: IO[ReadinessResponse] = {
    val ragCheck = ragInstance match {
      case Right(_) => CheckStatus("ok")
      case Left(e) => CheckStatus("error", Some(e.message))
    }

    val apiKeyCheck = if (config.apiKeys.hasAnyKey) {
      CheckStatus("ok")
    } else {
      CheckStatus("warning", Some("No API keys configured"))
    }

    IO.pure(ReadinessResponse(
      ready = ragInstance.isRight,
      checks = Map(
        "database" -> ragCheck,
        "api_keys" -> apiKeyCheck
      )
    ))
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
   * Close resources.
   */
  def close(): IO[Unit] =
    rag.flatMap(r => IO(r.close())) *> documentRegistry.close() *> chunkStore.close()
}

object RAGService {

  /**
   * Create a new RAGService with PostgreSQL-backed document registry and chunk store.
   * Does not enable permission-based RAG.
   */
  def create(config: AppConfig): IO[RAGService] =
    for {
      registry <- PgDocumentRegistry(config.database)
      chunks <- PgChunkStore(config.database)
    } yield new RAGService(config, registry, chunks, None)

  /**
   * Create a new RAGService with permission-based RAG enabled.
   * Requires a SearchIndex to be created and initialized first.
   *
   * @param config Application configuration
   * @param searchIndex Pre-configured SearchIndex for permission-aware operations
   */
  def createWithPermissions(config: AppConfig, searchIndex: SearchIndex): IO[RAGService] =
    for {
      registry <- PgDocumentRegistry(config.database)
      chunks <- PgChunkStore(config.database)
    } yield new RAGService(config, registry, chunks, Some(searchIndex))
}
