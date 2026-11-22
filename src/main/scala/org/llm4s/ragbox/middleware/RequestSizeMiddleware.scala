package org.llm4s.ragbox.middleware

import cats.data.{Kleisli, OptionT}
import cats.effect.{IO, Ref}
import fs2.{Pull, Stream}
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.`Content-Length`
import org.typelevel.ci.CIStringSyntax

import scala.util.control.NoStackTrace

/**
 * Request size limiting.
 *
 * A declared `Content-Length` over the limit is refused up front. Every body (any method,
 * including chunked bodies without a `Content-Length`) is also counted as it is read: once it
 * passes the limit the read fails and the request answers `413 Payload Too Large`, whatever the
 * route made of the failed read.
 */
object RequestSizeMiddleware {

  /**
   * @param maxBodySize maximum request body size in bytes
   * @param enabled     whether size limiting is enabled
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

  /** Raised by the body stream once it has passed the limit. */
  final case class BodyTooLarge(limit: Long)
    extends RuntimeException(s"Request body exceeds $limit bytes") with NoStackTrace

  def apply(config: Config)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    if (!config.enabled) routes
    else
      Kleisli { (request: Request[IO]) =>
        request.headers.get[`Content-Length`] match {
          case Some(cl) if cl.length > config.maxBodySize =>
            OptionT.liftF(IO.pure(payloadTooLarge(config)))
          case _ =>
            OptionT(
              Ref.of[IO, Boolean](false).flatMap { exceeded =>
                val limited = request.withBodyStream(limit(request.body, config.maxBodySize, exceeded.set(true)))
                routes(limited).value.attempt.flatMap { result =>
                  exceeded.get.flatMap { tooLarge =>
                    // However the route handled the failed read (400, 500, an error), it was a 413
                    if (tooLarge) IO.pure(Some(payloadTooLarge(config)))
                    else IO.fromEither(result)
                  }
                }
              }
            )
        }
      }

  /** `body`, failing with [[BodyTooLarge]] (after running `onExceeded`) past `max` bytes. */
  private[middleware] def limit(body: Stream[IO, Byte], max: Long, onExceeded: IO[Unit]): Stream[IO, Byte] = {
    def go(s: Stream[IO, Byte], seen: Long): Pull[IO, Byte, Unit] =
      s.pull.uncons.flatMap {
        case None => Pull.done
        case Some((chunk, rest)) =>
          val total = seen + chunk.size
          if (total > max) Pull.eval(onExceeded) >> Pull.raiseError[IO](BodyTooLarge(max))
          else Pull.output(chunk) >> go(rest, total)
      }
    go(body, 0L).stream
  }

  /** 413 Payload Too Large. */
  private def payloadTooLarge(config: Config): Response[IO] = {
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

  /** Format bytes as a human-readable string. */
  def formatBytes(bytes: Long): String = {
    if (bytes < 1024) s"${bytes}B"
    else if (bytes < 1024 * 1024) f"${bytes / 1024.0}%.1fKB"
    else if (bytes < 1024 * 1024 * 1024) f"${bytes / (1024.0 * 1024)}%.1fMB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.1fGB"
  }
}
