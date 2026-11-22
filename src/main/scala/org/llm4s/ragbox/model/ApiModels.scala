package org.llm4s.ragbox.model

import io.circe.Codec

import java.time.Instant
import java.util.UUID

// ============================================================
// Document Models
// ============================================================

/**
 * Request to upload a document.
 *
 * @param content The document content
 * @param filename Optional filename for the document. When omitted (e.g. pasted
 *                 text) the server assigns `untitled-<id prefix>.txt`.
 * @param metadata Optional metadata key-value pairs
 * @param collection Optional collection path (e.g., "acme/engineering")
 * @param readableBy Optional list of principals who can read this document.
 *                   Format: "user:alice@example.com" or "group:managers"
 *                   If empty, document inherits collection permissions.
 */
final case class DocumentUploadRequest(
  content: String,
  filename: Option[String] = None,
  metadata: Option[Map[String, String]] = None,
  collection: Option[String] = None,
  readableBy: Option[Seq[String]] = None
) derives Codec.AsObject

/**
 * Response after uploading a document.
 */
final case class DocumentUploadResponse(
  documentId: String,
  chunks: Int,
  message: String
) derives Codec.AsObject

/**
 * Document information for listing and detail views.
 *
 * @param collection Collection the document belongs to (if any)
 * @param updatedAt  When the document was last re-indexed
 */
final case class DocumentInfo(
  id: String,
  filename: String,
  chunkCount: Int,
  metadata: Map[String, String],
  createdAt: Option[Instant],
  collection: Option[String] = None,
  updatedAt: Option[Instant] = None
) derives Codec.AsObject

/**
 * Response for listing documents.
 *
 * @param total    Number of documents matching the filters (across all pages)
 * @param page     1-based page number returned
 * @param pageSize Page size used
 * @param hasMore  Whether more pages are available
 */
final case class DocumentListResponse(
  documents: Seq[DocumentInfo],
  total: Int,
  page: Int = 1,
  pageSize: Int = 0,
  hasMore: Boolean = false
) derives Codec.AsObject

/**
 * Request to upload document from URL.
 */
final case class UrlIngestionRequest(
  url: String,
  metadata: Option[Map[String, String]] = None,
  collection: Option[String] = None
) derives Codec.AsObject

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
 * @param source The ingester (sync source) the document belongs to; `POST /sync` prunes one
 *               source's documents at a time. Defaults to `api` (see `DocumentRoutes.DefaultSyncSource`).
 */
final case class DocumentUpsertRequest(
  content: String,
  metadata: Option[Map[String, String]] = None,
  contentHash: Option[String] = None,
  collection: Option[String] = None,
  readableBy: Option[Seq[String]] = None,
  source: Option[String] = None
) derives Codec.AsObject

/**
 * Response after upserting a document.
 */
final case class DocumentUpsertResponse(
  documentId: String,
  chunks: Int,
  action: String,  // "created", "updated", "unchanged"
  message: String
) derives Codec.AsObject

/**
 * Request to prune documents not in the provided list.
 * Used for garbage collection after sync.
 *
 * @param keepDocumentIds Document IDs to keep; the other documents in scope are deleted
 * @param dryRun If true, returns what would be deleted without actually deleting
 * @param source With the default scope, the sync source whose documents are pruned (default
 *               `api`: documents written by `PUT /documents/{id}` without a `source`)
 * @param scope `source` (the default): only `source`'s documents are pruned. `all` (admin
 *              only, explicit opt-in): every document, whoever wrote it, as before #54
 */
final case class SyncPruneRequest(
  keepDocumentIds: Seq[String],
  dryRun: Option[Boolean] = None,
  source: Option[String] = None,
  scope: Option[String] = None
) derives Codec.AsObject

/**
 * Response from prune operation.
 */
final case class SyncPruneResponse(
  message: String,
  prunedCount: Int,
  prunedIds: Option[Seq[String]] = None,  // Only included when dryRun=true
  dryRun: Boolean = false,
  scope: Option[String] = None,  // "source" or "all", when the request pruned
  source: Option[String] = None  // the source pruned (scope "source")
) derives Codec.AsObject

/**
 * Document state for sync operations.
 * Used by external ingesters to check document status.
 */
final case class DocumentSyncState(
  id: String,
  hash: Option[String] = None,
  updatedAt: Option[Instant] = None,
  collection: Option[String] = None,
  source: Option[String] = None  // the sync source that owns the document (none for uploads)
) derives Codec.AsObject

/**
 * A page of document sync states.
 *
 * @param total   documents matching the request across all pages
 * @param hasMore whether a later page has more
 */
final case class DocumentSyncListResponse(
  documents: Seq[DocumentSyncState],
  total: Int,
  page: Int,
  pageSize: Int,
  hasMore: Boolean
) derives Codec.AsObject

/**
 * Request to batch check document states.
 */
final case class SyncCheckRequest(
  documentIds: Seq[String]
) derives Codec.AsObject

/**
 * Response from batch document state check.
 */
final case class SyncCheckResponse(
  found: Seq[DocumentSyncState],
  missing: Seq[String]
) derives Codec.AsObject

/**
 * Request to delete multiple documents at once.
 *
 * @param documentIds List of document IDs to delete
 * @param dryRun If true, returns what would be deleted without actually deleting
 */
final case class BatchDeleteRequest(
  documentIds: Seq[String],
  dryRun: Option[Boolean] = None
) derives Codec.AsObject

/**
 * Response from batch delete operation.
 */
final case class BatchDeleteResponse(
  message: String,
  deletedCount: Int,
  deletedIds: Option[Seq[String]] = None,
  failedIds: Option[Seq[String]] = None,
  dryRun: Boolean = false
) derives Codec.AsObject

/**
 * Sync status response. (Deletes are applied as they are requested, so nothing is ever pending.)
 */
final case class SyncStatusResponse(
  lastSyncTime: Option[Instant],
  documentCount: Int,
  chunkCount: Int
) derives Codec.AsObject

// ============================================================
// Query Models
// ============================================================

/**
 * Override parameters for query experimentation.
 * Allows testing different configurations without changing system defaults.
 *
 * @param llmTemperature      rejected (400): llm4s 0.6.0 answers `/query` at temperature 0.0
 *                            (llm4s/llm4s#2715; ragbox #40 adopts the fix)
 * @param similarityThreshold minimum vector similarity (0 to 1) of a chunk to be used for the
 *                            answer and returned; keyword-only matches are kept
 */
final case class QueryOverrides(
  topK: Option[Int] = None,
  fusionStrategy: Option[String] = None,
  systemPrompt: Option[String] = None,
  llmTemperature: Option[Double] = None,
  similarityThreshold: Option[Double] = None
) derives Codec.AsObject

/**
 * Request to query the RAG system with answer generation.
 *
 * @param question The query question
 * @param collection Optional collection filter
 * @param topK Optional number of chunks to retrieve
 * @param includeMetadata Whether contexts carry their chunk metadata (default true)
 * @param experimentId Optional experiment ID to track this query
 * @param overrides Optional parameter overrides for experimentation
 */
final case class QueryRequest(
  question: String,
  collection: Option[String] = None,
  topK: Option[Int] = None,
  includeMetadata: Option[Boolean] = Some(true),
  experimentId: Option[String] = None,
  overrides: Option[QueryOverrides] = None
) derives Codec.AsObject

/**
 * Response from query with answer.
 */
final case class QueryResponse(
  answer: String,
  contexts: Seq[ContextItem],
  usage: Option[UsageInfo] = None,
  metrics: Option[QueryMetrics] = None,
  queryId: Option[String] = None
) derives Codec.AsObject

/**
 * Per-query metrics, from what llm4s traced while answering (`RAGConfig.withTracing` and
 * `TracingLLMClient`).
 *
 * @param embeddingLatencyMs time spent embedding the query, when the provider reported it
 * @param searchLatencyMs    the rest of retrieval (search, reranking, query rewriting); known
 *                           only when the embedding time is
 * @param llmLatencyMs       time spent generating the answer
 * @param embeddingTokens    tokens embedded
 * @param promptTokens       LLM prompt tokens, over every completion (rewriting, reranking, answer)
 * @param completionTokens   LLM completion tokens, over every completion
 * @param costUsd            estimated cost, for models whose pricing llm4s knows
 */
final case class QueryMetrics(
  embeddingLatencyMs: Option[Int],
  searchLatencyMs: Option[Int],
  llmLatencyMs: Option[Int],
  chunksRetrieved: Int,
  chunksUsed: Int,
  embeddingTokens: Option[Int] = None,
  promptTokens: Option[Int] = None,
  completionTokens: Option[Int] = None,
  costUsd: Option[Double] = None
) derives Codec.AsObject

/**
 * A context item (chunk) used to generate the answer.
 */
final case class ContextItem(
  content: String,
  score: Double,
  metadata: Map[String, String],
  documentId: Option[String] = None,
  chunkIndex: Option[Int] = None
) derives Codec.AsObject

/**
 * Token usage information.
 */
final case class UsageInfo(
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int
) derives Codec.AsObject

/**
 * Request to search without answer generation.
 */
final case class SearchRequest(
  query: String,
  topK: Option[Int] = None,
  collection: Option[String] = None
) derives Codec.AsObject

/**
 * Response from search (no LLM).
 */
final case class SearchResponse(
  results: Seq[ContextItem],
  count: Int
) derives Codec.AsObject

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
) derives Codec.AsObject

final case class EmbeddingConfigInfo(
  provider: String,
  model: String,
  dimensions: Option[Int]
) derives Codec.AsObject

final case class LLMConfigInfo(
  model: String,
  temperature: Double
) derives Codec.AsObject

/**
 * @param searchMode     `hybrid` (vector + keyword, fused) or `vector` (permission mode, where
 *                       llm4s search is vector-only: llm4s/llm4s#2716)
 * @param fusionApplies  whether `fusionStrategy` and `rrfK` affect search
 * @param reranking      reranking strategy: none, cohere or llm
 * @param queryTransform pre-retrieval query transform: none or llm-rewrite
 * @param limitations    settings that do not take effect, and why
 */
final case class RAGConfigInfo(
  chunkingStrategy: String,
  chunkSize: Int,
  chunkOverlap: Int,
  topK: Int,
  fusionStrategy: String,
  rrfK: Int = 60,
  searchMode: String = "hybrid",
  fusionApplies: Boolean = true,
  reranking: String = "none",
  queryTransform: String = "none",
  limitations: Seq[String] = Seq.empty
) derives Codec.AsObject

final case class DatabaseConfigInfo(
  host: String,
  port: Int,
  database: String,
  tableName: String,
  connected: Boolean
) derives Codec.AsObject

/**
 * Available providers.
 */
final case class ProvidersResponse(
  embeddingProviders: Seq[ProviderInfo],
  llmProviders: Seq[ProviderInfo],
  chunkingStrategies: Seq[String],
  fusionStrategies: Seq[String]
) derives Codec.AsObject

final case class ProviderInfo(
  name: String,
  models: Seq[String],
  configured: Boolean
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * System information for health checks.
 */
final case class SystemInfo(
  memoryUsedMb: Long,
  memoryMaxMb: Long,
  memoryFreePercent: Int,
  cpuCount: Int,
  javaVersion: String
) derives Codec.AsObject

/**
 * Readiness check response.
 */
final case class ReadinessResponse(
  ready: Boolean,
  checks: Map[String, CheckStatus]
) derives Codec.AsObject

final case class CheckStatus(
  status: String,
  message: Option[String] = None
) derives Codec.AsObject

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
) derives Codec.AsObject

final case class CollectionStats(
  name: String,
  documentCount: Int,
  chunkCount: Int
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * Request to ingest from URLs.
 */
final case class UrlIngestRequest(
  urls: Seq[String],
  metadata: Option[Map[String, String]] = None
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * Source configuration info.
 */
final case class SourceInfo(
  name: String,
  sourceType: String,
  enabled: Boolean,
  config: Map[String, String]
) derives Codec.AsObject

/**
 * Ingestion status response.
 */
final case class IngestionStatusResponse(
  running: Boolean,
  lastRun: Option[Instant],
  lastResults: Seq[IngestResponse],
  nextScheduledRun: Option[Instant],
  sources: Seq[SourceInfo]
) derives Codec.AsObject

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
) derives Codec.AsObject

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
 * Configuration snapshot for experiment tracking.
 * Records the effective configuration a query ran with - only the settings that applied.
 *
 * In permission mode llm4s 0.6.0 permission-filtered search is vector-only (llm4s/llm4s#2716),
 * so fusion, the RRF constant and reranking do not apply: the snapshot records
 * `searchMode = vector-only` and no `fusionStrategy`, `rrfK` or `reranking`.
 *
 * @param fusionStrategy the fusion strategy, in `hybrid` search mode (none in `vector-only` mode)
 * @param similarityThreshold the minimum chunk similarity the query applied (none: no threshold)
 * @param rrfK the RRF constant, with `rrf` fusion (none otherwise, and in entries logged before it was recorded)
 * @param reranking the reranking strategy (`none`, `cohere` or `llm`), in `hybrid` search mode
 *                  (none in `vector-only` mode, and in entries logged before it was recorded)
 * @param searchMode how the query searched: `hybrid` (vector + keyword, fused, then reranked) or
 *                   `vector-only` (permission mode, llm4s/llm4s#2716); none in entries logged
 *                   before it was recorded
 */
final case class QueryConfigSnapshot(
  topK: Int,
  fusionStrategy: Option[String] = None,
  systemPrompt: Option[String] = None,
  llmTemperature: Option[Double] = None,
  similarityThreshold: Option[Double] = None,
  rrfK: Option[Int] = None,
  reranking: Option[String] = None,
  searchMode: Option[String] = None
) derives Codec.AsObject

object QueryConfigSnapshot {

  /** Vector and keyword search, fused, then reranked: every search setting applies. */
  val HybridSearch: String = "hybrid"

  /** Permission-mode search: vector-only in llm4s 0.6.0 (llm4s/llm4s#2716). */
  val VectorOnlySearch: String = "vector-only"
}

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

  // Experiment tracking
  experimentId: Option[String] = None,
  configSnapshot: Option[QueryConfigSnapshot] = None,

  createdAt: Instant,

  // Token and cost accounting (llm4s tracing)
  promptTokens: Option[Int] = None,
  embeddingTokens: Option[Int] = None,
  costUsd: Option[Double] = None,

  // Experiment group (`baseline` / `variant`) and failure
  variant: Option[String] = None,
  failed: Boolean = false,
  errorMessage: Option[String] = None
) derives Codec.AsObject

/**
 * Request to submit feedback for a query.
 */
final case class QueryFeedbackRequest(
  queryId: String,
  rating: Int,               // 1-5 scale
  relevantChunks: Option[Seq[String]] = None,
  comment: Option[String] = None
) derives Codec.AsObject

/**
 * Response from submitting feedback.
 */
final case class QueryFeedbackResponse(
  success: Boolean,
  message: String
) derives Codec.AsObject

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
  periodEnd: Instant,
  // p95 of retrieval latency (total minus answer generation), over queries whose LLM latency
  // was recorded; None when there are none
  p95RetrievalLatencyMs: Option[Int] = None,
  // Total estimated cost of the period's queries, where llm4s knows the models' pricing
  totalCostUsd: Option[Double] = None
) derives Codec.AsObject

/**
 * Query stats by collection.
 */
final case class CollectionQueryStats(
  collection: String,
  queryCount: Int,
  averageLatencyMs: Double,
  averageRating: Option[Double]
) derives Codec.AsObject

/**
 * Query log list response.
 */
final case class QueryLogListResponse(
  queries: Seq[QueryLogEntry],
  total: Int,
  page: Int,
  pageSize: Int
) derives Codec.AsObject

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
) extends QueryStreamEvent derives Codec.AsObject {
  val eventType: String = "start"
}

/**
 * Event containing retrieved context/chunk.
 */
final case class QueryContextEvent(
  context: ContextItem,
  index: Int,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent derives Codec.AsObject {
  val eventType: String = "context"
}

/**
 * Event containing a chunk of the answer (for streaming LLM responses).
 */
final case class QueryChunkEvent(
  chunk: String,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent derives Codec.AsObject {
  val eventType: String = "chunk"
}

/**
 * Event containing the complete answer (when streaming not available).
 */
final case class QueryAnswerEvent(
  answer: String,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent derives Codec.AsObject {
  val eventType: String = "answer"
}

/**
 * Event containing usage information.
 */
final case class QueryUsageEvent(
  usage: UsageInfo,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent derives Codec.AsObject {
  val eventType: String = "usage"
}

/**
 * Event indicating query completed successfully.
 */
final case class QueryCompleteEvent(
  queryId: String,
  totalContexts: Int,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent derives Codec.AsObject {
  val eventType: String = "complete"
}

/**
 * Event indicating an error occurred.
 */
final case class QueryErrorEvent(
  error: String,
  message: String,
  timestamp: Instant = Instant.now()
) extends QueryStreamEvent derives Codec.AsObject {
  val eventType: String = "error"
}
