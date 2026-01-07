package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.service.ChunkingService
import org.llm4s.ragbox.testkit.TestFixtures

class ChunkingRoutesSpec extends AnyFlatSpec with Matchers {

  private val chunkingService = ChunkingService(TestFixtures.testAppConfig)
  private val routes = ChunkingRoutes.routes(chunkingService)

  // ============================================================
  // POST /api/v1/chunking/preview
  // ============================================================

  "POST /api/v1/chunking/preview" should "chunk simple content" in {
    val requestBody = """{"content": "This is a test document. It has multiple sentences. We want to see how chunking works with different strategies."}"""
    val request = Request[IO](Method.POST, uri"/api/v1/chunking/preview")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("chunks")
    body should include("stats")
    body should include("strategy")
  }

  it should "respect the requested strategy" in {
    val requestBody = """{"content": "# Heading\n\nSome paragraph text.\n\n## Subheading\n\nMore text here.", "strategy": "markdown"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/chunking/preview")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"strategy\":\"markdown\"")
  }

  it should "auto-detect markdown files" in {
    val requestBody = """{"content": "# Hello World\n\nSome content.", "filename": "README.md"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/chunking/preview")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"strategy\":\"markdown\"")
    body should include("\"source\":\"auto-detect\"")
  }

  it should "use custom chunk sizes" in {
    val requestBody = """{"content": "This is some test content that we want to chunk with custom sizes.", "targetSize": 100, "maxSize": 150, "overlap": 10}"""
    val request = Request[IO](Method.POST, uri"/api/v1/chunking/preview")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"targetSize\":100")
    body should include("\"maxSize\":150")
    body should include("\"overlap\":10")
  }

  it should "return stats with chunk information" in {
    val requestBody = """{"content": "First sentence. Second sentence. Third sentence. Fourth sentence."}"""
    val request = Request[IO](Method.POST, uri"/api/v1/chunking/preview")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("chunkCount")
    body should include("totalLength")
    body should include("avgChunkSize")
  }

  // ============================================================
  // POST /api/v1/chunking/compare
  // ============================================================

  "POST /api/v1/chunking/compare" should "compare multiple strategies" in {
    val requestBody = """{"content": "This is test content for comparison.", "strategies": ["simple", "sentence"]}"""
    val request = Request[IO](Method.POST, uri"/api/v1/chunking/compare")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("results")
    body should include("simple")
    body should include("sentence")
  }

  it should "use defaults when no strategies specified" in {
    val requestBody = """{"content": "Test content.", "strategies": []}"""
    val request = Request[IO](Method.POST, uri"/api/v1/chunking/compare")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("results")
    // Should include default strategies
    body should include("simple")
    body should include("sentence")
    body should include("markdown")
  }

  it should "include recommendation" in {
    val requestBody = """{"content": "# Markdown\n\nWith content.", "strategies": ["simple", "markdown"]}"""
    val request = Request[IO](Method.POST, uri"/api/v1/chunking/compare")
      .withEntity(requestBody)
      .withContentType(headers.`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("recommendation")
  }

  // ============================================================
  // GET /api/v1/chunking/strategies
  // ============================================================

  "GET /api/v1/chunking/strategies" should "list available strategies" in {
    val request = Request[IO](Method.GET, uri"/api/v1/chunking/strategies")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("strategies")
    body should include("currentDefault")
  }

  it should "include strategy details" in {
    val request = Request[IO](Method.GET, uri"/api/v1/chunking/strategies")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("simple")
    body should include("sentence")
    body should include("markdown")
    body should include("semantic")
    body should include("description")
    body should include("bestFor")
  }

  // ============================================================
  // GET /api/v1/chunking/presets
  // ============================================================

  "GET /api/v1/chunking/presets" should "list preset configurations" in {
    val request = Request[IO](Method.GET, uri"/api/v1/chunking/presets")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("presets")
    body should include("current")
  }

  it should "include preset details" in {
    val request = Request[IO](Method.GET, uri"/api/v1/chunking/presets")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("small")
    body should include("default")
    body should include("large")
    body should include("targetSize")
    body should include("maxSize")
    body should include("overlap")
  }

  it should "return current configuration" in {
    val request = Request[IO](Method.GET, uri"/api/v1/chunking/presets")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"current\"")
    body should include("Current Configuration")
  }
}
