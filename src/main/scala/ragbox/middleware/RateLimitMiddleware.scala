package ragbox.middleware

import cats.data.{Kleisli, OptionT}
import cats.effect.{Clock, IO, Ref}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.typelevel.ci.CIStringSyntax

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

/**
 * Rate limiting middleware for API protection.
 *
 * Implements a sliding window rate limiter using in-memory storage.
 * Tracks requests per IP address and enforces configurable limits.
 */
object RateLimitMiddleware {

  /**
   * Rate limit configuration.
   *
   * @param maxRequests Maximum requests allowed in the window
   * @param window Time window duration
   * @param enabled Whether rate limiting is enabled
   */
  final case class Config(
    maxRequests: Int,
    window: FiniteDuration,
    enabled: Boolean
  )

  object Config {
    val default: Config = Config(
      maxRequests = 100,
      window = 1.minute,
      enabled = false
    )
  }

  /**
   * Request record for tracking.
   */
  final case class RequestRecord(
    count: Int,
    windowStart: Long
  )

  /**
   * Rate limit state tracking requests per IP.
   */
  final case class RateLimitState(
    requests: Map[String, RequestRecord]
  )

  object RateLimitState {
    val empty: RateLimitState = RateLimitState(Map.empty)
  }

  /**
   * Create rate limiting middleware.
   *
   * @param config Rate limit configuration
   * @param state Shared state for request tracking
   * @return Middleware that enforces rate limits
   */
  def apply(config: Config, state: Ref[IO, RateLimitState])(routes: HttpRoutes[IO]): HttpRoutes[IO] = {
    if (!config.enabled) {
      routes
    } else {
      Kleisli { (request: Request[IO]) =>
        // Skip rate limiting for health endpoints
        if (isHealthEndpoint(request)) {
          routes(request)
        } else {
          for {
            clientIp <- OptionT.liftF(IO.pure(extractClientIp(request)))
            now <- OptionT.liftF(Clock[IO].realTime.map(_.toMillis))
            allowed <- OptionT.liftF(checkAndUpdateLimit(state, config, clientIp, now))
            response <- if (allowed) {
              routes(request)
            } else {
              OptionT.liftF(rateLimitExceeded(config))
            }
          } yield response
        }
      }
    }
  }

  /**
   * Create a new rate limit state reference.
   */
  def createState: IO[Ref[IO, RateLimitState]] =
    Ref.of[IO, RateLimitState](RateLimitState.empty)

  /**
   * Check if request is within rate limit and update counter.
   */
  private def checkAndUpdateLimit(
    state: Ref[IO, RateLimitState],
    config: Config,
    clientIp: String,
    now: Long
  ): IO[Boolean] = {
    val windowMs = config.window.toMillis

    state.modify { s =>
      val record = s.requests.get(clientIp)

      record match {
        case Some(r) if now - r.windowStart < windowMs =>
          // Within current window
          if (r.count >= config.maxRequests) {
            // Limit exceeded
            (s, false)
          } else {
            // Increment counter
            val updated = s.copy(
              requests = s.requests.updated(clientIp, r.copy(count = r.count + 1))
            )
            (updated, true)
          }

        case _ =>
          // New window or expired
          val updated = s.copy(
            requests = s.requests.updated(clientIp, RequestRecord(1, now))
          )
          (updated, true)
      }
    }
  }

  /**
   * Extract client IP from request.
   * Checks X-Forwarded-For header first (for proxied requests).
   */
  private def extractClientIp(request: Request[IO]): String = {
    request.headers.get(ci"X-Forwarded-For")
      .map(_.head.value.split(",").head.trim)
      .orElse(request.headers.get(ci"X-Real-IP").map(_.head.value))
      .getOrElse(request.remoteAddr.map(_.toUriString).getOrElse("unknown"))
  }

  /**
   * Check if this is a health endpoint (exempt from rate limiting).
   */
  private def isHealthEndpoint(request: Request[IO]): Boolean = {
    val path = request.pathInfo.renderString
    path == "/health" || path == "/health/ready" || path == "/health/live" || path == "/metrics"
  }

  /**
   * Return 429 Too Many Requests response.
   */
  private def rateLimitExceeded(config: Config): IO[Response[IO]] = {
    import io.circe.syntax._
    import org.http4s.circe._

    val retryAfter = config.window.toSeconds.toString
    val body = Map(
      "error" -> "Rate limit exceeded",
      "retryAfter" -> retryAfter,
      "limit" -> config.maxRequests.toString,
      "window" -> s"${config.window.toSeconds}s"
    )

    IO.pure(
      Response[IO](Status.TooManyRequests)
        .withHeaders(
          Header.Raw(ci"Retry-After", retryAfter),
          Header.Raw(ci"X-RateLimit-Limit", config.maxRequests.toString),
          Header.Raw(ci"X-RateLimit-Remaining", "0")
        )
        .withEntity(body.asJson)
    )
  }

  /**
   * Periodic cleanup of expired entries to prevent memory leaks.
   */
  def cleanup(state: Ref[IO, RateLimitState], maxAge: FiniteDuration): IO[Unit] = {
    for {
      now <- Clock[IO].realTime.map(_.toMillis)
      maxAgeMs = maxAge.toMillis
      _ <- state.update { s =>
        val cleaned = s.requests.filter { case (_, record) =>
          now - record.windowStart < maxAgeMs
        }
        s.copy(requests = cleaned)
      }
    } yield ()
  }
}
