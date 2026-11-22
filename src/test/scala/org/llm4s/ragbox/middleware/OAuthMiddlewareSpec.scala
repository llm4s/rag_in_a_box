package org.llm4s.ragbox.middleware

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.llm4s.rag.permissions.UserAuthorization
import org.llm4s.ragbox.auth.{OAuthSessionConfig, OAuthSessionData}
import org.llm4s.ragbox.routes.MockOidcService
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class OAuthMiddlewareSpec extends AsyncWordSpec with AsyncIOSpec with Matchers:

  private val sessionConfig = OAuthSessionConfig(cookieName = "ragbox_session", cookieSecure = false)

  private val session = OAuthSessionData(
    sessionId = "sess-1",
    userId = "https://idp|user-1",
    email = None,
    name = None,
    groups = Nil,
    provider = "test",
    expiresAt = System.currentTimeMillis() + 60000,
    createdAt = System.currentTimeMillis()
  )

  /** Protected route that reports whether it saw an authorization attribute. */
  private val inner: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ GET -> Root / "api" / "v1" / "secret" =>
    Ok(OAuthMiddleware.getAuthorization(req).isDefined.toString)
  }

  private def call(service: MockOidcService, withCookie: Boolean = true): IO[Response[IO]] =
    val base = Request[IO](Method.GET, uri"/api/v1/secret")
    val req = if withCookie then base.addCookie("ragbox_session", "sess-1") else base
    OAuthMiddleware(service, sessionConfig)(inner).orNotFound.run(req)

  "OAuthMiddleware" should {

    "return 401 when no session cookie is present" in {
      call(new MockOidcService(), withCookie = false).map(_.status shouldBe Status.Unauthorized)
    }

    "return 401 when the session is invalid" in {
      val service = new MockOidcService()
      service.setValidSession(None)
      call(service).map(_.status shouldBe Status.Unauthorized)
    }

    "return 403 (not pass through) when authorization cannot be resolved for a valid session" in {
      val service = new MockOidcService()
      service.setValidSession(Some(session))
      service.setAuthorization(None)
      call(service).map(_.status shouldBe Status.Forbidden)
    }

    "pass the request through with authorization attached when resolved" in {
      val service = new MockOidcService()
      service.setValidSession(Some(session))
      service.setAuthorization(Some(UserAuthorization.Anonymous))
      call(service).flatMap { response =>
        response.status shouldBe Status.Ok
        response.as[String].map(_ shouldBe "true")
      }
    }
  }
