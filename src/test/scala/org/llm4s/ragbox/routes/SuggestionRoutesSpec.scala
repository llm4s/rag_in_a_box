package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.llm4s.ragbox.config.RuntimeSettings
import org.llm4s.ragbox.model.{CollectionMetrics, QueryAnalyticsSummary}
import org.llm4s.ragbox.service.SuggestionService
import org.llm4s.ragbox.testkit.{InMemoryQueryLogRegistry, TestAuth, TestFixtures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** The suggestions API (`SuggestionRoutes`): filters, the summary, and admin-only access. */
class SuggestionRoutesSpec extends AnyFlatSpec with Matchers {

  private val summary = QueryAnalyticsSummary(
    totalQueries = 100, averageLatencyMs = 900, p50LatencyMs = 800, p95LatencyMs = 2000, p99LatencyMs = 3000,
    averageChunksRetrieved = 5, averageChunksUsed = 4, averageRating = Some(4.2), ratedQueriesCount = 40,
    queriesWithFeedback = 40, topCollections = Nil, periodStart = Instant.EPOCH, periodEnd = Instant.EPOCH,
    // global: "High Retrieval Latency" (warning)
    p95RetrievalLatencyMs = Some(900)
  )

  private def collection(name: String, zeroResults: Int, rating: Double) = CollectionMetrics(
    collection = name, queryCount = 20, avgLatencyMs = 900, avgChunksRetrieved = 5, avgChunksUsed = 4,
    avgRating = Some(rating), ratedQueryCount = 10, zeroResultQueries = zeroResults, p95LatencyMs = 2000,
    p95RetrievalLatencyMs = Some(200)
  )

  /** The period each request asked the registry for. */
  private val periods = new AtomicReference[List[(Instant, Instant)]](Nil)

  private val registry = new InMemoryQueryLogRegistry {
    override def getSummary(from: Instant, to: Instant): IO[QueryAnalyticsSummary] =
      IO(periods.updateAndGet((from, to) :: _)).as(summary)
    override def getCollectionMetrics(from: Instant, to: Instant, limit: Int): IO[Seq[CollectionMetrics]] =
      // "a": zero results (critical, content) and low ratings (warning, content); "b": zero results
      IO.pure(Seq(collection("a", 10, 1.0), collection("b", 10, 4.5)))
  }

  private val service = SuggestionService(registry, TestFixtures.testAppConfig, RuntimeSettings.fixed(TestFixtures.testAppConfig))

  private def get(path: String, as: org.llm4s.ragbox.auth.Principal = TestAuth.Admin): (Status, Json) = {
    val app = TestAuth.as(as)(SuggestionRoutes.routes(service)).orNotFound
    app.run(Request[IO](Method.GET, Uri.unsafeFromString(path))).flatMap(r => r.as[Json].map(r.status -> _)).unsafeRunSync()
  }

  private def titles(body: Json): Seq[String] =
    body.hcursor.downField("suggestions").values.toList.flatten.flatMap(_.hcursor.get[String]("title").toOption)

  "GET /api/v1/analytics/suggestions" should "list every suggestion" in {
    val (status, body) = get("/api/v1/analytics/suggestions")
    status shouldBe Status.Ok
    titles(body) should contain allOf ("High Zero-Result Rate for 'a'", "High Zero-Result Rate for 'b'", "Low Ratings for 'a'", "High Retrieval Latency")
    body.hcursor.get[Int]("analyzedQueries") shouldBe Right(100)
    body.hcursor.get[Int]("analyzedCollections") shouldBe Right(2)
  }

  it should "filter by type and severity, ignoring case" in {
    titles(get("/api/v1/analytics/suggestions?severity=CRITICAL")._2) shouldBe
      Seq("High Zero-Result Rate for 'a'", "High Zero-Result Rate for 'b'")
    titles(get("/api/v1/analytics/suggestions?type=content&severity=warning")._2) shouldBe Seq("Low Ratings for 'a'")
    titles(get("/api/v1/analytics/suggestions?type=Search")._2) shouldBe Seq("High Retrieval Latency")
    titles(get("/api/v1/analytics/suggestions?type=embedding")._2) shouldBe empty
  }

  it should "analyse only the collection asked for" in {
    titles(get("/api/v1/analytics/suggestions?collection=b&severity=critical")._2) shouldBe Seq("High Zero-Result Rate for 'b'")
  }

  it should "pass the period on, and reject an invalid one" in {
    periods.set(Nil)
    get("/api/v1/analytics/suggestions?from=2026-01-01T00:00:00Z&to=2026-01-08T00:00:00Z")._1 shouldBe Status.Ok
    periods.get.head shouldBe (Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-08T00:00:00Z"))
    get("/api/v1/analytics/suggestions?from=yesterday")._1 shouldBe Status.BadRequest
    get("/api/v1/analytics/suggestions/summary?to=never")._1 shouldBe Status.BadRequest
  }

  "GET /api/v1/analytics/suggestions/summary" should "count the suggestions by severity" in {
    val (status, body) = get("/api/v1/analytics/suggestions/summary")
    status shouldBe Status.Ok
    val c = body.hcursor
    (c.get[Int]("criticalCount"), c.get[Int]("warningCount"), c.get[Int]("infoCount"), c.get[Int]("total")) shouldBe
      (Right(2), Right(2), Right(0), Right(4))
    c.get[Seq[String]]("topIssues") shouldBe Right(Seq("High Zero-Result Rate for 'a'", "High Zero-Result Rate for 'b'", "High Retrieval Latency"))
  }

  "GET /api/v1/analytics/suggestions/collection/{name}" should "return only that collection's suggestions" in {
    val (status, body) = get("/api/v1/analytics/suggestions/collection/a")
    status shouldBe Status.Ok
    titles(body) shouldBe Seq("High Zero-Result Rate for 'a'", "Low Ratings for 'a'")
  }

  "The suggestions API" should "be admin-only" in {
    Seq("/api/v1/analytics/suggestions", "/api/v1/analytics/suggestions/summary", "/api/v1/analytics/suggestions/collection/a")
      .map(p => get(p, TestAuth.user("bob"))._1).distinct shouldBe Seq(Status.Forbidden)
  }
}
