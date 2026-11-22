package org.llm4s.ragbox.model

import io.circe.Codec

/**
 * Models for the Chunking Preview API.
 *
 * Allows users to test and compare chunking strategies before committing to them.
 */

// ============================================================
// Preview Request/Response
// ============================================================

/**
 * Request to preview chunking on sample content.
 *
 * @param content The text content to chunk
 * @param strategy Optional strategy override (default uses current config)
 * @param targetSize Optional target chunk size
 * @param maxSize Optional max chunk size
 * @param overlap Optional overlap size
 * @param filename Optional filename for auto-detection hints
 */
final case class ChunkingPreviewRequest(
  content: String,
  strategy: Option[String] = None,
  targetSize: Option[Int] = None,
  maxSize: Option[Int] = None,
  overlap: Option[Int] = None,
  filename: Option[String] = None
) derives Codec.AsObject

/**
 * A chunk from a preview operation.
 */
final case class PreviewChunk(
  index: Int,
  content: String,
  length: Int,
  headings: Seq[String],
  isCodeBlock: Boolean,
  language: Option[String]
) derives Codec.AsObject

/**
 * Statistics about a chunking preview.
 */
final case class ChunkingStats(
  chunkCount: Int,
  totalLength: Int,
  avgChunkSize: Double,
  minChunkSize: Int,
  maxChunkSize: Int,
  estimatedTokens: Int
) derives Codec.AsObject

/**
 * Warnings or suggestions about chunking.
 */
final case class ChunkingWarning(
  level: String,  // "info", "warning", "error"
  message: String,
  suggestion: Option[String] = None
) derives Codec.AsObject

/**
 * Response from a chunking preview.
 */
final case class ChunkingPreviewResponse(
  strategy: String,
  config: ChunkingConfigUsed,
  chunks: Seq[PreviewChunk],
  stats: ChunkingStats,
  warnings: Seq[ChunkingWarning]
) derives Codec.AsObject

/**
 * The chunking configuration that was used.
 */
final case class ChunkingConfigUsed(
  strategy: String,
  targetSize: Int,
  maxSize: Int,
  overlap: Int,
  source: String  // "request", "auto-detect", "default"
) derives Codec.AsObject

// ============================================================
// Compare Request/Response
// ============================================================

/**
 * Request to compare multiple chunking strategies.
 */
final case class ChunkingCompareRequest(
  content: String,
  strategies: Seq[String],  // e.g., ["simple", "sentence", "markdown"]
  targetSize: Option[Int] = None,
  overlap: Option[Int] = None
) derives Codec.AsObject

/**
 * Result of a single strategy in a comparison.
 */
final case class StrategyResult(
  strategy: String,
  chunks: Seq[PreviewChunk],
  stats: ChunkingStats,
  warnings: Seq[ChunkingWarning]
) derives Codec.AsObject

/**
 * Response comparing multiple chunking strategies.
 */
final case class ChunkingCompareResponse(
  results: Seq[StrategyResult],
  recommendation: Option[StrategyRecommendation]
) derives Codec.AsObject

/**
 * A recommendation based on the comparison.
 */
final case class StrategyRecommendation(
  strategy: String,
  reason: String
) derives Codec.AsObject

// ============================================================
// Strategy Information
// ============================================================

/**
 * Information about a chunking strategy.
 */
final case class StrategyInfo(
  name: String,
  displayName: String,
  description: String,
  bestFor: Seq[String],
  tradeoffs: Seq[String],
  requiresEmbeddings: Boolean
) derives Codec.AsObject

/**
 * Response listing available strategies.
 */
final case class StrategiesResponse(
  strategies: Seq[StrategyInfo],
  currentDefault: String
) derives Codec.AsObject

// ============================================================
// Preset Configurations
// ============================================================

/**
 * A preset chunking configuration.
 */
final case class PresetInfo(
  name: String,
  displayName: String,
  description: String,
  targetSize: Int,
  maxSize: Int,
  overlap: Int,
  bestFor: Seq[String]
) derives Codec.AsObject

/**
 * Response listing available presets.
 */
final case class PresetsResponse(
  presets: Seq[PresetInfo],
  current: PresetInfo
) derives Codec.AsObject
