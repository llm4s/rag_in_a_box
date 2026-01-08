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

  "GET /api/v1/oauth/login" should {

    "return authorization URL with state" in {
      val mockOidcService = new MockOidcService()
      val sessionConfig = OAuthSessionConfig(
        cookieName = "ragbox_session",
        cookieSecure = false,
        cookieMaxAge = 86400
      )

      val routes = OAuthRoutes(mockOidcService, sessionConfig).routes

      val request = Request[IO](Method.GET, uri"/api/v1/oauth/login")

      routes.run(request).value.flatMap {
        case Some(response) =>
          response.as[Json].map { json =>
            response.status shouldBe Status.Ok
            val authUrl = json.hcursor.get[String]("authorizationUrl").toOption
            val state = json.hcursor.get[String]("state").toOption
            authUrl should not be empty
            state should not be empty
          }
        case None =>
          fail("Route not matched")
      }
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
    state = "mock-state-123"
  )
  private var callbackResponse: Either[OidcError, OAuthSessionData] = Right(OAuthSessionData(
    sessionId = "mock-session",
    userId = "test-user",
    email = Some("test@example.com"),
    name = Some("Test User"),
    groups = List.empty,
    provider = "mock",
    expiresAt = System.currentTimeMillis() + 3600000,
    createdAt = System.currentTimeMillis()
  ))
  private var validSession: Option[OAuthSessionData] = None
  private var authorization: Option[UserAuthorization] = None

  def setLoginResponse(response: OAuthLoginResponse): Unit =
    loginResponse = response

  def setCallbackResponse(response: Either[OidcError, OAuthSessionData]): Unit =
    callbackResponse = response

  def setValidSession(session: Option[OAuthSessionData]): Unit =
    validSession = session

  def setAuthorization(auth: Option[UserAuthorization]): Unit =
    authorization = auth

  override def initiateLogin(redirectAfterLogin: Option[String]): IO[OAuthLoginResponse] =
    IO.pure(loginResponse)

  override def handleCallback(code: String, state: String): IO[Either[OidcError, OAuthSessionData]] =
    IO.pure(callbackResponse)

  override def validateSession(sessionId: String): IO[Option[OAuthSessionData]] =
    IO.pure(validSession)

  override def getAuthorization(sessionId: String): IO[Option[UserAuthorization]] =
    IO.pure(authorization)

  override def logout(sessionId: String): IO[Unit] =
    IO.unit
