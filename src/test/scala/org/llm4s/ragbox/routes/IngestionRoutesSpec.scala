package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.ingestion._
import java.time.Instant

class IngestionRoutesSpec extends AnyFlatSpec with Matchers {

  // Mock IngestionService for testing
  private class MockIngestionService extends IngestionService(
    IO.raiseError(new RuntimeException("Not initialized")),
    IngestionConfig(enabled = false, runOnStartup = false, schedule = None, sources = Seq.empty)
  ) {
    private val mockResult = IngestionResult(
      sourceName = "test-source",
      sourceType = "directory",
      documentsAdded = 5,
      documentsUpdated = 2,
      documentsDeleted = 1,
      documentsUnchanged = 10,
      documentsFailed = 0,
      startTime = Instant.now(),
      endTime = Instant.now()
    )

    private val mockSources = Seq(
      DirectorySourceConfig(
        name = "local-docs",
        path = "/data/docs",
        patterns = Set("*.md", "*.txt"),
        recursive = true,
        enabled = true
      ),
      UrlSourceConfig(
        name = "external-docs",
        urls = Seq("https://example.com/doc1.html"),
        enabled = true
      )
    )

    override def ingestDirectory(
      path: String,
      patterns: Set[String],
      recursive: Boolean,
      metadata: Map[String, String]
    ): IO[IngestionResult] = IO.pure(mockResult.copy(sourceName = path, sourceType = "directory"))

    override def ingestUrls(
      urls: Seq[String],
      metadata: Map[String, String]
    ): IO[IngestionResult] = IO.pure(mockResult.copy(sourceName = "url-ingest", sourceType = "url"))

    override def runAll(): IO[Seq[IngestionResult]] = IO.pure(Seq(mockResult))

    override def runSource(sourceName: String): IO[Option[IngestionResult]] =
      if (sourceName == "local-docs") IO.pure(Some(mockResult.copy(sourceName = sourceName)))
      else IO.pure(None)

    override def getStatus: IO[IngestionStatus] = IO.pure(IngestionStatus(
      running = false,
      lastRun = Some(Instant.now()),
      lastResults = Seq(mockResult),
      nextScheduledRun = None
    ))

    override def listSources: IO[Seq[SourceConfig]] = IO.pure(mockSources)
  }

  private val ingestionService = new MockIngestionService()
  private val routes = IngestionRoutes.routes(ingestionService)

  private def runRequest(request: Request[IO]): Response[IO] =
    routes.orNotFound.run(request).unsafeRunSync()

  // POST /api/v1/ingest/directory tests
  "POST /api/v1/ingest/directory" should "ingest a directory" in {
    val body = """{"path": "/data/test"}"""
    val request = Request[IO](Method.POST, uri"/api/v1/ingest/directory")
      .withEntity(parse(body).getOrElse(Json.Null))

    val response = runRequest(request)
    response.status shouldBe Status.Ok

    val json = response.as[Json].unsafeRunSync()
    (json \\ "sourceName").head.asString shouldBe Some("/data/test")
    (json \\ "sourceType").head.asString shouldBe Some("directory")
  }

  it should "accept custom patterns and recursive options" in {
    val body = """{"path": "/data/test", "patterns": ["*.md"], "recursive": false}"""
    val request = Request[IO](Method.POST, uri"/api/v1/ingest/directory")
      .withEntity(parse(body).getOrElse(Json.Null))

    val response = runRequest(request)
    response.status shouldBe Status.Ok
  }

  // POST /api/v1/ingest/url tests
  "POST /api/v1/ingest/url" should "ingest URLs" in {
    val body = """{"urls": ["https://example.com/doc1.html", "https://example.com/doc2.html"]}"""
    val request = Request[IO](Method.POST, uri"/api/v1/ingest/url")
      .withEntity(parse(body).getOrElse(Json.Null))

    val response = runRequest(request)
    response.status shouldBe Status.Ok

    val json = response.as[Json].unsafeRunSync()
    (json \\ "sourceType").head.asString shouldBe Some("url")
  }

  // POST /api/v1/ingest/run tests
  "POST /api/v1/ingest/run" should "run all configured sources" in {
    val request = Request[IO](Method.POST, uri"/api/v1/ingest/run")

    val response = runRequest(request)
    response.status shouldBe Status.Ok

    val json = response.as[Json].unsafeRunSync()
    json.isArray shouldBe true
  }

  // POST /api/v1/ingest/run/:source tests
  "POST /api/v1/ingest/run/:source" should "run a specific source" in {
    val request = Request[IO](Method.POST, uri"/api/v1/ingest/run/local-docs")

    val response = runRequest(request)
    response.status shouldBe Status.Ok

    val json = response.as[Json].unsafeRunSync()
    (json \\ "sourceName").head.asString shouldBe Some("local-docs")
  }

  it should "return 404 for unknown source" in {
    val request = Request[IO](Method.POST, uri"/api/v1/ingest/run/unknown-source")

    val response = runRequest(request)
    response.status shouldBe Status.NotFound
  }

  // GET /api/v1/ingest/status tests
  "GET /api/v1/ingest/status" should "return ingestion status" in {
    val request = Request[IO](Method.GET, uri"/api/v1/ingest/status")

    val response = runRequest(request)
    response.status shouldBe Status.Ok

    val json = response.as[Json].unsafeRunSync()
    (json \\ "running").head.asBoolean shouldBe Some(false)
    (json \\ "lastRun").headOption.isDefined shouldBe true
  }

  // GET /api/v1/ingest/sources tests
  "GET /api/v1/ingest/sources" should "list configured sources" in {
    val request = Request[IO](Method.GET, uri"/api/v1/ingest/sources")

    val response = runRequest(request)
    response.status shouldBe Status.Ok

    val json = response.as[Json].unsafeRunSync()
    json.isArray shouldBe true
    json.asArray.get.size shouldBe 2
  }

  it should "include source details" in {
    val request = Request[IO](Method.GET, uri"/api/v1/ingest/sources")

    val response = runRequest(request)
    val json = response.as[Json].unsafeRunSync()
    val sources = json.asArray.get

    val dirSource = sources.find(s => (s \\ "name").head.asString.contains("local-docs")).get
    (dirSource \\ "sourceType").head.asString shouldBe Some("directory")
    (dirSource \\ "enabled").head.asBoolean shouldBe Some(true)
  }
}
