package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.testkit.MockRAGService

class ConfigRoutesSpec extends AnyFlatSpec with Matchers {

  private def createRoutes(): HttpRoutes[IO] = {
    val ragService = MockRAGService()
    ConfigRoutes.routes(ragService)
  }

  // ============================================================
  // GET /api/v1/config
  // ============================================================

  "GET /api/v1/config" should "return current configuration" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("embedding")
    body should include("llm")
    body should include("rag")
    body should include("database")
  }

  it should "include embedding provider info" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("provider")
    body should include("model")
    body should include("dimensions")
  }

  it should "include LLM config info" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("temperature")
  }

  it should "include RAG settings" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("chunkingStrategy")
    body should include("chunkSize")
    body should include("chunkOverlap")
    body should include("topK")
    body should include("fusionStrategy")
  }

  // ============================================================
  // PUT /api/v1/config
  // ============================================================

  "PUT /api/v1/config" should "return bad request" in {
    val routes = createRoutes()

    val requestBody = """{"embedding": {"model": "new-model"}}"""
    val request = Request[IO](Method.PUT, uri"/api/v1/config")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
    val body = response.as[String].unsafeRunSync()
    body should include("Runtime configuration updates not supported")
  }

  // ============================================================
  // GET /api/v1/config/providers
  // ============================================================

  "GET /api/v1/config/providers" should "list available providers" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config/providers")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("embeddingProviders")
    body should include("llmProviders")
  }

  it should "include provider models" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config/providers")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("models")
    body should include("configured")
  }

  it should "include chunking and fusion strategies" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/config/providers")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("chunkingStrategies")
    body should include("fusionStrategies")
  }
}
