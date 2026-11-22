package org.llm4s.ragbox.routes

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.implicits._
import org.llm4s.ragbox.auth.Principal
import org.llm4s.ragbox.config.RuntimeConfigManager
import org.llm4s.ragbox.model.CollectionChunkingConfig
import org.llm4s.ragbox.registry.{DocumentEntry, PgCollectionConfigRegistry, PgDocumentRegistry}
import org.llm4s.ragbox.testkit.PgVectorFixture
import org.llm4s.ragbox.testkit.TestAuth._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

/**
 * The real [[CollectionConfigRoutes]] over the real registries and [[RuntimeConfigManager]], on a
 * migrated pgvector database (each test its own).
 */
class CollectionConfigRoutesSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private final case class Fixture(app: HttpApp[IO], configs: PgCollectionConfigRegistry, documents: PgDocumentRegistry)

  private def withRoutes[A](principal: Principal = Admin)(f: Fixture => IO[A]): A =
    withContainers { pg =>
      val db = freshDatabase(pg)
      (for {
        ds <- pool(db)
        runtime <- Resource.make(RuntimeConfigManager(testConfig(db), ds))(_.close())
        configs <- Resource.eval(PgCollectionConfigRegistry(ds))
        documents <- Resource.eval(PgDocumentRegistry(ds))
      } yield Fixture(CollectionConfigRoutes.routes(configs, documents, runtime).asPrincipal(principal).orNotFound, configs, documents))
        .use(f).timeout(60.seconds).unsafeRunSync()
    }

  private def json(method: Method, uri: Uri, body: String): Request[IO] =
    Request[IO](method, uri).withEntity(body).withContentType(headers.`Content-Type`(MediaType.application.json))

  private def call(app: HttpApp[IO], request: Request[IO]): IO[(Status, String)] =
    app.run(request).flatMap(r => r.as[String].map(r.status -> _))

  private def markdownConfig(collection: String): CollectionChunkingConfig =
    CollectionChunkingConfig(
      collection = collection,
      strategy = Some("markdown"),
      targetSize = Some(1000),
      maxSize = Some(1500),
      overlap = Some(100),
      fileTypeStrategies = Map.empty
    )

  "GET /api/v1/collections/{name}/config" should "return the defaults for a collection without its own config" in {
    val (status, body) = withRoutes()(f => call(f.app, Request[IO](Method.GET, uri"/api/v1/collections/test-collection/config")))
    status shouldBe Status.Ok
    body should include("\"collection\":\"test-collection\"")
    body should include("\"hasCustomConfig\":false")
    body should include("\"source\":\"default\"")
    body should include("\"documentCount\":0")
  }

  it should "return the collection's own config and document count" in {
    val (status, body) = withRoutes() { f =>
      f.configs.put(markdownConfig("custom-collection")) *>
        f.documents.put(DocumentEntry("doc-1", "h", 1, Map.empty, Some("custom-collection"), Instant.now(), Instant.now())) *>
        call(f.app, Request[IO](Method.GET, uri"/api/v1/collections/custom-collection/config"))
    }
    status shouldBe Status.Ok
    body should include("\"hasCustomConfig\":true")
    body should include("\"strategy\":\"markdown\"")
    body should include("\"documentCount\":1")
  }

  "PUT /api/v1/collections/{name}/config" should "store a custom config" in {
    val ((status, body), stored) = withRoutes() { f =>
      for {
        put <- call(f.app, json(Method.PUT, uri"/api/v1/collections/new-collection/config", """{"strategy": "sentence", "targetSize": 800}"""))
        stored <- f.configs.get("new-collection")
      } yield (put, stored)
    }
    status shouldBe Status.Ok
    body should include("Collection chunking configuration updated")
    body should include("\"strategy\":\"sentence\"")
    stored.map(c => (c.strategy, c.targetSize)) shouldBe Some((Some("sentence"), Some(800)))
  }

  it should "reject an invalid strategy" in {
    val (status, body) =
      withRoutes()(f => call(f.app, json(Method.PUT, uri"/api/v1/collections/test-collection/config", """{"strategy": "invalid_strategy"}""")))
    status shouldBe Status.BadRequest
    body should include("validation_failed")
  }

  it should "reject a targetSize below the minimum" in {
    val (status, body) =
      withRoutes()(f => call(f.app, json(Method.PUT, uri"/api/v1/collections/test-collection/config", """{"targetSize": 50}""")))
    status shouldBe Status.BadRequest
    body should include("targetSize must be at least 100")
  }

  it should "be admin-only" in {
    val (status, _) = withRoutes(user("alice"))(f =>
      call(f.app, json(Method.PUT, uri"/api/v1/collections/test-collection/config", """{"strategy": "sentence"}"""))
    )
    status shouldBe Status.Forbidden
  }

  "DELETE /api/v1/collections/{name}/config" should "remove an existing config" in {
    val ((status, body), stored) = withRoutes() { f =>
      for {
        _ <- f.configs.put(markdownConfig("to-delete"))
        deleted <- call(f.app, Request[IO](Method.DELETE, uri"/api/v1/collections/to-delete/config"))
        stored <- f.configs.get("to-delete")
      } yield (deleted, stored)
    }
    status shouldBe Status.Ok
    body should include("Custom configuration removed")
    stored shouldBe None
  }

  it should "answer 404 for a collection without its own config" in {
    val (status, body) = withRoutes()(f => call(f.app, Request[IO](Method.DELETE, uri"/api/v1/collections/nonexistent/config")))
    status shouldBe Status.NotFound
    body should include("not_found")
  }

  "GET /api/v1/collections/configs" should "list the collections with documents, their configs and the defaults" in {
    val (status, body) = withRoutes() { f =>
      f.configs.put(markdownConfig("docs")) *>
        f.documents.put(DocumentEntry("doc-1", "h", 1, Map.empty, Some("docs"), Instant.now(), Instant.now())) *>
        call(f.app, Request[IO](Method.GET, uri"/api/v1/collections/configs"))
    }
    status shouldBe Status.Ok
    body should include("\"defaultConfig\"")
    body should include("\"collection\":\"docs\"")
    body should include("\"hasCustomConfig\":true")
    body should include("\"documentCount\":1")
  }

  // ============================================================
  // Nested collection names (#54)
  // ============================================================

  "The config routes" should "accept a nested collection with raw or encoded slashes" in {
    val (raw, encoded, stored, removed) = withRoutes() { f =>
      for {
        raw <- call(f.app, json(Method.PUT, uri"/api/v1/collections/eng/backend/config", """{"strategy":"sentence"}"""))
        encoded <- call(f.app, Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/collections/eng%2Fbackend/config")))
        stored <- f.configs.get("eng/backend")
        removed <- call(f.app, Request[IO](Method.DELETE, Uri.unsafeFromString("/api/v1/collections/eng%2Fbackend/config")))
      } yield (raw, encoded, stored, removed)
    }
    raw._1 shouldBe Status.Ok
    raw._2 should include("\"collection\":\"eng/backend\"")
    stored.flatMap(_.strategy) shouldBe Some("sentence")
    encoded._1 shouldBe Status.Ok
    encoded._2 should include("\"collection\":\"eng/backend\"")
    encoded._2 should include("\"hasCustomConfig\":true")
    removed._1 shouldBe Status.Ok
  }

  "POST /api/v1/collections/{name}/config/preview" should "be open to any authenticated user (it changes nothing)" in {
    val (status, body) = withRoutes(user("alice")) { f =>
      call(f.app, json(Method.POST, uri"/api/v1/collections/eng/backend/config/preview", """{"collection":"eng/backend","filename":"a.md"}"""))
    }
    status shouldBe Status.Ok
    body should include("\"collection\":\"eng/backend\"")
    body should include("\"configResolutionPath\"")
  }

  it should "leave changing the config admin-only" in {
    val statuses = withRoutes(user("alice")) { f =>
      for {
        put <- f.app.run(json(Method.PUT, uri"/api/v1/collections/eng/backend/config", """{"strategy":"sentence"}"""))
        delete <- f.app.run(Request[IO](Method.DELETE, uri"/api/v1/collections/eng/backend/config"))
      } yield (put.status, delete.status)
    }
    statuses shouldBe (Status.Forbidden, Status.Forbidden)
  }
}
