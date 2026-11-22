package org.llm4s.ragbox.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.llm4s.ragbox.config.RuntimeSettings
import org.llm4s.ragbox.model.{CollectionMetrics, OptimizationSuggestion, QueryAnalyticsSummary, RuntimeConfig}
import org.llm4s.ragbox.registry.QueryLogRegistryBase
import org.llm4s.ragbox.testkit.{InMemoryQueryLogRegistry, TestFixtures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
 * The suggestion rules: on real per-collection metrics from logged queries (#53), and each rule
 * on crafted metrics, just past and just short of its threshold (#48).
 */
class SuggestionServiceSpec extends AnyFlatSpec with Matchers {

  /** Log a successful query to `collection` with the given latencies and retrieved chunks. */
  private def log(
    registry: QueryLogRegistryBase,
    collection: String,
    chunks: Int = 5,
    totalMs: Int = 1000,
    llmMs: Option[Int] = Some(900),
    failed: Boolean = false
  ): IO[String] =
    registry.logQuery("q", Some(collection), None, None, None, llmMs, totalMs, chunks, chunks, None, failed = failed)

  private def suggestions(registry: QueryLogRegistryBase): Seq[OptimizationSuggestion] =
    SuggestionService(registry, TestFixtures.testAppConfig).generateSuggestions().unsafeRunSync().suggestions

  private def titles(registry: QueryLogRegistryBase): Seq[String] = suggestions(registry).map(_.title)

  "The zero-result rule" should "fire for a collection whose queries often retrieve nothing" in {
    val registry = InMemoryQueryLogRegistry()
    (List.fill(15)(log(registry, "docs")) ++ List.fill(5)(log(registry, "docs", chunks = 0)) ++
      List.fill(20)(log(registry, "eng"))).sequence_.unsafeRunSync()

    val zero = suggestions(registry).filter(_.title.startsWith("High Zero-Result Rate"))
    zero.map(_.title) shouldBe Seq("High Zero-Result Rate for 'docs'")
    zero.head.severity shouldBe "critical"
    zero.head.evidence.currentValue shouldBe 0.25
    zero.head.evidence.sampleSize shouldBe 20
  }

  it should "not count failed queries" in {
    val registry = InMemoryQueryLogRegistry()
    (List.fill(18)(log(registry, "docs")) ++ List.fill(10)(log(registry, "docs", chunks = 0, failed = true)))
      .sequence_.unsafeRunSync()
    titles(registry).filter(_.startsWith("High Zero-Result")) shouldBe empty
  }

  "The per-collection latency rule" should "use the real p95 of retrieval latency" in {
    val registry = InMemoryQueryLogRegistry()
    // "slow": 2 of 20 queries take 2 s to retrieve - the p95, though the average is only 290 ms
    (List.fill(18)(log(registry, "slow", totalMs = 1100, llmMs = Some(1000))) ++
      List.fill(2)(log(registry, "slow", totalMs = 3000, llmMs = Some(1000))) ++
      // "llm-heavy": slow answers (3 s) but fast retrieval (50 ms): no latency suggestion
      List.fill(20)(log(registry, "llm-heavy", totalMs = 3050, llmMs = Some(3000)))).sequence_.unsafeRunSync()

    val latency = suggestions(registry).filter(_.title.startsWith("High Latency for"))
    latency.map(_.title) shouldBe Seq("High Latency for 'slow'")
    latency.head.evidence.metric shouldBe "p95_retrieval_latency_ms"
    latency.head.evidence.currentValue shouldBe 2000.0
  }

  "CollectionMetrics.fromEntries" should "compute each collection's own figures" in {
    val registry = InMemoryQueryLogRegistry()
    (List.fill(3)(log(registry, "a", chunks = 0, totalMs = 100, llmMs = None)) ++
      List(log(registry, "a", chunks = 4, totalMs = 300, llmMs = Some(200))) ++
      List(log(registry, "b"))).sequence_.unsafeRunSync()
    val now = Instant.now()
    val metrics = registry.getCollectionMetrics(now.minusSeconds(60), now.plusSeconds(60)).unsafeRunSync()
    metrics.map(_.collection) shouldBe Seq("a", "b")
    val a: CollectionMetrics = metrics.head
    (a.queryCount, a.zeroResultQueries, a.p95LatencyMs, a.p95RetrievalLatencyMs) shouldBe (4, 3, 300, Some(100))
    a.avgChunksRetrieved shouldBe 1.0
  }

  // ------------------------------------------------------------ rules on crafted metrics

  /** Metrics that trigger no rule: the baseline each rule test changes one figure of. */
  private val healthy = QueryAnalyticsSummary(
    totalQueries = 100,
    averageLatencyMs = 900,
    p50LatencyMs = 800,
    p95LatencyMs = 2000,
    p99LatencyMs = 3000,
    averageChunksRetrieved = 5,
    averageChunksUsed = 4,
    averageRating = Some(4.2),
    ratedQueriesCount = 40,
    queriesWithFeedback = 40,
    topCollections = Nil,
    periodStart = Instant.EPOCH,
    periodEnd = Instant.EPOCH,
    p95RetrievalLatencyMs = Some(200)
  )

  private val healthyCollection = CollectionMetrics(
    collection = "kb",
    queryCount = 20,
    avgLatencyMs = 900,
    avgChunksRetrieved = 5,
    avgChunksUsed = 4,
    avgRating = Some(4.2),
    ratedQueryCount = 10,
    zeroResultQueries = 0,
    p95LatencyMs = 2000,
    p95RetrievalLatencyMs = Some(200)
  )

  private val settings = RuntimeConfig(
    topK = 5, fusionStrategy = "rrf", rrfK = 60, systemPrompt = "", llmTemperature = 0.1,
    chunkingStrategy = "sentence", chunkSize = 800, chunkOverlap = 100
  )

  /** A registry that reports the given metrics for any period. */
  private class CraftedRegistry(summary: QueryAnalyticsSummary, collections: Seq[CollectionMetrics]) extends InMemoryQueryLogRegistry {
    override def getSummary(from: Instant, to: Instant): IO[QueryAnalyticsSummary] = IO.pure(summary)
    override def getCollectionMetrics(from: Instant, to: Instant, limit: Int): IO[Seq[CollectionMetrics]] = IO.pure(collections)
  }

  private def crafted(
    summary: QueryAnalyticsSummary = healthy,
    collections: Seq[CollectionMetrics] = Nil,
    runtime: RuntimeConfig = settings
  ): SuggestionService = {
    val runtimeSettings = new RuntimeSettings { def current: RuntimeConfig = runtime }
    SuggestionService(new CraftedRegistry(summary, collections), TestFixtures.testAppConfig, runtimeSettings)
  }

  private def fired(
    summary: QueryAnalyticsSummary = healthy,
    collections: Seq[CollectionMetrics] = Nil,
    runtime: RuntimeConfig = settings,
    collection: Option[String] = None
  ): Seq[OptimizationSuggestion] =
    crafted(summary, collections, runtime).generateSuggestions(collection = collection).unsafeRunSync().suggestions

  private def firedTitles(
    summary: QueryAnalyticsSummary = healthy,
    collections: Seq[CollectionMetrics] = Nil,
    runtime: RuntimeConfig = settings
  ): Seq[String] =
    fired(summary, collections, runtime).map(_.title)

  "Healthy metrics" should "trigger no suggestion" in {
    fired(collections = Seq(healthyCollection)) shouldBe empty
  }

  "The sample size rule" should "only suggest waiting for data below 10 queries" in {
    val few = fired(healthy.copy(totalQueries = 9, averageRating = Some(1.0)), Seq(healthyCollection.copy(zeroResultQueries = 20)))
    few.map(s => (s.title, s.severity)) shouldBe Seq(("Insufficient Data", "info"))
    few.head.evidence.currentValue shouldBe 9.0
    firedTitles(healthy.copy(totalQueries = 10)) shouldBe empty
  }

  "The chunk utilization rules" should "flag low utilization only when more than 5 chunks are retrieved" in {
    val rule = fired(healthy.copy(averageChunksRetrieved = 10, averageChunksUsed = 3.9)).find(_.title == "Low Chunk Utilization").get
    (rule.severity, rule.suggestedValue) shouldBe ("warning", Some("480 chars"))
    rule.evidence.currentValue shouldBe 0.39 +- 1e-9
    firedTitles(healthy.copy(averageChunksRetrieved = 10, averageChunksUsed = 4)) should not contain "Low Chunk Utilization"
    firedTitles(healthy.copy(averageChunksRetrieved = 5, averageChunksUsed = 1)) should not contain "Low Chunk Utilization"
  }

  they should "flag near-total utilization only with ratings below 4" in {
    val title = "High Chunk Utilization with Moderate Ratings"
    val full = healthy.copy(averageChunksRetrieved = 5, averageChunksUsed = 5)
    fired(full.copy(averageRating = Some(3.9))).find(_.title == title).flatMap(_.suggestedValue) shouldBe Some("1000 chars or topK=7")
    firedTitles(full.copy(averageRating = Some(4.0))) should not contain title
    firedTitles(full.copy(averageRating = None)) should not contain title
    firedTitles(full.copy(averageChunksUsed = 4.5, averageRating = Some(3.9))) should not contain title
  }

  "The retrieval volume rules" should "flag fewer than 3 chunks with ratings below 3.5, and more than 15" in {
    val few = healthy.copy(averageChunksRetrieved = 2, averageChunksUsed = 2)
    firedTitles(few.copy(averageRating = Some(3.4))) should contain("Low Retrieval Volume")
    firedTitles(few.copy(averageRating = Some(3.5))) should not contain "Low Retrieval Volume"
    firedTitles(few.copy(averageRating = None)) should not contain "Low Retrieval Volume"
    firedTitles(healthy.copy(averageChunksRetrieved = 16, averageChunksUsed = 15)) should contain("High Retrieval Volume")
    firedTitles(healthy.copy(averageChunksRetrieved = 15, averageChunksUsed = 14)) should not contain "High Retrieval Volume"
  }

  "The overlap rule" should "flag chunks without overlap" in {
    fired(runtime = settings.copy(chunkOverlap = 0)).map(s => (s.title, s.suggestedValue)) shouldBe
      Seq(("No Chunk Overlap Configured", Some("120 chars")))
  }

  "The topK rule" should "flag a topK over three times the chunks used" in {
    fired(healthy.copy(averageChunksUsed = 1.6)).find(_.title == "TopK May Be Too High").flatMap(_.suggestedValue) shouldBe Some("topK=5")
    firedTitles(healthy.copy(averageChunksUsed = 1.7)) should not contain "TopK May Be Too High"
    firedTitles(healthy.copy(averageChunksUsed = 0)) should not contain "TopK May Be Too High"
  }

  "The global latency rule" should "flag a p95 retrieval latency over 500 ms, and ignore total latency" in {
    firedTitles(healthy.copy(p95RetrievalLatencyMs = Some(501))) shouldBe Seq("High Retrieval Latency")
    firedTitles(healthy.copy(p95RetrievalLatencyMs = Some(500))) shouldBe empty
    firedTitles(healthy.copy(p95RetrievalLatencyMs = None, p95LatencyMs = 60000)) shouldBe empty
  }

  "The embedding rule" should "flag ratings below 2.5 despite more than 3 chunks retrieved" in {
    val title = "Low Ratings Despite Retrieval"
    val rule = fired(healthy.copy(averageRating = Some(2.4))).find(_.title == title).get
    rule.evidence.sampleSize shouldBe 40
    rule.currentValue shouldBe Some(s"${TestFixtures.testAppConfig.embedding.provider}/${TestFixtures.testAppConfig.embedding.model}")
    firedTitles(healthy.copy(averageRating = Some(2.5))) should not contain title
    firedTitles(healthy.copy(averageRating = Some(2.4), averageChunksRetrieved = 3, averageChunksUsed = 3)) should not contain title
  }

  "The per-collection rules" should "need at least 5 queries to the collection" in {
    firedTitles(collections = Seq(healthyCollection.copy(queryCount = 4, zeroResultQueries = 4, avgRating = Some(1.0)))) shouldBe empty
  }

  they should "each fire on their own collection's metrics" in {
    val c = healthyCollection
    firedTitles(collections = Seq(c.copy(avgChunksRetrieved = 10, avgChunksUsed = 3))) shouldBe Seq("Low Chunk Utilization in 'kb'")
    firedTitles(collections = Seq(c.copy(avgRating = Some(2.4)))) shouldBe Seq("Low Ratings for 'kb'")
    firedTitles(collections = Seq(c.copy(avgRating = Some(2.5)))) shouldBe empty
    firedTitles(collections = Seq(c.copy(zeroResultQueries = 3))) shouldBe Seq("High Zero-Result Rate for 'kb'")
    firedTitles(collections = Seq(c.copy(zeroResultQueries = 2))) shouldBe empty // exactly 10%
    firedTitles(collections = Seq(c.copy(p95RetrievalLatencyMs = Some(751)))) shouldBe Seq("High Latency for 'kb'")
    firedTitles(collections = Seq(c.copy(p95RetrievalLatencyMs = Some(750)))) shouldBe empty
  }

  they should "judge a collection by total latency when no retrieval latency is known" in {
    fired(collections = Seq(healthyCollection.copy(p95RetrievalLatencyMs = None, p95LatencyMs = 800)))
      .map(s => (s.title, s.evidence.metric)) shouldBe Seq(("High Latency for 'kb'", "p95_latency_ms"))
  }

  they should "suggest markdown chunking for documentation collections unless it is in use" in {
    val docs = Seq(healthyCollection.copy(collection = "product-docs"), healthyCollection.copy(collection = "readme-files"))
    firedTitles(collections = docs) shouldBe
      Seq("Consider Markdown Strategy for 'product-docs'", "Consider Markdown Strategy for 'readme-files'")
    firedTitles(collections = docs, runtime = settings.copy(chunkingStrategy = "markdown")) shouldBe empty
  }

  "generateSuggestions" should "analyse only the requested collection, and order by severity then title" in {
    val collections = Seq(
      healthyCollection.copy(collection = "a", zeroResultQueries = 10, avgRating = Some(1.0)),
      healthyCollection.copy(collection = "b", zeroResultQueries = 10)
    )
    fired(healthy.copy(p95RetrievalLatencyMs = Some(900)), collections, settings.copy(chunkOverlap = 0))
      .map(s => (s.severity, s.title)) shouldBe Seq(
        ("critical", "High Zero-Result Rate for 'a'"),
        ("critical", "High Zero-Result Rate for 'b'"),
        ("warning", "High Retrieval Latency"),
        ("warning", "Low Ratings for 'a'"),
        ("info", "No Chunk Overlap Configured")
      )
    fired(collections = collections, collection = Some("b")).map(_.title) shouldBe Seq("High Zero-Result Rate for 'b'")
    val response = crafted(healthy, collections).generateSuggestions().unsafeRunSync()
    (response.analyzedQueries, response.analyzedCollections) shouldBe (100, 2)
  }

  "getSuggestionsSummary" should "count suggestions by severity and name the top three" in {
    val collections = Seq(healthyCollection.copy(collection = "a", zeroResultQueries = 10, avgRating = Some(1.0)))
    val summary = crafted(healthy.copy(p95RetrievalLatencyMs = Some(900)), collections, settings.copy(chunkOverlap = 0))
      .getSuggestionsSummary().unsafeRunSync()
    (summary.criticalCount, summary.warningCount, summary.infoCount, summary.total) shouldBe (1, 2, 1, 4)
    summary.topIssues shouldBe Seq("High Zero-Result Rate for 'a'", "High Retrieval Latency", "Low Ratings for 'a'")
  }
}
