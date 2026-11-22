package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.Stream
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.Authorization
import org.http4s.server.websocket.WebSocketBuilder2
import org.llm4s.ragbox.RagboxApp
import org.llm4s.ragbox.auth._
import org.llm4s.ragbox.config._
import org.llm4s.ragbox.testkit.PgVectorFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIStringSyntax

/**
 * The hardening of #15 through the FULL application stack (the `HttpApp` `Main` serves, built
 * by [[RagboxApp]] over a real pgvector database): CORS, the bootstrap admin, rate limiting
 * in front of authentication, login throttling, streamed body limits, JWT claims, password
 * hash upgrades, /metrics protection and static path traversal.
 */
class SecurityHardeningStackSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private val ApiKey = "hardening-api-key-0123456789"
  private val AdminPassword = "hardening-admin-password"

  private def auth(mode: AuthMode): AuthConfig = AuthConfig(
    mode = mode,
    basic = BasicAuthConfig("admin", Some(AdminPassword)),
    oauth = None,
    jwtSecret = "jwt-secret-for-hardening-stack-spec-0123456789",
    jwtSecretExplicitlySet = true,
    jwtExpiration = 3600L,
    passwordHashIterations = 10000
  )

  private val openNoKey = SecurityConfig(apiKey = None, auth = auth(AuthMode.Open))
  private val openWithKey = SecurityConfig(apiKey = Some(ApiKey), auth = auth(AuthMode.Open))
  private val basic = SecurityConfig(apiKey = None, auth = auth(AuthMode.Basic))

  private def production(
    rateLimit: RateLimitConfig = RateLimitConfig(enabled = false, maxRequests = 100, windowSeconds = 60),
    maxBodyMb: Int = 10,
    cors: CorsConfig = CorsConfig()
  ): ProductionConfig = ProductionConfig(
    rateLimit = rateLimit,
    requestSize = RequestSizeConfig(enabled = true, maxBodySizeMb = maxBodyMb),
    shutdown = ShutdownConfig(timeoutSeconds = 30, drainConnectionsSeconds = 5),
    cors = cors
  )

  private def withApp[A](
    db: DatabaseConfig,
    security: SecurityConfig,
    prod: ProductionConfig = production(),
    metrics: MetricsConfig = MetricsConfig(enabled = false)
  )(test: (HttpApp[IO], RagboxApp) => IO[A]): A =
    (for {
      ds <- pool(db)
      rag <- permissionService(db)
      app <- RagboxApp.resource(testConfig(db).copy(security = security, production = prod, metrics = metrics), ds, rag)
    } yield app).use { app =>
      WebSocketBuilder2[IO].flatMap(wsb => test(app.httpApp(wsb), app))
    }.unsafeRunSync()

  private def get(path: String): Request[IO] = Request[IO](Method.GET, Uri.unsafeFromString(path))

  private def withKey(req: Request[IO]): Request[IO] = req.putHeaders(Header.Raw(ci"X-API-Key", ApiKey))

  private def bearer(token: String)(req: Request[IO]): Request[IO] =
    req.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

  private def fromPeer(ip: String)(req: Request[IO]): Request[IO] =
    req.withAttribute(
      Request.Keys.ConnectionInfo,
      Request.Connection(SocketAddress(ipv4"127.0.0.1", port"8080"), SocketAddress(IpAddress.fromString(ip).get, port"40000"), secure = false)
    )

  private def loginReq(user: String, password: String): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/auth/login"))
      .withEntity(Json.obj("username" -> user.asJson, "password" -> password.asJson))

  private def status(app: HttpApp[IO], req: Request[IO]): IO[Status] = app.run(req).map(_.status)

  private def login(app: HttpApp[IO], user: String, password: String): IO[(Status, Option[String])] =
    app.run(loginReq(user, password)).flatMap { r =>
      r.as[Json].handleError(_ => Json.Null).map(j => r.status -> j.hcursor.get[String]("token").toOption)
    }

  // ---------------------------------------------------------------- CORS

  private def allowOrigin(app: HttpApp[IO], origin: String): IO[Option[String]] =
    app.run(get("/health").putHeaders(Header.Raw(ci"Origin", origin)))
      .map(_.headers.get(ci"Access-Control-Allow-Origin").map(_.head.value))

  "CORS" should "send no CORS headers by default, so browsers block cross-origin reads" in withContainers { pg =>
    withApp(freshDatabase(pg), openNoKey) { (app, _) =>
      for {
        evil <- allowOrigin(app, "https://evil.example")
        preflight <- app.run(Request[IO](Method.OPTIONS, Uri.unsafeFromString("/api/v1/stats")).putHeaders(
          Header.Raw(ci"Origin", "https://evil.example"),
          Header.Raw(ci"Access-Control-Request-Method", "POST")
        ))
        sameOrigin <- status(app, get("/api/v1/stats"))
      } yield {
        evil shouldBe None
        preflight.headers.get(ci"Access-Control-Allow-Origin") shouldBe None
        sameOrigin shouldBe Status.Ok
      }
    }
  }

  it should "allow exactly the configured origins" in withContainers { pg =>
    val cors = CorsConfig(allowedOrigins = Seq("https://app.example.com"), allowAllOrigins = true)
    withApp(freshDatabase(pg), openNoKey, production(cors = cors)) { (app, _) =>
      for {
        allowed <- allowOrigin(app, "https://app.example.com")
        evil <- allowOrigin(app, "https://evil.example")
      } yield {
        allowed shouldBe Some("https://app.example.com")
        evil shouldBe None
      }
    }
  }

  it should "allow any origin with ALLOWED_ORIGINS=*" in withContainers { pg =>
    withApp(freshDatabase(pg), openNoKey, production(cors = CorsConfig(allowedOrigins = Seq("*")))) { (app, _) =>
      allowOrigin(app, "https://anywhere.example").map(_ shouldBe Some("*"))
    }
  }

  // ---------------------------------------------------------------- bootstrap admin

  "The bootstrap admin" should "not be created with a default password in open mode" in withContainers { pg =>
    withApp(freshDatabase(pg), openNoKey) { (_, app) =>
      app.userRegistry.count().map(_ shouldBe 0)
    }
  }

  it should "have the old default password 'admin' replaced by ADMIN_PASSWORD when basic mode starts" in withContainers { pg =>
    val db = freshDatabase(pg)
    // An earlier version seeded admin/admin in open mode
    withApp(db, openNoKey) { (_, app) =>
      app.userRegistry.create("admin", PasswordHashing.legacyFormat("admin"), UserRole.Admin).void
    }
    withApp(db, basic) { (http, app) =>
      for {
        oldDefault <- login(http, "admin", "admin")
        configured <- login(http, "admin", AdminPassword)
        stored <- app.userRegistry.getByUsername("admin")
      } yield {
        oldDefault._1 shouldBe Status.Unauthorized
        configured._1 shouldBe Status.Ok
        stored.get.passwordHash should startWith("pbkdf2-sha256$")
      }
    }
  }

  // ---------------------------------------------------------------- passwords

  "Password hashes" should "be upgraded from the legacy format on the next successful login" in withContainers { pg =>
    withApp(freshDatabase(pg), basic) { (http, app) =>
      for {
        _ <- app.userRegistry.create("legacy", PasswordHashing.legacyFormat("legacy-password-1"), UserRole.User)
        wrong <- login(http, "legacy", "not-the-password")
        stillLegacy <- app.userRegistry.getByUsername("legacy")
        right <- login(http, "legacy", "legacy-password-1")
        upgraded <- app.userRegistry.getByUsername("legacy")
        again <- login(http, "legacy", "legacy-password-1")
      } yield {
        wrong._1 shouldBe Status.Unauthorized
        stillLegacy.get.passwordHash should not startWith "pbkdf2"
        right._1 shouldBe Status.Ok
        upgraded.get.passwordHash should startWith("pbkdf2-sha256$10000$")
        again._1 shouldBe Status.Ok
      }
    }
  }

  "The password policy" should "answer 400 for empty and short passwords" in withContainers { pg =>
    withApp(freshDatabase(pg), basic) { (http, _) =>
      for {
        token <- login(http, "admin", AdminPassword).map(_._2.get)
        create = (pw: String) => status(http, bearer(token)(Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/users"))
          .withEntity(Json.obj("username" -> "newbie".asJson, "password" -> pw.asJson))))
        empty <- create("")
        short <- create("short")
        ok <- create("a-sufficiently-long-password")
        emptyLogin <- login(http, "admin", "")
      } yield {
        empty shouldBe Status.BadRequest
        short shouldBe Status.BadRequest
        ok shouldBe Status.Created
        emptyLogin._1 shouldBe Status.Unauthorized
      }
    }
  }

  // ---------------------------------------------------------------- JWT claims

  "JWTs" should "be rejected when they carry another audience, even with the right secret" in withContainers { pg =>
    withApp(freshDatabase(pg), basic) { (http, app) =>
      for {
        admin <- app.userRegistry.getByUsername("admin").map(_.get)
        foreign = new AuthService(app.userRegistry, basic.auth.copy(jwtAudience = "another-service")).generateToken(admin)
        own = app.authService.generateToken(admin)
        foreignStatus <- status(http, bearer(foreign)(get("/api/v1/stats")))
        ownStatus <- status(http, bearer(own)(get("/api/v1/stats")))
      } yield {
        foreignStatus shouldBe Status.Unauthorized
        ownStatus shouldBe Status.Ok
      }
    }
  }

  // ---------------------------------------------------------------- rate limiting

  "Rate limiting" should "count requests that fail authentication" in withContainers { pg =>
    val rl = RateLimitConfig(enabled = true, maxRequests = 3, windowSeconds = 60)
    withApp(freshDatabase(pg), openWithKey, production(rateLimit = rl)) { (http, _) =>
      val attacker: Request[IO] => Request[IO] = fromPeer("203.0.113.10")
      for {
        failures <- (1 to 3).toList.traverse(_ => status(http, attacker(get("/api/v1/stats").putHeaders(Header.Raw(ci"X-API-Key", "guess")))))
        nowWithKey <- status(http, attacker(withKey(get("/api/v1/stats"))))
        other <- status(http, fromPeer("203.0.113.11")(withKey(get("/api/v1/stats"))))
        health <- status(http, attacker(get("/health")))
      } yield {
        failures shouldBe List.fill(3)(Status.Unauthorized)
        nowWithKey shouldBe Status.TooManyRequests
        other shouldBe Status.Ok
        health shouldBe Status.Ok
      }
    }
  }

  it should "ignore X-Forwarded-For from untrusted peers and honour it from trusted proxies" in withContainers { pg =>
    val rl = RateLimitConfig(enabled = true, maxRequests = 2, windowSeconds = 60, trustedProxies = Seq("10.0.0.0/8"))
    withApp(freshDatabase(pg), openNoKey, production(rateLimit = rl)) { (http, _) =>
      def xff(ip: String)(req: Request[IO]) = req.putHeaders(Header.Raw(ci"X-Forwarded-For", ip))
      for {
        spoofed <- (1 to 3).toList.traverse(i => status(http, fromPeer("203.0.113.20")(xff(s"198.51.100.$i")(get("/api/v1/stats")))))
        proxied <- (1 to 3).toList.traverse(i => status(http, fromPeer("10.0.0.1")(xff(s"198.51.100.$i")(get("/api/v1/stats")))))
      } yield {
        spoofed shouldBe List(Status.Ok, Status.Ok, Status.TooManyRequests)
        proxied shouldBe List(Status.Ok, Status.Ok, Status.Ok)
      }
    }
  }

  "Login throttling" should "lock a username after repeated failures, independently of the general limit" in withContainers { pg =>
    val rl = RateLimitConfig(enabled = false, maxRequests = 100, windowSeconds = 60, loginMaxAttempts = 3)
    withApp(freshDatabase(pg), basic, production(rateLimit = rl)) { (http, _) =>
      for {
        failures <- (1 to 3).toList.traverse(i => http.run(fromPeer(s"203.0.113.$i")(loginReq("admin", "wrong"))).map(_.status))
        locked <- http.run(fromPeer("203.0.113.99")(loginReq("admin", AdminPassword)))
      } yield {
        failures shouldBe List.fill(3)(Status.Unauthorized)
        locked.status shouldBe Status.TooManyRequests
        locked.headers.get(ci"Retry-After").map(_.head.value) shouldBe Some("300")
      }
    }
  }

  // ---------------------------------------------------------------- request size

  "The body limit" should "apply to chunked bodies without a Content-Length, and to declared lengths for every method" in withContainers { pg =>
    withApp(freshDatabase(pg), openNoKey, production(maxBodyMb = 1)) { (http, _) =>
      def chunked(method: Method, path: String, bytes: Int): Request[IO] =
        Request[IO](method, Uri.unsafeFromString(path))
          // an unterminated JSON string, so a streaming JSON parser keeps reading
          .withBodyStream(Stream.emits("""{"content":"""".getBytes.toSeq ++ Seq.fill(bytes)('a'.toByte)).chunkN(8192).flatMap(Stream.chunk))
          .withContentType(headers.`Content-Type`(MediaType.application.json))
      val overLimit = 1024 * 1024 + 1
      for {
        post <- status(http, chunked(Method.POST, "/api/v1/documents", overLimit))
        delete <- status(http, chunked(Method.DELETE, "/api/v1/documents/some-doc", overLimit).putHeaders(headers.`Content-Length`(overLimit.toLong)))
        login <- status(http, chunked(Method.POST, "/api/v1/auth/login", overLimit))
        small <- status(http, Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/documents"))
          .withEntity(Json.obj("content" -> "hello".asJson, "metadata" -> Json.obj())))
      } yield {
        post shouldBe Status.PayloadTooLarge
        delete shouldBe Status.PayloadTooLarge
        login shouldBe Status.PayloadTooLarge
        small should not be Status.PayloadTooLarge
      }
    }
  }

  // ---------------------------------------------------------------- /metrics

  "/metrics" should "be for administrators only by default" in withContainers { pg =>
    withApp(freshDatabase(pg), openWithKey, metrics = MetricsConfig(enabled = true)) { (http, _) =>
      for {
        anonymous <- status(http, get("/metrics"))
        admin <- status(http, withKey(get("/metrics")))
        viaBearer <- status(http, bearer(ApiKey)(get("/metrics")))
      } yield {
        anonymous shouldBe Status.Unauthorized
        admin shouldBe Status.Ok
        viaBearer shouldBe Status.Ok
      }
    }
  }

  it should "refuse non-admin users" in withContainers { pg =>
    withApp(freshDatabase(pg), basic, metrics = MetricsConfig(enabled = true)) { (http, app) =>
      for {
        adminToken <- login(http, "admin", AdminPassword).map(_._2.get)
        _ <- app.authService.createUser("viewer", "viewer-password-1", UserRole.User)
        userToken <- login(http, "viewer", "viewer-password-1").map(_._2.get)
        asAdmin <- status(http, bearer(adminToken)(get("/metrics")))
        asUser <- status(http, bearer(userToken)(get("/metrics")))
      } yield {
        asAdmin shouldBe Status.Ok
        asUser shouldBe Status.Forbidden
      }
    }
  }

  it should "stay public with METRICS_REQUIRE_AUTH=false" in withContainers { pg =>
    withApp(freshDatabase(pg), openWithKey, metrics = MetricsConfig(enabled = true, requireAuth = false)) { (http, _) =>
      status(http, get("/metrics")).map(_ shouldBe Status.Ok)
    }
  }

  // ---------------------------------------------------------------- ?api_key and static paths

  "Credentials in query strings" should "only be accepted as ?access_token= on /ws" in withContainers { pg =>
    withApp(freshDatabase(pg), openWithKey) { (http, _) =>
      for {
        apiKeyParam <- status(http, get(s"/api/v1/stats?api_key=$ApiKey"))
        wsApiKeyParam <- status(http, get(s"/ws?api_key=$ApiKey"))
        wsToken <- status(http, get(s"/ws?access_token=$ApiKey"))
      } yield {
        apiKeyParam shouldBe Status.Unauthorized
        wsApiKeyParam shouldBe Status.Unauthorized
        wsToken should not be Status.Unauthorized
      }
    }
  }

  // ---------------------------------------------------------------- WebSocket origin (#45)

  private def ws(origin: Option[String], host: String = "localhost:8080", extra: Seq[Header.Raw] = Nil): Request[IO] =
    get("/ws").putHeaders((Header.Raw(ci"Host", host) :: origin.map(Header.Raw(ci"Origin", _)).toList) ++ extra.toList)

  "The /ws handshake" should "be refused for a cross-site Origin, even when authenticated" in withContainers { pg =>
    withApp(freshDatabase(pg), openNoKey) { (http, _) =>
      for {
        evil <- http.run(ws(Some("https://evil.example")))
        evilBody <- evil.as[Json]
        nullOrigin <- status(http, ws(Some("null")))
        lookalike <- status(http, ws(Some("http://localhost:8080.evil.example")))
      } yield {
        evil.status shouldBe Status.Forbidden
        evilBody.hcursor.get[String]("error") shouldBe Right("forbidden")
        nullOrigin shouldBe Status.Forbidden
        lookalike shouldBe Status.Forbidden
      }
    }
  }

  it should "accept the server's own origin, also behind a TLS-terminating proxy" in withContainers { pg =>
    withApp(freshDatabase(pg), openNoKey) { (http, _) =>
      for {
        same <- status(http, ws(Some("http://localhost:8080")))
        caseInsensitive <- status(http, ws(Some("http://LocalHost:8080")))
        tls <- status(http, ws(Some("https://rag.example.com"), host = "rag.example.com"))
        tlsExplicitPort <- status(http, ws(Some("https://rag.example.com"), host = "rag.example.com:443"))
        forwarded <- status(http, ws(
          Some("https://rag.example.com"), host = "ragbox:8080",
          extra = Seq(Header.Raw(ci"X-Forwarded-Host", "rag.example.com"))
        ))
        otherPort <- status(http, ws(Some("http://localhost:3000")))
      } yield {
        same should not be Status.Forbidden
        caseInsensitive should not be Status.Forbidden
        tls should not be Status.Forbidden
        tlsExplicitPort should not be Status.Forbidden
        forwarded should not be Status.Forbidden
        otherPort shouldBe Status.Forbidden
      }
    }
  }

  it should "accept origins in ALLOWED_ORIGINS and only those" in withContainers { pg =>
    val cors = CorsConfig(allowedOrigins = Seq("https://app.example.com"))
    withApp(freshDatabase(pg), openNoKey, production(cors = cors)) { (http, _) =>
      for {
        allowed <- status(http, ws(Some("https://app.example.com")))
        other <- status(http, ws(Some("https://evil.example")))
      } yield {
        allowed should not be Status.Forbidden
        other shouldBe Status.Forbidden
      }
    }
  }

  it should "keep working for non-browser clients (no Origin) with token auth" in withContainers { pg =>
    withApp(freshDatabase(pg), openWithKey) { (http, _) =>
      for {
        noCredential <- status(http, ws(None))
        token <- status(http, ws(None).withUri(Uri.unsafeFromString(s"/ws?access_token=$ApiKey")))
        header <- status(http, withKey(ws(None)))
        crossSiteWithToken <- status(http, withKey(ws(Some("https://evil.example"))))
      } yield {
        noCredential shouldBe Status.Unauthorized
        token should (not be Status.Unauthorized and not be Status.Forbidden)
        header should (not be Status.Unauthorized and not be Status.Forbidden)
        crossSiteWithToken shouldBe Status.Forbidden
      }
    }
  }

  "Admin UI paths" should "reject encoded traversal segments" in withContainers { pg =>
    withApp(freshDatabase(pg), openNoKey) { (http, _) =>
      for {
        dotdot <- status(http, get("/admin/%2e%2e/application.conf"))
        slash <- status(http, get("/admin/..%2f..%2fapplication.conf"))
      } yield {
        dotdot shouldBe Status.BadRequest
        slash shouldBe Status.BadRequest
      }
    }
  }
}
