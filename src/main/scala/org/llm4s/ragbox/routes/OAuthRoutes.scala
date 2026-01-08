package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Encoder, Json}
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Set-Cookie`, `WWW-Authenticate`}
import org.http4s.Challenge
import org.llm4s.ragbox.auth.*
import org.llm4s.ragbox.model.ErrorResponse
import org.llm4s.ragbox.model.Codecs.given

/**
 * HTTP routes for OAuth2/OIDC authentication.
 *
 * Endpoints:
 * - GET  /api/v1/oauth/login     - Initiate OAuth flow
 * - GET  /api/v1/oauth/callback  - Handle provider callback
 * - POST /api/v1/oauth/logout    - End session
 * - GET  /api/v1/oauth/userinfo  - Get current user info
 */
class OAuthRoutes(
    oidcService: OidcService[IO],
    sessionConfig: OAuthSessionConfig
):

  private object RedirectAfterParam extends OptionalQueryParamDecoderMatcher[String]("redirect_after")
  private object CodeParam extends QueryParamDecoderMatcher[String]("code")
  private object StateParam extends QueryParamDecoderMatcher[String]("state")
  private object ErrorParam extends OptionalQueryParamDecoderMatcher[String]("error")
  private object ErrorDescParam extends OptionalQueryParamDecoderMatcher[String]("error_description")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ============================================================
    // GET /api/v1/oauth/login - Initiate OAuth login flow
    // ============================================================
    case GET -> Root / "api" / "v1" / "oauth" / "login" :? RedirectAfterParam(redirectAfter) =>
      for
        response <- oidcService.initiateLogin(redirectAfter)
        result <- Ok(response.asJson)
      yield result

    // ============================================================
    // GET /api/v1/oauth/callback - Handle authorization callback
    // ============================================================
    case req @ GET -> Root / "api" / "v1" / "oauth" / "callback"
        :? CodeParam(code) +& StateParam(state) +& ErrorParam(errorOpt) +& ErrorDescParam(errorDescOpt) =>

      // Check for error response from provider
      errorOpt match
        case Some(error) =>
          val desc = errorDescOpt.getOrElse("Authentication failed")
          BadRequest(ErrorResponse.badRequest(s"OAuth error: $error - $desc").asJson)

        case None =>
          oidcService.handleCallback(code, state).flatMap {
            case Right(sessionData) =>
              // Create session cookie
              val cookie = createSessionCookie(sessionData.sessionId)

              // Check if there's a redirect_after URL stored in state
              // For now, redirect to admin UI root
              val redirectUri = Uri.unsafeFromString("/admin/")

              // Redirect with session cookie
              SeeOther(Location(redirectUri)).map(_.addCookie(cookie))

            case Left(OidcError.InvalidState(msg)) =>
              BadRequest(ErrorResponse.badRequest(s"Invalid state: $msg").asJson)

            case Left(OidcError.StateExpired(msg)) =>
              BadRequest(ErrorResponse.badRequest(s"State expired: $msg").asJson)

            case Left(OidcError.TokenExpired(msg)) =>
              Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "ragbox")))
                .map(_.withEntity(ErrorResponse.unauthorized(s"Token expired: $msg").asJson))

            case Left(OidcError.InvalidToken(msg)) =>
              Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "ragbox")))
                .map(_.withEntity(ErrorResponse.unauthorized(s"Invalid token: $msg").asJson))

            case Left(error) =>
              InternalServerError(
                ErrorResponse.internalError(s"Authentication failed: ${error.message}").asJson
              )
          }

    // Handle callback without code (error case)
    case GET -> Root / "api" / "v1" / "oauth" / "callback" :? ErrorParam(Some(error)) +& ErrorDescParam(errorDescOpt) =>
      val desc = errorDescOpt.getOrElse("Authentication failed")
      BadRequest(ErrorResponse.badRequest(s"OAuth error: $error - $desc").asJson)

    // ============================================================
    // POST /api/v1/oauth/logout - End session
    // ============================================================
    case req @ POST -> Root / "api" / "v1" / "oauth" / "logout" =>
      // Extract session ID from cookie
      val sessionIdOpt = req.cookies.find(_.name == sessionConfig.cookieName).map(_.content)

      sessionIdOpt match
        case Some(sessionId) =>
          for
            _ <- oidcService.logout(sessionId)
            cookie = clearSessionCookie()
            result <- Ok(Json.obj("message" -> Json.fromString("Logged out successfully")))
              .map(_.addCookie(cookie))
          yield result

        case None =>
          Ok(Json.obj("message" -> Json.fromString("No active session")))

    // ============================================================
    // GET /api/v1/oauth/userinfo - Get current user info
    // ============================================================
    case req @ GET -> Root / "api" / "v1" / "oauth" / "userinfo" =>
      val sessionIdOpt = req.cookies.find(_.name == sessionConfig.cookieName).map(_.content)

      sessionIdOpt match
        case Some(sessionId) =>
          oidcService.validateSession(sessionId).flatMap {
            case Some(session) =>
              val userInfo = OAuthUserInfo(
                userId = session.userId,
                email = session.email,
                name = session.name,
                groups = session.groups
              )
              Ok(userInfo.asJson)

            case None =>
              Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "ragbox")))
                .map(_.withEntity(ErrorResponse.unauthorized("Invalid or expired session").asJson))
          }

        case None =>
          Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "ragbox")))
            .map(_.withEntity(ErrorResponse.unauthorized("No session cookie").asJson))
  }

  private def createSessionCookie(sessionId: String): ResponseCookie =
    ResponseCookie(
      name = sessionConfig.cookieName,
      content = sessionId,
      maxAge = Some(sessionConfig.cookieMaxAge.toLong),
      path = Some("/"),
      secure = sessionConfig.cookieSecure,
      httpOnly = true,
      sameSite = Some(SameSite.Lax)
    )

  private def clearSessionCookie(): ResponseCookie =
    ResponseCookie(
      name = sessionConfig.cookieName,
      content = "",
      maxAge = Some(0),
      path = Some("/"),
      secure = sessionConfig.cookieSecure,
      httpOnly = true
    )

object OAuthRoutes:
  def apply(oidcService: OidcService[IO], sessionConfig: OAuthSessionConfig): OAuthRoutes =
    new OAuthRoutes(oidcService, sessionConfig)
