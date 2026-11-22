package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.rag.permissions.UserAuthorization
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.ingestion.{DirectorySourceConfig, IngestionConfig, IngestionResult, IngestionService}
import org.llm4s.ragbox.service.RAGService
import org.llm4s.ragbox.testkit.{FakeEmbeddings, PgVectorFixture}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/**
 * Multi-source ingestion against a real pgvector database (issue #10): each source syncs only
 * its own documents, and a restart does not re-embed unchanged documents.
 */
class SourceSyncSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private def sourceDir(files: (String, String)*): Path = {
    val dir = Files.createTempDirectory("ragbox-source")
    files.foreach { case (name, text) => Files.writeString(dir.resolve(name), text) }
    dir
  }

  private def ingestionConfig(sources: (String, Path)*): IngestionConfig =
    IngestionConfig(
      enabled = true,
      sources = sources.map { case (name, dir) =>
        DirectorySourceConfig(name = name, path = dir.toString, patterns = Set("*.txt"))
      }
    )

  private def counts(r: IngestionResult): (Int, Int, Int, Int, Option[String]) =
    (r.documentsAdded, r.documentsUpdated, r.documentsUnchanged, r.documentsDeleted, r.error)

  private def ownedBy(service: RAGService, source: String): Set[String] =
    service.listSourceDocumentIds(source).unsafeRunSync().toSet

  private val alphaText = "Alpha source document about glaciers and icebergs in polar oceans."
  private val alphaText2 = "Second alpha document about tundra and permafrost."
  private val betaText = "Beta source document about volcanoes and magma chambers."

  private def syncSourcesIndependently(db: DatabaseConfig, mkService: DatabaseConfig => cats.effect.Resource[IO, RAGService]) = {
    val alpha = sourceDir("a1.txt" -> alphaText, "a2.txt" -> alphaText2)
    val beta = sourceDir("b1.txt" -> betaText)
    val config = ingestionConfig("alpha" -> alpha, "beta" -> beta)

    mkService(db).use { service =>
      IO {
        val ingestion = IngestionService(service, config)

        ingestion.runAll().unsafeRunSync().map(counts) shouldBe Seq((2, 0, 0, 0, None), (1, 0, 0, 0, None))
        ownedBy(service, "alpha") should have size 2
        ownedBy(service, "beta") should have size 1

        // Syncing beta - which never listed alpha's files - leaves alpha's documents alone
        ingestion.runSource("beta").unsafeRunSync().map(counts) shouldBe Some((0, 0, 1, 0, None))
        ownedBy(service, "alpha") should have size 2
        service.listDocumentIds.unsafeRunSync() should have size 3

        // ...and so does a second full run: nothing is re-ingested or deleted
        ingestion.runAll().unsafeRunSync().map(counts) shouldBe Seq((0, 0, 2, 0, None), (0, 0, 1, 0, None))

        // A file removed from alpha is removed by alpha's sync only
        Files.delete(alpha.resolve("a2.txt"))
        ingestion.runSource("beta").unsafeRunSync().map(_.documentsDeleted) shouldBe Some(0)
        ownedBy(service, "alpha") should have size 2
        ingestion.runSource("alpha").unsafeRunSync().map(counts) shouldBe Some((0, 0, 1, 1, None))
        ownedBy(service, "alpha") should have size 1
        ownedBy(service, "beta") should have size 1
        service.listDocumentIds.unsafeRunSync() should have size 2
      }
    }.unsafeRunSync()
  }

  "Source sync" should "keep source A's documents when source B syncs (plain mode)" in withContainers { pg =>
    syncSourcesIndependently(freshDatabase(pg), db => plainService(db))
  }

  it should "keep source A's documents when source B syncs (permission mode)" in withContainers { pg =>
    syncSourcesIndependently(freshDatabase(pg), db => permissionService(db))
  }

  it should "remove vectors of a document deleted at its source from search results" in withContainers { pg =>
    val db = freshDatabase(pg)
    val alpha = sourceDir("a1.txt" -> alphaText)
    val beta = sourceDir("b1.txt" -> betaText)
    val config = ingestionConfig("alpha" -> alpha, "beta" -> beta)
    def search(service: RAGService, query: String) =
      service.searchWithPermissions(query, UserAuthorization.Anonymous, "*", Some(5)).unsafeRunSync().results

    permissionService(db).use { service =>
      IO {
        val ingestion = IngestionService(service, config)
        ingestion.runAll().unsafeRunSync()
        search(service, "glaciers icebergs polar") should not be empty

        Files.delete(alpha.resolve("a1.txt"))
        ingestion.runSource("alpha").unsafeRunSync().map(_.documentsDeleted) shouldBe Some(1)

        search(service, "glaciers icebergs polar").flatMap(_.documentId).filter(_.contains("a1")) shouldBe empty
        search(service, "volcanoes magma chambers").flatMap(_.documentId).exists(_.contains("b1")) shouldBe true
      }
    }.unsafeRunSync()
  }

  it should "not re-embed unchanged documents after a restart, and apply changes made while down" in withContainers { pg =>
    val db = freshDatabase(pg)
    val alpha = sourceDir("a1.txt" -> alphaText, "a2.txt" -> alphaText2)
    val beta = sourceDir("b1.txt" -> betaText)
    val config = ingestionConfig("alpha" -> alpha, "beta" -> beta)

    // First server instance
    val first = FakeEmbeddings()
    permissionService(db, first).use { service =>
      IngestionService(service, config).runAll()
    }.unsafeRunSync().map(counts) shouldBe Seq((2, 0, 0, 0, None), (1, 0, 0, 0, None))
    first.embeddedCount should be > 0

    // While the server is down: one file deleted, one changed
    Files.delete(alpha.resolve("a2.txt"))
    Files.writeString(beta.resolve("b1.txt"), betaText + " Now also about lava flows.")

    // A new instance over the same database
    val second = FakeEmbeddings()
    permissionService(db, second).use { service =>
      IO {
        val results = IngestionService(service, config).runAll().unsafeRunSync()
        results.map(counts) shouldBe Seq((0, 0, 1, 1, None), (0, 1, 0, 0, None))

        // Only the changed document was embedded again (as its chunks plus nothing else)
        val changedChunks = service.listSourceDocumentIds("beta").unsafeRunSync()
          .flatMap(id => service.getDocumentEntries(Seq(id)).unsafeRunSync()).map(_.chunkCount).sum
        second.embeddedCount shouldBe changedChunks

        // A third run on the same instance embeds nothing at all
        val before = second.embeddedCount
        IngestionService(service, config).runAll().unsafeRunSync().map(counts) shouldBe
          Seq((0, 0, 1, 0, None), (0, 0, 1, 0, None))
        second.embeddedCount shouldBe before
      }
    }.unsafeRunSync()
  }

  it should "not remove documents uploaded through the API" in withContainers { pg =>
    val db = freshDatabase(pg)
    val alpha = sourceDir("a1.txt" -> alphaText)
    plainService(db).use { service =>
      IO {
        service.upsertDocument("api-doc", betaText, Map.empty, None).unsafeRunSync()
        val ingestion = IngestionService(service, ingestionConfig("alpha" -> alpha))
        ingestion.runAll().unsafeRunSync()
        Files.delete(alpha.resolve("a1.txt"))
        ingestion.runAll().unsafeRunSync().map(_.documentsDeleted) shouldBe Seq(1)

        service.listDocumentIds.unsafeRunSync() shouldBe Seq("api-doc")
      }
    }.unsafeRunSync()
  }
}
