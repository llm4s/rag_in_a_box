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
import org.llm4s.ragbox.testkit.{MockRAGService, TestFixtures}
import org.llm4s.ragbox.model.Codecs._

class DocumentRoutesSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var mockService: MockRAGService = _
  private var routes: HttpRoutes[IO] = _

  override def beforeEach(): Unit = {
    mockService = MockRAGService()
    routes = DocumentRoutes.routes(mockService)
  }

  // ============================================================
  // POST /api/v1/documents - Upload document
  // ============================================================

  "POST /api/v1/documents" should "upload a document successfully" in {
    val body = """{"content": "Test content", "filename": "test.txt"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/documents")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Created

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("documentId")
    responseBody should include("chunks")
    responseBody should include("Successfully")
  }

  it should "include metadata in ingested document" in {
    val body = """{"content": "Test content", "filename": "test.txt", "metadata": {"author": "test"}}"""
    val request = Request[IO](Method.POST, uri"/api/v1/documents")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Created

    // Verify the ingest call was made
    mockService.getIngestCalls should have size 1
  }

  it should "support collection parameter" in {
    val body = """{"content": "Test content", "filename": "test.txt", "collection": "test-collection"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/documents")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Created
  }

  // ============================================================
  // GET /api/v1/documents - List documents
  // ============================================================

  "GET /api/v1/documents" should "return empty list when no documents" in {
    val request = Request[IO](Method.GET, uri"/api/v1/documents")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("\"documents\":[]")
    body should include("\"total\":0")
  }

  it should "return document list when documents exist" in {
    // Seed with documents
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val request = Request[IO](Method.GET, uri"/api/v1/documents")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("doc-1")
    body should include("doc-2")
    body should include("doc-3")
  }

  // ============================================================
  // PUT /api/v1/documents/:id - Upsert document
  // ============================================================

  "PUT /api/v1/documents/:id" should "create new document when not exists" in {
    val body = """{"content": "Test content for upsert"}"""
    val request = Request[IO](Method.PUT, uri"/api/v1/documents/new-doc-id")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Created

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("\"action\":\"created\"")
    responseBody should include("new-doc-id")
  }

  it should "return unchanged when content hash matches" in {
    // First upload
    val body = """{"content": "Test content for upsert"}"""
    val request1 = Request[IO](Method.PUT, uri"/api/v1/documents/upsert-doc")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    routes.orNotFound.run(request1).unsafeRunSync()

    // Second upload with same content
    val request2 = Request[IO](Method.PUT, uri"/api/v1/documents/upsert-doc")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request2).unsafeRunSync()

    response.status shouldBe Status.Ok

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("\"action\":\"unchanged\"")
  }

  it should "update document when content changes" in {
    // First upload
    val body1 = """{"content": "Original content"}"""
    val request1 = Request[IO](Method.PUT, uri"/api/v1/documents/update-doc")
      .withEntity(body1)
      .withContentType(`Content-Type`(MediaType.application.json))

    routes.orNotFound.run(request1).unsafeRunSync()

    // Second upload with different content
    val body2 = """{"content": "Updated content"}"""
    val request2 = Request[IO](Method.PUT, uri"/api/v1/documents/update-doc")
      .withEntity(body2)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request2).unsafeRunSync()

    response.status shouldBe Status.Ok

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("\"action\":\"updated\"")
  }

  // ============================================================
  // DELETE /api/v1/documents/:id - Delete document
  // ============================================================

  "DELETE /api/v1/documents/:id" should "delete a document" in {
    // First create a document
    mockService.documentRegistry.seed(Seq(TestFixtures.sampleDocument1))
    mockService.chunkStore.seed("doc-1", TestFixtures.sampleChunks)

    val request = Request[IO](Method.DELETE, uri"/api/v1/documents/doc-1")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.NoContent

    // Verify document was deleted
    mockService.documentRegistry.hasDocument("doc-1") shouldBe false
  }

  // ============================================================
  // DELETE /api/v1/documents - Clear all
  // ============================================================

  "DELETE /api/v1/documents (no body)" should "clear all documents" in {
    // Seed with documents
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val request = Request[IO](Method.DELETE, uri"/api/v1/documents")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("cleared")

    // Verify all documents cleared
    mockService.documentRegistry.documentCount shouldBe 0
  }

  // ============================================================
  // GET /api/v1/collections - List collections
  // ============================================================

  "GET /api/v1/collections" should "return empty list when no collections" in {
    val request = Request[IO](Method.GET, uri"/api/v1/collections")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("\"collections\":[]")
  }

  it should "return collection names when documents exist" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val request = Request[IO](Method.GET, uri"/api/v1/collections")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("test-collection")
    body should include("another-collection")
  }

  // ============================================================
  // GET /api/v1/sync/status - Get sync status
  // ============================================================

  "GET /api/v1/sync/status" should "return sync status" in {
    val request = Request[IO](Method.GET, uri"/api/v1/sync/status")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("documentCount")
    body should include("chunkCount")
  }

  // ============================================================
  // GET /api/v1/sync/documents - List synced document IDs
  // ============================================================

  "GET /api/v1/sync/documents" should "return document states" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val request = Request[IO](Method.GET, uri"/api/v1/sync/documents")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("doc-1")
    body should include("doc-2")
    body should include("doc-3")
  }

  it should "include hash when requested" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val request = Request[IO](Method.GET, uri"/api/v1/sync/documents?include=hash")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("abc123hash")
  }

  // ============================================================
  // POST /api/v1/sync - Mark sync complete / prune
  // ============================================================

  "POST /api/v1/sync" should "mark sync complete without body" in {
    val request = Request[IO](Method.POST, uri"/api/v1/sync")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("Sync completed")
    body should include("\"prunedCount\":0")
  }

  it should "prune documents not in keep list" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val body = """{"keepDocumentIds": ["doc-1"]}"""
    val request = Request[IO](Method.POST, uri"/api/v1/sync")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    // Documents doc-2 and doc-3 should be deleted
    mockService.documentRegistry.hasDocument("doc-1") shouldBe true
  }

  it should "support dry run mode" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val body = """{"keepDocumentIds": ["doc-1"], "dryRun": true}"""
    val request = Request[IO](Method.POST, uri"/api/v1/sync")
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val responseBody = response.as[String].unsafeRunSync()
    responseBody should include("\"dryRun\":true")
    responseBody should include("prunedIds")

    // Documents should NOT be deleted in dry run
    mockService.documentRegistry.hasDocument("doc-2") shouldBe true
    mockService.documentRegistry.hasDocument("doc-3") shouldBe true
  }

  // ============================================================
  // GET /api/v1/stats - Get statistics
  // ============================================================

  "GET /api/v1/stats" should "return statistics" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)
    mockService.chunkStore.seed("doc-1", TestFixtures.sampleChunks)

    val request = Request[IO](Method.GET, uri"/api/v1/stats")

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("documentCount")
    body should include("chunkCount")
    body should include("vectorCount")
    body should include("collections")
  }
}
