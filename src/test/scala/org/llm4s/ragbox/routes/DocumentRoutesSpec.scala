package org.llm4s.ragbox.routes

import scala.compiletime.uninitialized
import org.llm4s.ragbox.testkit.TestAuth.*

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

  private var mockService: MockRAGService = uninitialized
  private var routes: HttpRoutes[IO] = uninitialized

  override def beforeEach(): Unit = {
    mockService = MockRAGService()
    routes = DocumentRoutes.routes(mockService).asAdmin
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

  private def getJson(uri: Uri): io.circe.Json = {
    val response = routes.orNotFound.run(Request[IO](Method.GET, uri)).unsafeRunSync()
    response.status shouldBe Status.Ok
    parse(response.as[String].unsafeRunSync()).toOption.get
  }

  private def ids(json: io.circe.Json): Seq[String] =
    json.hcursor.downField("documents").as[Seq[io.circe.Json]].toOption.get
      .flatMap(_.hcursor.get[String]("id").toOption)

  it should "return newest first with pagination metadata" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val json = getJson(uri"/api/v1/documents")
    ids(json) shouldBe Seq("doc-3", "doc-2", "doc-1")
    json.hcursor.get[Int]("total").toOption shouldBe Some(3)
    json.hcursor.get[Int]("page").toOption shouldBe Some(1)
    json.hcursor.get[Int]("pageSize").toOption shouldBe Some(DocumentRoutes.DefaultPageSize)
    json.hcursor.get[Boolean]("hasMore").toOption shouldBe Some(false)
  }

  it should "paginate with page and pageSize" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val first = getJson(uri"/api/v1/documents?page=1&pageSize=2")
    ids(first) shouldBe Seq("doc-3", "doc-2")
    first.hcursor.get[Int]("total").toOption shouldBe Some(3)
    first.hcursor.get[Boolean]("hasMore").toOption shouldBe Some(true)

    val second = getJson(uri"/api/v1/documents?page=2&pageSize=2")
    ids(second) shouldBe Seq("doc-1")
    second.hcursor.get[Boolean]("hasMore").toOption shouldBe Some(false)

    val beyond = getJson(uri"/api/v1/documents?page=9&pageSize=2")
    ids(beyond) shouldBe empty
    beyond.hcursor.get[Int]("total").toOption shouldBe Some(3)
  }

  it should "clamp pageSize and page into range" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)
    val json = getJson(uri"/api/v1/documents?pageSize=100000&page=0")
    json.hcursor.get[Int]("pageSize").toOption shouldBe Some(DocumentRoutes.MaxPageSize)
    json.hcursor.get[Int]("page").toOption shouldBe Some(1)
  }

  it should "filter by collection and expose it as a top-level field" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)
    val json = getJson(uri"/api/v1/documents?collection=another-collection")
    ids(json) shouldBe Seq("doc-2")
    json.hcursor.get[Int]("total").toOption shouldBe Some(1)
    json.hcursor.downField("documents").downArray.get[String]("collection").toOption shouldBe Some("another-collection")
  }

  it should "filter by case-insensitive search on id and filename" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments :+
      TestFixtures.sampleDocument1.copy(documentId = "doc-4", metadata = Map("filename" -> "Quarterly-Report.pdf")))
    ids(getJson(uri"/api/v1/documents?search=QUARTERLY")) shouldBe Seq("doc-4")
    ids(getJson(uri"/api/v1/documents?search=doc-2")) shouldBe Seq("doc-2")
  }

  // ============================================================
  // GET /api/v1/documents/:id - Document detail
  // ============================================================

  "GET /api/v1/documents/:id" should "return document details from the registry" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)
    val json = getJson(uri"/api/v1/documents/doc-1")
    json.hcursor.get[String]("id").toOption shouldBe Some("doc-1")
    json.hcursor.get[Int]("chunkCount").toOption shouldBe Some(3)
    json.hcursor.get[String]("collection").toOption shouldBe Some("test-collection")
    json.hcursor.get[String]("updatedAt").toOption shouldBe Some("2024-01-01T00:00:00Z")
  }

  it should "return 404 for unknown documents" in {
    val response = routes.orNotFound.run(Request[IO](Method.GET, uri"/api/v1/documents/missing")).unsafeRunSync()
    response.status shouldBe Status.NotFound
  }

  "POST /api/v1/documents without filename" should "assign a default filename" in {
    val request = Request[IO](Method.POST, uri"/api/v1/documents")
      .withEntity("""{"content": "Pasted text"}""")
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()
    response.status shouldBe Status.Created
    val (_, _, metadata) = mockService.getIngestCalls.head
    metadata.get("filename").exists(_.startsWith("untitled-")) shouldBe true
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
  // DELETE /api/v1/documents - Batch delete / explicit clear all
  // ============================================================

  private def deleteDocuments(uri: Uri, body: Option[String] = None): Response[IO] = {
    val base = Request[IO](Method.DELETE, uri)
    val request = body match {
      case Some(b) => base.withEntity(b).withContentType(`Content-Type`(MediaType.application.json))
      case None => base
    }
    routes.orNotFound.run(request).unsafeRunSync()
  }

  "DELETE /api/v1/documents (no body)" should "return 400 and not clear anything" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents")

    response.status shouldBe Status.BadRequest
    response.as[String].unsafeRunSync() should include("all=true")
    mockService.documentRegistry.documentCount shouldBe 3
  }

  it should "return 400 when all=false" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents?all=false")

    response.status shouldBe Status.BadRequest
    mockService.documentRegistry.documentCount shouldBe 3
  }

  it should "return 400 when the all parameter is not a boolean" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents?all=yes")

    response.status shouldBe Status.BadRequest
    mockService.documentRegistry.documentCount shouldBe 3
  }

  "DELETE /api/v1/documents (malformed body)" should "return 400 for a misspelled field and not clear anything" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents", Some("""{"documentIDs": ["doc-1"]}"""))

    response.status shouldBe Status.BadRequest
    mockService.documentRegistry.documentCount shouldBe 3
    mockService.getDeleteCalls shouldBe empty
  }

  it should "return 400 for invalid JSON and not clear anything" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents", Some("""{not json"""))

    response.status shouldBe Status.BadRequest
    mockService.documentRegistry.documentCount shouldBe 3
  }

  "DELETE /api/v1/documents?all=true" should "clear all documents" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents?all=true")

    response.status shouldBe Status.Ok
    response.as[String].unsafeRunSync() should include("cleared")
    mockService.documentRegistry.documentCount shouldBe 0
  }

  it should "return 400 when combined with a batch body" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents?all=true", Some("""{"documentIds": ["doc-1"]}"""))

    response.status shouldBe Status.BadRequest
    mockService.documentRegistry.documentCount shouldBe 3
  }

  "DELETE /api/v1/documents (batch body)" should "delete only the listed documents" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents", Some("""{"documentIds": ["doc-1", "doc-2"]}"""))

    response.status shouldBe Status.Ok
    val body = response.as[String].unsafeRunSync()
    body should include("\"deletedCount\":2")
    mockService.documentRegistry.hasDocument("doc-1") shouldBe false
    mockService.documentRegistry.hasDocument("doc-2") shouldBe false
    mockService.documentRegistry.hasDocument("doc-3") shouldBe true
  }

  it should "not delete anything in dry run mode" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents", Some("""{"documentIds": ["doc-1"], "dryRun": true}"""))

    response.status shouldBe Status.Ok
    response.as[String].unsafeRunSync() should include("\"dryRun\":true")
    mockService.documentRegistry.documentCount shouldBe 3
  }

  it should "return 400 for an empty documentIds list" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)

    val response = deleteDocuments(uri"/api/v1/documents", Some("""{"documentIds": []}"""))

    response.status shouldBe Status.BadRequest
    mockService.documentRegistry.documentCount shouldBe 3
  }

  it should "return 400 when the batch exceeds the maximum batch size" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)
    val ids = (1 to 1001).map(i => s"doc-$i")
    val body = Map("documentIds" -> ids).asJson.noSpaces

    val response = deleteDocuments(uri"/api/v1/documents", Some(body))

    response.status shouldBe Status.BadRequest
    response.as[String].unsafeRunSync() should include("Batch size too large")
    mockService.documentRegistry.documentCount shouldBe 3
    mockService.getDeleteCalls shouldBe empty
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

  it should "mark sync complete with a whitespace-only body" in {
    val request = Request[IO](Method.POST, uri"/api/v1/sync")
      .withEntity("  \n")
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
    response.as[String].unsafeRunSync() should include("Sync completed")
  }

  it should "return 400 for a malformed body and not mark sync complete" in {
    mockService.documentRegistry.seed(TestFixtures.sampleDocuments)
    val request = Request[IO](Method.POST, uri"/api/v1/sync")
      .withEntity("""{"keepDocumentIDs": ["doc-1"]}""")
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
    mockService.documentRegistry.getSyncInfo().unsafeRunSync().lastSyncTime shouldBe None
    mockService.documentRegistry.documentCount shouldBe 3
  }

  it should "return 400 for invalid JSON" in {
    val request = Request[IO](Method.POST, uri"/api/v1/sync")
      .withEntity("""{not json""")
      .withContentType(`Content-Type`(MediaType.application.json))

    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.BadRequest
    mockService.documentRegistry.getSyncInfo().unsafeRunSync().lastSyncTime shouldBe None
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
