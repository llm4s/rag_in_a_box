package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.testkit.{MockRAGService, TestFixtures}

class VisibilityRoutesSpec extends AnyFlatSpec with Matchers {

  private def createRoutes(): (HttpRoutes[IO], MockRAGService) = {
    val ragService = MockRAGService()
    val routes = VisibilityRoutes.routes(ragService)
    (routes, ragService)
  }

  // ============================================================
  // GET /api/v1/visibility/config
  // ============================================================

  "GET /api/v1/visibility/config" should "return detailed configuration" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("embedding")
    body should include("rag")
    body should include("llm")
  }

  it should "include changeability annotations" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/config")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("changeability")
  }

  // ============================================================
  // GET /api/v1/visibility/chunks
  // ============================================================

  "GET /api/v1/visibility/chunks" should "return paginated chunk list" in {
    val (routes, ragService) = createRoutes()

    // Add a document with chunks
    ragService.ingestDocument("Test content for chunking.", "doc-1", Map("collection" -> "test")).unsafeRunSync()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/chunks")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("chunks")
    body should include("total")
  }

  it should "support pagination parameters" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/chunks?page=1&pageSize=10")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("page")
    body should include("pageSize")
  }

  // ============================================================
  // GET /api/v1/visibility/chunks/{docId}
  // ============================================================

  "GET /api/v1/visibility/chunks/{docId}" should "return chunks for a specific document" in {
    val (routes, ragService) = createRoutes()

    // Add a document
    ragService.ingestDocument("Test document content.", "test-doc", Map("collection" -> "test")).unsafeRunSync()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/chunks/test-doc")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("documentId")
    body should include("chunks")
  }

  it should "return 404 for non-existent document" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/chunks/nonexistent")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NotFound
  }

  // ============================================================
  // GET /api/v1/visibility/chunks/{docId}/{idx}
  // ============================================================

  "GET /api/v1/visibility/chunks/{docId}/{idx}" should "return specific chunk" in {
    val (routes, ragService) = createRoutes()

    // Add a document
    ragService.ingestDocument("Test document with multiple chunks.", "chunk-doc", Map("collection" -> "test")).unsafeRunSync()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/chunks/chunk-doc/0")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("content")
    body should include("index")
  }

  it should "return 404 for non-existent chunk" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/chunks/nonexistent/99")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NotFound
  }

  // ============================================================
  // GET /api/v1/visibility/stats
  // ============================================================

  "GET /api/v1/visibility/stats" should "return detailed statistics" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/stats")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("documentCount")
    body should include("chunkCount")
  }

  // ============================================================
  // GET /api/v1/visibility/collections
  // ============================================================

  "GET /api/v1/visibility/collections" should "return collection details" in {
    val (routes, _) = createRoutes()

    val request = Request[IO](Method.GET, uri"/api/v1/visibility/collections")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("collections")
  }
}
