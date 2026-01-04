package org.llm4s.ragbox.model

import java.time.Instant

/**
 * Models for Per-Collection Chunking Configuration.
 *
 * Allows different chunking strategies and settings per collection,
 * with optional file-type-specific overrides.
 */

// ============================================================
// Collection Chunking Configuration
// ============================================================

/**
 * Chunking configuration for a specific collection.
 *
 * @param collection Collection name
 * @param strategy Chunking strategy (simple, sentence, markdown, semantic)
 * @param targetSize Target chunk size in characters
 * @param maxSize Maximum chunk size
 * @param overlap Overlap between chunks
 * @param fileTypeStrategies File extension to strategy mapping (e.g., ".md" -> "markdown")
 * @param updatedAt Last update timestamp
 */
final case class CollectionChunkingConfig(
  collection: String,
  strategy: Option[String] = None,
  targetSize: Option[Int] = None,
  maxSize: Option[Int] = None,
  overlap: Option[Int] = None,
  fileTypeStrategies: Map[String, String] = Map.empty,
  updatedAt: Option[Instant] = None
)

/**
 * Request to create or update collection chunking config.
 */
final case class CollectionConfigUpdateRequest(
  strategy: Option[String] = None,
  targetSize: Option[Int] = None,
  maxSize: Option[Int] = None,
  overlap: Option[Int] = None,
  fileTypeStrategies: Option[Map[String, String]] = None
)

/**
 * Effective configuration after merging collection config with defaults.
 *
 * @param strategy Effective chunking strategy
 * @param targetSize Effective target chunk size
 * @param maxSize Effective max chunk size
 * @param overlap Effective overlap
 * @param source Where this config came from: "collection", "file-type", or "default"
 * @param appliedFileTypeOverride If file-type override was applied, which extension
 */
final case class EffectiveCollectionConfig(
  strategy: String,
  targetSize: Int,
  maxSize: Int,
  overlap: Int,
  source: String,
  appliedFileTypeOverride: Option[String] = None
)

/**
 * Response for getting a collection's chunking config.
 */
final case class CollectionConfigResponse(
  collection: String,
  hasCustomConfig: Boolean,
  config: Option[CollectionChunkingConfig],
  effectiveConfig: EffectiveCollectionConfig,
  documentCount: Int
)

/**
 * Response after updating collection config.
 */
final case class CollectionConfigUpdateResponse(
  collection: String,
  config: CollectionChunkingConfig,
  message: String
)

/**
 * Response after deleting collection config.
 */
final case class CollectionConfigDeleteResponse(
  collection: String,
  message: String
)

/**
 * Request to preview effective config for a file in a collection.
 */
final case class EffectiveConfigPreviewRequest(
  collection: String,
  filename: Option[String] = None
)

/**
 * Response showing effective config for a file in a collection.
 */
final case class EffectiveConfigPreviewResponse(
  collection: String,
  filename: Option[String],
  effectiveConfig: EffectiveCollectionConfig,
  configResolutionPath: Seq[String]
)

/**
 * Summary of all collection configs.
 */
final case class AllCollectionConfigsResponse(
  collections: Seq[CollectionConfigResponse],
  defaultConfig: EffectiveCollectionConfig
)
