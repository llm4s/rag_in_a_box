package org.llm4s.ragbox.model

import java.time.Instant

/**
 * Models for the Visibility API - providing insight into RAG configuration and behavior.
 */

// ============================================================
// Configuration Visibility
// ============================================================

/**
 * Describes whether and how a configuration setting can be changed.
 */
final case class ChangeabilityInfo(
  category: String,           // "hot", "warm", or "cold"
  canChangeAtRuntime: Boolean,
  requiresReindex: Boolean,
  affectsNewDocsOnly: Boolean,
  description: String
)

object ChangeabilityInfo {
  val hot: ChangeabilityInfo = ChangeabilityInfo(
    category = "hot",
    canChangeAtRuntime = true,
    requiresReindex = false,
    affectsNewDocsOnly = false,
    description = "Can be changed at runtime with immediate effect"
  )

  val warm: ChangeabilityInfo = ChangeabilityInfo(
    category = "warm",
    canChangeAtRuntime = true,
    requiresReindex = false,
    affectsNewDocsOnly = true,
    description = "Can be changed at runtime, affects new documents only"
  )

  val cold: ChangeabilityInfo = ChangeabilityInfo(
    category = "cold",
    canChangeAtRuntime = false,
    requiresReindex = true,
    affectsNewDocsOnly = false,
    description = "Requires restart and full re-indexing to change"
  )
}

/**
 * A configuration setting with its current value and changeability info.
 */
final case class ConfigSetting(
  name: String,
  value: String,
  changeability: ChangeabilityInfo
)

/**
 * Embedding configuration details for visibility API.
 */
final case class VisibilityEmbeddingConfig(
  provider: String,
  model: String,
  dimensions: Option[Int],
  changeability: ChangeabilityInfo
)

/**
 * LLM configuration details for visibility API.
 */
final case class VisibilityLLMConfig(
  model: String,
  temperature: Double,
  changeability: ChangeabilityInfo
)

/**
 * Detailed RAG configuration with changeability annotations.
 */
final case class DetailedRAGConfigInfo(
  chunkingStrategy: ConfigSetting,
  chunkSize: ConfigSetting,
  chunkOverlap: ConfigSetting,
  topK: ConfigSetting,
  fusionStrategy: ConfigSetting,
  rrfK: ConfigSetting,
  systemPrompt: Option[String]
)

/**
 * Database configuration for visibility (no credentials exposed).
 */
final case class VisibilityDatabaseConfig(
  host: String,
  port: Int,
  database: String,
  tableName: String,
  connected: Boolean
)

/**
 * Summary of what's configurable at runtime.
 */
final case class RuntimeConfigurableInfo(
  hotSettings: Seq[String],   // Can change immediately
  warmSettings: Seq[String],  // Can change for new documents
  coldSettings: Seq[String]   // Require restart/full re-index
)

/**
 * Complete detailed configuration response.
 */
final case class DetailedConfigResponse(
  embedding: VisibilityEmbeddingConfig,
  llm: VisibilityLLMConfig,
  rag: DetailedRAGConfigInfo,
  database: VisibilityDatabaseConfig,
  runtimeConfigurable: RuntimeConfigurableInfo
)

// ============================================================
// Chunk Visibility
// ============================================================

/**
 * Metadata for a single chunk.
 */
final case class ChunkMetadataInfo(
  headings: Seq[String],
  isCodeBlock: Boolean,
  language: Option[String],
  startOffset: Option[Int],
  endOffset: Option[Int]
)

/**
 * Information about a single chunk.
 */
final case class ChunkInfo(
  id: String,
  documentId: String,
  index: Int,
  content: String,
  contentLength: Int,
  metadata: Map[String, String],
  chunkMetadata: ChunkMetadataInfo
)

/**
 * Paginated list of chunks.
 */
final case class ChunkListResponse(
  chunks: Seq[ChunkInfo],
  total: Int,
  page: Int,
  pageSize: Int,
  hasMore: Boolean
)

/**
 * Snapshot of chunking configuration used for a document.
 */
final case class ChunkConfigSnapshot(
  strategy: String,
  targetSize: Int,
  maxSize: Int,
  overlap: Int
)

/**
 * All chunks for a specific document.
 */
final case class DocumentChunksResponse(
  documentId: String,
  filename: Option[String],
  collection: Option[String],
  chunkCount: Int,
  chunks: Seq[ChunkInfo],
  chunkingConfig: ChunkConfigSnapshot
)

// ============================================================
// Statistics Visibility
// ============================================================

/**
 * A histogram bucket for chunk size distribution.
 */
final case class SizeBucket(
  rangeStart: Int,
  rangeEnd: Int,
  count: Int
)

/**
 * Distribution of chunk sizes.
 */
final case class ChunkSizeDistribution(
  min: Int,
  max: Int,
  avg: Double,
  median: Double,
  p90: Double,
  buckets: Seq[SizeBucket]
)

/**
 * Detailed statistics for a collection.
 */
final case class DetailedCollectionStats(
  name: String,
  documentCount: Int,
  chunkCount: Int,
  avgChunksPerDoc: Double,
  avgChunkSize: Double,
  chunkingStrategy: Option[String]
)

/**
 * Detailed system statistics.
 */
final case class DetailedStatsResponse(
  documentCount: Int,
  chunkCount: Int,
  vectorCount: Long,
  collectionCount: Int,
  avgChunksPerDocument: Double,
  collections: Seq[DetailedCollectionStats],
  chunkSizeDistribution: ChunkSizeDistribution,
  indexedSince: Option[Instant],
  lastIngestion: Option[Instant],
  currentConfig: ChunkConfigSnapshot
)

// ============================================================
// Collection Visibility
// ============================================================

/**
 * Collection chunking configuration (if customized).
 */
final case class CollectionChunkingInfo(
  strategy: Option[String],
  targetSize: Option[Int],
  maxSize: Option[Int],
  overlap: Option[Int],
  fileTypeStrategies: Map[String, String]
)

/**
 * Effective configuration after merging with defaults.
 */
final case class EffectiveChunkingInfo(
  strategy: String,
  targetSize: Int,
  maxSize: Int,
  overlap: Int,
  source: String  // "collection", "default", or "file-type"
)

/**
 * Collection details with chunking configuration.
 */
final case class CollectionDetailInfo(
  name: String,
  documentCount: Int,
  chunkCount: Int,
  customConfig: Option[CollectionChunkingInfo],
  effectiveConfig: EffectiveChunkingInfo
)

/**
 * List of all collections with their configurations.
 */
final case class CollectionsResponse(
  collections: Seq[CollectionDetailInfo],
  defaultConfig: EffectiveChunkingInfo
)
