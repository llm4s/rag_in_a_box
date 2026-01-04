package org.llm4s.ragbox.service

import cats.effect.IO
import org.llm4s.chunking.{ChunkerFactory, ChunkingConfig, DocumentChunk}
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.model._

/**
 * Service for chunking preview and comparison.
 *
 * Allows users to test chunking strategies without persisting any data.
 */
class ChunkingService(config: AppConfig) {

  // Token estimation: ~4 chars per token on average
  private val CharsPerToken = 4

  /**
   * Preview chunking on sample content.
   */
  def preview(request: ChunkingPreviewRequest): IO[ChunkingPreviewResponse] = IO {
    // Determine strategy to use
    val (strategy, strategySource) = resolveStrategy(request.strategy, request.filename)

    // Build chunking config
    val chunkingConfig = ChunkingConfig(
      targetSize = request.targetSize.getOrElse(config.rag.chunking.size),
      maxSize = request.maxSize.getOrElse((config.rag.chunking.size * 1.5).toInt),
      overlap = request.overlap.getOrElse(config.rag.chunking.overlap)
    )

    // Get chunker
    val chunker = ChunkerFactory.create(strategy).getOrElse(ChunkerFactory.default)

    // Chunk the content
    val docChunks = request.filename match {
      case Some(f) => chunker.chunkWithSource(request.content, f, chunkingConfig)
      case None => chunker.chunk(request.content, chunkingConfig)
    }

    // Build response
    val previewChunks = docChunks.map(toPreviewChunk)
    val stats = calculateStats(previewChunks)
    val warnings = generateWarnings(previewChunks, chunkingConfig, strategy)

    val configUsed = ChunkingConfigUsed(
      strategy = strategy,
      targetSize = chunkingConfig.targetSize,
      maxSize = chunkingConfig.maxSize,
      overlap = chunkingConfig.overlap,
      source = strategySource
    )

    ChunkingPreviewResponse(
      strategy = strategy,
      config = configUsed,
      chunks = previewChunks,
      stats = stats,
      warnings = warnings
    )
  }

  /**
   * Compare multiple chunking strategies on the same content.
   */
  def compare(request: ChunkingCompareRequest): IO[ChunkingCompareResponse] = IO {
    val strategies = if (request.strategies.isEmpty) {
      Seq("simple", "sentence", "markdown")
    } else {
      request.strategies
    }

    val chunkingConfig = ChunkingConfig(
      targetSize = request.targetSize.getOrElse(config.rag.chunking.size),
      maxSize = request.targetSize.map(t => (t * 1.5).toInt).getOrElse((config.rag.chunking.size * 1.5).toInt),
      overlap = request.overlap.getOrElse(config.rag.chunking.overlap)
    )

    val results = strategies.flatMap { strategyName =>
      ChunkerFactory.create(strategyName).map { chunker =>
        val docChunks = chunker.chunk(request.content, chunkingConfig)
        val previewChunks = docChunks.map(toPreviewChunk)
        val stats = calculateStats(previewChunks)
        val warnings = generateWarnings(previewChunks, chunkingConfig, strategyName)

        StrategyResult(
          strategy = strategyName,
          chunks = previewChunks,
          stats = stats,
          warnings = warnings
        )
      }
    }

    val recommendation = generateRecommendation(results, request.content)

    ChunkingCompareResponse(
      results = results,
      recommendation = recommendation
    )
  }

  /**
   * List available chunking strategies.
   */
  def getStrategies: IO[StrategiesResponse] = IO.pure {
    StrategiesResponse(
      strategies = Seq(
        StrategyInfo(
          name = "simple",
          displayName = "Simple",
          description = "Splits text at fixed character boundaries. Fast but may break mid-sentence.",
          bestFor = Seq("Raw data", "Log files", "Unstructured text"),
          tradeoffs = Seq("May split sentences", "No semantic awareness"),
          requiresEmbeddings = false
        ),
        StrategyInfo(
          name = "sentence",
          displayName = "Sentence-Aware",
          description = "Respects sentence boundaries for more coherent chunks.",
          bestFor = Seq("Prose", "Articles", "General documents"),
          tradeoffs = Seq("Slightly slower than simple", "May produce variable chunk sizes"),
          requiresEmbeddings = false
        ),
        StrategyInfo(
          name = "markdown",
          displayName = "Markdown-Aware",
          description = "Preserves markdown structure including headings, code blocks, and lists.",
          bestFor = Seq("Documentation", "README files", "Technical docs", "Code with markdown"),
          tradeoffs = Seq("Only effective for markdown content", "May produce larger chunks for code blocks"),
          requiresEmbeddings = false
        ),
        StrategyInfo(
          name = "semantic",
          displayName = "Semantic",
          description = "Uses embeddings to detect topic boundaries. Highest quality but slowest.",
          bestFor = Seq("Mixed-topic documents", "Research papers", "Long-form content"),
          tradeoffs = Seq("Requires embedding API calls", "Slower processing", "Higher cost"),
          requiresEmbeddings = true
        )
      ),
      currentDefault = config.rag.chunking.strategy
    )
  }

  /**
   * List available preset configurations.
   */
  def getPresets: IO[PresetsResponse] = IO.pure {
    val presets = Seq(
      PresetInfo(
        name = "small",
        displayName = "Small Chunks",
        description = "Fine-grained chunks for precise retrieval",
        targetSize = 400,
        maxSize = 600,
        overlap = 75,
        bestFor = Seq("Q&A systems", "Precise answers", "Short documents")
      ),
      PresetInfo(
        name = "default",
        displayName = "Default",
        description = "Balanced chunk size for general use",
        targetSize = 800,
        maxSize = 1200,
        overlap = 150,
        bestFor = Seq("General documents", "Mixed content", "Most use cases")
      ),
      PresetInfo(
        name = "large",
        displayName = "Large Chunks",
        description = "Larger chunks for broader context",
        targetSize = 1500,
        maxSize = 2000,
        overlap = 250,
        bestFor = Seq("Long documents", "Narrative content", "Context-heavy queries")
      )
    )

    val current = PresetInfo(
      name = "current",
      displayName = "Current Configuration",
      description = "Currently active chunking configuration",
      targetSize = config.rag.chunking.size,
      maxSize = (config.rag.chunking.size * 1.5).toInt,
      overlap = config.rag.chunking.overlap,
      bestFor = Seq()
    )

    PresetsResponse(presets = presets, current = current)
  }

  // ============================================================
  // Helper Methods
  // ============================================================

  private def resolveStrategy(requestedStrategy: Option[String], filename: Option[String]): (String, String) = {
    requestedStrategy match {
      case Some(s) if ChunkerFactory.create(s).isDefined =>
        (s, "request")
      case _ =>
        filename match {
          case Some(f) if f.endsWith(".md") || f.endsWith(".markdown") =>
            ("markdown", "auto-detect")
          case Some(f) if f.endsWith(".json") || f.endsWith(".xml") =>
            ("simple", "auto-detect")
          case _ =>
            (config.rag.chunking.strategy, "default")
        }
    }
  }

  private def toPreviewChunk(chunk: DocumentChunk): PreviewChunk =
    PreviewChunk(
      index = chunk.index,
      content = chunk.content,
      length = chunk.length,
      headings = chunk.metadata.headings,
      isCodeBlock = chunk.metadata.isCodeBlock,
      language = chunk.metadata.language
    )

  private def calculateStats(chunks: Seq[PreviewChunk]): ChunkingStats = {
    if (chunks.isEmpty) {
      ChunkingStats(0, 0, 0.0, 0, 0, 0)
    } else {
      val lengths = chunks.map(_.length)
      val totalLength = lengths.sum
      ChunkingStats(
        chunkCount = chunks.size,
        totalLength = totalLength,
        avgChunkSize = totalLength.toDouble / chunks.size,
        minChunkSize = lengths.min,
        maxChunkSize = lengths.max,
        estimatedTokens = totalLength / CharsPerToken
      )
    }
  }

  private def generateWarnings(
    chunks: Seq[PreviewChunk],
    config: ChunkingConfig,
    strategy: String
  ): Seq[ChunkingWarning] = {
    val warnings = scala.collection.mutable.ArrayBuffer.empty[ChunkingWarning]

    // Check for very small chunks
    val smallChunks = chunks.filter(_.length < 100)
    if (smallChunks.nonEmpty) {
      warnings += ChunkingWarning(
        level = "warning",
        message = s"${smallChunks.size} chunk(s) are smaller than 100 characters",
        suggestion = Some("Consider increasing overlap or using a different strategy")
      )
    }

    // Check for very large chunks
    val largeChunks = chunks.filter(_.length > config.maxSize)
    if (largeChunks.nonEmpty) {
      warnings += ChunkingWarning(
        level = "warning",
        message = s"${largeChunks.size} chunk(s) exceed the max size of ${config.maxSize}",
        suggestion = Some("This may happen with code blocks. Consider adjusting maxSize")
      )
    }

    // Check for single chunk
    if (chunks.size == 1 && chunks.head.length < config.targetSize / 2) {
      warnings += ChunkingWarning(
        level = "info",
        message = "Content produced only one small chunk",
        suggestion = Some("This is fine for small documents")
      )
    }

    // Strategy-specific warnings
    if (strategy == "simple" && chunks.exists(c => c.content.contains(". ") && c.content.last != '.')) {
      warnings += ChunkingWarning(
        level = "info",
        message = "Simple strategy may have split sentences",
        suggestion = Some("Consider using 'sentence' strategy for better coherence")
      )
    }

    warnings.toSeq
  }

  private def generateRecommendation(results: Seq[StrategyResult], content: String): Option[StrategyRecommendation] = {
    if (results.isEmpty) return None

    // Simple heuristics for recommendation
    val isMarkdown = content.contains("```") || content.matches("(?m)^#{1,6}\\s+\\S.*")
    val hasCodeBlocks = content.contains("```")

    if (isMarkdown && results.exists(_.strategy == "markdown")) {
      Some(StrategyRecommendation(
        strategy = "markdown",
        reason = "Content appears to be markdown with " + (if (hasCodeBlocks) "code blocks" else "headings")
      ))
    } else {
      // Find strategy with fewest warnings and reasonable chunk distribution
      val scored = results.map { r =>
        val warningPenalty = r.warnings.count(_.level == "warning") * 10
        val sizeVariance = if (r.stats.chunkCount > 1) {
          math.abs(r.stats.avgChunkSize - 800) / 100  // Prefer chunks near 800 chars
        } else 0
        (r.strategy, warningPenalty + sizeVariance)
      }

      val best = scored.minByOption(_._2).map(_._1)
      best.map(s => StrategyRecommendation(
        strategy = s,
        reason = "Best balance of chunk sizes and minimal warnings"
      ))
    }
  }
}

object ChunkingService {
  def apply(config: AppConfig): ChunkingService = new ChunkingService(config)
}
