package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.Authorization
import org.http4s.server.websocket.WebSocketBuilder2
import org.llm4s.ragbox.RagboxApp
import org.llm4s.ragbox.config._
import org.llm4s.ragbox.testkit.{FakeLLM, PgVectorFixture}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIStringSyntax

/**
 * The API consistency fixes of #54 through the FULL application stack (permission mode over
 * pgvector, basic auth with the users alice and bob, the API key as administrator):
 * nested collection paths on every per-collection endpoint, the read-only config preview for
 * any user, query-log ids from /query and the stream with owner-only feedback, 413/429 as
 * ErrorResponses, and source-scoped `POST /sync`.
 */
class ApiConsistency54StackSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private val ApiKey = "api-consistency-54-key-0123456789"

  private val security = SecurityConfig(
    apiKey = Some(ApiKey),
    auth = AuthConfig(
      mode = AuthMode.Basic,
      basic = BasicAuthConfig("admin", Some("admin-password-123")),
      oauth = None,
      jwtSecret = "jwt-secret-for-api-consistency-54-stack-spec-0123456789",
      jwtSecretExplicitlySet = true,
      jwtExpiration = 3600L,
      passwordHashIterations = 10000
    )
  )

  private final case class Fixture(app: HttpApp[IO], alice: String, bob: String, llm: FakeLLM) {
    def run(req: Request[IO]): IO[(Status, Json)] =
      app.run(req).flatMap(r => r.as[Json].handleError(_ => Json.Null).map(r.status -> _))
    def status(req: Request[IO]): IO[Status] = app.run(req).map(_.status)
  }

  private def withFixture[A](configure: AppConfig => AppConfig = identity)(test: Fixture => IO[A]): A =
    withContainers { pg =>
      val db = freshDatabase(pg)
      val llm = FakeLLM()
      (for {
        ds <- pool(db)
        rag <- permissionService(db, llm = Some(llm))
        app <- RagboxApp.resource(configure(testConfig(db, Some(llm)).copy(security = security)), ds, rag)
      } yield app).use { ragbox =>
        for {
          wsb <- WebSocketBuilder2[IO]
          http = ragbox.httpApp(wsb)
          _ <- List("alice", "bob").traverse_ { u =>
            http.run(asAdmin(post("/api/v1/users", Json.obj(
              "username" -> u.asJson, "password" -> s"$u-password".asJson, "role" -> "user".asJson))))
              .map(_.status shouldBe Status.Created)
          }
          alice <- login(http, "alice")
          bob <- login(http, "bob")
          result <- test(Fixture(http, alice, bob, llm))
        } yield result
      }.unsafeRunSync()
    }

  private def login(app: HttpApp[IO], user: String): IO[String] =
    app.run(post("/api/v1/auth/login", Json.obj("username" -> user.asJson, "password" -> s"$user-password".asJson)))
      .flatMap(_.as[Json]).map(_.hcursor.get[String]("token").toOption.get)

  private def req(method: Method, path: String): Request[IO] = Request[IO](method, Uri.unsafeFromString(path))
  private def get(path: String): Request[IO] = req(Method.GET, path)
  private def delete(path: String): Request[IO] = req(Method.DELETE, path)
  private def post(path: String, body: Json): Request[IO] = req(Method.POST, path).withEntity(body)
  private def put(path: String, body: Json): Request[IO] = req(Method.PUT, path).withEntity(body)
  private def asAdmin(r: Request[IO]): Request[IO] = r.putHeaders(Header.Raw(ci"X-API-Key", ApiKey))
  private def as(token: String)(r: Request[IO]): Request[IO] =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

  private def str(j: Json, field: String): Option[String] = j.hcursor.get[String](field).toOption

  // ------------------------------------------------------------ nested collection paths

  "Nested collection paths" should "work with raw and encoded slashes on every collection endpoint" in withFixture() { f =>
    for {
      _ <- f.status(asAdmin(post("/api/v1/collections", Json.obj("path" -> "eng".asJson, "isLeaf" -> false.asJson))))
      created <- f.status(asAdmin(post("/api/v1/collections", Json.obj("path" -> "eng/backend".asJson))))
      getRaw <- f.run(asAdmin(get("/api/v1/collections/eng/backend")))
      getEncoded <- f.run(asAdmin(get("/api/v1/collections/eng%2Fbackend")))
      statsRaw <- f.run(asAdmin(get("/api/v1/collections/eng/backend/stats")))
      statsEncoded <- f.run(asAdmin(get("/api/v1/collections/eng%2Fbackend/stats")))
      perms <- f.run(asAdmin(put("/api/v1/collections/eng%2Fbackend/permissions", Json.obj("queryableBy" -> List("user:alice").asJson))))
      configPut <- f.run(asAdmin(put("/api/v1/collections/eng/backend/config", Json.obj("strategy" -> "sentence".asJson))))
      configGet <- f.run(asAdmin(get("/api/v1/collections/eng%2Fbackend/config")))
      // The config routes are mounted before the collection routes: neither shadows the other
      collectionAfterConfig <- f.run(asAdmin(get("/api/v1/collections/eng/backend")))
      deleted <- f.status(asAdmin(delete("/api/v1/collections/eng/backend")))
      gone <- f.status(asAdmin(get("/api/v1/collections/eng%2Fbackend")))
    } yield {
      created shouldBe Status.Created
      List(getRaw, getEncoded).foreach { case (s, j) => s shouldBe Status.Ok; str(j, "path") shouldBe Some("eng/backend") }
      List(statsRaw, statsEncoded).foreach { case (s, j) => s shouldBe Status.Ok; str(j, "path") shouldBe Some("eng/backend") }
      perms._1 shouldBe Status.Ok
      str(perms._2, "path") shouldBe Some("eng/backend")
      configPut._1 shouldBe Status.Ok
      configGet._1 shouldBe Status.Ok
      str(configGet._2, "collection") shouldBe Some("eng/backend")
      configGet._2.hcursor.get[Boolean]("hasCustomConfig") shouldBe Right(true)
      str(collectionAfterConfig._2, "path") shouldBe Some("eng/backend")
      deleted shouldBe Status.NoContent
      gone shouldBe Status.NotFound
    }
  }

  "The chunking config preview" should "be open to any user, while changing the config stays admin-only" in withFixture() { f =>
    for {
      preview <- f.run(as(f.alice)(post("/api/v1/collections/eng/backend/config/preview",
        Json.obj("collection" -> "eng/backend".asJson, "filename" -> "notes.md".asJson))))
      change <- f.status(as(f.alice)(put("/api/v1/collections/eng/backend/config", Json.obj("strategy" -> "sentence".asJson))))
      remove <- f.status(as(f.alice)(delete("/api/v1/collections/eng/backend/config")))
      create <- f.status(as(f.alice)(post("/api/v1/collections", Json.obj("path" -> "mine".asJson))))
    } yield {
      preview._1 shouldBe Status.Ok
      str(preview._2, "collection") shouldBe Some("eng/backend")
      change shouldBe Status.Forbidden
      remove shouldBe Status.Forbidden
      create shouldBe Status.Forbidden
    }
  }

  // ------------------------------------------------------------ query ids and feedback

  private def seed(f: Fixture): IO[Unit] =
    f.status(asAdmin(post("/api/v1/collections", Json.obj("path" -> "public".asJson)))).map(_ shouldBe Status.Created) *>
      f.status(asAdmin(put("/api/v1/documents/solar", Json.obj(
        "content" -> "Photovoltaic solar panels convert sunlight into electricity.".asJson,
        "collection" -> "public".asJson
      )))).map(_ shouldBe Status.Created)

  private def rate(f: Fixture, auth: Request[IO] => Request[IO], queryId: String, rating: Int): IO[Status] =
    f.status(auth(post("/api/v1/feedback", Json.obj("queryId" -> queryId.asJson, "rating" -> rating.asJson))))

  "POST /api/v1/query" should "return the query-log id, which its asker (and only they, or an admin) can rate" in withFixture() { f =>
    for {
      _ <- seed(f)
      answered <- f.run(as(f.alice)(post("/api/v1/query", Json.obj("question" -> "How do solar panels work?".asJson))))
      queryId = str(answered._2, "queryId").getOrElse(fail(s"no queryId in ${answered._2}"))
      logged <- f.run(asAdmin(get(s"/api/v1/analytics/queries/$queryId")))
      byBob <- rate(f, as(f.bob), queryId, 1)
      byAlice <- rate(f, as(f.alice), queryId, 5)
      afterAlice <- f.run(asAdmin(get(s"/api/v1/analytics/queries/$queryId")))
      byAdmin <- rate(f, asAdmin, queryId, 4)
      unknown <- rate(f, as(f.alice), java.util.UUID.randomUUID().toString, 3)
    } yield {
      answered._1 shouldBe Status.Ok
      logged._1 shouldBe Status.Ok
      str(logged._2, "userId") shouldBe Some("alice")
      // Not bob's query: the same 404 as an unknown id
      byBob shouldBe Status.NotFound
      byAlice shouldBe Status.Ok
      afterAlice._2.hcursor.get[Int]("userRating") shouldBe Right(5)
      byAdmin shouldBe Status.Ok
      unknown shouldBe Status.NotFound
    }
  }

  "POST /api/v1/query/stream" should "use the query-log id as the events' queryId" in withFixture() { f =>
    for {
      _ <- seed(f)
      streamed <- f.app.run(as(f.alice)(post("/api/v1/query/stream", Json.obj("question" -> "How do solar panels work?".asJson))))
        .flatMap(_.as[String])
      events = streamed.split("\n\n").toList.flatMap { block =>
        val lines = block.linesIterator.toList
        for {
          event <- lines.collectFirst { case l if l.startsWith("event: ") => l.stripPrefix("event: ") }
          data <- lines.collectFirst { case l if l.startsWith("data: ") => l.stripPrefix("data: ") }
          json <- parse(data).toOption
        } yield event -> json
      }.toMap
      startId = str(events("start"), "queryId").get
      completeId = str(events("complete"), "queryId").get
      logged <- f.status(asAdmin(get(s"/api/v1/analytics/queries/$startId")))
      rated <- rate(f, as(f.alice), startId, 4)
    } yield {
      completeId shouldBe startId
      logged shouldBe Status.Ok
      rated shouldBe Status.Ok
    }
  }

  "POST /api/v1/feedback" should "work for an access token with the query scope, on its own queries" in withFixture() { f =>
    for {
      _ <- seed(f)
      created <- f.run(asAdmin(post("/api/v1/tokens", Json.obj("name" -> "bot".asJson, "scopes" -> List("query").asJson))))
      token = str(created._2, "token").get
      answered <- f.run(as(token)(post("/api/v1/query", Json.obj("question" -> "solar panels?".asJson))))
      queryId = str(answered._2, "queryId").get
      byToken <- rate(f, as(token), queryId, 5)
      byAlice <- rate(f, as(f.alice), queryId, 1)
    } yield {
      answered._1 shouldBe Status.Ok
      byToken shouldBe Status.Ok
      byAlice shouldBe Status.NotFound
    }
  }

  "A failed chat answer" should "be logged as a failed query, linked from the chat's error reply" in withFixture() { f =>
    def failedLogs: IO[List[Json]] =
      f.run(asAdmin(get("/api/v1/analytics/queries?pageSize=100"))).map(_._2.hcursor.downField("queries").values.toList.flatten
        .filter(_.hcursor.get[Boolean]("failed").contains(true)))
    for {
      _ <- seed(f)
      session <- f.run(as(f.alice)(post("/api/v1/chat/sessions", Json.obj("title" -> "outage".asJson))))
      sessionId = session._2.hcursor.downField("session").get[String]("id").toOption.get
      _ <- IO(f.llm.failing = Some("provider down"))
      sent <- f.run(as(f.alice)(post(s"/api/v1/chat/sessions/$sessionId/messages", Json.obj("content" -> "How do solar panels work?".asJson))))
      streamed <- f.app.run(as(f.alice)(post(s"/api/v1/chat/sessions/$sessionId/messages/stream",
        Json.obj("content" -> "And at night?".asJson)))).flatMap(_.as[String])
      _ <- IO(f.llm.failing = None)
      failed <- failedLogs
      messages <- f.run(as(f.alice)(get(s"/api/v1/chat/sessions/$sessionId")))
    } yield {
      sent._1 shouldBe Status.Ok
      sent._2.hcursor.get[String]("error").isRight shouldBe true
      val replyLogId = sent._2.hcursor.downField("assistantMessage").get[String]("queryLogId").toOption
      val streamId = "\"queryId\":\"([^\"]+)\"".r.findFirstMatchIn(streamed).map(_.group(1))
      failed.flatMap(str(_, "id")).toSet shouldBe (replyLogId.toSet ++ streamId.toSet)
      failed should have size 2
      failed.flatMap(str(_, "userId")).distinct shouldBe List("alice")
      // Both error replies point at their log entries
      val replies = messages._2.hcursor.downField("messages").values.toList.flatten.filter(m => str(m, "role").contains("assistant"))
      replies.flatMap(str(_, "queryLogId")).toSet shouldBe failed.flatMap(str(_, "id")).toSet
    }
  }

  // ------------------------------------------------------------ 413 / 429 bodies

  "A 413" should "be an ErrorResponse with the limit in details" in withFixture(c =>
    c.copy(production = c.production.copy(requestSize = RequestSizeConfig(enabled = true, maxBodySizeMb = 1)))
  ) { f =>
    val big = "x" * (1024 * 1024 + 10)
    f.run(asAdmin(post("/api/v1/documents", Json.obj("content" -> big.asJson)))).map { case (status, body) =>
      status shouldBe Status.PayloadTooLarge
      str(body, "error") shouldBe Some("payload_too_large")
      str(body, "details") shouldBe Some(s"maxSizeBytes=${1024 * 1024}")
    }
  }

  "A 429" should "be an ErrorResponse with the numbers in details, and keep Retry-After" in withFixture(c =>
    // The fixture's own setup (two users, two logins) counts too: allow it, then run out
    c.copy(production = c.production.copy(rateLimit = c.production.rateLimit.copy(enabled = true, maxRequests = 6, windowSeconds = 60)))
  ) { f =>
    def untilLimited(n: Int): IO[Response[IO]] =
      f.app.run(asAdmin(get("/api/v1/stats"))).flatMap { r =>
        if (r.status == Status.TooManyRequests || n == 0) IO.pure(r) else untilLimited(n - 1)
      }
    for {
      limited <- untilLimited(10)
      body <- limited.as[Json]
    } yield {
      limited.status shouldBe Status.TooManyRequests
      limited.headers.get(ci"Retry-After").map(_.head.value) shouldBe Some("60")
      str(body, "error") shouldBe Some("rate_limited")
      str(body, "details") shouldBe Some("limit=6; window=60s; retryAfter=60")
    }
  }

  // ------------------------------------------------------------ scoped POST /sync

  "POST /api/v1/sync" should "only prune the documents of the source it names (default: api)" in withFixture() { f =>
    def ids(j: Json) = j.hcursor.get[Seq[String]]("prunedIds").toOption.getOrElse(Nil).toSet
    def doc(id: String, source: Option[String]) =
      asAdmin(put(s"/api/v1/documents/$id", Json.obj(
        "content" -> s"Document $id about wind turbines.".asJson
      ).deepMerge(source.fold(Json.obj())(s => Json.obj("source" -> s.asJson)))))
    for {
      _ <- f.status(asAdmin(post("/api/v1/documents", Json.obj("content" -> "An uploaded note about tides.".asJson))))
        .map(_ shouldBe Status.Created)
      _ <- List(doc("api-keep", None), doc("api-gone", None), doc("conf-1", Some("confluence")), doc("conf-2", Some("confluence")))
        .traverse_(r => f.status(r).map(_ shouldBe Status.Created))
      token <- f.run(asAdmin(post("/api/v1/tokens", Json.obj("name" -> "ingester".asJson, "scopes" -> List("sync:write").asJson))))
        .map(r => str(r._2, "token").get)

      // Default scope: the `api` source only - the upload and confluence's documents are kept
      apiDry <- f.run(as(token)(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List("api-keep").asJson, "dryRun" -> true.asJson))))
      confDry <- f.run(as(token)(post("/api/v1/sync", Json.obj(
        "keepDocumentIds" -> List("conf-1").asJson, "source" -> "confluence".asJson, "dryRun" -> true.asJson))))
      // Every document, explicitly: admin-only
      allByToken <- f.run(as(token)(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson, "scope" -> "all".asJson, "dryRun" -> true.asJson))))
      allByAdmin <- f.run(asAdmin(post("/api/v1/sync", Json.obj(
        "keepDocumentIds" -> List("api-keep", "api-gone", "conf-1", "conf-2").asJson, "scope" -> "all".asJson, "dryRun" -> true.asJson))))
      badScope <- f.status(as(token)(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson, "scope" -> "everything".asJson))))
      both <- f.status(asAdmin(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson, "scope" -> "all".asJson, "source" -> "x".asJson))))
      badSource <- f.status(as(token)(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson, "source" -> "no spaces".asJson))))

      pruned <- f.run(as(token)(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List("api-keep").asJson))))
      left <- f.run(asAdmin(get("/api/v1/documents?pageSize=100")))
    } yield {
      apiDry._1 shouldBe Status.Ok
      ids(apiDry._2) shouldBe Set("api-gone")
      str(apiDry._2, "scope") shouldBe Some("source")
      str(apiDry._2, "source") shouldBe Some("api")
      ids(confDry._2) shouldBe Set("conf-2")
      allByToken._1 shouldBe Status.Forbidden
      allByAdmin._1 shouldBe Status.Ok
      ids(allByAdmin._2) should have size 1 // the upload, which belongs to no source
      str(allByAdmin._2, "scope") shouldBe Some("all")
      badScope shouldBe Status.BadRequest
      both shouldBe Status.BadRequest
      badSource shouldBe Status.BadRequest

      pruned._1 shouldBe Status.Ok
      pruned._2.hcursor.get[Int]("prunedCount") shouldBe Right(1)
      val remaining = left._2.hcursor.downField("documents").values.toList.flatten.flatMap(str(_, "id")).toSet
      remaining should contain allOf ("api-keep", "conf-1", "conf-2")
      remaining should not contain "api-gone"
      remaining should have size 4
    }
  }

  it should "move an unchanged document to the source that re-sends it" in withFixture() { f =>
    val body = Json.obj("content" -> "Shared content about rivers.".asJson)
    for {
      _ <- f.status(asAdmin(put("/api/v1/documents/shared", body))).map(_ shouldBe Status.Created)
      // A second ingester takes the document over without re-indexing it
      again <- f.run(asAdmin(put("/api/v1/documents/shared", body.deepMerge(Json.obj("source" -> "wiki".asJson)))))
      apiDry <- f.run(asAdmin(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson, "dryRun" -> true.asJson))))
      wikiDry <- f.run(asAdmin(post("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson, "source" -> "wiki".asJson, "dryRun" -> true.asJson))))
    } yield {
      str(again._2, "action") shouldBe Some("unchanged")
      apiDry._2.hcursor.get[Int]("prunedCount") shouldBe Right(0)
      wikiDry._2.hcursor.get[Seq[String]]("prunedIds") shouldBe Right(Seq("shared"))
    }
  }
}
