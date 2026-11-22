package org.llm4s.ragbox.routes

import org.llm4s.ragbox.testkit.TestAuth.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.llm4s.ragbox.ingestion._
import org.llm4s.ragbox.model.DocumentUpsertResponse
import org.llm4s.ragbox.testkit.MockRAGService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** The ingestion routes with a real [[IngestionService]], checking the ingestion policy. */
class IngestionPolicyRoutesSpec extends AnyFlatSpec with Matchers {

  /** Records upserted content; never touches a database. */
  private class RecordingRAGService extends MockRAGService() {
    @volatile var upserted: Vector[String] = Vector.empty

    override def upsertSourceDocument(
      sourceId: String,
      documentId: String,
      content: String,
      metadata: Map[String, String]
    ): IO[DocumentUpsertResponse] =
      IO { upserted = upserted :+ content }.as(DocumentUpsertResponse(documentId, 1, "created", "ok"))

    override def listSourceDocumentIds(sourceId: String): IO[Seq[String]] = IO.pure(Seq.empty)
  }

  private def setup(allowedRoots: Option[Seq[String]] = None): (Path, Path, RecordingRAGService, HttpRoutes[IO]) = {
    val base = Files.createTempDirectory("ragbox-ingest-policy")
    val root = Files.createDirectories(base.resolve("root"))
    Files.writeString(root.resolve("doc.md"), "allowed content")
    val outside = Files.createDirectories(base.resolve("outside"))
    Files.writeString(outside.resolve("app.conf"), "db.password = SUPERSECRET")
    Files.createSymbolicLink(root.resolve("escape"), outside)
    val rag = new RecordingRAGService
    val config = IngestionConfig(
      policy = IngestionPolicyConfig(allowedRoots = Some(allowedRoots.getOrElse(Seq(root.toString))))
    )
    (root, outside, rag, IngestionRoutes.routes(IngestionService(rag, config)).asAdmin)
  }

  private def post(routes: HttpRoutes[IO], path: String, body: Json): (Status, Json) = {
    val uri = Uri.unsafeFromString(path)
    val response = routes.orNotFound.run(Request[IO](Method.POST, uri).withEntity(body)).unsafeRunSync()
    (response.status, response.as[Json].unsafeRunSync())
  }

  private def json(s: String): Json = parse(s).toOption.get

  private def directory(path: String, patterns: String = """["*.md", "*.conf"]"""): Json =
    json(s"""{"path": ${Json.fromString(path).noSpaces}, "patterns": $patterns}""")

  private def assertRejected(status: Status, body: Json, messagePart: String): Unit = {
    status shouldBe Status.BadRequest
    body.hcursor.get[String]("error") shouldBe Right("bad_request")
    body.hcursor.get[String]("message").toOption.get should include(messagePart)
    body.noSpaces should not include "SUPERSECRET"
  }

  "POST /api/v1/ingest/directory" should "ingest a directory under an allowed root" in {
    val (root, _, rag, routes) = setup()
    val (status, _) = post(routes, "/api/v1/ingest/directory", directory(root.toString))
    status shouldBe Status.Ok
    rag.upserted shouldBe Vector("allowed content") // not the file behind the escaping symlink
  }

  it should "return 400 for a directory outside the allowed roots" in {
    val (_, outside, rag, routes) = setup()
    val (status, body) = post(routes, "/api/v1/ingest/directory", directory(outside.toString))
    assertRejected(status, body, "outside the allowed ingestion roots")
    rag.upserted shouldBe empty
  }

  it should "return 400 for a '..' escape" in {
    val (root, _, rag, routes) = setup()
    val (status, body) = post(routes, "/api/v1/ingest/directory", directory(s"$root/../outside"))
    assertRejected(status, body, "'..'")
    rag.upserted shouldBe empty
  }

  it should "return 400 for a symlink pointing outside the root" in {
    val (root, _, rag, routes) = setup()
    val (status, body) = post(routes, "/api/v1/ingest/directory", directory(root.resolve("escape").toString))
    assertRejected(status, body, "outside the allowed ingestion roots")
    rag.upserted shouldBe empty
  }

  it should "return 400 for patterns with path separators" in {
    val (root, _, _, routes) = setup()
    val (status, body) = post(routes, "/api/v1/ingest/directory", directory(root.toString, """["../*.conf"]"""))
    assertRejected(status, body, "Invalid file pattern")
  }

  it should "return 400 when no roots are allowed" in {
    val (root, _, _, routes) = setup(allowedRoots = Some(Seq.empty))
    val (status, body) = post(routes, "/api/v1/ingest/directory", directory(root.toString))
    assertRejected(status, body, "Directory ingestion is disabled")
  }

  "POST /api/v1/ingest/url" should "return 400 for internal and metadata addresses" in {
    val (_, _, rag, routes) = setup()
    Seq(
      "http://169.254.169.254/latest/meta-data/",
      "http://2130706433/",
      "http://0x7f.1/",
      "http://[::ffff:127.0.0.1]/",
      "http://localhost:8080/admin",
      "http://10.0.0.1/",
      "file:///etc/passwd"
    ).foreach { url =>
      withClue(url) {
        val (status, body) = post(routes, "/api/v1/ingest/url", json(s"""{"urls": ["https://93.184.215.14/", "$url"]}"""))
        assertRejected(status, body, "refused")
      }
    }
    rag.upserted shouldBe empty
  }

  it should "return 400 for an empty URL list" in {
    val (_, _, _, routes) = setup()
    val (status, body) = post(routes, "/api/v1/ingest/url", json("""{"urls": []}"""))
    assertRejected(status, body, "At least one URL")
  }

  "A configured directory source" should "fail when its path is outside the allowed roots" in {
    val base = Files.createTempDirectory("ragbox-ingest-policy-src")
    val allowed = Files.createDirectories(base.resolve("allowed"))
    val other = Files.createDirectories(base.resolve("other"))
    Files.writeString(other.resolve("x.md"), "x")
    val rag = new RecordingRAGService
    val config = IngestionConfig(
      enabled = true,
      sources = Seq(DirectorySourceConfig(name = "other", path = other.toString, patterns = Set("*.md"))),
      policy = IngestionPolicyConfig(allowedRoots = Some(Seq(allowed.toString)))
    )
    val results = IngestionService(rag, config).runAll().unsafeRunSync()
    results.map(_.error.exists(_.contains("outside the allowed ingestion roots"))) shouldBe Seq(true)
    rag.upserted shouldBe empty
  }

  it should "be allowed by default, as its own root" in {
    val dir = Files.createTempDirectory("ragbox-ingest-policy-default")
    Files.writeString(dir.resolve("x.md"), "x")
    val rag = new RecordingRAGService
    val config = IngestionConfig(
      enabled = true,
      sources = Seq(DirectorySourceConfig(name = "docs", path = dir.toString, patterns = Set("*.md")))
    )
    IngestionService(rag, config).runAll().unsafeRunSync().map(_.documentsAdded) shouldBe Seq(1)
  }
}
