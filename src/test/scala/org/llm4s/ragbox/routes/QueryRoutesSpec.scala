package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.http4s.headers.`Content-Type`
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.llm4s.ragbox.testkit.{MockRAGService, InMemoryQueryLogRegistry, TestFixtures}
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._

class QueryRoutesSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var mockService: MockRAGService = _
  private var queryLogRegistry: InMemoryQueryLogRegistry = _
  private var routes: HttpRoutes[IO] = _

  override def beforeEach(): Unit = {
    mockService = MockRAGService()
    queryLogRegistry = InMemoryQueryLogRegistry()
    routes = QueryRoutes.routes(mockService, queryLogRegistry, allowAdminHeader = false)
  }

  // ============================================================
  // POST /api/v1/query - Query with answer
  // ============================================================

  "POST /api/v1/query" should "return answer for valid query" in {
    // Set up mock response
    mockService.setQueryResponse(QueryResponse(
      answer = "This is the mock answer",
      contexts = Seq(
        ContextItem(
          content = "Relevant context",
          score = 0.9,
          metadata = Map("docId" -> "doc-1"),
          documentId = Some("doc-1"),
          chunkIndex = Some(0)
        )
      ),
      usage = Some(UsageInfo(100, 50, 150))
    ))

    val body = """{"question": "What is RAG?"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/query")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("This is the mock answer")
    responseBody should include("contexts")
  }

  it should "include usage information" in {
    mockService.setQueryResponse(QueryResponse(
      answer = "Answer",
      contexts = Seq.empty,
      usage = Some(UsageInfo(100, 50, 150))
    ))

    val body = """{"question": "What is RAG?"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/query")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("usage")
    responseBody should include("promptTokens")
    responseBody should include("completionTokens")
  }

  it should "support collection filter" in {
    mockService.setQueryResponse(QueryResponse("Answer", Seq.empty, None))

    val body = """{"question": "What is RAG?", "collection": "test-collection"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/query")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
  }

  it should "support topK parameter" in {
    mockService.setQueryResponse(QueryResponse("Answer", Seq.empty, None))

    val body = """{"question": "What is RAG?", "topK": 10}"""
    val request = Request[IO](Method.POST, uri"/api/v1/query")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    // Verify topK was passed
    mockService.getQueryCalls.head._2 shouldBe Some(10)
  }

  it should "log query to registry" in {
    mockService.setQueryResponse(QueryResponse("Answer", Seq.empty, None))

    val body = """{"question": "What is RAG?"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/query")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    routes.orNotFound.run(request).unsafeRunSync()

    // Give async logging a moment
    Thread.sleep(50)

    queryLogRegistry.logCount shouldBe 1
  }

  it should "return error when query fails" in {
    mockService.setError(new RuntimeException("Query failed"))

    val body = """{"question": "What is RAG?"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/query")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.InternalServerError

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("Query failed")

    mockService.clearError()
  }

  // ============================================================
  // POST /api/v1/search - Search without answer
  // ============================================================

  "POST /api/v1/search" should "return search results" in {
    mockService.setSearchResponse(SearchResponse(
      results = Seq(
        ContextItem(
          content = "Search result content",
          score = 0.85,
          metadata = Map("docId" -> "doc-1"),
          documentId = Some("doc-1"),
          chunkIndex = Some(0)
        )
      ),
      count = 1
    ))

    val body = """{"query": "RAG"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/search")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("results")
    responseBody should include("Search result content")
    responseBody should include("\"count\":1")
  }

  it should "support collection filter" in {
    mockService.setSearchResponse(SearchResponse(Seq.empty, 0))

    val body = """{"query": "RAG", "collection": "test-collection"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/search")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
  }

  it should "support topK parameter" in {
    mockService.setSearchResponse(SearchResponse(Seq.empty, 0))

    val body = """{"query": "RAG", "topK": 5}"""
    val request = Request[IO](Method.POST, uri"/api/v1/search")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    mockService.getSearchCalls.head._2 shouldBe Some(5)
  }

  it should "return error when search fails" in {
    mockService.setError(new RuntimeException("Search failed"))

    val body = """{"query": "RAG"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/search")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.InternalServerError

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("Search failed")

    mockService.clearError()
  }

  it should "return empty results when no matches" in {
    mockService.setSearchResponse(SearchResponse(Seq.empty, 0))

    val body = """{"query": "nonexistent term"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/search")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("\"results\":[]")
    responseBody should include("\"count\":0")
  }
}
