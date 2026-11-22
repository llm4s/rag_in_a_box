package org.llm4s.ragbox.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.llm4s.ragbox.model.{CollectionMetrics, OptimizationSuggestion}
import org.llm4s.ragbox.registry.QueryLogRegistryBase
import org.llm4s.ragbox.testkit.{InMemoryQueryLogRegistry, TestFixtures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The per-collection suggestion rules, on real per-collection metrics (#53). */
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
}
