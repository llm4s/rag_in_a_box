package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.llm4s.ragbox.RagboxApp
import org.llm4s.ragbox.config._
import org.llm4s.ragbox.model.{ChunkInfo, ChunkMetadataInfo, SizeBucket}
import org.llm4s.ragbox.service.RAGService
import org.llm4s.ragbox.store.PgChunkStore
import org.llm4s.ragbox.testkit.PgVectorFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The API consistency of #22 through the FULL application stack (the `HttpApp` `Main` serves,
 * built by [[RagboxApp]] over a real pgvector database): one error shape, 400 for bad bodies and
 * query parameters, no exception text in responses, paged sync listing, grouped collection
 * statistics and the chunk size histogram.
 */
class ApiConsistencyStackSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  // Open mode: every request is the administrator
  private val open = SecurityConfig(apiKey = None, auth = AuthConfig(
    mode = AuthMode.Open,
    basic = BasicAuthConfig("admin", None),
    oauth = None,
    jwtSecret = "jwt-secret-for-api-consistency-stack-spec-0123456789",
    jwtSecretExplicitlySet = true,
    jwtExpiration = 3600L,
    passwordHashIterations = 10000
  ))

  private def withApp[A](db: DatabaseConfig, permissions: Boolean = false)(test: (HttpApp[IO], RAGService) => IO[A]): A =
    (for {
      ds <- pool(db)
      rag <- if (permissions) permissionService(db) else plainService(db)
      app <- RagboxApp.resource(testConfig(db).copy(security = open), ds, rag)
    } yield (app, rag)).use { case (app, rag) =>
      WebSocketBuilder2[IO].flatMap(wsb => test(app.httpApp(wsb), rag))
    }.unsafeRunSync()

  private def get(path: String): Request[IO] = Request[IO](Method.GET, Uri.unsafeFromString(path))

  private def call(app: HttpApp[IO], req: Request[IO]): IO[(Status, Json)] =
    app.run(req).flatMap(r => r.as[Json].handleError(_ => Json.Null).map(r.status -> _))

  private def errorOf(json: Json): Option[String] = json.hcursor.get[String]("error").toOption

  private def ingest(rag: RAGService, docs: (String, String, Option[String])*): IO[Unit] =
    docs.toList.traverse_ { case (id, text, collection) =>
      rag.ingestDocument(text, id, collection.map("collection" -> _).toMap)
    }

  "Request bodies" should "that are not valid JSON, or miss a field, be a 400 ErrorResponse" in withContainers { pg =>
    withApp(freshDatabase(pg)) { (app, _) =>
      val json = `Content-Type`(MediaType.application.json)
      for {
        malformed <- call(app, Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/query")).withEntity("{not json").withContentType(json))
        missing <- call(app, Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/query")).withEntity(Json.obj()).withContentType(json))
      } yield {
        malformed._1 shouldBe Status.BadRequest
        errorOf(malformed._2) shouldBe Some("bad_request")
        missing._1 shouldBe Status.BadRequest
        errorOf(missing._2) shouldBe Some("bad_request")
        missing._2.hcursor.get[String]("details").toOption.get should include("question")
      }
    }
  }

  "Query parameters" should "that do not parse be a 400 naming them, not a 404 or 500" in withContainers { pg =>
    withApp(freshDatabase(pg)) { (app, _) =>
      for {
        documents <- call(app, get("/api/v1/documents?page=abc"))
        sync <- call(app, get("/api/v1/sync/documents?since=yesterday"))
        chunks <- call(app, get("/api/v1/visibility/chunks?pageSize=x"))
        // Out-of-range paging is clamped, not a database error
        pageZero <- call(app, get("/api/v1/analytics/queries?page=0&pageSize=-5"))
        // An id that cannot exist is a 404, not a SQL error
        badId <- call(app, get("/api/v1/analytics/queries/foo"))
      } yield {
        documents._1 shouldBe Status.BadRequest
        documents._2.hcursor.get[String]("details").toOption.get should include("page")
        sync._1 shouldBe Status.BadRequest
        sync._2.hcursor.get[String]("details").toOption.get should include("since")
        chunks._1 shouldBe Status.BadRequest
        pageZero._1 shouldBe Status.Ok
        pageZero._2.hcursor.get[Int]("page") shouldBe Right(1)
        pageZero._2.hcursor.get[Int]("pageSize") shouldBe Right(1)
        badId._1 shouldBe Status.NotFound
        errorOf(badId._2) shouldBe Some("not_found")
      }
    }
  }

  "Server errors" should "be a generic 500 ErrorResponse without the exception's text" in withContainers { pg =>
    val db = freshDatabase(pg)
    withApp(db) { (app, _) =>
      for {
        // Break the database under the running app
        _ <- connect(db).use(c => IO.blocking(c.createStatement().execute("DROP TABLE document_registry")))
        failed <- app.run(get("/api/v1/documents"))
        body <- failed.as[String]
      } yield {
        failed.status shouldBe Status.InternalServerError
        body should include("\"error\":\"internal_error\"")
        body should include("Internal server error")
        body should not include "document_registry"
        body.toLowerCase should not include "relation"
      }
    }
  }

  "GET /api/v1/sync/documents" should "page through the registry, with totals" in withContainers { pg =>
    withApp(freshDatabase(pg)) { (app, rag) =>
      for {
        _ <- ingest(rag, (1 to 5).map(i => (s"doc-$i", s"Document number $i about zebras.", None))*)
        all <- call(app, get("/api/v1/sync/documents"))
        first <- call(app, get("/api/v1/sync/documents?pageSize=2"))
        last <- call(app, get("/api/v1/sync/documents?page=3&pageSize=2"))
        beyond <- call(app, get("/api/v1/sync/documents?page=9&pageSize=2"))
      } yield {
        def ids(j: Json) = j.hcursor.downField("documents").values.toList.flatten.flatMap(_.hcursor.get[String]("id").toOption)
        // Backward compatible: without paging parameters every document of a small registry
        ids(all._2).toSet shouldBe (1 to 5).map(i => s"doc-$i").toSet
        all._2.hcursor.get[Int]("total") shouldBe Right(5)
        all._2.hcursor.get[Boolean]("hasMore") shouldBe Right(false)

        ids(first._2) should have size 2
        first._2.hcursor.get[Boolean]("hasMore") shouldBe Right(true)
        ids(last._2) should have size 1
        last._2.hcursor.get[Boolean]("hasMore") shouldBe Right(false)
        (ids(first._2) ++ ids(last._2)).distinct should have size 3
        ids(beyond._2) shouldBe empty
        beyond._2.hcursor.get[Int]("total") shouldBe Right(5)
      }
    }
  }

  "Collection statistics" should "count each collection's documents and chunks" in withContainers { pg =>
    withApp(freshDatabase(pg)) { (app, rag) =>
      for {
        _ <- ingest(rag,
          ("a1", "Alpha one.", Some("alpha")), ("a2", "Alpha two.", Some("alpha")),
          ("b1", "Beta one.", Some("beta")), ("d1", "No collection.", None)
        )
        stats <- call(app, get("/api/v1/stats"))
        detailed <- call(app, get("/api/v1/visibility/stats"))
        collections <- call(app, get("/api/v1/collections"))
      } yield {
        def byName(j: Json, field: String) =
          j.hcursor.downField("collections").values.toList.flatten.map(c =>
            c.hcursor.get[String]("name").toOption.get -> c.hcursor.get[Int](field).toOption.get
          )
        byName(stats._2, "documentCount") shouldBe Seq("default" -> 1, "alpha" -> 2, "beta" -> 1)
        // Chunk counts are now real (they were always 0)
        byName(stats._2, "chunkCount").forall(_._2 > 0) shouldBe true
        byName(detailed._2, "documentCount") shouldBe Seq("default" -> 1, "alpha" -> 2, "beta" -> 1)
        detailed._2.hcursor.get[String]("indexedSince").isRight shouldBe true
        detailed._2.hcursor.get[String]("lastIngestion").isRight shouldBe true
        collections._2.hcursor.get[List[String]]("collections") shouldBe Right(List("default", "alpha", "beta"))
      }
    }
  }

  "The chunk size distribution" should "label every bucket with its real range" in withContainers { pg =>
    val db = freshDatabase(pg)
    val sizes = Seq(50, 250, 1200, 1300, 4000)
    val chunks = sizes.zipWithIndex.map { case (n, i) =>
      ChunkInfo(s"c-$i", "doc", i, "x" * n, n, Map.empty, ChunkMetadataInfo(Seq.empty, isCodeBlock = false, None, None, None))
    }
    val distribution = pool(db).use { ds =>
      for {
        store <- PgChunkStore(ds)
        _ <- store.store("doc", chunks)
        d <- store.getChunkSizeDistribution()
      } yield d
    }.unsafeRunSync()

    distribution.min shouldBe 50
    distribution.max shouldBe 4000
    distribution.median shouldBe 1200.0
    distribution.p90 shouldBe (1300.0 + 0.6 * (4000 - 1300)) +- 0.001
    distribution.buckets shouldBe Seq(
      SizeBucket(0, 200, 1),
      SizeBucket(200, 400, 1),
      SizeBucket(1000, 1500, 2),
      SizeBucket(1500, Int.MaxValue, 1)
    )
  }

  it should "be empty without chunks" in withContainers { pg =>
    val db = freshDatabase(pg)
    val distribution = pool(db).use(ds => PgChunkStore(ds).flatMap(_.getChunkSizeDistribution())).unsafeRunSync()
    distribution.buckets shouldBe empty
    distribution.max shouldBe 0
  }

  "Permission mode" should "list collections with their permissions at /collections/accessible" in withContainers { pg =>
    withApp(freshDatabase(pg), permissions = true) { (app, _) =>
      val create = Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/collections"))
        .withEntity(Json.obj("path" -> "docs".asJson))
      for {
        created <- call(app, create)
        duplicate <- call(app, create)
        names <- call(app, get("/api/v1/collections"))
        accessible <- call(app, get("/api/v1/collections/accessible"))
        badPattern <- call(app, get("/api/v1/collections/accessible?pattern=a**b"))
      } yield {
        created._1 shouldBe Status.Created
        // A domain conflict, not a database error
        duplicate._1 shouldBe Status.Conflict
        errorOf(duplicate._2) shouldBe Some("conflict")
        names._2.hcursor.get[List[String]]("collections").isRight shouldBe true
        accessible._2.hcursor.downField("collections").values.toList.flatten
          .flatMap(_.hcursor.get[String]("path").toOption) should contain("docs")
        badPattern._1 shouldBe Status.BadRequest
      }
    }
  }
}
