package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.server.websocket.WebSocketBuilder2
import org.llm4s.ragbox.RagboxApp
import org.llm4s.ragbox.config._
import org.llm4s.ragbox.ingestion.UrlSourceConfig
import org.llm4s.ragbox.testkit.{FakeLLM, PgVectorFixture}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIStringSyntax

/**
 * The follow-ups of #59 through the FULL application stack (permission mode over pgvector, the
 * API key as administrator, a configured ingestion source named `wiki`): 404s for missing
 * collections, the sync listing's `source` field and filter, API sync sources that collide with
 * ingestion sources, the similarity threshold in the query-log snapshot, and clearing fields
 * with `PATCH /experiments/{id}`.
 */
class ApiConsistency59StackSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private val ApiKey = "api-consistency-59-key-0123456789"

  /** The configured ingestion source (never run here). */
  private val WikiSource = UrlSourceConfig(name = "wiki", urls = Seq("https://wiki.example.com/"))

  private final case class Fixture(app: HttpApp[IO]) {
    def run(req: Request[IO]): IO[(Status, Json)] =
      app.run(req).flatMap(r => r.as[Json].handleError(_ => Json.Null).map(r.status -> _))
    def status(req: Request[IO]): IO[Status] = app.run(req).map(_.status)
  }

  private def withFixture[A](test: Fixture => IO[A]): A =
    withContainers { pg =>
      val db = freshDatabase(pg)
      val llm = FakeLLM()
      val base = testConfig(db, Some(llm))
      val config = base.copy(
        security = base.security.copy(apiKey = Some(ApiKey)),
        ingestion = base.ingestion.copy(sources = Seq(WikiSource))
      )
      (for {
        ds <- pool(db)
        rag <- permissionService(db, llm = Some(llm))
        app <- RagboxApp.resource(config, ds, rag)
      } yield app).use { ragbox =>
        WebSocketBuilder2[IO].flatMap(wsb => test(Fixture(ragbox.httpApp(wsb))))
      }.unsafeRunSync()
    }

  private def req(method: Method, path: String): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path)).putHeaders(Header.Raw(ci"X-API-Key", ApiKey))
  private def get(path: String): Request[IO] = req(Method.GET, path)
  private def delete(path: String): Request[IO] = req(Method.DELETE, path)
  private def post(path: String, body: Json): Request[IO] = req(Method.POST, path).withEntity(body)
  private def put(path: String, body: Json): Request[IO] = req(Method.PUT, path).withEntity(body)
  private def patch(path: String, body: Json): Request[IO] = req(Method.PATCH, path).withEntity(body)

  private def str(j: Json, field: String): Option[String] = j.hcursor.get[String](field).toOption

  private def notFoundResponse(response: (Status, Json), message: String): Unit = {
    response._1 shouldBe Status.NotFound
    str(response._2, "error") shouldBe Some("not_found")
    str(response._2, "message") shouldBe Some(message)
  }

  // ------------------------------------------------------------ missing collections

  "The per-collection endpoints" should "answer 404 with an ErrorResponse for a collection that does not exist" in withFixture { f =>
    for {
      perms <- f.run(put("/api/v1/collections/nope/permissions", Json.obj("queryableBy" -> List("user:alice").asJson)))
      nestedPerms <- f.run(put("/api/v1/collections/eng/missing/permissions", Json.obj("queryableBy" -> List.empty[String].asJson)))
      stats <- f.run(get("/api/v1/collections/nope/stats"))
      deleted <- f.run(delete("/api/v1/collections/nope"))
      got <- f.run(get("/api/v1/collections/nope"))
      // An existing one still works
      _ <- f.status(post("/api/v1/collections", Json.obj("path" -> "real".asJson))).map(_ shouldBe Status.Created)
      realPerms <- f.status(put("/api/v1/collections/real/permissions", Json.obj("queryableBy" -> List("user:alice").asJson)))
      realStats <- f.status(get("/api/v1/collections/real/stats"))
      realDelete <- f.status(delete("/api/v1/collections/real"))
      deletedAgain <- f.status(delete("/api/v1/collections/real"))
    } yield {
      notFoundResponse(perms, "Collection not found: nope")
      notFoundResponse(nestedPerms, "Collection not found: eng/missing")
      notFoundResponse(stats, "Collection not found: nope")
      notFoundResponse(deleted, "Collection not found: nope")
      notFoundResponse(got, "Collection not found: nope")
      realPerms shouldBe Status.Ok
      realStats shouldBe Status.Ok
      realDelete shouldBe Status.NoContent
      deletedAgain shouldBe Status.NotFound
    }
  }

  // ------------------------------------------------------------ sync sources

  private def upsert(id: String, source: Option[String]): Request[IO] =
    put(s"/api/v1/documents/$id", Json.obj("content" -> s"Document $id about tidal power.".asJson)
      .deepMerge(source.fold(Json.obj())(s => Json.obj("source" -> s.asJson))))

  "GET /api/v1/sync/documents" should "include each document's source and filter by ?source=" in withFixture { f =>
    def docs(j: Json): List[Json] = j.hcursor.downField("documents").values.toList.flatten
    for {
      _ <- List(upsert("a1", None), upsert("a2", None), upsert("c1", Some("confluence")))
        .traverse_(r => f.status(r).map(_ shouldBe Status.Created))
      _ <- f.status(post("/api/v1/documents", Json.obj("content" -> "An uploaded note.".asJson))).map(_ shouldBe Status.Created)
      all <- f.run(get("/api/v1/sync/documents"))
      confluence <- f.run(get("/api/v1/sync/documents?source=confluence"))
      api <- f.run(get("/api/v1/sync/documents?source=api&pageSize=1&page=2"))
      unknown <- f.run(get("/api/v1/sync/documents?source=nobody"))
      invalid <- f.run(get("/api/v1/sync/documents?source=has%20space"))
      single <- f.run(get("/api/v1/sync/documents/c1"))
      checked <- f.run(post("/api/v1/sync/check", Json.obj("documentIds" -> List("a1").asJson)))
    } yield {
      all._1 shouldBe Status.Ok
      docs(all._2).map(d => str(d, "id").get -> str(d, "source")).filterNot(_._1.length > 2).toMap shouldBe
        Map("a1" -> Some("api"), "a2" -> Some("api"), "c1" -> Some("confluence"))
      // The upload belongs to no source
      docs(all._2).filter(d => str(d, "id").exists(_.length > 2)).map(str(_, "source")) shouldBe List(None)
      all._2.hcursor.get[Int]("total") shouldBe Right(4)

      docs(confluence._2).flatMap(str(_, "id")) shouldBe List("c1")
      confluence._2.hcursor.get[Int]("total") shouldBe Right(1)
      // Paged within the source: 2 api documents, 1 per page
      api._2.hcursor.get[Int]("total") shouldBe Right(2)
      docs(api._2) should have size 1
      api._2.hcursor.get[Boolean]("hasMore") shouldBe Right(false)
      unknown._2.hcursor.get[Int]("total") shouldBe Right(0)
      invalid._1 shouldBe Status.BadRequest

      str(single._2, "source") shouldBe Some("confluence")
      checked._2.hcursor.downField("found").values.toList.flatten.flatMap(str(_, "source")) shouldBe List("api")
    }
  }

  "An API sync source" should "not share an ingestion source's name (409)" in withFixture { f =>
    def conflict(response: (Status, Json)): Unit = {
      response._1 shouldBe Status.Conflict
      str(response._2, "error") shouldBe Some("conflict")
      str(response._2, "message").get should include("ingestion source")
    }
    for {
      upsertWiki <- f.run(upsert("w1", Some("wiki")))
      upsertUrl <- f.run(upsert("w2", Some("api:url")))
      upsertDir <- f.run(upsert("w3", Some("api:directory:/data/docs")))
      syncWiki <- f.run(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson, "source" -> "wiki".asJson, "dryRun" -> true.asJson)))
      syncUrl <- f.run(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson, "source" -> "api:url".asJson)))
      // Other names, and listing an ingestion source's documents, are fine
      upsertOther <- f.status(upsert("w4", Some("wiki-api")))
      listWiki <- f.status(get("/api/v1/sync/documents?source=wiki"))
      stored <- f.run(get("/api/v1/sync/documents"))
    } yield {
      List(upsertWiki, upsertUrl, upsertDir, syncWiki, syncUrl).foreach(conflict)
      upsertOther shouldBe Status.Created
      listWiki shouldBe Status.Ok
      stored._2.hcursor.downField("documents").values.toList.flatten.flatMap(str(_, "id")) shouldBe List("w4")
    }
  }

  // ------------------------------------------------------------ query-log snapshot

  "The query-log config snapshot" should "record the similarity threshold the query applied" in withFixture { f =>
    for {
      _ <- f.status(upsert("solar", None)).map(_ shouldBe Status.Created)
      withThreshold <- f.run(post("/api/v1/query", Json.obj(
        "question" -> "tidal power?".asJson,
        "overrides" -> Json.obj("similarityThreshold" -> 0.25.asJson)
      )))
      without <- f.run(post("/api/v1/query", Json.obj("question" -> "tidal power?".asJson)))
      logged <- f.run(get(s"/api/v1/analytics/queries/${str(withThreshold._2, "queryId").get}"))
      loggedWithout <- f.run(get(s"/api/v1/analytics/queries/${str(without._2, "queryId").get}"))
    } yield {
      withThreshold._1 shouldBe Status.Ok
      logged._2.hcursor.downField("configSnapshot").get[Double]("similarityThreshold") shouldBe Right(0.25)
      loggedWithout._2.hcursor.downField("configSnapshot").get[Option[Double]]("similarityThreshold") shouldBe Right(None)
      loggedWithout._2.hcursor.downField("configSnapshot").get[Int]("topK").isRight shouldBe true
      // Permission mode searches as plain mode does: fusion, rrfK and reranking apply, and the
      // snapshot records them, with the temperature the answer was generated at
      val snapshot = loggedWithout._2.hcursor.downField("configSnapshot")
      snapshot.get[String]("searchMode") shouldBe Right("hybrid")
      snapshot.get[String]("fusionStrategy") shouldBe Right("rrf")
      snapshot.get[Int]("rrfK") shouldBe Right(60)
      snapshot.get[String]("reranking") shouldBe Right("none")
      snapshot.get[Double]("llmTemperature").isRight shouldBe true
    }
  }

  // ------------------------------------------------------------ PATCH /experiments

  "PATCH /api/v1/experiments/{id}" should "clear description and collection with null, change collection, and keep fields left out" in withFixture { f =>
    def experiment(j: Json) = j.hcursor.downField("experiment")
    for {
      created <- f.run(post("/api/v1/experiments", Json.obj(
        "name" -> "prompts".asJson,
        "description" -> "shorter answers".asJson,
        "variantConfig" -> Json.obj("topK" -> 3.asJson),
        "collection" -> "docs".asJson
      )))
      id = experiment(created._2).get[String]("id").toOption.get
      path = s"/api/v1/experiments/$id"
      untouched <- f.run(patch(path, Json.obj("trafficSplit" -> 0.3.asJson)))
      moved <- f.run(patch(path, Json.obj("collection" -> "eng/backend".asJson)))
      cleared <- f.run(patch(path, Json.obj("description" -> Json.Null, "collection" -> Json.Null)))
      reread <- f.run(get(path))
      nullName <- f.run(patch(path, Json.obj("name" -> Json.Null)))
      nullSplit <- f.status(patch(path, Json.obj("trafficSplit" -> Json.Null)))
      tooLong <- f.status(patch(path, Json.obj("collection" -> ("c" * 256).asJson)))
      _ <- f.status(req(Method.PUT, s"$path/start")).map(_ shouldBe Status.Ok)
      running <- f.status(patch(path, Json.obj("collection" -> Json.Null)))
    } yield {
      untouched._1 shouldBe Status.Ok
      experiment(untouched._2).get[String]("description") shouldBe Right("shorter answers")
      experiment(untouched._2).get[String]("collection") shouldBe Right("docs")
      experiment(untouched._2).get[Double]("trafficSplit") shouldBe Right(0.3)

      experiment(moved._2).get[String]("collection") shouldBe Right("eng/backend")
      experiment(moved._2).get[String]("description") shouldBe Right("shorter answers")

      cleared._1 shouldBe Status.Ok
      experiment(cleared._2).get[Option[String]]("description") shouldBe Right(None)
      experiment(cleared._2).get[Option[String]]("collection") shouldBe Right(None)
      experiment(cleared._2).get[Double]("trafficSplit") shouldBe Right(0.3)
      reread._2.hcursor.get[Option[String]]("description") shouldBe Right(None)
      reread._2.hcursor.get[Option[String]]("collection") shouldBe Right(None)
      reread._2.hcursor.get[String]("name") shouldBe Right("prompts")

      nullName._1 shouldBe Status.BadRequest
      str(nullName._2, "details").get should include("name cannot be cleared")
      nullSplit shouldBe Status.BadRequest
      tooLong shouldBe Status.BadRequest
      running shouldBe Status.Conflict
    }
  }
}
