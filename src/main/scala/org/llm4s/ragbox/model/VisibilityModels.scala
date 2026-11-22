package org.llm4s.ragbox.model

import io.circe.Codec

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
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * Embedding configuration details for visibility API.
 */
final case class VisibilityEmbeddingConfig(
  provider: String,
  model: String,
  dimensions: Option[Int],
  changeability: ChangeabilityInfo
) derives Codec.AsObject

/**
 * LLM configuration details for visibility API.
 */
final case class VisibilityLLMConfig(
  model: String,
  temperature: Double,
  changeability: ChangeabilityInfo
) derives Codec.AsObject

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
  systemPrompt: Option[String],
  searchMode: String = "hybrid",
  limitations: Seq[String] = Seq.empty
) derives Codec.AsObject

/**
 * Database configuration for visibility (no credentials exposed).
 */
final case class VisibilityDatabaseConfig(
  host: String,
  port: Int,
  database: String,
  tableName: String,
  connected: Boolean
) derives Codec.AsObject

/**
 * Summary of what's configurable at runtime.
 */
final case class RuntimeConfigurableInfo(
  hotSettings: Seq[String],   // Can change immediately
  warmSettings: Seq[String],  // Can change for new documents
  coldSettings: Seq[String]   // Require restart/full re-index
) derives Codec.AsObject

/**
 * Complete detailed configuration response.
 */
final case class DetailedConfigResponse(
  embedding: VisibilityEmbeddingConfig,
  llm: VisibilityLLMConfig,
  rag: DetailedRAGConfigInfo,
  database: VisibilityDatabaseConfig,
  runtimeConfigurable: RuntimeConfigurableInfo
) derives Codec.AsObject

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
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * Paginated list of chunks.
 */
final case class ChunkListResponse(
  chunks: Seq[ChunkInfo],
  total: Int,
  page: Int,
  pageSize: Int,
  hasMore: Boolean
) derives Codec.AsObject

/**
 * Snapshot of chunking configuration used for a document.
 */
final case class ChunkConfigSnapshot(
  strategy: String,
  targetSize: Int,
  maxSize: Int,
  overlap: Int
) derives Codec.AsObject

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
) derives Codec.AsObject

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
) derives Codec.AsObject

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
) derives Codec.AsObject

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
) derives Codec.AsObject

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
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * Effective configuration after merging with defaults.
 */
final case class EffectiveChunkingInfo(
  strategy: String,
  targetSize: Int,
  maxSize: Int,
  overlap: Int,
  source: String  // "collection", "default", or "file-type"
) derives Codec.AsObject

/**
 * Collection details with chunking configuration.
 */
final case class CollectionDetailInfo(
  name: String,
  documentCount: Int,
  chunkCount: Int,
  customConfig: Option[CollectionChunkingInfo],
  effectiveConfig: EffectiveChunkingInfo
) derives Codec.AsObject

/**
 * List of all collections with their configurations.
 */
final case class CollectionsResponse(
  collections: Seq[CollectionDetailInfo],
  defaultConfig: EffectiveChunkingInfo
) derives Codec.AsObject
