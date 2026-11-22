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
 * - GET  /api/v1/oauth/login     - Initiate OAuth flow (302 to the provider)
 * - GET  /api/v1/oauth/callback  - Handle provider callback
 * - POST /api/v1/oauth/logout    - End session
 * - GET  /api/v1/oauth/userinfo  - Get current user info
 *
 * Login CSRF / session fixation protection: `/login` sets a short-lived HttpOnly,
 * SameSite=Lax pre-auth cookie holding a random browser-binding value whose hash is
 * stored with the OAuth state. `/callback` requires that cookie, and the service
 * rejects the callback unless it matches the state. On success any session id the
 * browser already presented is invalidated and a fresh one is issued.
 *
 * @param stateTtlSeconds lifetime of the pre-auth cookie; should equal `OAuthConfig.stateTtl`
 */
class OAuthRoutes(
    oidcService: OidcService[IO],
    sessionConfig: OAuthSessionConfig,
    stateTtlSeconds: Int = 300
):

  /** Pre-auth cookie is only needed by the OAuth endpoints */
  private val PreAuthCookiePath = "/api/v1/oauth"

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
        // Only keep a safe relative target; anything else falls back to /admin/ after login
        login <- oidcService.initiateLogin(redirectAfter.flatMap(OAuthRoutes.safeRedirect))
        authUri <- IO.fromEither(Uri.fromString(login.authorizationUrl))
        result <- Found(Location(authUri)).map(_.addCookie(createPreAuthCookie(login.browserBinding)))
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
          BadRequest(ErrorResponse.badRequest(s"OAuth error: $error - $desc").asJson).map(_.addCookie(clearPreAuthCookie()))

        case None =>
          req.cookies.find(_.name == sessionConfig.preAuthCookieName).map(_.content) match
            case None =>
              BadRequest(
                ErrorResponse.badRequest("Missing login cookie: start the login again from /api/v1/oauth/login").asJson
              ).map(_.addCookie(clearPreAuthCookie()))

            case Some(browserBinding) =>
              oidcService.handleCallback(code, state, browserBinding)
                .flatMap(result => callbackResponse(req, result))
                .map(_.addCookie(clearPreAuthCookie()))

    // Handle callback without code (error case)
    case GET -> Root / "api" / "v1" / "oauth" / "callback" :? ErrorParam(Some(error)) +& ErrorDescParam(errorDescOpt) =>
      val desc = errorDescOpt.getOrElse("Authentication failed")
      BadRequest(ErrorResponse.badRequest(s"OAuth error: $error - $desc").asJson).map(_.addCookie(clearPreAuthCookie()))

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

  private def callbackResponse(
      req: Request[IO],
      result: Either[OidcError, OAuthCallbackResult]
  ): IO[Response[IO]] =
    result match
      case Right(OAuthCallbackResult(sessionData, redirectAfter)) =>
        // Session fixation: invalidate any session id the browser already carried
        val previousSession = req.cookies.find(_.name == sessionConfig.cookieName).map(_.content)
        val rotate = previousSession.filter(_ != sessionData.sessionId).fold(IO.unit)(oidcService.logout)

        // Back to where the login started, if that is a safe relative path, else the admin UI
        val target = redirectAfter.flatMap(OAuthRoutes.safeRedirect).getOrElse(OAuthRoutes.DefaultRedirect)
        rotate *> SeeOther(Location(Uri.unsafeFromString(target))).map(_.addCookie(createSessionCookie(sessionData.sessionId)))

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

      case Left(OidcError.InvalidNonce(msg)) =>
        Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "ragbox")))
          .map(_.withEntity(ErrorResponse.unauthorized(s"Invalid token: $msg").asJson))

      case Left(OidcError.IdentityError(msg)) =>
        Forbidden(ErrorResponse("forbidden", s"Login rejected: $msg").asJson)

      case Left(error) =>
        InternalServerError(
          ErrorResponse.internalError(s"Authentication failed: ${error.message}").asJson
        )

  private def createPreAuthCookie(browserBinding: String): ResponseCookie =
    ResponseCookie(
      name = sessionConfig.preAuthCookieName,
      content = browserBinding,
      maxAge = Some(stateTtlSeconds.toLong),
      path = Some(PreAuthCookiePath),
      secure = sessionConfig.cookieSecure,
      httpOnly = true,
      // Lax (not Strict): the cookie must accompany the top-level redirect back from the IdP
      sameSite = Some(SameSite.Lax)
    )

  private def clearPreAuthCookie(): ResponseCookie =
    ResponseCookie(
      name = sessionConfig.preAuthCookieName,
      content = "",
      maxAge = Some(0),
      path = Some(PreAuthCookiePath),
      secure = sessionConfig.cookieSecure,
      httpOnly = true,
      sameSite = Some(SameSite.Lax)
    )

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

  /** Where the callback sends the browser when there is no (safe) `redirect_after`. */
  val DefaultRedirect = "/admin/"

  /**
   * `redirect_after` is attacker-controllable, so it is only honoured when it is a relative
   * path on this server: it must start with a single `/`, must not start with `//` or `/\`
   * (protocol-relative URLs), and must not contain a scheme, backslashes, or control characters.
   *
   * @return the path to redirect to, or None if it is not safe
   */
  def safeRedirect(target: String): Option[String] =
    val t = target.trim
    val ok =
      t.startsWith("/") &&
        !t.startsWith("//") &&
        !t.contains('\\') &&
        !t.exists(c => c.isControl || c.isWhitespace) &&
        !t.takeWhile(c => c != '?' && c != '#').contains(':') &&
        t.length <= 2048 &&
        Uri.fromString(t).toOption.exists(u => u.scheme.isEmpty && u.authority.isEmpty)
    Option.when(ok)(t)

  def apply(
      oidcService: OidcService[IO],
      sessionConfig: OAuthSessionConfig,
      stateTtlSeconds: Int = 300
  ): OAuthRoutes =
    new OAuthRoutes(oidcService, sessionConfig, stateTtlSeconds)
