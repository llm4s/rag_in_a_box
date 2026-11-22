package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.Authorization
import org.http4s.server.websocket.WebSocketBuilder2
import org.llm4s.ragbox.RagboxApp
import org.llm4s.ragbox.config.{AuthConfig, AuthMode, BasicAuthConfig, DatabaseConfig, SecurityConfig}
import org.llm4s.ragbox.testkit.PgVectorFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIStringSyntax

import scala.util.Using

/**
 * Authorization (#13) through the full application stack over pgvector: admin-only endpoints,
 * filtered read paths, identity from the authenticated principal (header spoofing ignored,
 * trusted-proxy mode), and chat session ownership.
 */
class AuthorizationStackSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private val ApiKey = "authz-api-key-0123456789"
  private val AdminPassword = "admin-password-123"

  private def security(trusted: Boolean = false): SecurityConfig = SecurityConfig(
    apiKey = Some(ApiKey),
    auth = AuthConfig(
      mode = AuthMode.Basic,
      basic = BasicAuthConfig("admin", Some(AdminPassword)),
      oauth = None,
      jwtSecret = "jwt-secret-for-authz-stack-spec-0123456789",
      jwtSecretExplicitlySet = true,
      jwtExpiration = 3600L,
      trustedProxyHeaders = trusted
    )
  )

  /** The app, plus JWTs for the users alice and bob (both role `user`). */
  private final case class Fixture(app: HttpApp[IO], db: DatabaseConfig, alice: String, bob: String) {
    def run(req: Request[IO]): IO[(Status, Json)] =
      app.run(req).flatMap(r => r.as[Json].handleError(_ => Json.Null).map(r.status -> _))
    def status(req: Request[IO]): IO[Status] = app.run(req).map(_.status)
  }

  private def withFixture[A](trusted: Boolean = false)(test: Fixture => IO[A]): A =
    withContainers { pg =>
      val db = freshDatabase(pg)
      (for {
        ds <- pool(db)
        rag <- permissionService(db)
        app <- RagboxApp.resource(testConfig(db).copy(security = security(trusted)), ds, rag)
      } yield app).use { ragbox =>
        for {
          wsb <- WebSocketBuilder2[IO]
          http = ragbox.httpApp(wsb)
          _ <- List("alice", "bob").traverse_ { u =>
            http.run(asAdmin(post("/api/v1/users", Json.obj(
              "username" -> u.asJson, "password" -> s"$u-password".asJson, "role" -> "user".asJson))))
              .map(r => r.status shouldBe Status.Created)
          }
          alice <- login(http, "alice")
          bob <- login(http, "bob")
          result <- test(Fixture(http, db, alice, bob))
        } yield result
      }.unsafeRunSync()
    }

  private def login(app: HttpApp[IO], user: String): IO[String] =
    app.run(post("/api/v1/auth/login", Json.obj("username" -> user.asJson, "password" -> s"$user-password".asJson)))
      .flatMap(_.as[Json]).map(_.hcursor.get[String]("token").toOption.get)

  private def req(method: Method, path: String): Request[IO] = Request[IO](method, Uri.unsafeFromString(path))
  private def get(path: String): Request[IO] = req(Method.GET, path)
  private def post(path: String, body: Json): Request[IO] = req(Method.POST, path).withEntity(body)
  private def put(path: String, body: Json): Request[IO] = req(Method.PUT, path).withEntity(body)
  private def asAdmin(r: Request[IO]): Request[IO] = r.putHeaders(Header.Raw(ci"X-API-Key", ApiKey))
  private def as(token: String)(r: Request[IO]): Request[IO] =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
  private def withUserHeaders(user: String, groups: String = "")(r: Request[IO]): Request[IO] =
    r.putHeaders(Header.Raw(ci"X-User-Id", user), Header.Raw(ci"X-Group-Ids", groups))

  /** `public` (anyone) and `secret` (alice only), each with one document about solar panels. */
  private def seed(f: Fixture): IO[Unit] =
    for {
      _ <- f.status(asAdmin(post("/api/v1/collections", Json.obj("path" -> "public".asJson))))
        .map(_ shouldBe Status.Created)
      _ <- f.status(asAdmin(post("/api/v1/collections", Json.obj(
        "path" -> "secret".asJson, "queryableBy" -> List("user:alice").asJson))))
        .map(_ shouldBe Status.Created)
      _ <- List("public", "secret").traverse_ { c =>
        f.status(asAdmin(put(s"/api/v1/documents/doc-$c", Json.obj(
          "content" -> s"Photovoltaic solar panels convert sunlight into electricity ($c).".asJson,
          "collection" -> c.asJson
        )))).map(_ shouldBe Status.Created)
      }
    } yield ()

  private def searchIds(f: Fixture, r: Request[IO] => Request[IO]): IO[Set[String]] =
    f.run(r(post("/api/v1/search", Json.obj("query" -> "solar panels sunlight electricity".asJson, "topK" -> 10.asJson))))
      .map { case (s, body) =>
        s shouldBe Status.Ok
        body.hcursor.downField("results").values.toList.flatten.flatMap(_.hcursor.get[String]("documentId").toOption).toSet
      }

  // ------------------------------------------------------------ admin-only endpoints

  "Admin endpoints" should "answer 403 to a non-admin user and work for an admin" in withFixture() { f =>
    val adminOnly: List[Request[IO]] = List(
      post("/api/v1/collections", Json.obj("path" -> "bobs".asJson)),
      put("/api/v1/collections/public/permissions", Json.obj("queryableBy" -> List("user:bob").asJson)),
      req(Method.DELETE, "/api/v1/collections/public"),
      post("/api/v1/principals/users", Json.obj("externalId" -> "x".asJson)),
      get("/api/v1/principals/users"),
      put("/api/v1/config/runtime", Json.obj("systemPrompt" -> "pwned".asJson)),
      get("/api/v1/config/runtime/history"),
      put("/api/v1/config", Json.obj()),
      put("/api/v1/collections/public/config", Json.obj("strategy" -> "simple".asJson)),
      req(Method.DELETE, "/api/v1/collections/public/config"),
      post("/api/v1/experiments", Json.obj("name" -> "x".asJson)),
      get("/api/v1/experiments"),
      post("/api/v1/ingest/run", Json.obj()),
      post("/api/v1/ingest/directory", Json.obj("path" -> "/etc".asJson)),
      get("/api/v1/ingest/status"),
      req(Method.DELETE, "/api/v1/documents?all=true"),
      post("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson)),
      get("/api/v1/sync/status"),
      post("/api/v1/users", Json.obj("username" -> "eve".asJson, "password" -> "x".asJson)),
      get("/api/v1/users"),
      post("/api/v1/tokens", Json.obj("name" -> "x".asJson, "scopes" -> List("admin").asJson)),
      get("/api/v1/tokens"),
      get("/api/v1/visibility/stats"),
      get("/api/v1/visibility/chunks"),
      get("/api/v1/analytics/queries"),
      get("/api/v1/analytics/queries/summary"),
      get("/api/v1/analytics/suggestions")
    )
    for {
      _ <- seed(f)
      bob <- adminOnly.traverse(r => f.status(as(f.bob)(r)).map(r.method.name + " " + r.uri.renderString -> _))
      adminReads <- List("/api/v1/principals/users", "/api/v1/visibility/stats", "/api/v1/analytics/queries",
        "/api/v1/ingest/status", "/api/v1/config/runtime/history", "/api/v1/experiments", "/api/v1/users")
        .traverse(p => f.status(asAdmin(get(p))).map(p -> _))
      bobReadsRuntime <- f.status(as(f.bob)(get("/api/v1/config/runtime")))
    } yield {
      bob.filterNot(_._2 == Status.Forbidden) shouldBe empty
      adminReads.filterNot(_._2 == Status.Ok) shouldBe empty
      bobReadsRuntime shouldBe Status.Ok
    }
  }

  it should "deny access tokens without the admin scope outside their scopes" in withFixture() { f =>
    for {
      created <- f.run(asAdmin(post("/api/v1/tokens", Json.obj("name" -> "q".asJson, "scopes" -> List("query").asJson))))
      token = created._2.hcursor.get[String]("token").toOption.get
      config <- f.status(as(token)(get("/api/v1/config")))
      chunking <- f.status(as(token)(get("/api/v1/chunking/strategies")))
      collection <- f.status(as(token)(get("/api/v1/collections/public")))
      me <- f.status(as(token)(get("/api/v1/auth/me")))
    } yield {
      config shouldBe Status.Forbidden
      chunking shouldBe Status.Forbidden
      collection shouldBe Status.Forbidden
      me shouldBe Status.Ok
    }
  }

  // ------------------------------------------------------------ read filtering

  "Read paths" should "only show a user the collections and documents they may query" in withFixture() { f =>
    def collections(r: Request[IO] => Request[IO]) =
      f.run(r(get("/api/v1/collections"))).map(_._2.hcursor.get[List[String]]("collections").toOption.get.toSet)
    def documents(r: Request[IO] => Request[IO]) =
      f.run(r(get("/api/v1/documents"))).map(_._2.hcursor.downField("documents").values.toList.flatten
        .flatMap(_.hcursor.get[String]("id").toOption).toSet)
    for {
      _ <- seed(f)
      bobCollections <- collections(as(f.bob))
      aliceCollections <- collections(as(f.alice))
      adminCollections <- collections(asAdmin)
      bobDocs <- documents(as(f.bob))
      aliceDocs <- documents(as(f.alice))
      bobGetsSecretDoc <- f.status(as(f.bob)(get("/api/v1/documents/doc-secret")))
      bobGetsSecretCollection <- f.status(as(f.bob)(get("/api/v1/collections/secret")))
      aliceGetsSecretCollection <- f.status(as(f.alice)(get("/api/v1/collections/secret")))
      bobWritesSecret <- f.status(as(f.bob)(put("/api/v1/documents/doc-bob", Json.obj(
        "content" -> "bob was here".asJson, "collection" -> "secret".asJson))))
      bobDeletesSecret <- f.status(as(f.bob)(req(Method.DELETE, "/api/v1/documents/doc-secret")))
      bobAccessible <- f.run(as(f.bob)(get("/api/v1/collections/accessible")))
      bobSearch <- searchIds(f, as(f.bob))
      aliceSearch <- searchIds(f, as(f.alice))
    } yield {
      bobCollections should contain("public")
      bobCollections should not contain "secret"
      aliceCollections should contain("secret")
      adminCollections should contain allOf ("public", "secret")
      bobDocs shouldBe Set("doc-public")
      aliceDocs shouldBe Set("doc-public", "doc-secret")
      bobGetsSecretDoc shouldBe Status.NotFound
      bobGetsSecretCollection shouldBe Status.NotFound
      aliceGetsSecretCollection shouldBe Status.Ok
      bobWritesSecret shouldBe Status.Forbidden
      bobDeletesSecret shouldBe Status.Forbidden
      bobAccessible._2.hcursor.downField("collections").values.toList.flatten
        .flatMap(_.hcursor.get[String]("path").toOption) should not contain "secret"
      bobSearch shouldBe Set("doc-public")
      aliceSearch shouldBe Set("doc-public", "doc-secret")
    }
  }

  // ------------------------------------------------------------ identity headers

  "Identity headers" should "be ignored unless trusted-proxy mode is on" in withFixture() { f =>
    for {
      _ <- seed(f)
      bobAsAlice <- searchIds(f, r => withUserHeaders("alice")(as(f.bob)(r)))
      bobMe <- f.run(withUserHeaders("alice")(as(f.bob)(get("/api/v1/auth/me"))))
      keyAsBob <- f.run(withUserHeaders("bob")(asAdmin(get("/api/v1/auth/me"))))
      keySearch <- searchIds(f, r => withUserHeaders("bob")(asAdmin(r)))
      bobAdminHeader <- f.status(as(f.bob)(get("/api/v1/users").putHeaders(Header.Raw(ci"X-Admin", "true"))))
    } yield {
      bobAsAlice shouldBe Set("doc-public")
      bobMe._2.hcursor.get[String]("id") shouldBe Right("user:bob")
      keyAsBob._2.hcursor.get[String]("authMethod") shouldBe Right("api-key")
      keyAsBob._2.hcursor.get[Boolean]("isAdmin") shouldBe Right(true)
      keySearch shouldBe Set("doc-public", "doc-secret")
      bobAdminHeader shouldBe Status.Forbidden
    }
  }

  it should "let an admin credential act for an end user in trusted-proxy mode" in withFixture(trusted = true) { f =>
    for {
      _ <- seed(f)
      proxiedBob <- f.run(withUserHeaders("bob")(asAdmin(get("/api/v1/auth/me"))))
      proxiedBobSearch <- searchIds(f, r => withUserHeaders("bob")(asAdmin(r)))
      proxiedAliceSearch <- searchIds(f, r => withUserHeaders("alice")(asAdmin(r)))
      proxiedBobAdmin <- f.status(withUserHeaders("bob")(asAdmin(get("/api/v1/users"))))
      // A non-admin credential can never assert another identity
      bobAsAlice <- searchIds(f, r => withUserHeaders("alice")(as(f.bob)(r)))
      noHeaders <- searchIds(f, asAdmin)
    } yield {
      proxiedBob._2.hcursor.get[String]("id") shouldBe Right("user:bob")
      proxiedBob._2.hcursor.get[Boolean]("isAdmin") shouldBe Right(false)
      proxiedBobSearch shouldBe Set("doc-public")
      proxiedAliceSearch shouldBe Set("doc-public", "doc-secret")
      proxiedBobAdmin shouldBe Status.Forbidden
      bobAsAlice shouldBe Set("doc-public")
      noHeaders shouldBe Set("doc-public", "doc-secret")
    }
  }

  // ------------------------------------------------------------ chat ownership

  "Chat sessions" should "be visible and usable only by their owner and administrators" in withFixture() { f =>
    def sessionIds(r: Request[IO] => Request[IO]): IO[Set[String]] =
      f.run(r(get("/api/v1/chat/sessions"))).map(_._2.hcursor.downField("sessions").values.toList.flatten
        .flatMap(_.hcursor.get[String]("id").toOption).toSet)
    for {
      created <- f.run(as(f.bob)(post("/api/v1/chat/sessions", Json.obj("title" -> "bob's chat".asJson))))
      bobSession = created._2.hcursor.downField("session").get[String]("id").toOption.get
      owner = created._2.hcursor.downField("session").get[String]("ownerId").toOption
      // A session from before owners existed (owner_id NULL)
      _ <- connect(f.db).use(conn => IO.blocking(Using.resource(conn.createStatement())(_.execute(
        "INSERT INTO chat_sessions (id, title, collection_pattern, message_count, is_archived) VALUES ('chat-legacy', 'old', '*', 0, FALSE)"
      ))))
      bobList <- sessionIds(as(f.bob))
      aliceList <- sessionIds(as(f.alice))
      adminList <- sessionIds(asAdmin)
      aliceGet <- f.status(as(f.alice)(get(s"/api/v1/chat/sessions/$bobSession")))
      alicePut <- f.status(as(f.alice)(put(s"/api/v1/chat/sessions/$bobSession", Json.obj("title" -> "mine".asJson))))
      alicePost <- f.status(as(f.alice)(post(s"/api/v1/chat/sessions/$bobSession/messages", Json.obj("content" -> "hi".asJson))))
      aliceDelete <- f.status(as(f.alice)(req(Method.DELETE, s"/api/v1/chat/sessions/$bobSession")))
      bobLegacy <- f.status(as(f.bob)(get("/api/v1/chat/sessions/chat-legacy")))
      adminLegacy <- f.status(asAdmin(get("/api/v1/chat/sessions/chat-legacy")))
      bobGet <- f.status(as(f.bob)(get(s"/api/v1/chat/sessions/$bobSession")))
      bobEmpty <- f.status(as(f.bob)(post(s"/api/v1/chat/sessions/$bobSession/messages", Json.obj("content" -> "   ".asJson))))
      bobTooLong <- f.status(as(f.bob)(post(s"/api/v1/chat/sessions/$bobSession/messages", Json.obj("content" -> ("x" * 20000).asJson))))
      bobDelete <- f.status(as(f.bob)(req(Method.DELETE, s"/api/v1/chat/sessions/$bobSession")))
    } yield {
      owner shouldBe Some("user:bob")
      bobList shouldBe Set(bobSession)
      aliceList shouldBe empty
      adminList shouldBe Set(bobSession, "chat-legacy")
      aliceGet shouldBe Status.NotFound
      alicePut shouldBe Status.NotFound
      alicePost shouldBe Status.NotFound
      aliceDelete shouldBe Status.NotFound
      bobLegacy shouldBe Status.NotFound
      adminLegacy shouldBe Status.Ok
      bobGet shouldBe Status.Ok
      bobEmpty shouldBe Status.BadRequest
      bobTooLong shouldBe Status.BadRequest
      bobDelete shouldBe Status.Ok
    }
  }
}
