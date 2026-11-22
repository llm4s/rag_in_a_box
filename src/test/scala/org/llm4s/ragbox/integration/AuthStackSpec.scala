package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.{Authorization, Location}
import org.http4s.implicits._
import org.http4s.server.websocket.WebSocketBuilder2
import org.llm4s.ragbox.RagboxApp
import org.llm4s.ragbox.auth._
import org.llm4s.ragbox.config.{AuthConfig, AuthMode, BasicAuthConfig, DatabaseConfig, SecurityConfig}
import org.llm4s.ragbox.testkit.{MockOidcServer, PgVectorFixture}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIStringSyntax

import java.net.URLDecoder

/**
 * Authentication through the FULL application stack: the `HttpApp` that `Main` serves, built
 * by [[RagboxApp]] over a real pgvector database, for every auth mode.
 */
class AuthStackSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private val ApiKey = "test-api-key-0123456789"
  private val AdminPassword = "admin-password-123"
  private val OidcPort = 9987

  private def auth(mode: AuthMode, oauth: Option[OAuthConfig] = None): AuthConfig = AuthConfig(
    mode = mode,
    basic = BasicAuthConfig("admin", Some(AdminPassword)),
    oauth = oauth,
    jwtSecret = "jwt-secret-for-auth-stack-spec-0123456789",
    jwtSecretExplicitlySet = true,
    jwtExpiration = 3600L
  )

  /** Run `test` against the full HTTP app built over a fresh database. */
  private def withApp[A](db: DatabaseConfig, security: SecurityConfig)(test: (HttpApp[IO], RagboxApp) => IO[A]): A =
    (for {
      ds <- pool(db)
      rag <- permissionService(db)
      app <- RagboxApp.resource(testConfig(db).copy(security = security), ds, rag)
    } yield app).use { app =>
      WebSocketBuilder2[IO].flatMap(wsb => test(app.httpApp(wsb), app))
    }.unsafeRunSync()

  private def get(path: String): Request[IO] = Request[IO](Method.GET, Uri.unsafeFromString(path))

  private def bearer(token: String)(req: Request[IO]): Request[IO] =
    req.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

  private def jsonPost(path: String, body: Json, method: Method = Method.POST): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)

  private def status(app: HttpApp[IO], req: Request[IO]): IO[Status] = app.run(req).map(_.status)

  private def json(app: HttpApp[IO], req: Request[IO]): IO[(Status, Json)] =
    app.run(req).flatMap(r => r.as[Json].handleError(_ => Json.Null).map(r.status -> _))

  private def login(app: HttpApp[IO], user: String, password: String): IO[String] =
    json(app, jsonPost("/api/v1/auth/login", Json.obj("username" -> user.asJson, "password" -> password.asJson)))
      .map { case (s, body) =>
        s shouldBe Status.Ok
        body.hcursor.get[String]("token").toOption.get
      }

  // ---------------------------------------------------------------- open

  "Open mode without an API key" should "let every request through as an anonymous administrator" in withContainers { pg =>
    withApp(freshDatabase(pg), SecurityConfig(apiKey = None, auth = auth(AuthMode.Open))) { (app, _) =>
      for {
        stats <- status(app, get("/api/v1/stats"))
        me <- json(app, get("/api/v1/auth/me"))
        mode <- json(app, get("/api/v1/auth/mode"))
        tokens <- status(app, get("/api/v1/tokens"))
      } yield {
        stats shouldBe Status.Ok
        me._2.hcursor.get[String]("authMethod") shouldBe Right("open")
        me._2.hcursor.get[Boolean]("isAdmin") shouldBe Right(true)
        mode._2.hcursor.get[Boolean]("authRequired") shouldBe Right(false)
        tokens shouldBe Status.Ok
      }
    }
  }

  // ---------------------------------------------------------------- api key

  "Open mode with API_KEY" should "require the key on API routes but not on public ones" in withContainers { pg =>
    withApp(freshDatabase(pg), SecurityConfig(apiKey = Some(ApiKey), auth = auth(AuthMode.Open))) { (app, _) =>
      for {
        none <- status(app, get("/api/v1/stats"))
        wrong <- status(app, get("/api/v1/stats").putHeaders(Header.Raw(ci"X-API-Key", "nope")))
        header <- status(app, get("/api/v1/stats").putHeaders(Header.Raw(ci"X-API-Key", ApiKey)))
        viaBearer <- status(app, bearer(ApiKey)(get("/api/v1/stats")))
        viaParam <- status(app, get(s"/api/v1/stats?api_key=$ApiKey"))
        health <- status(app, get("/health"))
        mode <- json(app, get("/api/v1/auth/mode"))
        adminUi <- status(app, get("/admin/"))
        unknown <- status(app, get("/api/v1/does-not-exist"))
        // Password login is disabled outside basic mode (the bootstrap admin has a default password)
        pwLogin <- status(app, jsonPost("/api/v1/auth/login", Json.obj("username" -> "admin".asJson, "password" -> "admin".asJson)))
        // WebSocket: browsers cannot send headers, so ?access_token= is accepted there only
        wsNone <- status(app, get("/ws"))
        wsToken <- status(app, get(s"/ws?access_token=$ApiKey"))
        tokenElsewhere <- status(app, get(s"/api/v1/stats?access_token=$ApiKey"))
      } yield {
        none shouldBe Status.Unauthorized
        wrong shouldBe Status.Unauthorized
        header shouldBe Status.Ok
        viaBearer shouldBe Status.Ok
        viaParam shouldBe Status.Ok
        health shouldBe Status.Ok
        mode._1 shouldBe Status.Ok
        mode._2.hcursor.get[List[String]]("methods") shouldBe Right(List("api-key"))
        adminUi should not be Status.Unauthorized
        unknown shouldBe Status.Unauthorized
        pwLogin shouldBe Status.BadRequest
        wsNone shouldBe Status.Unauthorized
        wsToken should not be Status.Unauthorized
        tokenElsewhere shouldBe Status.Unauthorized
      }
    }
  }

  // ---------------------------------------------------------------- basic (JWT)

  "Basic mode without API_KEY" should "accept JWTs from /auth/login and enforce the admin role" in withContainers { pg =>
    withApp(freshDatabase(pg), SecurityConfig(apiKey = None, auth = auth(AuthMode.Basic))) { (app, _) =>
      for {
        anonymous <- status(app, get("/api/v1/stats"))
        badLogin <- status(app, jsonPost("/api/v1/auth/login", Json.obj("username" -> "admin".asJson, "password" -> "wrong".asJson)))
        adminToken <- login(app, "admin", AdminPassword)
        stats <- status(app, bearer(adminToken)(get("/api/v1/stats")))
        me <- json(app, bearer(adminToken)(get("/api/v1/auth/me")))
        garbage <- status(app, bearer("not.a.jwt")(get("/api/v1/stats")))
        created <- status(app, bearer(adminToken)(jsonPost("/api/v1/users",
          Json.obj("username" -> "bob".asJson, "password" -> "bob-password".asJson, "role" -> "user".asJson))))
        bobToken <- login(app, "bob", "bob-password")
        bobMe <- json(app, bearer(bobToken)(get("/api/v1/auth/me")))
        bobCreates <- status(app, bearer(bobToken)(jsonPost("/api/v1/users",
          Json.obj("username" -> "eve".asJson, "password" -> "x".asJson))))
        bobTokens <- status(app, bearer(bobToken)(get("/api/v1/tokens")))
      } yield {
        anonymous shouldBe Status.Unauthorized
        badLogin shouldBe Status.Unauthorized
        stats shouldBe Status.Ok
        me._2.hcursor.get[String]("username") shouldBe Right("admin")
        me._2.hcursor.get[String]("authMethod") shouldBe Right("password")
        garbage shouldBe Status.Unauthorized
        created shouldBe Status.Created
        bobMe._2.hcursor.get[Boolean]("isAdmin") shouldBe Right(false)
        bobCreates shouldBe Status.Forbidden
        bobTokens shouldBe Status.Forbidden
      }
    }
  }

  // ---------------------------------------------------------------- rat_ tokens

  "Access tokens" should "be accepted and limited to their scopes and collections" in withContainers { pg =>
    withApp(freshDatabase(pg), SecurityConfig(apiKey = Some(ApiKey), auth = auth(AuthMode.Basic))) { (app, _) =>
      val admin = (req: Request[IO]) => req.putHeaders(Header.Raw(ci"X-API-Key", ApiKey))

      def createToken(name: String, scopes: List[String], collections: Option[List[String]]): IO[(String, String)] =
        json(app, admin(jsonPost("/api/v1/tokens", Json.obj(
          "name" -> name.asJson, "scopes" -> scopes.asJson, "collections" -> collections.asJson
        )))).map { case (s, body) =>
          withClue(body.noSpaces)(s shouldBe Status.Created)
          (body.hcursor.get[String]("id").toOption.get, body.hcursor.get[String]("token").toOption.get)
        }

      def put(token: String, id: String, collection: String): IO[Status] =
        json(app, bearer(token)(jsonPost(s"/api/v1/documents/$id",
          Json.obj("content" -> s"Content of $id about solar panels".asJson, "collection" -> collection.asJson), Method.PUT)))
          .map { case (s, body) =>
            if (s.code >= 500) fail(s"PUT $id failed: ${body.noSpaces}")
            s
          }

      def search(token: String, collection: String) =
        status(app, bearer(token)(jsonPost("/api/v1/search",
          Json.obj("query" -> "solar panels".asJson, "collection" -> collection.asJson))))

      for {
        (writerId, writer) <- createToken("eng-ingester", List("documents:write", "sync:read"), Some(List("eng")))
        (_, reader) <- createToken("eng-reader", List("query", "documents:read"), Some(List("eng")))
        (_, full) <- createToken("everything", List("admin"), None)
        _ <- List("eng" -> false, "eng/docs" -> true, "eng/api" -> true, "hr" -> true).traverse_ { case (path, leaf) =>
          status(app, admin(jsonPost("/api/v1/collections", Json.obj("path" -> path.asJson, "isLeaf" -> leaf.asJson))))
            .map(s => withClue(path)(s shouldBe Status.Created))
        }

        writeEng <- put(writer, "doc-eng", "eng/docs")
        writeEngChild <- put(writer, "doc-eng-api", "eng/api")
        writeHr <- put(writer, "doc-hr", "hr")
        writerLists <- status(app, bearer(writer)(get("/api/v1/documents")))
        writerQueries <- search(writer, "eng/**")
        writerClearsAll <- status(app, bearer(writer)(Request[IO](Method.DELETE, uri"/api/v1/documents?all=true")))
        writerSync <- status(app, bearer(writer)(get("/api/v1/sync/status")))
        writerSyncWrite <- status(app, bearer(writer)(jsonPost("/api/v1/sync", Json.obj("keepDocumentIds" -> List.empty[String].asJson))))
        writerMakesToken <- status(app, bearer(writer)(jsonPost("/api/v1/tokens", Json.obj("name" -> "x".asJson, "scopes" -> List("admin").asJson))))

        adminWritesHr <- put(full, "doc-hr", "hr")
        readerSearchEng <- search(reader, "eng/**")
        readerSearchAll <- search(reader, "*")
        readerSearchHr <- search(reader, "hr")
        readerList <- json(app, bearer(reader)(get("/api/v1/documents")))
        readerGetsHr <- status(app, bearer(reader)(get("/api/v1/documents/doc-hr")))
        readerWrites <- put(reader, "doc-x", "eng/docs")
        readerUploads <- status(app, bearer(reader)(Request[IO](Method.POST, uri"/api/v1/documents/upload")))

        tokenInfo <- json(app, admin(get(s"/api/v1/tokens/$writerId")))
        _ <- status(app, admin(Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/tokens/$writerId"))))
        revoked <- status(app, bearer(writer)(get("/api/v1/sync/status")))
        unknownToken <- status(app, bearer("rat_doesnotexist")(get("/api/v1/sync/status")))
      } yield {
        writeEng shouldBe Status.Created
        writeEngChild shouldBe Status.Created
        writeHr shouldBe Status.Forbidden
        writerLists shouldBe Status.Forbidden
        writerQueries shouldBe Status.Forbidden
        writerClearsAll shouldBe Status.Forbidden
        writerSync shouldBe Status.Ok
        writerSyncWrite shouldBe Status.Forbidden
        writerMakesToken shouldBe Status.Forbidden

        adminWritesHr shouldBe Status.Created
        readerSearchEng shouldBe Status.Ok
        readerSearchAll shouldBe Status.Forbidden
        readerSearchHr shouldBe Status.Forbidden
        readerList._1 shouldBe Status.Ok
        val listed = readerList._2.hcursor.downField("documents").values.toList.flatten
          .flatMap(_.hcursor.get[String]("id").toOption)
        listed.toSet shouldBe Set("doc-eng", "doc-eng-api")
        readerGetsHr shouldBe Status.NotFound
        readerWrites shouldBe Status.Forbidden
        readerUploads shouldBe Status.Forbidden

        tokenInfo._2.hcursor.get[String]("lastUsedAt").isRight shouldBe true
        revoked shouldBe Status.Unauthorized
        unknownToken shouldBe Status.Unauthorized
      }
    }
  }

  // ---------------------------------------------------------------- oauth

  "OAuth mode" should "log in through the OIDC flow and authenticate with the session cookie" in withContainers { pg =>
    MockOidcServer.resource(OidcPort).use { oidc =>
      val provider = OidcProviderConfig(
        clientId = "test-client-id",
        clientSecret = "test-secret",
        redirectUri = "http://localhost/api/v1/oauth/callback",
        authorizationEndpoint = oidc.authorizationEndpoint,
        tokenEndpoint = oidc.tokenEndpoint,
        userinfoEndpoint = oidc.userinfoEndpoint,
        jwksUri = oidc.jwksUri,
        issuer = oidc.issuer
      )
      val oauth = OAuthConfig(
        provider = provider,
        session = OAuthSessionConfig(cookieSecure = false),
        sessionStorage = OAuthSessionStorage.Postgres,
        adminGroups = Set("ragbox-admins")
      )
      IO(withApp(freshDatabase(pg), SecurityConfig(apiKey = None, auth = auth(AuthMode.OAuth, Some(oauth)))) { (app, _) =>

        /** Run the browser flow; returns the callback response. */
        def browserLogin(redirectAfter: String, groups: List[String], subject: String): IO[Response[IO]] =
          for {
            start <- app.run(get(s"/api/v1/oauth/login?redirect_after=${java.net.URLEncoder.encode(redirectAfter, "UTF-8")}"))
            location = start.headers.get[Location].get.uri
            state = location.query.params("state")
            nonce = URLDecoder.decode(location.query.params("nonce"), "UTF-8")
            binding = start.cookies.find(_.name == oauth.session.preAuthCookieName).get.content
            _ = oidc.nextIdToken.set(Some(oidc.generateIdToken(subject = subject, groups = groups, nonce = Some(nonce))))
            callback <- app.run(
              get(s"/api/v1/oauth/callback?code=code-$subject&state=$state")
                .addCookie(oauth.session.preAuthCookieName, binding)
            )
          } yield callback

        def sessionOf(resp: Response[IO]): String =
          resp.cookies.find(_.name == oauth.session.cookieName).get.content

        for {
          anonymous <- status(app, get("/api/v1/stats"))
          mode <- json(app, get("/api/v1/auth/mode"))
          adminLogin <- browserLogin("/admin/documents", List("ragbox-admins"), "alice")
          adminMe <- json(app, get("/api/v1/auth/me").addCookie(oauth.session.cookieName, sessionOf(adminLogin)))
          adminTokens <- status(app, get("/api/v1/tokens").addCookie(oauth.session.cookieName, sessionOf(adminLogin)))
          userLogin <- browserLogin("//evil.example.com/phish", Nil, "bob")
          userMe <- json(app, get("/api/v1/auth/me").addCookie(oauth.session.cookieName, sessionOf(userLogin)))
          userTokens <- status(app, get("/api/v1/tokens").addCookie(oauth.session.cookieName, sessionOf(userLogin)))
          forged <- status(app, get("/api/v1/stats").addCookie(oauth.session.cookieName, "forged-session"))
          _ <- app.run(Request[IO](Method.POST, uri"/api/v1/oauth/logout").addCookie(oauth.session.cookieName, sessionOf(adminLogin)))
          afterLogout <- status(app, get("/api/v1/stats").addCookie(oauth.session.cookieName, sessionOf(adminLogin)))
          jwtLogin <- status(app, jsonPost("/api/v1/auth/login", Json.obj("username" -> "admin".asJson, "password" -> AdminPassword.asJson)))
        } yield {
          anonymous shouldBe Status.Unauthorized
          mode._2.hcursor.get[List[String]]("methods") shouldBe Right(List("sso"))
          mode._2.hcursor.get[String]("oauthLoginUrl") shouldBe Right("/api/v1/oauth/login")

          adminLogin.status shouldBe Status.SeeOther
          adminLogin.headers.get[Location].get.uri.renderString shouldBe "/admin/documents"
          adminMe._1 shouldBe Status.Ok
          adminMe._2.hcursor.get[String]("authMethod") shouldBe Right("oidc")
          adminMe._2.hcursor.get[Boolean]("isAdmin") shouldBe Right(true)
          adminTokens shouldBe Status.Ok

          // An absolute / protocol-relative redirect_after is ignored
          userLogin.headers.get[Location].get.uri.renderString shouldBe "/admin/"
          userMe._2.hcursor.get[Boolean]("isAdmin") shouldBe Right(false)
          userMe._2.hcursor.get[String]("id") shouldBe Right(s"oidc:${oidc.issuer}|bob")
          userTokens shouldBe Status.Forbidden

          forged shouldBe Status.Unauthorized
          afterLogout shouldBe Status.Unauthorized
          jwtLogin shouldBe Status.BadRequest
        }
      })
    }.unsafeRunSync()
  }

  "OAuthRoutes.safeRedirect" should "only accept relative paths on this server" in {
    import org.llm4s.ragbox.routes.OAuthRoutes.safeRedirect
    safeRedirect("/admin/documents?x=1#y") shouldBe Some("/admin/documents?x=1#y")
    safeRedirect("/admin/") shouldBe Some("/admin/")
    Seq(
      "//evil.com", "/\\evil.com", "https://evil.com", "evil.com", "javascript:alert(1)",
      "/a\nb", " //evil.com", "/%0d%0aSet-Cookie:x", "", "/x:y"
    ).foreach(bad => withClue(bad)(safeRedirect(bad) shouldBe None))
  }
}
