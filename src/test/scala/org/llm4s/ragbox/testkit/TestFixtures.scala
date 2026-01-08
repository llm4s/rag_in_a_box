package org.llm4s.ragbox.testkit

import org.llm4s.ragbox.config._
import org.llm4s.ragbox.ingestion.IngestionConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.DocumentEntry
import java.time.Instant

/**
 * Common test fixtures and sample data for tests.
 */
object TestFixtures {

  // ============================================================
  // Test Configurations
  // ============================================================

  val testDatabaseConfig: DatabaseConfig = DatabaseConfig(
    host = "localhost",
    port = 5432,
    database = "ragdb_test",
    user = "test",
    password = "test",
    tableName = "test_vectors",
    url = None
  )

  val testEmbeddingConfig: EmbeddingConfig = EmbeddingConfig(
    provider = "openai",
    model = "text-embedding-3-small",
    dimensions = Some(1536)
  )

  val testLLMConfig: LLMConfig = LLMConfig(
    model = "openai/gpt-4o-mini",
    temperature = 0.7
  )

  val testChunkingSettings: ChunkingSettings = ChunkingSettings(
    strategy = "simple",
    size = 500,
    overlap = 50
  )

  val testSearchSettings: SearchSettings = SearchSettings(
    topK = 5,
    fusionStrategy = "rrf",
    rrfK = 60
  )

  val testRAGSettings: RAGSettings = RAGSettings(
    chunking = testChunkingSettings,
    search = testSearchSettings,
    systemPrompt = "You are a helpful assistant."
  )

  val testServerConfig: ServerConfig = ServerConfig(
    host = "localhost",
    port = 8080
  )

  val testApiKeysConfig: ApiKeysConfig = ApiKeysConfig(
    openai = Some("test-openai-key"),
    anthropic = Some("test-anthropic-key"),
    voyage = None
  )

  val testAuthConfig: AuthConfig = AuthConfig(
    mode = AuthMode.Open,
    basic = BasicAuthConfig("admin", None),
    oauth = None,
    jwtSecret = "test-jwt-secret-at-least-32-chars",
    jwtSecretExplicitlySet = true,
    jwtExpiration = 3600L
  )

  val testSecurityConfig: SecurityConfig = SecurityConfig(
    apiKey = Some("test-api-key"),
    allowAdminHeader = false,
    auth = testAuthConfig
  )

  val testIngestionConfig: IngestionConfig = IngestionConfig(
    enabled = false,
    runOnStartup = false,
    schedule = None,
    sources = Seq.empty
  )

  val testMetricsConfig: MetricsConfig = MetricsConfig(
    enabled = false
  )

  val testProductionConfig: ProductionConfig = ProductionConfig(
    rateLimit = RateLimitConfig(enabled = false, maxRequests = 100, windowSeconds = 60),
    requestSize = RequestSizeConfig(enabled = false, maxBodySizeMb = 10),
    shutdown = ShutdownConfig(timeoutSeconds = 30, drainConnectionsSeconds = 5),
    cors = CorsConfig(allowedOrigins = Seq.empty, allowAllOrigins = true)
  )

  val testAppConfig: AppConfig = AppConfig(
    server = testServerConfig,
    database = testDatabaseConfig,
    embedding = testEmbeddingConfig,
    llm = testLLMConfig,
    rag = testRAGSettings,
    apiKeys = testApiKeysConfig,
    security = testSecurityConfig,
    ingestion = testIngestionConfig,
    metrics = testMetricsConfig,
    production = testProductionConfig
  )

  // ============================================================
  // Sample Documents
  // ============================================================

  val sampleDocument1: DocumentEntry = DocumentEntry(
    documentId = "doc-1",
    contentHash = "abc123hash",
    chunkCount = 3,
    metadata = Map("author" -> "Test Author", "collection" -> "test-collection"),
    collection = Some("test-collection"),
    indexedAt = Instant.parse("2024-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2024-01-01T00:00:00Z")
  )

  val sampleDocument2: DocumentEntry = DocumentEntry(
    documentId = "doc-2",
    contentHash = "def456hash",
    chunkCount = 5,
    metadata = Map("author" -> "Another Author", "collection" -> "another-collection"),
    collection = Some("another-collection"),
    indexedAt = Instant.parse("2024-01-02T00:00:00Z"),
    updatedAt = Instant.parse("2024-01-02T00:00:00Z")
  )

  val sampleDocument3: DocumentEntry = DocumentEntry(
    documentId = "doc-3",
    contentHash = "ghi789hash",
    chunkCount = 2,
    metadata = Map("author" -> "Third Author"),
    collection = None,
    indexedAt = Instant.parse("2024-01-03T00:00:00Z"),
    updatedAt = Instant.parse("2024-01-03T00:00:00Z")
  )

  val sampleDocuments: Seq[DocumentEntry] = Seq(sampleDocument1, sampleDocument2, sampleDocument3)

  // ============================================================
  // Sample Chunks
  // ============================================================

  val sampleChunk1: ChunkInfo = ChunkInfo(
    id = "doc-1-0",
    documentId = "doc-1",
    index = 0,
    content = "This is the first chunk of content for testing.",
    contentLength = 50,
    metadata = Map("collection" -> "test-collection"),
    chunkMetadata = ChunkMetadataInfo(
      headings = Seq("Introduction"),
      isCodeBlock = false,
      language = None,
      startOffset = Some(0),
      endOffset = Some(50)
    )
  )

  val sampleChunk2: ChunkInfo = ChunkInfo(
    id = "doc-1-1",
    documentId = "doc-1",
    index = 1,
    content = "This is the second chunk of content for testing.",
    contentLength = 52,
    metadata = Map("collection" -> "test-collection"),
    chunkMetadata = ChunkMetadataInfo(
      headings = Seq("Body"),
      isCodeBlock = false,
      language = None,
      startOffset = Some(50),
      endOffset = Some(102)
    )
  )

  val sampleChunk3: ChunkInfo = ChunkInfo(
    id = "doc-1-2",
    documentId = "doc-1",
    index = 2,
    content = "```scala\ndef example(): Unit = println(\"Hello\")\n```",
    contentLength = 55,
    metadata = Map("collection" -> "test-collection"),
    chunkMetadata = ChunkMetadataInfo(
      headings = Seq("Code Example"),
      isCodeBlock = true,
      language = Some("scala"),
      startOffset = Some(102),
      endOffset = Some(157)
    )
  )

  val sampleChunks: Seq[ChunkInfo] = Seq(sampleChunk1, sampleChunk2, sampleChunk3)

  // ============================================================
  // Sample Query Logs
  // ============================================================

  val sampleQueryLog1: QueryLogEntry = QueryLogEntry(
    id = "query-1",
    queryText = "What is the purpose of RAG?",
    collectionPattern = Some("test-collection"),
    userId = Some("user-1"),
    embeddingLatencyMs = Some(50),
    searchLatencyMs = Some(100),
    llmLatencyMs = Some(500),
    totalLatencyMs = 650,
    chunksRetrieved = 5,
    chunksUsed = 3,
    answerTokens = Some(150),
    userRating = Some(4),
    createdAt = Instant.parse("2024-01-01T10:00:00Z")
  )

  val sampleQueryLog2: QueryLogEntry = QueryLogEntry(
    id = "query-2",
    queryText = "How does embedding work?",
    collectionPattern = Some("another-collection"),
    userId = Some("user-2"),
    embeddingLatencyMs = Some(40),
    searchLatencyMs = Some(80),
    llmLatencyMs = Some(400),
    totalLatencyMs = 520,
    chunksRetrieved = 4,
    chunksUsed = 2,
    answerTokens = Some(200),
    userRating = None,
    createdAt = Instant.parse("2024-01-02T10:00:00Z")
  )

  val sampleQueryLogs: Seq[QueryLogEntry] = Seq(sampleQueryLog1, sampleQueryLog2)

  // ============================================================
  // Sample API Responses
  // ============================================================

  val sampleStatsResponse: StatsResponse = StatsResponse(
    documentCount = 3,
    chunkCount = 10,
    vectorCount = 10,
    collections = Seq(
      CollectionStats("test-collection", 1, 3),
      CollectionStats("another-collection", 1, 5)
    )
  )

  val sampleContextItem: ContextItem = ContextItem(
    content = "This is relevant context for the query.",
    score = 0.85,
    metadata = Map("docId" -> "doc-1", "collection" -> "test-collection"),
    documentId = Some("doc-1"),
    chunkIndex = Some(0)
  )

  val sampleSearchResponse: SearchResponse = SearchResponse(
    results = Seq(sampleContextItem),
    count = 1
  )

  val sampleQueryResponse: QueryResponse = QueryResponse(
    answer = "RAG combines retrieval and generation for better answers.",
    contexts = Seq(sampleContextItem),
    usage = Some(UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150))
  )

  val sampleHealthResponse: HealthResponse = HealthResponse(
    status = "healthy",
    version = "0.1.0",
    uptime = 60000L,
    system = Some(SystemInfo(
      memoryUsedMb = 256,
      memoryMaxMb = 1024,
      memoryFreePercent = 75,
      cpuCount = 4,
      javaVersion = "21.0.1"
    ))
  )

  // ============================================================
  // Helper Methods
  // ============================================================

  def createDocumentEntry(
    id: String,
    collection: Option[String] = None,
    chunkCount: Int = 1
  ): DocumentEntry = DocumentEntry(
    documentId = id,
    contentHash = s"hash-$id",
    chunkCount = chunkCount,
    metadata = collection.map(c => Map("collection" -> c)).getOrElse(Map.empty),
    collection = collection,
    indexedAt = Instant.now(),
    updatedAt = Instant.now()
  )

  def createChunkInfo(
    documentId: String,
    index: Int,
    content: String
  ): ChunkInfo = ChunkInfo(
    id = s"$documentId-$index",
    documentId = documentId,
    index = index,
    content = content,
    contentLength = content.length,
    metadata = Map.empty,
    chunkMetadata = ChunkMetadataInfo(
      headings = Seq.empty,
      isCodeBlock = false,
      language = None,
      startOffset = Some(0),
      endOffset = Some(content.length)
    )
  )

  def createQueryLogEntry(
    id: String,
    queryText: String,
    totalLatencyMs: Int = 100
  ): QueryLogEntry = QueryLogEntry(
    id = id,
    queryText = queryText,
    collectionPattern = None,
    userId = None,
    embeddingLatencyMs = Some(20),
    searchLatencyMs = Some(30),
    llmLatencyMs = Some(50),
    totalLatencyMs = totalLatencyMs,
    chunksRetrieved = 5,
    chunksUsed = 3,
    answerTokens = Some(100),
    userRating = None,
    createdAt = Instant.now()
  )
}
