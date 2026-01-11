package org.llm4s.ragbox.model

import java.time.Instant
import java.util.UUID

// ============================================================
// Document Models
// ============================================================

/**
 * Request to upload a document.
 *
 * @param content The document content
 * @param filename The filename for the document
 * @param metadata Optional metadata key-value pairs
 * @param collection Optional collection path (e.g., "acme/engineering")
 * @param readableBy Optional list of principals who can read this document.
 *                   Format: "user:alice@example.com" or "group:managers"
 *                   If empty, document inherits collection permissions.
 */
final case class DocumentUploadRequest(
  content: String,
  filename: String,
  metadata: Option[Map[String, String]] = None,
  collection: Option[String] = None,
  readableBy: Option[Seq[String]] = None
)

/**
 * Response after uploading a document.
 */
final case class DocumentUploadResponse(
  documentId: String,
  chunks: Int,
  message: String
)

/**
 * Document information for listing.
 */
final case class DocumentInfo(
  id: String,
  filename: String,
  chunkCount: Int,
  metadata: Map[String, String],
  createdAt: Option[Instant]
)

/**
 * Response for listing documents.
 */
final case class DocumentListResponse(
  documents: Seq[DocumentInfo],
  total: Int
)

/**
 * Request to upload document from URL.
 */
final case class UrlIngestionRequest(
  url: String,
  metadata: Option[Map[String, String]] = None,
  collection: Option[String] = None
)

// ============================================================
// Upsert Models (for SDK/Sidecar pattern)
// ============================================================

/**
 * Request to upsert a document (idempotent create/update).
 * Used by SDK clients for incremental ingestion.
 *
 * @param content The document content
 * @param metadata Optional metadata key-value pairs
 * @param contentHash Optional content hash - server computes if not provided
 * @param collection Optional collection path (e.g., "acme/engineering")
 * @param readableBy Optional list of principals who can read this document.
 *                   Format: "user:alice@example.com" or "group:managers"
 */
final case class DocumentUpsertRequest(
  content: String,
  metadata: Option[Map[String, String]] = None,
  contentHash: Option[String] = None,
  collection: Option[String] = None,
  readableBy: Option[Seq[String]] = None
)

/**
 * Response after upserting a document.
 */
final case class DocumentUpsertResponse(
  documentId: String,
  chunks: Int,
  action: String,  // "created", "updated", "unchanged"
  message: String
)

/**
 * Request to prune documents not in the provided list.
 * Used for garbage collection after sync.
 *
 * @param keepDocumentIds List of document IDs to keep (all others will be deleted)
 * @param dryRun If true, returns what would be deleted without actually deleting
 */
final case class SyncPruneRequest(
  keepDocumentIds: Seq[String],
  dryRun: Option[Boolean] = None
)

/**
 * Response from prune operation.
 */
final case class SyncPruneResponse(
  message: String,
  prunedCount: Int,
  prunedIds: Option[Seq[String]] = None,  // Only included when dryRun=true
  dryRun: Boolean = false
)

/**
 * Document state for sync operations.
 * Used by external ingesters to check document status.
 */
final case class DocumentSyncState(
  id: String,
  hash: Option[String] = None,
  updatedAt: Option[Instant] = None,
  collection: Option[String] = None
)

/**
 * Response for listing document sync states.
 */
final case class DocumentSyncListResponse(
  documents: Seq[DocumentSyncState],
  total: Int
)

/**
 * Request to batch check document states.
 */
final case class SyncCheckRequest(
  documentIds: Seq[String]
)

/**
 * Response from batch document state check.
 */
final case class SyncCheckResponse(
  found: Seq[DocumentSyncState],
  missing: Seq[String]
)

/**
 * Request to delete multiple documents at once.
 *
 * @param documentIds List of document IDs to delete
 * @param dryRun If true, returns what would be deleted without actually deleting
 */
final case class BatchDeleteRequest(
  documentIds: Seq[String],
  dryRun: Option[Boolean] = None
)

/**
 * Response from batch delete operation.
 */
final case class BatchDeleteResponse(
  message: String,
  deletedCount: Int,
  deletedIds: Option[Seq[String]] = None,
  failedIds: Option[Seq[String]] = None,
  dryRun: Boolean = false
)

/**
 * Sync status response.
 */
final case class SyncStatusResponse(
  lastSyncTime: Option[Instant],
  documentCount: Int,
  chunkCount: Int,
  pendingDeletes: Int
)

// ============================================================
// Query Models
// ============================================================

/**
 * Request to query the RAG system with answer generation.
 */
final case class QueryRequest(
  question: String,
  collection: Option[String] = None,
  topK: Option[Int] = None,
  includeMetadata: Option[Boolean] = Some(true)
)

/**
 * Response from query with answer.
 */
final case class QueryResponse(
  answer: String,
  contexts: Seq[ContextItem],
  usage: Option[UsageInfo] = None
)

/**
 * A context item (chunk) used to generate the answer.
 */
final case class ContextItem(
  content: String,
  score: Double,
  metadata: Map[String, String],
  documentId: Option[String] = None,
  chunkIndex: Option[Int] = None
)

/**
 * Token usage information.
 */
final case class UsageInfo(
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int
)

/**
 * Request to search without answer generation.
 */
final case class SearchRequest(
  query: String,
  topK: Option[Int] = None,
  collection: Option[String] = None
)

/**
 * Response from search (no LLM).
 */
final case class SearchResponse(
  results: Seq[ContextItem],
  count: Int
)

// ============================================================
// Configuration Models
// ============================================================

/**
 * Current configuration status.
 */
final case class ConfigResponse(
  embedding: EmbeddingConfigInfo,
  llm: LLMConfigInfo,
  rag: RAGConfigInfo,
  database: DatabaseConfigInfo
)

final case class EmbeddingConfigInfo(
  provider: String,
  model: String,
  dimensions: Option[Int]
)

final case class LLMConfigInfo(
  model: String,
  temperature: Double
)

final case class RAGConfigInfo(
  chunkingStrategy: String,
  chunkSize: Int,
  chunkOverlap: Int,
  topK: Int,
  fusionStrategy: String
)

final case class DatabaseConfigInfo(
  host: String,
  port: Int,
  database: String,
  tableName: String,
  connected: Boolean
)

/**
 * Available providers.
 */
final case class ProvidersResponse(
  embeddingProviders: Seq[ProviderInfo],
  llmProviders: Seq[ProviderInfo],
  chunkingStrategies: Seq[String],
  fusionStrategies: Seq[String]
)

final case class ProviderInfo(
  name: String,
  models: Seq[String],
  configured: Boolean
)

// ============================================================
// Health Models
// ============================================================

/**
 * Health check response with system info.
 */
final case class HealthResponse(
  status: String,
  version: String,
  uptime: Long,
  system: Option[SystemInfo] = None
)

/**
 * System information for health checks.
 */
final case class SystemInfo(
  memoryUsedMb: Long,
  memoryMaxMb: Long,
  memoryFreePercent: Int,
  cpuCount: Int,
  javaVersion: String
)

/**
 * Readiness check response.
 */
final case class ReadinessResponse(
  ready: Boolean,
  checks: Map[String, CheckStatus]
)

final case class CheckStatus(
  status: String,
  message: Option[String] = None
)

// ============================================================
// Statistics Models
// ============================================================

/**
 * RAG system statistics.
 */
final case class StatsResponse(
  documentCount: Int,
  chunkCount: Int,
  vectorCount: Long,
  collections: Seq[CollectionStats]
)

final case class CollectionStats(
  name: String,
  documentCount: Int,
  chunkCount: Int
)

// ============================================================
// Ingestion Models
// ============================================================

/**
 * Request to ingest from a directory.
 */
final case class DirectoryIngestRequest(
  path: String,
  patterns: Option[Seq[String]] = None, // Default: ["*.md", "*.txt", "*.pdf"]
  recursive: Option[Boolean] = None, // Default: true
  metadata: Option[Map[String, String]] = None
)

/**
 * Request to ingest from URLs.
 */
final case class UrlIngestRequest(
  urls: Seq[String],
  metadata: Option[Map[String, String]] = None
)

/**
 * Response from an ingestion operation.
 */
final case class IngestResponse(
  sourceName: String,
  sourceType: String,
  documentsAdded: Int,
  documentsUpdated: Int,
  documentsDeleted: Int,
  documentsUnchanged: Int,
  documentsFailed: Int,
  durationMs: Long,
  error: Option[String] = None
)

/**
 * Source configuration info.
 */
final case class SourceInfo(
  name: String,
  sourceType: String,
  enabled: Boolean,
  config: Map[String, String]
)

/**
 * Ingestion status response.
 */
final case class IngestionStatusResponse(
  running: Boolean,
  lastRun: Option[Instant],
  lastResults: Seq[IngestResponse],
  nextScheduledRun: Option[Instant],
  sources: Seq[SourceInfo]
)

// ============================================================
// Error Models
// ============================================================

/**
 * Error response.
 */
final case class ErrorResponse(
  error: String,
  message: String,
  details: Option[String] = None
)

object ErrorResponse {
  def badRequest(message: String, details: Option[String] = None): ErrorResponse =
    ErrorResponse("bad_request", message, details)

  def notFound(message: String): ErrorResponse =
    ErrorResponse("not_found", message)

  def internalError(message: String, details: Option[String] = None): ErrorResponse =
    ErrorResponse("internal_error", message, details)

  def configError(message: String): ErrorResponse =
    ErrorResponse("configuration_error", message)

  def unauthorized(message: String): ErrorResponse =
    ErrorResponse("unauthorized", message)
}

// ============================================================
// Query Analytics Models
// ============================================================

/**
 * A logged query for analytics.
 */
final case class QueryLogEntry(
  id: String,
  queryText: String,
  collectionPattern: Option[String],
  userId: Option[String],

  // Timing (in milliseconds)
  embeddingLatencyMs: Option[Int],
  searchLatencyMs: Option[Int],
  llmLatencyMs: Option[Int],
  totalLatencyMs: Int,

  // Results
  chunksRetrieved: Int,
  chunksUsed: Int,
  answerTokens: Option[Int],

  // Feedback
  userRating: Option[Int],

  createdAt: Instant
)

/**
 * Request to submit feedback for a query.
 */
final case class QueryFeedbackRequest(
  queryId: String,
  rating: Int,               // 1-5 scale
  relevantChunks: Option[Seq[String]] = None,
  comment: Option[String] = None
)

/**
 * Response from submitting feedback.
 */
final case class QueryFeedbackResponse(
  success: Boolean,
  message: String
)

/**
 * Query analytics summary.
 */
final case class QueryAnalyticsSummary(
  totalQueries: Int,
  averageLatencyMs: Double,
  p50LatencyMs: Int,
  p95LatencyMs: Int,
  p99LatencyMs: Int,
  averageChunksRetrieved: Double,
  averageChunksUsed: Double,
  averageRating: Option[Double],
  ratedQueriesCount: Int,
  queriesWithFeedback: Int,
  topCollections: Seq[CollectionQueryStats],
  periodStart: Instant,
  periodEnd: Instant
)

/**
 * Query stats by collection.
 */
final case class CollectionQueryStats(
  collection: String,
  queryCount: Int,
  averageLatencyMs: Double,
  averageRating: Option[Double]
)

/**
 * Query log list response.
 */
final case class QueryLogListResponse(
  queries: Seq[QueryLogEntry],
  total: Int,
  page: Int,
  pageSize: Int
)

// ============================================================
// SSE Streaming Models
// ============================================================

/**
 * Base trait for Server-Sent Events during query streaming.
 */
sealed trait QueryStreamEvent {
  def eventType: String
  def timestamp: Instant
}

/**
 * Event indicating query processing has started.
 */
final case class QueryStartEvent(
  queryId: String,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent {
  val eventType: String = "start"
}

/**
 * Event containing retrieved context/chunk.
 */
final case class QueryContextEvent(
  context: ContextItem,
  index: Int,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent {
  val eventType: String = "context"
}

/**
 * Event containing a chunk of the answer (for streaming LLM responses).
 */
final case class QueryChunkEvent(
  chunk: String,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent {
  val eventType: String = "chunk"
}

/**
 * Event containing the complete answer (when streaming not available).
 */
final case class QueryAnswerEvent(
  answer: String,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent {
  val eventType: String = "answer"
}

/**
 * Event containing usage information.
 */
final case class QueryUsageEvent(
  usage: UsageInfo,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent {
  val eventType: String = "usage"
}

/**
 * Event indicating query completed successfully.
 */
final case class QueryCompleteEvent(
  queryId: String,
  totalContexts: Int,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent {
  val eventType: String = "complete"
}

/**
 * Event indicating an error occurred.
 */
final case class QueryErrorEvent(
  error: String,
  message: String,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent {
  val eventType: String = "error"
}
