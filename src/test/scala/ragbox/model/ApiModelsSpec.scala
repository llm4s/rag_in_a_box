package ragbox.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.circe.syntax._
import io.circe.parser._
import ragbox.model.Codecs._
import java.time.Instant

class ApiModelsSpec extends AnyFlatSpec with Matchers {

  // ============================================================
  // Document Upload Request/Response
  // ============================================================

  "DocumentUploadRequest" should "serialize to JSON correctly" in {
    val request = DocumentUploadRequest(
      content = "Hello world",
      filename = "test.txt",
      metadata = Some(Map("author" -> "test")),
      collection = Some("docs")
    )

    val json = request.asJson
    json.hcursor.get[String]("content").toOption `shouldBe` Some("Hello world")
    json.hcursor.get[String]("filename").toOption `shouldBe` Some("test.txt")
    json.hcursor.downField("metadata").get[String]("author").toOption `shouldBe` Some("test")
    json.hcursor.get[String]("collection").toOption `shouldBe` Some("docs")
  }

  it should "deserialize from JSON correctly" in {
    val json = """{"content": "test content", "filename": "doc.md"}"""
    val result = decode[DocumentUploadRequest](json)

    result.isRight `shouldBe` true
    result.toOption.get.content `shouldBe` "test content"
    result.toOption.get.filename `shouldBe` "doc.md"
    result.toOption.get.metadata `shouldBe` None
    result.toOption.get.collection `shouldBe` None
  }

  it should "require content field" in {
    val json = """{"filename": "doc.md"}"""
    val result = decode[DocumentUploadRequest](json)
    result.isLeft `shouldBe` true
  }

  "DocumentUploadResponse" should "serialize correctly" in {
    val response = DocumentUploadResponse(
      documentId = "doc-123",
      chunks = 5,
      message = "Success"
    )

    val json = response.asJson
    json.hcursor.get[String]("documentId").toOption `shouldBe` Some("doc-123")
    json.hcursor.get[Int]("chunks").toOption `shouldBe` Some(5)
    json.hcursor.get[String]("message").toOption `shouldBe` Some("Success")
  }

  // ============================================================
  // Document Upsert Response
  // ============================================================

  "DocumentUpsertResponse" should "serialize with action field" in {
    val response = DocumentUpsertResponse(
      documentId = "doc-456",
      chunks = 3,
      action = "created",
      message = "Document created"
    )

    val json = response.asJson
    json.hcursor.get[String]("action").toOption `shouldBe` Some("created")
  }

  // ============================================================
  // Query Request/Response
  // ============================================================

  "QueryRequest" should "deserialize with defaults" in {
    val json = """{"question": "What is RAG?"}"""
    val result = decode[QueryRequest](json)

    result.isRight `shouldBe` true
    val req = result.toOption.get
    req.question `shouldBe` "What is RAG?"
    req.topK `shouldBe` None
    req.collection `shouldBe` None
  }

  it should "deserialize with all fields" in {
    val json = """{"question": "What is RAG?", "topK": 10, "collection": "faq"}"""
    val result = decode[QueryRequest](json)

    result.isRight `shouldBe` true
    val req = result.toOption.get
    req.topK `shouldBe` Some(10)
    req.collection `shouldBe` Some("faq")
  }

  "QueryResponse" should "serialize with contexts" in {
    val response = QueryResponse(
      answer = "RAG is Retrieval Augmented Generation",
      contexts = Seq(
        ContextItem(
          content = "RAG combines retrieval with generation",
          score = 0.95,
          metadata = Map("source" -> "wiki"),
          documentId = Some("doc-1")
        )
      )
    )

    val json = response.asJson
    json.hcursor.get[String]("answer").toOption `shouldBe` Some("RAG is Retrieval Augmented Generation")
    json.hcursor.downField("contexts").downArray.get[Double]("score").toOption `shouldBe` Some(0.95)
  }

  // ============================================================
  // Search Request/Response
  // ============================================================

  "SearchRequest" should "deserialize correctly" in {
    val json = """{"query": "search query", "topK": 5}"""
    val result = decode[SearchRequest](json)

    result.isRight `shouldBe` true
    result.toOption.get.query `shouldBe` "search query"
    result.toOption.get.topK `shouldBe` Some(5)
  }

  "SearchResponse" should "serialize results array" in {
    val response = SearchResponse(
      results = Seq(
        ContextItem("content 1", 0.9, Map.empty, Some("doc-1")),
        ContextItem("content 2", 0.8, Map.empty, Some("doc-2"))
      ),
      count = 2
    )

    val json = response.asJson
    val results = json.hcursor.downField("results").as[Seq[ContextItem]]
    results.isRight `shouldBe` true
    results.toOption.get.size `shouldBe` 2
  }

  // ============================================================
  // Stats Response
  // ============================================================

  "StatsResponse" should "serialize with collections" in {
    val response = StatsResponse(
      documentCount = 100,
      chunkCount = 500,
      vectorCount = 500L,
      collections = Seq(
        CollectionStats("docs", 50, 250),
        CollectionStats("faq", 50, 250)
      )
    )

    val json = response.asJson
    json.hcursor.get[Int]("documentCount").toOption `shouldBe` Some(100)
    json.hcursor.downField("collections").downArray.get[String]("name").toOption `shouldBe` Some("docs")
  }

  // ============================================================
  // Error Response
  // ============================================================

  "ErrorResponse" should "create bad request error" in {
    val error = ErrorResponse.badRequest("Invalid input")

    error.error `shouldBe` "bad_request"
    error.message `shouldBe` "Invalid input"
  }

  it should "create not found error" in {
    val error = ErrorResponse.notFound("Document not found")

    error.error `shouldBe` "not_found"
  }

  it should "create internal error" in {
    val error = ErrorResponse.internalError("Server error", Some("database connection failed"))

    error.error `shouldBe` "internal_error"
    error.details `shouldBe` Some("database connection failed")
  }

  it should "serialize to JSON" in {
    val error = ErrorResponse("test_error", "Test message", Some("details"))
    val json = error.asJson

    json.hcursor.get[String]("error").toOption `shouldBe` Some("test_error")
    json.hcursor.get[String]("message").toOption `shouldBe` Some("Test message")
    json.hcursor.get[String]("details").toOption `shouldBe` Some("details")
  }

  // ============================================================
  // Sync Models
  // ============================================================

  "SyncStatusResponse" should "serialize correctly" in {
    val now = Instant.now()
    val response = SyncStatusResponse(
      lastSyncTime = Some(now),
      documentCount = 10,
      chunkCount = 50,
      pendingDeletes = 0
    )

    val json = response.asJson
    json.hcursor.get[Int]("documentCount").toOption `shouldBe` Some(10)
  }

  "SyncPruneRequest" should "deserialize keep list" in {
    val json = """{"keepDocumentIds": ["doc-1", "doc-2"]}"""
    val result = decode[SyncPruneRequest](json)

    result.isRight `shouldBe` true
    result.toOption.get.keepDocumentIds `shouldBe` Seq("doc-1", "doc-2")
  }
}
