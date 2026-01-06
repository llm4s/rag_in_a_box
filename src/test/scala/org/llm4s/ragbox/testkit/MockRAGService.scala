package org.llm4s.ragbox.testkit

import cats.effect.IO
import org.llm4s.ragbox.config._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{DocumentEntry, DocumentRegistry}
import org.llm4s.ragbox.service.RAGService
import org.llm4s.ragbox.store.ChunkStore

import java.time.Instant
import java.security.MessageDigest

/**
 * Mock RAGService for fast testing of routes.
 *
 * Provides configurable responses for all RAGService methods.
 * Uses in-memory registries by default.
 */
class MockRAGService(
  config: AppConfig = TestFixtures.testAppConfig,
  val documentRegistry: InMemoryDocumentRegistry = InMemoryDocumentRegistry(),
  val chunkStore: InMemoryChunkStore = InMemoryChunkStore()
) extends RAGService(config, documentRegistry, chunkStore, None) {

  // Default responses that can be overridden
  private var searchResponse: SearchResponse = SearchResponse(Seq.empty, 0)
  private var queryResponse: QueryResponse = QueryResponse("Mock answer", Seq.empty, None)
  private var statsResponse: StatsResponse = StatsResponse(0, 0, 0, Seq.empty)
  private var errorToThrow: Option[Throwable] = None

  // Track method calls for assertions
  private var searchCalls: Seq[(String, Option[Int])] = Seq.empty
  private var queryCalls: Seq[(String, Option[Int])] = Seq.empty
  private var ingestCalls: Seq[(String, String, Map[String, String])] = Seq.empty
  private var deleteCalls: Seq[String] = Seq.empty

  // ============================================================
  // Configuration Methods
  // ============================================================

  /**
   * Set the response for search operations.
   */
  def setSearchResponse(response: SearchResponse): MockRAGService = {
    searchResponse = response
    this
  }

  /**
   * Set the response for query operations.
   */
  def setQueryResponse(response: QueryResponse): MockRAGService = {
    queryResponse = response
    this
  }

  /**
   * Set the response for stats operations.
   */
  def setStatsResponse(response: StatsResponse): MockRAGService = {
    statsResponse = response
    this
  }

  /**
   * Configure to throw an error on next call.
   */
  def setError(error: Throwable): MockRAGService = {
    errorToThrow = Some(error)
    this
  }

  /**
   * Clear error state.
   */
  def clearError(): MockRAGService = {
    errorToThrow = None
    this
  }

  // ============================================================
  // Override Core Methods
  // ============================================================

  override def search(query: String, topK: Option[Int]): IO[SearchResponse] = {
    searchCalls = searchCalls :+ (query, topK)
    errorToThrow match {
      case Some(e) => IO.raiseError(e)
      case None => IO.pure(searchResponse)
    }
  }

  override def queryWithAnswer(question: String, topK: Option[Int]): IO[QueryResponse] = {
    queryCalls = queryCalls :+ (question, topK)
    errorToThrow match {
      case Some(e) => IO.raiseError(e)
      case None => IO.pure(queryResponse)
    }
  }

  override def getStats: IO[StatsResponse] = {
    errorToThrow match {
      case Some(e) => IO.raiseError(e)
      case None =>
        for {
          docCount <- documentRegistry.count()
          chunkCount <- chunkStore.count()
          collections <- getCollectionStats
        } yield statsResponse.copy(
          documentCount = docCount,
          chunkCount = chunkCount,
          collections = collections
        )
    }
  }

  override def ingestDocument(
    content: String,
    documentId: String,
    metadata: Map[String, String]
  ): IO[DocumentUploadResponse] = {
    ingestCalls = ingestCalls :+ (documentId, content, metadata)
    errorToThrow match {
      case Some(e) => IO.raiseError(e)
      case None =>
        val now = Instant.now()
        val hash = computeHash(content)
        val entry = DocumentEntry(
          documentId = documentId,
          contentHash = hash,
          chunkCount = 3, // Mock chunk count
          metadata = metadata,
          collection = metadata.get("collection"),
          indexedAt = now,
          updatedAt = now
        )
        for {
          _ <- documentRegistry.put(entry)
          _ <- chunkStore.store(documentId, createMockChunks(documentId, content, metadata.get("collection")))
        } yield DocumentUploadResponse(documentId, 3, "Successfully ingested 3 chunks")
    }
  }

  override def upsertDocument(
    documentId: String,
    content: String,
    metadata: Map[String, String],
    providedHash: Option[String]
  ): IO[DocumentUpsertResponse] = {
    val hash = providedHash.getOrElse(computeHash(content))

    documentRegistry.get(documentId).flatMap {
      case Some(existing) if existing.contentHash == hash =>
        IO.pure(DocumentUpsertResponse(documentId, existing.chunkCount, "unchanged", "Document unchanged"))

      case Some(_) =>
        for {
          _ <- deleteDocument(documentId)
          result <- ingestDocument(content, documentId, metadata)
        } yield DocumentUpsertResponse(documentId, result.chunks, "updated", result.message)

      case None =>
        ingestDocument(content, documentId, metadata).map { result =>
          DocumentUpsertResponse(documentId, result.chunks, "created", result.message)
        }
    }
  }

  override def deleteDocument(documentId: String): IO[Unit] = {
    deleteCalls = deleteCalls :+ documentId
    errorToThrow match {
      case Some(e) => IO.raiseError(e)
      case None =>
        for {
          _ <- documentRegistry.remove(documentId)
          _ <- chunkStore.deleteByDocument(documentId)
        } yield ()
    }
  }

  override def clearAll: IO[Unit] =
    for {
      _ <- documentRegistry.clear()
      _ <- chunkStore.clear()
    } yield ()

  override def getSyncStatus: IO[SyncStatusResponse] =
    for {
      docCount <- documentRegistry.count()
      chunkCount <- chunkStore.count()
      syncInfo <- documentRegistry.getSyncInfo()
    } yield SyncStatusResponse(
      lastSyncTime = syncInfo.lastSyncTime,
      documentCount = docCount,
      chunkCount = chunkCount,
      pendingDeletes = 0
    )

  override def listDocumentIds: IO[Seq[String]] =
    documentRegistry.listIds()

  override def listDocumentEntries: IO[Seq[DocumentEntry]] =
    documentRegistry.listEntries()

  override def listCollections: IO[Seq[String]] =
    documentRegistry.listCollections()

  override def getCollectionStats: IO[Seq[CollectionStats]] =
    for {
      collections <- documentRegistry.listCollections()
      stats <- cats.effect.IO.pure(collections.map { coll =>
        CollectionStats(coll, 1, 3) // Mock counts
      })
    } yield stats

  override def listChunks(page: Int, pageSize: Int): IO[ChunkListResponse] =
    chunkStore.listChunks(page, pageSize)

  override def getDocumentChunks(documentId: String): IO[Option[DocumentChunksResponse]] =
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

  override def getChunk(documentId: String, index: Int): IO[Option[ChunkInfo]] =
    chunkStore.getChunk(documentId, index)

  override def healthCheck: IO[HealthResponse] = IO.pure(
    HealthResponse(
      status = "healthy",
      version = "0.1.0",
      uptime = 60000L,
      system = Some(SystemInfo(256, 1024, 75, 4, "21"))
    )
  )

  override def readinessCheck: IO[ReadinessResponse] = IO.pure(
    ReadinessResponse(
      ready = true,
      checks = Map(
        "database" -> CheckStatus("ok"),
        "api_keys" -> CheckStatus("ok")
      )
    )
  )

  override def getConfigInfo: IO[ConfigResponse] = IO.pure(
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
        connected = true
      )
    )
  )

  override def getProviders: IO[ProvidersResponse] = IO.pure(
    ProvidersResponse(
      embeddingProviders = Seq(
        ProviderInfo("openai", Seq("text-embedding-3-small"), configured = true)
      ),
      llmProviders = Seq(
        ProviderInfo("openai", Seq("gpt-4o-mini"), configured = true)
      ),
      chunkingStrategies = Seq("simple", "sentence", "markdown"),
      fusionStrategies = Seq("rrf", "weighted")
    )
  )

  override def close(): IO[Unit] = IO.unit

  // ============================================================
  // Assertion Helpers
  // ============================================================

  /**
   * Get all search calls made.
   */
  def getSearchCalls: Seq[(String, Option[Int])] = searchCalls

  /**
   * Get all query calls made.
   */
  def getQueryCalls: Seq[(String, Option[Int])] = queryCalls

  /**
   * Get all ingest calls made.
   */
  def getIngestCalls: Seq[(String, String, Map[String, String])] = ingestCalls

  /**
   * Get all delete calls made.
   */
  def getDeleteCalls: Seq[String] = deleteCalls

  /**
   * Reset all call tracking.
   */
  def resetCalls(): Unit = {
    searchCalls = Seq.empty
    queryCalls = Seq.empty
    ingestCalls = Seq.empty
    deleteCalls = Seq.empty
  }

  /**
   * Reset everything (calls, registries, errors).
   */
  def reset(): Unit = {
    resetCalls()
    documentRegistry.reset()
    chunkStore.reset()
    errorToThrow = None
  }

  // ============================================================
  // Private Helpers
  // ============================================================

  private def computeHash(content: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(content.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  private def createMockChunks(documentId: String, content: String, collection: Option[String]): Seq[ChunkInfo] = {
    val chunkSize = 100
    val chunks = content.grouped(chunkSize).zipWithIndex.map { case (text, idx) =>
      ChunkInfo(
        id = s"$documentId-$idx",
        documentId = documentId,
        index = idx,
        content = text,
        contentLength = text.length,
        metadata = collection.map(c => Map("collection" -> c)).getOrElse(Map.empty),
        chunkMetadata = ChunkMetadataInfo(
          headings = Seq.empty,
          isCodeBlock = false,
          language = None,
          startOffset = Some(idx * chunkSize),
          endOffset = Some(idx * chunkSize + text.length)
        )
      )
    }.toSeq
    chunks.take(3) // Return at most 3 chunks for consistency
  }
}

object MockRAGService {
  /**
   * Create a new mock RAG service.
   */
  def apply(): MockRAGService = new MockRAGService()

  /**
   * Create a mock RAG service with custom config.
   */
  def withConfig(config: AppConfig): MockRAGService = new MockRAGService(config)

  /**
   * Create a mock RAG service pre-seeded with documents.
   */
  def withDocuments(entries: Seq[DocumentEntry]): MockRAGService = {
    val service = new MockRAGService()
    service.documentRegistry.seed(entries)
    service
  }
}
