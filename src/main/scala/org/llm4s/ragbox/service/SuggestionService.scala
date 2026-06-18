package org.llm4s.ragbox.service

import cats.effect.IO
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.QueryLogRegistry

import java.time.{Duration, Instant}
import java.util.UUID

/**
 * Service for generating optimization suggestions based on query analytics.
 *
 * Analyzes query metrics, user feedback, and system configuration to
 * provide actionable recommendations for improving RAG performance.
 */
class SuggestionService(
  queryLogRegistry: QueryLogRegistry,
  config: AppConfig
) {

  // Thresholds for triggering suggestions
  private object Thresholds {
    val LowChunkUtilization = 0.4     // chunks_used / chunks_retrieved
    val HighChunkUtilization = 0.9
    val LowRating = 2.5               // 1-5 scale
    val HighLatencyP95Ms = 500
    val LowChunksRetrieved = 3
    val HighChunksRetrieved = 15
    val MinSampleSize = 10            // Minimum queries to generate suggestions
    val ZeroResultThreshold = 0.1     // 10% of queries returning no results
  }

  /**
   * Generate optimization suggestions based on query analytics.
   *
   * @param from Start of analysis period (default: 7 days ago)
   * @param to End of analysis period (default: now)
   * @param collection Optional collection to analyze specifically
   */
  def generateSuggestions(
    from: Option[Instant] = None,
    to: Option[Instant] = None,
    collection: Option[String] = None
  ): IO[SuggestionsResponse] = {
    val now = Instant.now()
    val periodStart = from.getOrElse(now.minus(Duration.ofDays(7)))
    val periodEnd = to.getOrElse(now)

    for {
      summary <- queryLogRegistry.getSummary(periodStart, periodEnd)
      collectionMetrics <- getCollectionMetrics(periodStart, periodEnd)
      configSnapshot = getCurrentConfig
      suggestions = analyzeSuggestions(summary, collectionMetrics, configSnapshot, periodStart, periodEnd, collection)
    } yield SuggestionsResponse(
      suggestions = suggestions.sortBy(s => (Severity.fromString(s.severity).map(_.priority).getOrElse(99), s.title)),
      analyzedQueries = summary.totalQueries,
      analyzedCollections = collectionMetrics.size,
      analysisTimestamp = now
    )
  }

  /**
   * Get suggestions summary (counts by severity).
   */
  def getSuggestionsSummary(
    from: Option[Instant] = None,
    to: Option[Instant] = None
  ): IO[SuggestionsSummary] = {
    generateSuggestions(from, to).map { response =>
      val critical = response.suggestions.count(_.severity == "critical")
      val warning = response.suggestions.count(_.severity == "warning")
      val info = response.suggestions.count(_.severity == "info")

      SuggestionsSummary(
        criticalCount = critical,
        warningCount = warning,
        infoCount = info,
        total = response.suggestions.size,
        topIssues = response.suggestions.take(3).map(_.title)
      )
    }
  }

  // ============================================================
  // Analysis Engine
  // ============================================================

  private def analyzeSuggestions(
    summary: QueryAnalyticsSummary,
    collectionMetrics: Seq[CollectionMetrics],
    configSnapshot: ConfigSnapshot,
    periodStart: Instant,
    periodEnd: Instant,
    filterCollection: Option[String]
  ): Seq[OptimizationSuggestion] = {
    if (summary.totalQueries < Thresholds.MinSampleSize) {
      return Seq(
        createSuggestion(
          suggestionType = "info",
          severity = "info",
          title = "Insufficient Data",
          description = s"Only ${summary.totalQueries} queries in the analysis period. Need at least ${Thresholds.MinSampleSize} queries for meaningful suggestions.",
          collection = None,
          evidence = SuggestionEvidence("total_queries", summary.totalQueries.toDouble, Thresholds.MinSampleSize.toDouble, summary.totalQueries, formatTimeRange(periodStart, periodEnd)),
          recommendation = "Continue using the system and check back later for suggestions.",
          estimatedImpact = "N/A",
          currentValue = None,
          suggestedValue = None
        )
      )
    }

    val timeRange = formatTimeRange(periodStart, periodEnd)
    val suggestions = scala.collection.mutable.ArrayBuffer.empty[OptimizationSuggestion]

    // Global suggestions
    suggestions ++= analyzeChunking(summary, configSnapshot, timeRange)
    suggestions ++= analyzeSearch(summary, configSnapshot, timeRange)
    suggestions ++= analyzeEmbedding(summary, configSnapshot, timeRange)

    // Per-collection suggestions
    val filteredCollections = filterCollection match {
      case Some(c) => collectionMetrics.filter(_.collection == c)
      case None => collectionMetrics
    }

    filteredCollections.foreach { metrics =>
      suggestions ++= analyzeCollectionChunking(metrics, configSnapshot, timeRange)
      suggestions ++= analyzeCollectionContent(metrics, timeRange)
    }

    suggestions.toSeq
  }

  // ============================================================
  // Chunking Analysis
  // ============================================================

  private def analyzeChunking(
    summary: QueryAnalyticsSummary,
    config: ConfigSnapshot,
    timeRange: String
  ): Seq[OptimizationSuggestion] = {
    val suggestions = scala.collection.mutable.ArrayBuffer.empty[OptimizationSuggestion]

    // Low chunk utilization
    if (summary.averageChunksRetrieved > 0) {
      val utilization = summary.averageChunksUsed / summary.averageChunksRetrieved

      if (utilization < Thresholds.LowChunkUtilization && summary.averageChunksRetrieved > 5) {
        suggestions += createSuggestion(
          suggestionType = "chunking",
          severity = "warning",
          title = "Low Chunk Utilization",
          description = f"Only ${utilization * 100}%.0f%% of retrieved chunks are being used. This suggests chunks are too large or not focused enough.",
          collection = None,
          evidence = SuggestionEvidence("chunk_utilization", utilization, Thresholds.LowChunkUtilization, summary.totalQueries, timeRange),
          recommendation = "Reduce chunk size to improve precision. Smaller chunks are more likely to contain focused, relevant content.",
          estimatedImpact = "Improved retrieval precision, faster queries, lower LLM token usage",
          currentValue = Some(s"${config.chunkSize} chars"),
          suggestedValue = Some(s"${(config.chunkSize * 0.6).toInt} chars")
        )
      }

      if (utilization > Thresholds.HighChunkUtilization && summary.averageRating.exists(_ < 4.0)) {
        suggestions += createSuggestion(
          suggestionType = "chunking",
          severity = "info",
          title = "High Chunk Utilization with Moderate Ratings",
          description = "Almost all retrieved chunks are being used, but ratings indicate room for improvement. You may need more context per query.",
          collection = None,
          evidence = SuggestionEvidence("chunk_utilization", utilization, Thresholds.HighChunkUtilization, summary.totalQueries, timeRange),
          recommendation = "Consider increasing chunk size or topK to provide more context to the LLM.",
          estimatedImpact = "Better answer quality with more context",
          currentValue = Some(s"${config.chunkSize} chars, topK=${config.topK}"),
          suggestedValue = Some(s"${(config.chunkSize * 1.25).toInt} chars or topK=${config.topK + 2}")
        )
      }
    }

    // Very low chunks retrieved
    if (summary.averageChunksRetrieved < Thresholds.LowChunksRetrieved && summary.averageRating.exists(_ < 3.5)) {
      suggestions += createSuggestion(
        suggestionType = "chunking",
        severity = "warning",
        title = "Low Retrieval Volume",
        description = f"Queries retrieve only ${summary.averageChunksRetrieved}%.1f chunks on average with below-average ratings.",
        collection = None,
        evidence = SuggestionEvidence("avg_chunks_retrieved", summary.averageChunksRetrieved, Thresholds.LowChunksRetrieved.toDouble, summary.totalQueries, timeRange),
        recommendation = "Chunk size may be too large, reducing the number of discrete chunks. Consider reducing chunk size.",
        estimatedImpact = "More chunks for search to match against",
        currentValue = Some(s"${config.chunkSize} chars"),
        suggestedValue = Some(s"${(config.chunkSize * 0.5).toInt} chars")
      )
    }

    // Very high chunks retrieved
    if (summary.averageChunksRetrieved > Thresholds.HighChunksRetrieved) {
      suggestions += createSuggestion(
        suggestionType = "chunking",
        severity = "info",
        title = "High Retrieval Volume",
        description = f"Queries retrieve ${summary.averageChunksRetrieved}%.1f chunks on average, which may indicate very small chunks.",
        collection = None,
        evidence = SuggestionEvidence("avg_chunks_retrieved", summary.averageChunksRetrieved, Thresholds.HighChunksRetrieved.toDouble, summary.totalQueries, timeRange),
        recommendation = "Consider increasing chunk size to reduce fragmentation and improve coherence.",
        estimatedImpact = "Better context preservation, lower search overhead",
        currentValue = Some(s"${config.chunkSize} chars"),
        suggestedValue = Some(s"${(config.chunkSize * 1.5).toInt} chars")
      )
    }

    // No overlap configured
    if (config.chunkOverlap == 0) {
      suggestions += createSuggestion(
        suggestionType = "chunking",
        severity = "info",
        title = "No Chunk Overlap Configured",
        description = "Chunks have no overlap, which may cause context to be lost at chunk boundaries.",
        collection = None,
        evidence = SuggestionEvidence("chunk_overlap", 0.0, 1.0, summary.totalQueries, timeRange),
        recommendation = "Add 10-20% overlap to preserve context across chunk boundaries.",
        estimatedImpact = "Better handling of queries that span chunk boundaries",
        currentValue = Some("0 chars"),
        suggestedValue = Some(s"${(config.chunkSize * 0.15).toInt} chars")
      )
    }

    suggestions.toSeq
  }

  // ============================================================
  // Search Analysis
  // ============================================================

  private def analyzeSearch(
    summary: QueryAnalyticsSummary,
    config: ConfigSnapshot,
    timeRange: String
  ): Seq[OptimizationSuggestion] = {
    val suggestions = scala.collection.mutable.ArrayBuffer.empty[OptimizationSuggestion]

    // TopK too high
    if (summary.averageChunksUsed > 0 && config.topK > summary.averageChunksUsed * 3) {
      suggestions += createSuggestion(
        suggestionType = "search",
        severity = "info",
        title = "TopK May Be Too High",
        description = f"topK is set to ${config.topK} but only ${summary.averageChunksUsed}%.1f chunks are used on average.",
        collection = None,
        evidence = SuggestionEvidence("topK_usage_ratio", config.topK.toDouble / summary.averageChunksUsed, 3.0, summary.totalQueries, timeRange),
        recommendation = "Reducing topK can speed up queries without affecting answer quality.",
        estimatedImpact = "Faster query execution",
        currentValue = Some(s"topK=${config.topK}"),
        suggestedValue = Some(s"topK=${math.max(5, (summary.averageChunksUsed * 1.5).toInt)}")
      )
    }

    // High latency
    if (summary.p95LatencyMs > Thresholds.HighLatencyP95Ms) {
      suggestions += createSuggestion(
        suggestionType = "search",
        severity = "warning",
        title = "High Query Latency",
        description = s"p95 latency is ${summary.p95LatencyMs}ms, which may impact user experience.",
        collection = None,
        evidence = SuggestionEvidence("p95_latency_ms", summary.p95LatencyMs.toDouble, Thresholds.HighLatencyP95Ms.toDouble, summary.totalQueries, timeRange),
        recommendation = "Consider reducing topK, adding caching, or optimizing the embedding provider.",
        estimatedImpact = "Faster response times",
        currentValue = Some(s"${summary.p95LatencyMs}ms"),
        suggestedValue = Some(s"<${Thresholds.HighLatencyP95Ms}ms")
      )
    }

    suggestions.toSeq
  }

  // ============================================================
  // Embedding Analysis
  // ============================================================

  private def analyzeEmbedding(
    summary: QueryAnalyticsSummary,
    config: ConfigSnapshot,
    timeRange: String
  ): Seq[OptimizationSuggestion] = {
    val suggestions = scala.collection.mutable.ArrayBuffer.empty[OptimizationSuggestion]

    // Low ratings despite retrieving chunks suggests embedding mismatch
    summary.averageRating match {
      case Some(rating) if rating < Thresholds.LowRating && summary.averageChunksRetrieved > 3 =>
        suggestions += createSuggestion(
          suggestionType = "embedding",
          severity = "warning",
          title = "Low Ratings Despite Retrieval",
          description = f"Average rating is $rating%.1f despite retrieving ${summary.averageChunksRetrieved}%.1f chunks per query.",
          collection = None,
          evidence = SuggestionEvidence("avg_rating_with_chunks", rating, Thresholds.LowRating, summary.ratedQueriesCount, timeRange),
          recommendation = "Retrieved chunks may not be semantically relevant. Consider trying a different embedding model or reviewing chunk content quality.",
          estimatedImpact = "Better semantic matching, improved relevance",
          currentValue = Some(s"${config.embeddingProvider}/${config.embeddingModel}"),
          suggestedValue = Some("Try voyage-3 or text-embedding-3-large for better semantic understanding")
        )
      case _ => // OK
    }

    suggestions.toSeq
  }

  // ============================================================
  // Per-Collection Analysis
  // ============================================================

  private def analyzeCollectionChunking(
    metrics: CollectionMetrics,
    config: ConfigSnapshot,
    timeRange: String
  ): Seq[OptimizationSuggestion] = {
    val suggestions = scala.collection.mutable.ArrayBuffer.empty[OptimizationSuggestion]

    if (metrics.queryCount < 5) return Seq.empty  // Not enough data

    // Collection-specific utilization issues
    if (metrics.avgChunksRetrieved > 0) {
      val utilization = metrics.avgChunksUsed / metrics.avgChunksRetrieved

      if (utilization < Thresholds.LowChunkUtilization && metrics.avgChunksRetrieved > 5) {
        suggestions += createSuggestion(
          suggestionType = "chunking",
          severity = "warning",
          title = s"Low Chunk Utilization in '${metrics.collection}'",
          description = f"Only ${utilization * 100}%.0f%% of retrieved chunks are used for this collection.",
          collection = Some(metrics.collection),
          evidence = SuggestionEvidence("chunk_utilization", utilization, Thresholds.LowChunkUtilization, metrics.queryCount, timeRange),
          recommendation = "Configure collection-specific chunk size via the /api/v1/config/collections endpoint.",
          estimatedImpact = "Better precision for this collection",
          currentValue = Some(s"${config.chunkSize} chars (global)"),
          suggestedValue = Some(s"${(config.chunkSize * 0.6).toInt} chars for this collection")
        )
      }
    }

    // Markdown content with wrong strategy
    if (metrics.collection.contains("docs") || metrics.collection.contains("readme")) {
      if (config.chunkingStrategy != "markdown") {
        suggestions += createSuggestion(
          suggestionType = "chunking",
          severity = "info",
          title = s"Consider Markdown Strategy for '${metrics.collection}'",
          description = "Collection name suggests documentation content. Markdown-aware chunking may improve quality.",
          collection = Some(metrics.collection),
          evidence = SuggestionEvidence("strategy_match", 0.0, 1.0, metrics.queryCount, timeRange),
          recommendation = "Configure markdown strategy for this collection to preserve document structure.",
          estimatedImpact = "Better handling of headings, code blocks, and lists",
          currentValue = Some(config.chunkingStrategy),
          suggestedValue = Some("markdown")
        )
      }
    }

    suggestions.toSeq
  }

  private def analyzeCollectionContent(
    metrics: CollectionMetrics,
    timeRange: String
  ): Seq[OptimizationSuggestion] = {
    val suggestions = scala.collection.mutable.ArrayBuffer.empty[OptimizationSuggestion]

    if (metrics.queryCount < 5) return Seq.empty

    // Low ratings for collection
    metrics.avgRating match {
      case Some(rating) if rating < Thresholds.LowRating =>
        suggestions += createSuggestion(
          suggestionType = "content",
          severity = "warning",
          title = s"Low Ratings for '${metrics.collection}'",
          description = f"Average rating for this collection is $rating%.1f, below the ${Thresholds.LowRating} threshold.",
          collection = Some(metrics.collection),
          evidence = SuggestionEvidence("avg_rating", rating, Thresholds.LowRating, metrics.ratedQueryCount, timeRange),
          recommendation = "Review source documents for quality, completeness, and relevance. Consider re-ingesting with better chunking.",
          estimatedImpact = "Improved answer quality for queries against this collection",
          currentValue = Some(f"$rating%.1f average rating"),
          suggestedValue = Some(">3.5 average rating")
        )
      case _ => // OK
    }

    // High zero-result rate
    if (metrics.queryCount > 0) {
      val zeroResultRate = metrics.zeroResultQueries.toDouble / metrics.queryCount
      if (zeroResultRate > Thresholds.ZeroResultThreshold) {
        suggestions += createSuggestion(
          suggestionType = "content",
          severity = "critical",
          title = s"High Zero-Result Rate for '${metrics.collection}'",
          description = f"${zeroResultRate * 100}%.0f%% of queries to this collection return no results.",
          collection = Some(metrics.collection),
          evidence = SuggestionEvidence("zero_result_rate", zeroResultRate, Thresholds.ZeroResultThreshold, metrics.queryCount, timeRange),
          recommendation = "Check that documents are properly indexed. Verify collection name is correct and documents have been ingested.",
          estimatedImpact = "Queries will return relevant results",
          currentValue = Some(f"${zeroResultRate * 100}%.0f%% zero-result queries"),
          suggestedValue = Some("<10% zero-result queries")
        )
      }
    }

    // High latency for collection
    if (metrics.p95LatencyMs > Thresholds.HighLatencyP95Ms * 1.5) {
      suggestions += createSuggestion(
        suggestionType = "search",
        severity = "warning",
        title = s"High Latency for '${metrics.collection}'",
        description = s"p95 latency for this collection is ${metrics.p95LatencyMs}ms, significantly above average.",
        collection = Some(metrics.collection),
        evidence = SuggestionEvidence("p95_latency_ms", metrics.p95LatencyMs.toDouble, Thresholds.HighLatencyP95Ms * 1.5, metrics.queryCount, timeRange),
        recommendation = "This collection may have too many documents or very large chunks. Consider optimizing.",
        estimatedImpact = "Faster queries for this collection",
        currentValue = Some(s"${metrics.p95LatencyMs}ms"),
        suggestedValue = Some(s"<${Thresholds.HighLatencyP95Ms}ms")
      )
    }

    suggestions.toSeq
  }

  // ============================================================
  // Helper Methods
  // ============================================================

  private def getCurrentConfig: ConfigSnapshot = {
    ConfigSnapshot(
      chunkingStrategy = config.rag.chunking.strategy,
      chunkSize = config.rag.chunking.size,
      chunkOverlap = config.rag.chunking.overlap,
      topK = config.rag.search.topK,
      embeddingProvider = config.embedding.provider,
      embeddingModel = config.embedding.model
    )
  }

  private def getCollectionMetrics(from: Instant, to: Instant): IO[Seq[CollectionMetrics]] = {
    // Derive collection metrics from the summary's topCollections
    // This is a simplified implementation that uses the existing summary data
    queryLogRegistry.getSummary(from, to).map { summary =>
      summary.topCollections.map { cs =>
        CollectionMetrics(
          collection = cs.collection,
          queryCount = cs.queryCount,
          avgLatencyMs = cs.averageLatencyMs,
          avgChunksRetrieved = summary.averageChunksRetrieved, // Global average as proxy
          avgChunksUsed = summary.averageChunksUsed,           // Global average as proxy
          avgRating = cs.averageRating,
          ratedQueryCount = (cs.queryCount * summary.ratedQueriesCount.toDouble / math.max(1, summary.totalQueries)).toInt,
          zeroResultQueries = 0, // Not available from summary, assume 0
          p95LatencyMs = (cs.averageLatencyMs * 1.5).toInt // Estimate p95 as 1.5x average
        )
      }
    }
  }

  private def createSuggestion(
    suggestionType: String,
    severity: String,
    title: String,
    description: String,
    collection: Option[String],
    evidence: SuggestionEvidence,
    recommendation: String,
    estimatedImpact: String,
    currentValue: Option[String],
    suggestedValue: Option[String]
  ): OptimizationSuggestion = {
    OptimizationSuggestion(
      id = UUID.randomUUID().toString,
      suggestionType = suggestionType,
      severity = severity,
      title = title,
      description = description,
      collection = collection,
      evidence = evidence,
      recommendation = recommendation,
      estimatedImpact = estimatedImpact,
      currentValue = currentValue,
      suggestedValue = suggestedValue,
      createdAt = Instant.now()
    )
  }

  private def formatTimeRange(from: Instant, to: Instant): String = {
    val days = Duration.between(from, to).toDays
    if (days == 1) "Last 24 hours"
    else if (days <= 7) s"Last $days days"
    else if (days <= 30) s"Last ${days / 7} weeks"
    else s"Last ${days / 30} months"
  }
}

object SuggestionService {

  /**
   * Create a SuggestionService with the given dependencies.
   */
  def apply(
    queryLogRegistry: QueryLogRegistry,
    config: AppConfig
  ): SuggestionService = {
    new SuggestionService(queryLogRegistry, config)
  }
}
