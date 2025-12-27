package ragbox.service

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.llmconnect.{LLMClient, LLMConnect}
import org.llm4s.llmconnect.config.{EmbeddingProviderConfig, OpenAIConfig, AnthropicConfig}
import org.llm4s.rag.{EmbeddingProvider, RAG, RAGConfig, RAGSearchResult, RAGAnswerResult, RAGStats}
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.types.Result
import ragbox.config.AppConfig
import ragbox.model._
import ragbox.registry.{DocumentEntry, DocumentRegistry, PgDocumentRegistry}

import java.security.MessageDigest
import java.time.Instant

/**
 * Service wrapping llm4s RAG functionality.
 *
 * Provides a high-level interface for document ingestion, search, and query operations.
 */

class RAGService(config: AppConfig, documentRegistry: DocumentRegistry) {

  private val startTime: Long = System.currentTimeMillis()

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
      .withPgVector(
        connectionString = config.database.connectionString,
        user = config.database.effectiveUser,
        password = config.database.effectivePassword,
        tableName = config.database.tableName
      )
      .withChunking(
        config.rag.chunking.toChunkerStrategy,
        config.rag.chunking.size,
        config.rag.chunking.overlap
      )
      .withTopK(config.rag.search.topK)
      .withFusion(config.rag.search.toFusionStrategy)
      .withSystemPrompt(config.rag.systemPrompt)

    // Add LLM client if available
    llmClient.fold(baseConfig)(baseConfig.withLLM)
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
  ): IO[DocumentUploadResponse] =
    rag.flatMap { r =>
      IO.fromEither(
        r.ingestText(content, documentId, metadata)
          .map(chunks => DocumentUploadResponse(documentId, chunks, s"Successfully ingested $chunks chunks"))
          .left.map(e => new RuntimeException(e.message))
      )
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
    } *> documentRegistry.remove(documentId)

  /**
   * Get RAG statistics.
   */
  def getStats: IO[StatsResponse] =
    for {
      r <- rag
      stats <- IO.fromEither(r.stats.left.map(e => new RuntimeException(e.message)))
      collectionStats <- getCollectionStats
    } yield StatsResponse(
      documentCount = stats.documentCount,
      chunkCount = stats.chunkCount,
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
    } *> documentRegistry.clear()

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
      ).flatMap { chunks =>
        val now = Instant.now()
        val collection = metadata.get("collection")
        documentRegistry.put(DocumentEntry(
          documentId = documentId,
          contentHash = contentHash,
          chunkCount = chunks,
          metadata = metadata,
          collection = collection,
          indexedAt = now,
          updatedAt = now
        )).as(DocumentUpsertResponse(
          documentId = documentId,
          chunks = chunks,
          action = "created",
          message = s"Successfully indexed $chunks chunks"
        ))
      }
    }

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
  def getCollectionStats: IO[Seq[CollectionStats]] =
    for {
      collections <- documentRegistry.listCollections()
      stats <- collections.traverse { coll =>
        documentRegistry.countByCollection(coll).map { count =>
          CollectionStats(name = coll, documentCount = count, chunkCount = 0) // Chunk count per collection requires llm4s changes
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
  // Health Operations
  // ============================================================

  /**
   * Basic health check.
   */
  def healthCheck: IO[HealthResponse] = IO.pure {
    HealthResponse(
      status = "healthy",
      version = "0.1.0",
      uptime = System.currentTimeMillis() - startTime
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
   * Close resources.
   */
  def close(): IO[Unit] =
    rag.flatMap(r => IO(r.close())) *> documentRegistry.close()
}

object RAGService {

  /**
   * Create a new RAGService with PostgreSQL-backed document registry.
   */
  def create(config: AppConfig): IO[RAGService] =
    PgDocumentRegistry(config.database).map { registry =>
      new RAGService(config, registry)
    }
}
