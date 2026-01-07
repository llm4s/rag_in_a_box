package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.QueryLogRegistryBase

import java.time.Instant

class AnalyticsRoutesSpec extends AnyFlatSpec with Matchers {

  // Mock query log registry for testing
  private class MockQueryLogRegistry extends QueryLogRegistryBase {
    private var logs = Map[String, QueryLogEntry]()
    private var ratings = Map[String, Int]()

    def addEntry(entry: QueryLogEntry): Unit = {
      logs = logs + (entry.id -> entry)
    }

    override def initialize() = IO.unit

    override def logQuery(
      queryText: String,
      collectionPattern: Option[String],
      userId: Option[String],
      embeddingLatencyMs: Option[Int],
      searchLatencyMs: Option[Int],
      llmLatencyMs: Option[Int],
      totalLatencyMs: Int,
      chunksRetrieved: Int,
      chunksUsed: Int,
      answerTokens: Option[Int]
    ) = IO {
      val id = java.util.UUID.randomUUID().toString
      val entry = QueryLogEntry(
        id = id,
        queryText = queryText,
        collectionPattern = collectionPattern,
        userId = userId,
        embeddingLatencyMs = embeddingLatencyMs,
        searchLatencyMs = searchLatencyMs,
        llmLatencyMs = llmLatencyMs,
        totalLatencyMs = totalLatencyMs,
        chunksRetrieved = chunksRetrieved,
        chunksUsed = chunksUsed,
        answerTokens = answerTokens,
        userRating = None,
        createdAt = Instant.now()
      )
      logs = logs + (id -> entry)
      id
    }

    override def list(
      from: Option[Instant],
      to: Option[Instant],
      collection: Option[String],
      page: Int,
      pageSize: Int
    ) = IO {
      val filtered = logs.values.toSeq
        .filter(e => from.forall(f => !e.createdAt.isBefore(f)))
        .filter(e => to.forall(t => !e.createdAt.isAfter(t)))
        .filter(e => collection.forall(c => e.collectionPattern.contains(c)))
        .sortBy(_.createdAt)(Ordering[Instant].reverse)

      val paginated = filtered.drop((page - 1) * pageSize).take(pageSize)
      (paginated, filtered.size)
    }

    override def get(id: String) = IO.pure(logs.get(id))

    override def getSummary(from: Instant, to: Instant) = IO.pure {
      val entries = logs.values.toSeq
        .filter(e => !e.createdAt.isBefore(from) && !e.createdAt.isAfter(to))

      val avgLatency = if (entries.isEmpty) 0.0 else entries.map(_.totalLatencyMs).sum.toDouble / entries.size
      val avgChunksRetrieved = if (entries.isEmpty) 0.0 else entries.map(_.chunksRetrieved).sum.toDouble / entries.size
      val avgRating = if (ratings.isEmpty) None else Some(ratings.values.sum.toDouble / ratings.size)

      QueryAnalyticsSummary(
        totalQueries = entries.size,
        averageLatencyMs = avgLatency,
        p50LatencyMs = 0,
        p95LatencyMs = 0,
        p99LatencyMs = 0,
        averageChunksRetrieved = avgChunksRetrieved,
        averageChunksUsed = avgChunksRetrieved,
        averageRating = avgRating,
        ratedQueriesCount = ratings.size,
        queriesWithFeedback = ratings.size,
        topCollections = entries.flatMap(_.collectionPattern).groupBy(identity).map { case (k, v) =>
          CollectionQueryStats(k, v.size, avgLatency, avgRating)
        }.toSeq.sortBy(-_.queryCount).take(5),
        periodStart = from,
        periodEnd = to
      )
    }

    override def addFeedback(
      queryId: String,
      rating: Int,
      relevantChunks: Option[Seq[String]],
      comment: Option[String]
    ) = IO {
      if (logs.contains(queryId)) {
        ratings = ratings + (queryId -> rating)
        true
      } else {
        false
      }
    }

    override def close() = IO.unit
  }

  private def createRoutes(): (HttpRoutes[IO], MockQueryLogRegistry) = {
    val registry = new MockQueryLogRegistry()
    val routes = AnalyticsRoutes.routes(registry)
    (routes, registry)
  }

  private val sampleEntry = QueryLogEntry(
    id = "query-1",
    queryText = "What is RAG?",
    collectionPattern = Some("docs"),
    userId = Some("user-1"),
    embeddingLatencyMs = Some(50),
    searchLatencyMs = Some(100),
    llmLatencyMs = Some(500),
    totalLatencyMs = 650,
    chunksRetrieved = 5,
    chunksUsed = 3,
    answerTokens = Some(150),
    userRating = None,
    createdAt = Instant.now()
  )

  "GET /api/v1/analytics/queries" should "return empty list when no queries" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/analytics/queries")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"queries\"")
    body should include("\"total\":0")
  }

  it should "return queries when they exist" in {
    val (routes, registry) = createRoutes()
    registry.addEntry(sampleEntry)

    val request = Request[IO](Method.GET, uri"/api/v1/analytics/queries")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("What is RAG?")
    body should include("\"total\":1")
  }

  it should "support pagination" in {
    val (routes, registry) = createRoutes()
    // Add multiple entries
    for (i <- 1 to 10) {
      registry.addEntry(sampleEntry.copy(id = s"query-$i", queryText = s"Query $i"))
    }

    val request = Request[IO](Method.GET, uri"/api/v1/analytics/queries?page=2&pageSize=3")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"total\":10")
    body should include("\"page\":2")
    body should include("\"pageSize\":3")
  }

  it should "filter by collection" in {
    val (routes, registry) = createRoutes()
    registry.addEntry(sampleEntry.copy(id = "q1", collectionPattern = Some("docs")))
    registry.addEntry(sampleEntry.copy(id = "q2", collectionPattern = Some("other")))

    val request = Request[IO](Method.GET, uri"/api/v1/analytics/queries?collection=docs")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"total\":1")
  }

  "GET /api/v1/analytics/queries/summary" should "return analytics summary" in {
    val (routes, registry) = createRoutes()
    registry.addEntry(sampleEntry)

    val request = Request[IO](Method.GET, uri"/api/v1/analytics/queries/summary")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("totalQueries")
    body should include("averageLatencyMs")
  }

  it should "return zero stats when no queries" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/analytics/queries/summary")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"totalQueries\":0")
  }

  "GET /api/v1/analytics/queries/:id" should "return query by id" in {
    val (routes, registry) = createRoutes()
    registry.addEntry(sampleEntry)

    val request = Request[IO](Method.GET, uri"/api/v1/analytics/queries/query-1")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("What is RAG?")
    body should include("query-1")
  }

  it should "return 404 for non-existent query" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/analytics/queries/nonexistent")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NotFound
  }

  "POST /api/v1/feedback" should "accept valid feedback" in {
    val (routes, registry) = createRoutes()
    registry.addEntry(sampleEntry)

    val requestBody = """{"queryId": "query-1", "rating": 5, "comment": "Great answer!"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/feedback")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"success\":true")
  }

  it should "reject invalid rating" in {
    val (routes, registry) = createRoutes()
    registry.addEntry(sampleEntry)

    val requestBody = """{"queryId": "query-1", "rating": 10}"""
    val request = Request[IO](Method.POST, uri"/api/v1/feedback")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
    val body = response.as[String].unsafeRunSync()
    body should include("Rating must be between 1 and 5")
  }

  it should "return 404 for non-existent query" in {
    val (routes, _) = createRoutes()

    val requestBody = """{"queryId": "nonexistent", "rating": 4}"""
    val request = Request[IO](Method.POST, uri"/api/v1/feedback")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NotFound
  }
}
