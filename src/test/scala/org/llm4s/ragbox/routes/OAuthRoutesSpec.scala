package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.llm4s.ragbox.auth.*
import org.llm4s.rag.permissions.UserAuthorization
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class OAuthRoutesSpec extends AsyncWordSpec with AsyncIOSpec with Matchers:

  private val sessionConfig = OAuthSessionConfig(
    cookieName = "ragbox_session",
    cookieSecure = false,
    cookieMaxAge = 86400
  )

  private def run(routes: HttpRoutes[IO], request: Request[IO]): IO[Response[IO]] =
    routes.run(request).value.map(_.getOrElse(fail("Route not matched")))

  "GET /api/v1/oauth/login" should {

    "redirect to the IdP and set a short-lived HttpOnly, SameSite=Lax binding cookie" in {
      val mockOidcService = new MockOidcService()
      val routes = OAuthRoutes(mockOidcService, sessionConfig, stateTtlSeconds = 600).routes

      run(routes, Request[IO](Method.GET, uri"/api/v1/oauth/login")).map { response =>
        response.status shouldBe Status.Found
        response.headers.get[headers.Location].map(_.uri.renderString) shouldBe
          Some("https://provider.example.com/authorize?client_id=test")
        val cookie = response.cookies.find(_.name == sessionConfig.preAuthCookieName)
          .getOrElse(fail("pre-auth cookie not set"))
        cookie.content shouldBe "mock-binding-456"
        cookie.httpOnly shouldBe true
        cookie.secure shouldBe false // follows sessionConfig.cookieSecure
        cookie.sameSite shouldBe Some(SameSite.Lax)
        cookie.maxAge shouldBe Some(600L)
        cookie.path shouldBe Some("/api/v1/oauth")
        // The state must not be handed to the client as JSON
        response.headers.get[headers.`Content-Type`] shouldBe None
      }
    }

    "mark the binding cookie Secure when session cookies are secure" in {
      val routes = OAuthRoutes(new MockOidcService(), sessionConfig.copy(cookieSecure = true)).routes
      run(routes, Request[IO](Method.GET, uri"/api/v1/oauth/login")).map { response =>
        response.cookies.find(_.name == sessionConfig.preAuthCookieName).map(_.secure) shouldBe Some(true)
      }
    }
  }

  "GET /api/v1/oauth/callback" should {

    "reject a callback without the binding cookie and not call the service" in {
      val mockOidcService = new MockOidcService()
      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes

      run(routes, Request[IO](Method.GET, uri"/api/v1/oauth/callback?code=c&state=s")).map { response =>
        response.status shouldBe Status.BadRequest
        mockOidcService.callbackCalls shouldBe empty
        response.cookies.find(_.name == sessionConfig.cookieName) shouldBe None
      }
    }

    "pass the binding cookie to the service, set the session cookie and clear the binding cookie" in {
      val mockOidcService = new MockOidcService()
      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes
      val request = Request[IO](Method.GET, uri"/api/v1/oauth/callback?code=c&state=s")
        .addCookie(sessionConfig.preAuthCookieName, "binding-1")

      run(routes, request).map { response =>
        response.status shouldBe Status.SeeOther
        mockOidcService.callbackCalls shouldBe List(("c", "s", "binding-1"))
        response.cookies.find(_.name == sessionConfig.cookieName).map(_.content) shouldBe Some("mock-session")
        response.cookies.find(_.name == sessionConfig.preAuthCookieName).flatMap(_.maxAge) shouldBe Some(0L)
      }
    }

    "rotate the session: invalidate any pre-existing session id presented by the browser" in {
      val mockOidcService = new MockOidcService()
      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes
      val request = Request[IO](Method.GET, uri"/api/v1/oauth/callback?code=c&state=s")
        .addCookie(sessionConfig.preAuthCookieName, "binding-1")
        .addCookie(sessionConfig.cookieName, "fixated-session-id")

      run(routes, request).map { response =>
        response.status shouldBe Status.SeeOther
        mockOidcService.loggedOut shouldBe List("fixated-session-id")
        response.cookies.find(_.name == sessionConfig.cookieName).map(_.content) shouldBe Some("mock-session")
      }
    }

    "return 400 and clear the binding cookie when the service rejects the state" in {
      val mockOidcService = new MockOidcService()
      mockOidcService.setCallbackResponse(Left(OidcError.InvalidState("binding mismatch")))
      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes
      val request = Request[IO](Method.GET, uri"/api/v1/oauth/callback?code=c&state=s")
        .addCookie(sessionConfig.preAuthCookieName, "binding-1")

      run(routes, request).map { response =>
        response.status shouldBe Status.BadRequest
        response.cookies.find(_.name == sessionConfig.cookieName) shouldBe None
        response.cookies.find(_.name == sessionConfig.preAuthCookieName).flatMap(_.maxAge) shouldBe Some(0L)
      }
    }

    "return 401 for an ID token nonce mismatch" in {
      val mockOidcService = new MockOidcService()
      mockOidcService.setCallbackResponse(Left(OidcError.InvalidNonce("nonce mismatch")))
      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes
      val request = Request[IO](Method.GET, uri"/api/v1/oauth/callback?code=c&state=s")
        .addCookie(sessionConfig.preAuthCookieName, "binding-1")

      run(routes, request).map(_.status shouldBe Status.Unauthorized)
    }

    "return 403 when the identity cannot be established (e.g. unverified email)" in {
      val mockOidcService = new MockOidcService()
      mockOidcService.setCallbackResponse(Left(OidcError.IdentityError("email not verified")))
      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes
      val request = Request[IO](Method.GET, uri"/api/v1/oauth/callback?code=c&state=s")
        .addCookie(sessionConfig.preAuthCookieName, "binding-1")

      run(routes, request).map(_.status shouldBe Status.Forbidden)
    }
  }

  "POST /api/v1/oauth/logout" should {

    "return success with cleared cookie when session exists" in {
      val mockOidcService = new MockOidcService()
      val sessionConfig = OAuthSessionConfig(
        cookieName = "ragbox_session",
        cookieSecure = false,
        cookieMaxAge = 86400
      )

      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes

      val request = Request[IO](Method.POST, uri"/api/v1/oauth/logout")
        .addCookie("ragbox_session", "test-session-id")

      routes.run(request).value.flatMap {
        case Some(response) =>
          response.as[Json].map { json =>
            response.status shouldBe Status.Ok
            val message = json.hcursor.get[String]("message").toOption
            message shouldBe Some("Logged out successfully")
            // Check that cookie is being cleared
            val cookies = response.cookies
            cookies.find(_.name == "ragbox_session").foreach { cookie =>
              cookie.maxAge shouldBe Some(0)
            }
          }
        case None =>
          fail("Route not matched")
      }
    }

    "return success message when no session exists" in {
      val mockOidcService = new MockOidcService()
      val sessionConfig = OAuthSessionConfig(
        cookieName = "ragbox_session",
        cookieSecure = false,
        cookieMaxAge = 86400
      )

      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes

      val request = Request[IO](Method.POST, uri"/api/v1/oauth/logout")

      routes.run(request).value.flatMap {
        case Some(response) =>
          response.as[Json].map { json =>
            response.status shouldBe Status.Ok
            val message = json.hcursor.get[String]("message").toOption
            message shouldBe Some("No active session")
          }
        case None =>
          fail("Route not matched")
      }
    }
  }

  "GET /api/v1/oauth/userinfo" should {

    "return user info when session is valid" in {
      val mockOidcService = new MockOidcService()
      mockOidcService.setValidSession(Some(OAuthSessionData(
        sessionId = "test-session",
        userId = "user@example.com",
        email = Some("user@example.com"),
        name = Some("Test User"),
        groups = List("users", "admins"),
        provider = "test",
        expiresAt = System.currentTimeMillis() + 3600000,
        createdAt = System.currentTimeMillis()
      )))

      val sessionConfig = OAuthSessionConfig(
        cookieName = "ragbox_session",
        cookieSecure = false,
        cookieMaxAge = 86400
      )

      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes

      val request = Request[IO](Method.GET, uri"/api/v1/oauth/userinfo")
        .addCookie("ragbox_session", "test-session")

      routes.run(request).value.flatMap {
        case Some(response) =>
          response.as[Json].map { json =>
            response.status shouldBe Status.Ok
            json.hcursor.get[String]("userId").toOption shouldBe Some("user@example.com")
            json.hcursor.get[String]("email").toOption shouldBe Some("user@example.com")
            json.hcursor.get[String]("name").toOption shouldBe Some("Test User")
            json.hcursor.get[List[String]]("groups").toOption shouldBe Some(List("users", "admins"))
          }
        case None =>
          fail("Route not matched")
      }
    }

    "return 401 when no session cookie" in {
      val mockOidcService = new MockOidcService()
      val sessionConfig = OAuthSessionConfig(
        cookieName = "ragbox_session",
        cookieSecure = false,
        cookieMaxAge = 86400
      )

      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes

      val request = Request[IO](Method.GET, uri"/api/v1/oauth/userinfo")

      routes.run(request).value.flatMap {
        case Some(response) =>
          IO.pure {
            response.status shouldBe Status.Unauthorized
          }
        case None =>
          fail("Route not matched")
      }
    }

    "return 401 when session is invalid" in {
      val mockOidcService = new MockOidcService()
      mockOidcService.setValidSession(None)

      val sessionConfig = OAuthSessionConfig(
        cookieName = "ragbox_session",
        cookieSecure = false,
        cookieMaxAge = 86400
      )

      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes

      val request = Request[IO](Method.GET, uri"/api/v1/oauth/userinfo")
        .addCookie("ragbox_session", "invalid-session")

      routes.run(request).value.flatMap {
        case Some(response) =>
          IO.pure {
            response.status shouldBe Status.Unauthorized
          }
        case None =>
          fail("Route not matched")
      }
    }
  }

/**
 * Mock OidcService for testing routes without actual OIDC flow.
 */
class MockOidcService extends OidcService[IO]:
  private var loginResponse = OAuthLoginResponse(
    authorizationUrl = "https://provider.example.com/authorize?client_id=test",
    state = "mock-state-123",
    browserBinding = "mock-binding-456"
  )
  @volatile var callbackCalls: List[(String, String, String)] = Nil
  @volatile var loggedOut: List[String] = Nil
  private var callbackResponse: Either[OidcError, OAuthCallbackResult] = Right(OAuthCallbackResult(OAuthSessionData(
    sessionId = "mock-session",
    userId = "test-user",
    email = Some("test@example.com"),
    name = Some("Test User"),
    groups = List.empty,
    provider = "mock",
    expiresAt = System.currentTimeMillis() + 3600000,
    createdAt = System.currentTimeMillis()
  ), None))
  private var validSession: Option[OAuthSessionData] = None
  private var authorization: Option[UserAuthorization] = None

  def setLoginResponse(response: OAuthLoginResponse): Unit =
    loginResponse = response

  def setCallbackResponse(response: Either[OidcError, OAuthCallbackResult]): Unit =
    callbackResponse = response

  def setValidSession(session: Option[OAuthSessionData]): Unit =
    validSession = session

  def setAuthorization(auth: Option[UserAuthorization]): Unit =
    authorization = auth

  override def initiateLogin(redirectAfterLogin: Option[String]): IO[OAuthLoginResponse] =
    IO.pure(loginResponse)

  override def handleCallback(
      code: String,
      state: String,
      browserBinding: String
  ): IO[Either[OidcError, OAuthCallbackResult]] =
    IO { callbackCalls = callbackCalls :+ ((code, state, browserBinding)) }.as(callbackResponse)

  override def validateSession(sessionId: String): IO[Option[OAuthSessionData]] =
    IO.pure(validSession)

  override def getAuthorization(sessionId: String): IO[Option[UserAuthorization]] =
    IO.pure(authorization)

  override def logout(sessionId: String): IO[Unit] =
    IO { loggedOut = loggedOut :+ sessionId }
