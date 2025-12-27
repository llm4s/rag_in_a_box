package ragbox.middleware

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers.{Authorization, `WWW-Authenticate`}
import org.typelevel.ci.CIStringSyntax
import ragbox.config.SecurityConfig

/**
 * API key authentication middleware.
 *
 * When enabled, requires all requests (except health endpoints) to include
 * a valid API key via:
 * - X-API-Key header
 * - Authorization: Bearer <key> header
 * - ?api_key=<key> query parameter
 */
object AuthMiddleware {

  /**
   * Create authentication middleware.
   *
   * @param security Security configuration with API key
   * @return Middleware that enforces API key authentication
   */
  def apply(security: SecurityConfig)(routes: HttpRoutes[IO]): HttpRoutes[IO] = {
    if (!security.isEnabled) {
      // Auth disabled - pass through all requests
      routes
    } else {
      Kleisli { (request: Request[IO]) =>
        // Skip auth for health endpoints
        if (isHealthEndpoint(request)) {
          routes(request)
        } else {
          extractApiKey(request) match {
            case Some(key) if security.validateKey(key) =>
              routes(request)
            case Some(_) =>
              OptionT.liftF(Forbidden(ErrorResponse("Invalid API key")))
            case None =>
              OptionT.liftF(Unauthorized(
                `WWW-Authenticate`(Challenge("Bearer", "RAG in a Box API")),
                ErrorResponse("API key required. Use X-API-Key header, Authorization: Bearer, or ?api_key parameter")
              ))
          }
        }
      }
    }
  }

  /**
   * Check if this is a health endpoint (exempt from auth).
   */
  private def isHealthEndpoint(request: Request[IO]): Boolean = {
    val path = request.pathInfo.renderString
    path == "/health" || path == "/health/ready" || path == "/health/live" || path == "/metrics"
  }

  /**
   * Extract API key from request.
   */
  private def extractApiKey(request: Request[IO]): Option[String] = {
    // Try X-API-Key header first
    request.headers.get(ci"X-API-Key").map(_.head.value)
      // Try Authorization: Bearer header
      .orElse {
        request.headers.get[Authorization].flatMap { auth =>
          auth.credentials match {
            case Credentials.Token(AuthScheme.Bearer, token) => Some(token)
            case _ => None
          }
        }
      }
      // Try query parameter
      .orElse {
        request.params.get("api_key")
      }
  }

  /**
   * Simple error response helper.
   */
  private case class ErrorResponse(message: String)

  private object ErrorResponse {
    import io.circe.syntax._
    import org.http4s.circe._

    implicit val encoder: io.circe.Encoder[ErrorResponse] =
      io.circe.Encoder.forProduct1("error")(_.message)

    implicit val entityEncoder: EntityEncoder[IO, ErrorResponse] =
      jsonEncoderOf[IO, ErrorResponse]
  }
}
