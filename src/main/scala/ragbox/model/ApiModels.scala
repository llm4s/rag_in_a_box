package ragbox.model

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
 */
final case class SyncPruneRequest(
  keepDocumentIds: Seq[String]
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
 * Health check response.
 */
final case class HealthResponse(
  status: String,
  version: String,
  uptime: Long
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
}
