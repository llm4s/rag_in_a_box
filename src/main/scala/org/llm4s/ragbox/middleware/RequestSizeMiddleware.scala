package org.llm4s.ragbox.middleware

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Length`
import org.typelevel.ci.CIStringSyntax

/**
 * Request size limiting middleware.
 *
 * Prevents oversized requests from consuming server resources.
 * Enforces Content-Length limits before reading request body.
 */
object RequestSizeMiddleware {

  /**
   * Size limit configuration.
   *
   * @param maxBodySize Maximum request body size in bytes
   * @param enabled Whether size limiting is enabled
   */
  final case class Config(
    maxBodySize: Long,
    enabled: Boolean
  )

  object Config {
    // Default: 10MB max body size
    val default: Config = Config(
      maxBodySize = 10L * 1024 * 1024,
      enabled = true
    )
  }

  /**
   * Create request size limiting middleware.
   *
   * @param config Size limit configuration
   * @return Middleware that enforces request size limits
   */
  def apply(config: Config)(routes: HttpRoutes[IO]): HttpRoutes[IO] = {
    if (!config.enabled) {
      routes
    } else {
      Kleisli { (request: Request[IO]) =>
        // Skip size check for GET/HEAD/DELETE requests (no body expected)
        if (request.method == Method.GET || request.method == Method.HEAD || request.method == Method.DELETE) {
          routes(request)
        } else {
          checkContentLength(request, config) match {
            case Right(_) =>
              routes(request)
            case Left(response) =>
              OptionT.liftF(IO.pure(response))
          }
        }
      }
    }
  }

  /**
   * Check Content-Length header against limit.
   */
  private def checkContentLength(request: Request[IO], config: Config): Either[Response[IO], Unit] = {
    request.headers.get[`Content-Length`] match {
      case Some(cl) if cl.length > config.maxBodySize =>
        Left(payloadTooLarge(config))
      case _ =>
        // No Content-Length or within limits - allow (streaming bodies handled separately)
        Right(())
    }
  }

  /**
   * Return 413 Payload Too Large response.
   */
  private def payloadTooLarge(config: Config): Response[IO] = {
    import io.circe.syntax._
    import org.http4s.circe._

    val maxSizeMB = config.maxBodySize / (1024 * 1024)
    val body = Map(
      "error" -> "Request body too large",
      "maxSizeBytes" -> config.maxBodySize.toString,
      "maxSizeMB" -> maxSizeMB.toString
    )

    Response[IO](Status.PayloadTooLarge)
      .withHeaders(Header.Raw(ci"X-Max-Body-Size", config.maxBodySize.toString))
      .withEntity(body.asJson)
  }

  /**
   * Format bytes as human-readable string.
   */
  def formatBytes(bytes: Long): String = {
    if (bytes < 1024) s"${bytes}B"
    else if (bytes < 1024 * 1024) f"${bytes / 1024.0}%.1fKB"
    else if (bytes < 1024 * 1024 * 1024) f"${bytes / (1024.0 * 1024)}%.1fMB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.1fGB"
  }
}
