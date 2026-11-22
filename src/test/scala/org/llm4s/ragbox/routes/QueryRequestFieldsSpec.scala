package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.`Content-Type`
import org.http4s.implicits._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.testkit.{InMemoryQueryLogRegistry, MockRAGService}
import org.llm4s.ragbox.testkit.TestAuth.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The optional `/query` request fields: `includeMetadata` and the `overrides` (#53). */
class QueryRequestFieldsSpec extends AnyFlatSpec with Matchers {

  private val context = ContextItem("chunk", 0.8, Map("docId" -> "doc-1", "source" -> "a.md"), Some("doc-1"), Some(2))

  private def setup(): (MockRAGService, HttpRoutes[IO]) = {
    val service = MockRAGService()
    service.setQueryResponse(QueryResponse("answer", Seq(context)))
    service -> QueryRoutes.routes(service, InMemoryQueryLogRegistry()).asAdmin
  }

  private def post(routes: HttpRoutes[IO], path: String, body: String): Response[IO] =
    routes.orNotFound.run(
      Request[IO](Method.POST, Uri.unsafeFromString(path))
        .withEntity(body)
        .withContentType(`Content-Type`(MediaType.application.json))
    ).unsafeRunSync()

  private def contexts(response: Response[IO]): Vector[Json] =
    response.as[Json].unsafeRunSync().hcursor.downField("contexts").as[Vector[Json]].toOption.get

  "includeMetadata" should "return chunk metadata by default and when true" in {
    val (_, routes) = setup()
    Seq("""{"question": "q"}""", """{"question": "q", "includeMetadata": true}""").foreach { body =>
      val ctx = contexts(post(routes, "/api/v1/query", body)).head
      ctx.hcursor.downField("metadata").downField("source").as[String] shouldBe Right("a.md")
    }
  }

  it should "leave the metadata out when false, keeping documentId and chunkIndex" in {
    val (_, routes) = setup()
    val ctx = contexts(post(routes, "/api/v1/query", """{"question": "q", "includeMetadata": false}""")).head
    ctx.hcursor.downField("metadata").as[Map[String, String]] shouldBe Right(Map.empty)
    ctx.hcursor.downField("documentId").as[String] shouldBe Right("doc-1")
    ctx.hcursor.downField("chunkIndex").as[Int] shouldBe Right(2)
    ctx.hcursor.downField("content").as[String] shouldBe Right("chunk")
  }

  it should "apply to streamed contexts too" in {
    val (_, routes) = setup()
    val body = post(routes, "/api/v1/query/stream", """{"question": "q", "includeMetadata": false}""").as[String].unsafeRunSync()
    val contextEvent = body.split("\n\n").find(_.startsWith("event: context")).get
    val data = parse(contextEvent.linesIterator.find(_.startsWith("data: ")).get.stripPrefix("data: ")).toOption.get
    data.hcursor.downField("context").downField("metadata").as[Map[String, String]] shouldBe Right(Map.empty)
  }

  "overrides.similarityThreshold" should "be passed to the query" in {
    val (service, routes) = setup()
    post(routes, "/api/v1/query", """{"question": "q", "overrides": {"similarityThreshold": 0.75}}""").status shouldBe Status.Ok
    service.getQueryOverrides.last.similarityThreshold shouldBe Some(0.75)
  }

  it should "be rejected outside 0 to 1" in {
    val (service, routes) = setup()
    Seq("1.5", "-0.1").foreach { t =>
      val response = post(routes, "/api/v1/query", s"""{"question": "q", "overrides": {"similarityThreshold": $t}}""")
      response.status shouldBe Status.BadRequest
      response.as[String].unsafeRunSync() should include("similarityThreshold must be between 0 and 1")
    }
    service.getQueryOverrides shouldBe empty
  }

  "overrides.llmTemperature" should "be rejected, on /query and before a stream starts" in {
    val (service, routes) = setup()
    Seq("/api/v1/query", "/api/v1/query/stream").foreach { path =>
      val response = post(routes, path, """{"question": "q", "overrides": {"llmTemperature": 0.7}}""")
      response.status shouldBe Status.BadRequest
      response.as[String].unsafeRunSync() should include("llmTemperature is not supported")
    }
    service.getQueryOverrides shouldBe empty
  }
}
