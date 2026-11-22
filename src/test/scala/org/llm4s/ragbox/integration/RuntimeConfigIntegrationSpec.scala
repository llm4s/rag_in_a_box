package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.llm4s.rag.permissions.{CollectionConfig, CollectionPath}
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.routes.CollectionConfigRoutes
import org.llm4s.ragbox.service.RAGService
import org.llm4s.ragbox.testkit.{FakeLLM, PgVectorFixture}
import org.llm4s.ragbox.testkit.TestAuth.asAdmin
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.util.Using

/**
 * Runtime settings and per-collection chunking take effect without a restart, and runtime
 * settings survive one (issue #19). Against a real pgvector database, with the fake embedder and LLM.
 */
class RuntimeConfigIntegrationSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private def run[A](io: IO[A]): A = io.timeout(120.seconds).unsafeRunSync()

  /** A document long enough to make several chunks at the test chunk size (500). */
  private val longDocument: String =
    (1 to 40).map(i => s"Sentence number $i describes subject $i with a little detail.").mkString(" ")

  private def ingestAnimals(service: RAGService): IO[Unit] =
    Seq("zebra", "penguin", "lion", "otter", "eagle", "shark").foldLeft(IO.unit) { (acc, animal) =>
      acc *> service.ingestDocument(s"The $animal is an animal that lives in the wild.", animal, Map.empty).void
    }

  private def systemPromptOfLastAnswer(llm: FakeLLM): String =
    llm.requests.last._1.messages.head.content

  "A runtime topK change" should "apply to the next query, without a restart" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    val (before, after, searched) = run(liveService(db, permissions = false, llm = Some(llm)).use { live =>
      for {
        _ <- ingestAnimals(live.service)
        before <- live.service.queryWithAnswer("animal wild", topK = None)
        _ <- live.runtime.update(RuntimeConfigUpdateRequest(topK = Some(2)))
        after <- live.service.queryWithAnswer("animal wild", topK = None)
        searched <- live.service.search("animal wild", topK = None)
      } yield (before, after, searched)
    })
    before.contexts should have size 5 // the configuration file's topK
    after.contexts should have size 2
    searched.results should have size 2
  }

  it should "still yield to a topK passed with the call" in withContainers { pg =>
    val db = freshDatabase(pg)
    val results = run(liveService(db, permissions = false).use { live =>
      ingestAnimals(live.service) *>
        live.runtime.update(RuntimeConfigUpdateRequest(topK = Some(2))) *>
        live.service.search("animal wild", topK = Some(4))
    })
    results.results should have size 4
  }

  "A runtime system prompt change" should "be the system prompt of the next /query answer" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    val (first, second) = run(liveService(db, permissions = false, llm = Some(llm)).use { live =>
      for {
        _ <- ingestAnimals(live.service)
        _ <- live.service.queryWithAnswer("zebra", topK = Some(1))
        first = systemPromptOfLastAnswer(llm)
        _ <- live.runtime.update(RuntimeConfigUpdateRequest(systemPrompt = Some("Answer like a pirate.")))
        _ <- live.service.queryWithAnswer("zebra", topK = Some(1))
      } yield (first, systemPromptOfLastAnswer(llm))
    })
    first shouldBe "You are a helpful assistant."
    second shouldBe "Answer like a pirate."
  }

  it should "apply in permission mode too" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    val prompt = run(liveService(db, permissions = true, llm = Some(llm)).use { live =>
      for {
        _ <- live.service.ingestWithPermissions(RAGService.DefaultCollection, "zebra", "Zebras have stripes.")
        _ <- live.runtime.update(RuntimeConfigUpdateRequest(systemPrompt = Some("Be brief.")))
        _ <- live.service.queryWithPermissionsAndAnswer(
          "zebras", org.llm4s.rag.permissions.UserAuthorization.Anonymous, "*", topK = Some(1)
        )
      } yield systemPromptOfLastAnswer(llm)
    })
    prompt shouldBe "Be brief."
  }

  "A runtime temperature and system prompt change" should "apply to the next chat answer" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    run(liveService(db, permissions = false, llm = Some(llm)).use { live =>
      for {
        _ <- ingestAnimals(live.service)
        _ <- live.runtime.update(RuntimeConfigUpdateRequest(llmTemperature = Some(0.2), systemPrompt = Some("Chat prompt.")))
        _ <- live.service.chatAnswer("zebra?", Seq.empty, permissions = None, topK = Some(1))
      } yield ()
    })
    val (conversation, options) = llm.requests.last
    options.temperature shouldBe 0.2
    conversation.messages.head.content shouldBe "Chat prompt."
  }

  "Runtime settings" should "survive a restart, and follow the configuration file again once set back to it" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(liveService(db, permissions = false).use { live =>
      live.runtime.update(RuntimeConfigUpdateRequest(topK = Some(3), systemPrompt = Some("Persisted."), chunkSize = Some(800)))
    })

    val (restored, lastModified, searched) = run(liveService(db, permissions = false).use { live =>
      for {
        current <- live.runtime.get
        _ <- ingestAnimals(live.service)
        searched <- live.service.search("animal wild", topK = None)
      } yield (current.config, current.lastModified, searched)
    })
    (restored.topK, restored.systemPrompt, restored.chunkSize) shouldBe (3, "Persisted.", 800)
    lastModified shouldBe defined
    searched.results should have size 3

    // Back to the configuration file's value: no stored row, so a file change would apply again
    run(liveService(db, permissions = false).use(_.runtime.update(RuntimeConfigUpdateRequest(topK = Some(5)))))
    val storedFields = run(connect(db).use { conn =>
      IO.blocking(Using.resource(conn.createStatement()) { stmt =>
        val rs = stmt.executeQuery("SELECT field FROM runtime_config ORDER BY field")
        Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
      })
    })
    storedFields shouldBe List("chunkSize", "systemPrompt")
  }

  it should "ignore a stored value that is no longer valid" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(liveService(db, permissions = false).use(_.runtime.update(RuntimeConfigUpdateRequest(rrfK = Some(10)))))
    run(connect(db).use { conn =>
      IO.blocking(Using.resource(conn.createStatement())(_.execute("UPDATE runtime_config SET value = 'lots' WHERE field = 'rrfK'")))
    })
    val restored = run(liveService(db, permissions = false).use(_.runtime.get))
    restored.config.rrfK shouldBe 60
  }

  "An update response" should "say where a setting does not apply" in withContainers { pg =>
    val db = freshDatabase(pg)
    val (plain, permission) = run(
      liveService(db, permissions = false).use(_.runtime.update(RuntimeConfigUpdateRequest(llmTemperature = Some(0.1), rrfK = Some(10))))
        .flatMap(p => liveService(freshDatabase(pg), permissions = true).use(_.runtime.update(RuntimeConfigUpdateRequest(rrfK = Some(10)))).map(p -> _))
    )
    val temperature = plain.changes.find(_.field == "llmTemperature").get
    temperature.effectiveImmediately shouldBe true
    temperature.note.get should include("llm4s/llm4s#2715")
    plain.changes.find(_.field == "rrfK").get.effectiveImmediately shouldBe true
    val rrf = permission.changes.find(_.field == "rrfK").get
    rrf.effectiveImmediately shouldBe false
    rrf.note.get should include("llm4s/llm4s#2716")
  }

  // ============================================================
  // Per-collection chunking
  // ============================================================

  private def chunkCount(service: RAGService, documentId: String): IO[Int] =
    service.getDocumentChunks(documentId).map(_.map(_.chunkCount).getOrElse(0))

  "A collection's chunking config" should "change the chunks of documents ingested into it" in withContainers { pg =>
    val db = freshDatabase(pg)
    val (inSmall, inPlain) = run(liveService(db, permissions = false).use { live =>
      for {
        _ <- live.collectionConfigs.put(CollectionChunkingConfig("small", targetSize = Some(150), maxSize = Some(200), overlap = Some(0)))
        _ <- live.service.ingestDocument(longDocument, "in-small", Map("collection" -> "small"))
        _ <- live.service.ingestDocument(longDocument, "in-plain", Map("collection" -> "plain"))
        small <- chunkCount(live.service, "in-small")
        plain <- chunkCount(live.service, "in-plain")
      } yield (small, plain)
    })
    inPlain should be > 1
    inSmall should be > inPlain * 2
  }

  it should "apply in permission mode, and through the runtime chunk size elsewhere" in withContainers { pg =>
    val db = freshDatabase(pg)
    val (inSmall, inDefault, afterRuntimeChange) = run(liveService(db, permissions = true).use { live =>
      for {
        _ <- live.collectionConfigs.put(CollectionChunkingConfig("small", targetSize = Some(150), maxSize = Some(200), overlap = Some(0)))
        _ <- IO.blocking(live.service.collections.get.create(CollectionConfig(CollectionPath.unsafe("small"))))
        _ <- live.service.ingestWithPermissions("small", "in-small", longDocument)
        _ <- live.service.ingestWithPermissions(RAGService.DefaultCollection, "in-default", longDocument)
        small <- chunkCount(live.service, "in-small")
        default <- chunkCount(live.service, "in-default")
        _ <- live.runtime.update(RuntimeConfigUpdateRequest(chunkSize = Some(1500)))
        _ <- live.service.ingestWithPermissions(RAGService.DefaultCollection, "after", longDocument)
        after <- chunkCount(live.service, "after")
      } yield (small, default, after)
    })
    inSmall should be > inDefault * 2
    afterRuntimeChange should be < inDefault
  }

  it should "match file-type overrides case-insensitively, saved through the API as '.MD'" in withContainers { pg =>
    val db = freshDatabase(pg)
    val markdown = "# Zebras\n\nZebras have stripes.\n\n## Habitat\n\nZebras live in Africa.\n"
    val (status, stored, effective, headings) = run(liveService(db, permissions = false).use { live =>
      val routes = CollectionConfigRoutes.routes(live.collectionConfigs, live.service.getDocumentRegistry, live.runtime).asAdmin.orNotFound
      val body = CollectionConfigUpdateRequest(strategy = Some("simple"), fileTypeStrategies = Some(Map(".MD" -> "markdown")))
      for {
        response <- routes.run(Request[IO](Method.PUT, uri"/api/v1/collections/docs/config").withEntity(body.asJson))
        stored <- live.collectionConfigs.get("docs")
        effective <- live.service.effectiveChunking(Some("docs"), Some("Guide.MD"))
        _ <- live.service.ingestDocument(markdown, "guide", Map("collection" -> "docs", "filename" -> "Guide.MD"))
        chunks <- live.service.getDocumentChunks("guide")
      } yield (response.status, stored, effective, chunks.toSeq.flatMap(_.chunks).flatMap(_.chunkMetadata.headings))
    })
    status shouldBe Status.Ok
    stored.map(_.fileTypeStrategies) shouldBe Some(Map(".md" -> "markdown"))
    (effective.strategy, effective.source) shouldBe ("markdown", "file-type")
    headings should contain("Zebras")
  }

  "Saving a collection config" should "validate it against the effective values" in withContainers { pg =>
    val db = freshDatabase(pg)
    val responses = run(liveService(db, permissions = false).use { live =>
      val routes = CollectionConfigRoutes.routes(live.collectionConfigs, live.service.getDocumentRegistry, live.runtime).asAdmin.orNotFound
      def put(body: CollectionConfigUpdateRequest): IO[(Status, String)] =
        routes.run(Request[IO](Method.PUT, uri"/api/v1/collections/c/config").withEntity(body.asJson))
          .flatMap(r => r.as[Json].map(j => r.status -> j.hcursor.get[String]("details").getOrElse("")))
      for {
        // maxSize below the runtime chunk size (500)
        maxBelowTarget <- put(CollectionConfigUpdateRequest(maxSize = Some(300)))
        // overlap not below a target size set to less than the runtime overlap... and the default 1000 the old check assumed
        overlapTooBig <- put(CollectionConfigUpdateRequest(overlap = Some(600)))
        conflicting <- put(CollectionConfigUpdateRequest(fileTypeStrategies = Some(Map(".md" -> "markdown", ".MD" -> "simple"))))
        fine <- put(CollectionConfigUpdateRequest(targetSize = Some(200), maxSize = Some(300), overlap = Some(20)))
      } yield (maxBelowTarget, overlapTooBig, conflicting, fine)
    })
    val (maxBelowTarget, overlapTooBig, conflicting, fine) = responses
    maxBelowTarget._1 shouldBe Status.BadRequest
    maxBelowTarget._2 should include("maxSize (300) must be at least targetSize (500)")
    overlapTooBig._1 shouldBe Status.BadRequest
    overlapTooBig._2 should include("overlap (600) must be less than targetSize (500)")
    conflicting._1 shouldBe Status.BadRequest
    fine._1 shouldBe Status.Ok
  }
}
