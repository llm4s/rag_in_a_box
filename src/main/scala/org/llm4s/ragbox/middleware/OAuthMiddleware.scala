package org.llm4s.ragbox.middleware

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`WWW-Authenticate`
import org.llm4s.ragbox.auth.{OAuthSessionConfig, OAuthSessionData, OidcService}
import org.llm4s.rag.permissions.UserAuthorization
import org.typelevel.vault.Key

/**
 * OAuth session authentication middleware.
 *
 * When enabled, requires all requests (except public endpoints) to include
 * a valid session cookie from a successful OAuth login.
 *
 * The validated session data is stored in the request attributes for use
 * by downstream handlers.
 */
object OAuthMiddleware:

  /** Vault key for storing session data in request attributes */
  val SessionDataKey: Key[OAuthSessionData] = Key.newKey[IO, OAuthSessionData].unsafeRunSync()

  /** Vault key for storing user authorization in request attributes */
  val AuthorizationKey: Key[UserAuthorization] = Key.newKey[IO, UserAuthorization].unsafeRunSync()

  /**
   * Create OAuth session authentication middleware.
   *
   * @param oidcService Service for session validation
   * @param sessionConfig Session cookie configuration
   * @return Middleware that enforces OAuth session authentication
   */
  def apply(
      oidcService: OidcService[IO],
      sessionConfig: OAuthSessionConfig
  )(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli { (request: Request[IO]) =>
      // Skip auth for public endpoints
      if isPublicEndpoint(request) then routes(request)
      else
        extractSessionId(request, sessionConfig.cookieName) match
          case Some(sessionId) =>
            OptionT(
              oidcService.validateSession(sessionId).flatMap {
                case Some(sessionData) =>
                  // Get authorization for the session
                  oidcService.getAuthorization(sessionId).flatMap {
                    case Some(auth) =>
                      // Store session data and auth in request attributes
                      val enrichedRequest = request
                        .withAttribute(SessionDataKey, sessionData)
                        .withAttribute(AuthorizationKey, auth)
                      routes(enrichedRequest).value

                    case None =>
                      // Session valid but couldn't get authorization
                      routes(request.withAttribute(SessionDataKey, sessionData)).value
                  }

                case None =>
                  // Session invalid or expired
                  Unauthorized(
                    `WWW-Authenticate`(Challenge("Bearer", "RAG in a Box API")),
                    ErrorResponse("Session expired or invalid. Please login again.")
                  ).map(Some(_))
              }
            )

          case None =>
            OptionT.liftF(
              Unauthorized(
                `WWW-Authenticate`(Challenge("Bearer", "RAG in a Box API")),
                ErrorResponse("Authentication required. Please login via /api/v1/oauth/login")
              )
            )
    }

  /**
   * Check if this is a public endpoint (exempt from auth).
   */
  private def isPublicEndpoint(request: Request[IO]): Boolean =
    val path = request.pathInfo.renderString
    path == "/health" ||
    path == "/health/ready" ||
    path == "/health/live" ||
    path == "/metrics" ||
    path.startsWith("/api/v1/oauth/") || // OAuth endpoints are public
    path.startsWith("/admin/") ||        // Static admin UI files
    path.startsWith("/public/")          // Other public files

  /**
   * Extract session ID from cookie.
   */
  private def extractSessionId(request: Request[IO], cookieName: String): Option[String] =
    request.cookies.find(_.name == cookieName).map(_.content)

  /**
   * Extract session data from request attributes.
   */
  def getSessionData(request: Request[IO]): Option[OAuthSessionData] =
    request.attributes.lookup(SessionDataKey)

  /**
   * Extract user authorization from request attributes.
   */
  def getAuthorization(request: Request[IO]): Option[UserAuthorization] =
    request.attributes.lookup(AuthorizationKey)

  /**
   * Simple error response helper.
   */
  private case class ErrorResponse(message: String)

  private object ErrorResponse:
    import io.circe.syntax.*
    import org.http4s.circe.*

    given io.circe.Encoder[ErrorResponse] =
      io.circe.Encoder.forProduct1("error")(_.message)

    given EntityEncoder[IO, ErrorResponse] =
      jsonEncoderOf[IO, ErrorResponse]
