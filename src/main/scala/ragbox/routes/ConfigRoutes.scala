package ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import ragbox.model._
import ragbox.model.Codecs._
import ragbox.service.RAGService

/**
 * HTTP routes for configuration management.
 */
object ConfigRoutes {

  def routes(ragService: RAGService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /api/v1/config - Get current configuration
    case GET -> Root / "api" / "v1" / "config" =>
      for {
        config <- ragService.getConfigInfo
        response <- Ok(config.asJson)
      } yield response

    // PUT /api/v1/config - Update configuration (limited)
    case req @ PUT -> Root / "api" / "v1" / "config" =>
      // Note: Runtime configuration updates are limited
      // Most settings require restart
      BadRequest(ErrorResponse.badRequest(
        "Runtime configuration updates not supported. Restart with new environment variables."
      ).asJson)

    // GET /api/v1/config/providers - List available providers
    case GET -> Root / "api" / "v1" / "config" / "providers" =>
      for {
        providers <- ragService.getProviders
        response <- Ok(providers.asJson)
      } yield response
  }
}
