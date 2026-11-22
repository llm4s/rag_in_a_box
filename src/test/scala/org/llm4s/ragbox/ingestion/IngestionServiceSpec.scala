package org.llm4s.ragbox.ingestion

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.implicits._
import org.llm4s.rag.loader.SourceBackedLoader
import org.llm4s.rag.loader.s3.S3DocumentSource
import org.llm4s.ragbox.model.DocumentUpsertResponse
import org.llm4s.ragbox.routes.IngestionRoutes
import org.llm4s.ragbox.testkit.MockRAGService
import org.llm4s.security.Secret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import scala.concurrent.duration._

class IngestionServiceSpec extends AnyFlatSpec with Matchers {

  /** A RAGService whose source upserts wait until `release` completes. */
  private class GatedRAGService(release: Deferred[IO, Unit]) extends MockRAGService() {
    override def upsertSourceDocument(
      sourceId: String,
      documentId: String,
      content: String,
      metadata: Map[String, String]
    ): IO[DocumentUpsertResponse] =
      release.get.as(DocumentUpsertResponse(documentId, 1, "created", "ok"))

    override def listSourceDocumentIds(sourceId: String): IO[Seq[String]] = IO.pure(Seq.empty)
  }

  private def oneFileSource(): IngestionConfig = {
    val dir = Files.createTempDirectory("ragbox-ingest")
    Files.writeString(dir.resolve("doc.txt"), "some content")
    IngestionConfig(
      enabled = true,
      sources = Seq(DirectorySourceConfig(name = "docs", path = dir.toString, patterns = Set("*.txt")))
    )
  }

  private def waitUntilRunning(service: IngestionService): IO[Unit] =
    service.getStatus.flatMap(s => if (s.running) IO.unit else IO.sleep(10.millis) *> waitUntilRunning(service))

  "IngestionService" should "refuse an overlapping run, with 409 from the routes, and clear the flag after" in {
    val test = for {
      release <- Deferred[IO, Unit]
      service = IngestionService(new GatedRAGService(release), oneFileSource())
      routes = IngestionRoutes.routes(service).orNotFound
      first <- service.runAll().start
      _ <- waitUntilRunning(service).timeout(10.seconds)

      second <- service.runAll().attempt
      single <- service.runSource("docs").attempt
      runAllResp <- routes.run(Request[IO](Method.POST, uri"/api/v1/ingest/run"))
      runOneResp <- routes.run(Request[IO](Method.POST, uri"/api/v1/ingest/run/docs"))

      _ <- release.complete(())
      results <- first.joinWithNever
      after <- service.getStatus
      third <- service.runAll()
    } yield {
      second.left.toOption.get shouldBe a[IngestionAlreadyRunning]
      single.left.toOption.get shouldBe a[IngestionAlreadyRunning]
      runAllResp.status shouldBe Status.Conflict
      runOneResp.status shouldBe Status.Conflict
      results.map(_.documentsAdded) shouldBe Seq(1)
      after.running shouldBe false
      after.lastResults shouldBe results
      third.map(_.documentsAdded) shouldBe Seq(1)
    }
    test.unsafeRunSync()
  }

  it should "clear the running flag when a run is cancelled" in {
    val test = for {
      release <- Deferred[IO, Unit] // never completed
      service = IngestionService(new GatedRAGService(release), oneFileSource())
      run <- service.runAll().start
      _ <- waitUntilRunning(service).timeout(10.seconds)
      _ <- run.cancel
      after <- service.getStatus
    } yield after.running shouldBe false
    test.unsafeRunSync()
  }

  it should "clear the running flag when a source fails" in {
    val failing = IngestionConfig(
      enabled = true,
      sources = Seq(DatabaseSourceConfig(
        name = "db", url = "jdbc:postgresql://127.0.0.1:1/none", user = "u", password = "p", query = "SELECT 1"
      ))
    )
    val service = IngestionService(new MockRAGService(), failing)
    val results = service.runAll().unsafeRunSync()
    results.map(_.error.isDefined) shouldBe Seq(true)
    service.getStatus.unsafeRunSync().running shouldBe false
  }

  "s3Loader" should "keep the source's extensions and metadata when static credentials are configured" in {
    val config = S3SourceConfig(
      name = "bucket-docs",
      bucket = "my-bucket",
      prefix = "docs/",
      region = "eu-west-1",
      accessKeyId = Some(Secret("AKIAEXAMPLE")),
      secretAccessKey = Some(Secret("secret")),
      patterns = Set("*.md"),
      metadata = Map("team" -> "search")
    )

    IngestionService.s3Loader(config) match {
      case SourceBackedLoader(source: S3DocumentSource, _, _, _) =>
        source.bucket shouldBe "my-bucket"
        source.prefix shouldBe "docs/"
        source.region shouldBe "eu-west-1"
        source.extensions shouldBe Set("md")
        source.metadata shouldBe Map("team" -> "search", "source" -> "bucket-docs")
        source.credentials shouldBe defined
      case other => fail(s"Unexpected loader: $other")
    }

    IngestionService.s3Loader(config.copy(accessKeyId = None, secretAccessKey = None)) match {
      case SourceBackedLoader(source: S3DocumentSource, _, _, _) =>
        source.extensions shouldBe Set("md")
        source.credentials shouldBe None
      case other => fail(s"Unexpected loader: $other")
    }
  }
}
