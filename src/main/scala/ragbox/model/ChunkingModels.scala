package ragbox.model

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
)

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
)

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
)

/**
 * Warnings or suggestions about chunking.
 */
final case class ChunkingWarning(
  level: String,  // "info", "warning", "error"
  message: String,
  suggestion: Option[String] = None
)

/**
 * Response from a chunking preview.
 */
final case class ChunkingPreviewResponse(
  strategy: String,
  config: ChunkingConfigUsed,
  chunks: Seq[PreviewChunk],
  stats: ChunkingStats,
  warnings: Seq[ChunkingWarning]
)

/**
 * The chunking configuration that was used.
 */
final case class ChunkingConfigUsed(
  strategy: String,
  targetSize: Int,
  maxSize: Int,
  overlap: Int,
  source: String  // "request", "auto-detect", "default"
)

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
)

/**
 * Result of a single strategy in a comparison.
 */
final case class StrategyResult(
  strategy: String,
  chunks: Seq[PreviewChunk],
  stats: ChunkingStats,
  warnings: Seq[ChunkingWarning]
)

/**
 * Response comparing multiple chunking strategies.
 */
final case class ChunkingCompareResponse(
  results: Seq[StrategyResult],
  recommendation: Option[StrategyRecommendation]
)

/**
 * A recommendation based on the comparison.
 */
final case class StrategyRecommendation(
  strategy: String,
  reason: String
)

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
)

/**
 * Response listing available strategies.
 */
final case class StrategiesResponse(
  strategies: Seq[StrategyInfo],
  currentDefault: String
)

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
)

/**
 * Response listing available presets.
 */
final case class PresetsResponse(
  presets: Seq[PresetInfo],
  current: PresetInfo
)
