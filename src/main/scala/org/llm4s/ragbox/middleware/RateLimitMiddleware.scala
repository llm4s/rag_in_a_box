package org.llm4s.ragbox.middleware

import cats.data.{Kleisli, OptionT}
import cats.effect.{Clock, IO, Ref}
import fs2.{Chunk, Stream}
import io.circe.parser.parse
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.llm4s.ragbox.config.RateLimitConfig
import org.llm4s.ragbox.model.ErrorResponse
import org.typelevel.ci.CIStringSyntax

import scala.collection.immutable.VectorMap
import scala.concurrent.duration._

/**
 * Rate limiting, applied in front of authentication so that failed-authentication traffic is
 * limited as well.
 *
 *  - '''Per client''' (when enabled): at most `maxRequests` requests per `window` per client
 *    IP. Health checks and `/metrics` are exempt.
 *  - '''Login throttling''' (on unless `loginMaxAttempts` is 0, independently of the per-client
 *    limit): after `loginMaxAttempts` failed `POST /api/v1/auth/login` attempts within
 *    `loginWindow`, from one client IP or for one username, further attempts answer 429 until the
 *    window ends. A successful login clears the username's count.
 *
 * Client IPs come from [[ClientIp]]: forwarding headers count only from trusted proxies. Every
 * counter is a bounded map (`maxClients` entries); expired windows are dropped as new ones are
 * added and, beyond the bound, the oldest windows are evicted, so spoofed or rotating addresses
 * cannot grow memory without limit.
 */
object RateLimitMiddleware {

  final case class Config(
    maxRequests: Int,
    window: FiniteDuration,
    enabled: Boolean,
    trustedProxies: Seq[String] = Seq.empty,
    maxClients: Int = RateLimitConfig.DefaultMaxClients,
    loginMaxAttempts: Int = RateLimitConfig.DefaultLoginMaxAttempts,
    loginWindow: FiniteDuration = RateLimitConfig.DefaultLoginWindowSeconds.seconds
  ) {
    def loginThrottled: Boolean = loginMaxAttempts > 0
  }

  object Config {
    val default: Config = Config(maxRequests = 100, window = 1.minute, enabled = false)

    def from(c: RateLimitConfig): Config = Config(
      maxRequests = c.maxRequests,
      window = c.windowSeconds.seconds,
      enabled = c.enabled,
      trustedProxies = c.trustedProxies,
      maxClients = c.maxClients,
      loginMaxAttempts = c.loginMaxAttempts,
      loginWindow = c.loginWindowSeconds.seconds
    )
  }

  /** The counters behind one middleware instance. */
  final class Limiters private[RateLimitMiddleware] (
    val requests: WindowCounter,
    val loginsByIp: WindowCounter,
    val loginsByUser: WindowCounter
  )

  def createLimiters(config: Config): IO[Limiters] =
    for {
      requests <- WindowCounter.create(config.window, config.maxClients)
      byIp <- WindowCounter.create(config.loginWindow, config.maxClients)
      byUser <- WindowCounter.create(config.loginWindow, config.maxClients)
    } yield new Limiters(requests, byIp, byUser)

  val LoginPath = "/api/v1/auth/login"

  def apply(config: Config, limiters: Limiters)(routes: HttpRoutes[IO]): HttpRoutes[IO] = {
    val clientIp = ClientIp(config.trustedProxies)
    Kleisli { (request: Request[IO]) =>
      val ip = clientIp(request)
      val throttled =
        if (config.loginThrottled && request.method == Method.POST && request.pathInfo.renderString == LoginPath)
          login(config, limiters, ip, request, routes)
        else routes(request)

      if (!config.enabled || isExempt(request)) throttled
      else
        OptionT.liftF(limiters.requests.tryAcquire(ip, config.maxRequests)).flatMap { allowed =>
          if (allowed) throttled
          else OptionT.liftF(tooManyRequests("Rate limit exceeded", config.maxRequests, config.window))
        }
    }
  }

  /**
   * Login throttling. Each attempt is counted before it runs (so concurrent attempts cannot
   * overshoot the limit) and refunded unless it answers 401; a success also clears the
   * username's count.
   */
  private def login(
    config: Config,
    limiters: Limiters,
    ip: String,
    request: Request[IO],
    routes: HttpRoutes[IO]
  ): OptionT[IO, Response[IO]] =
    OptionT.liftF(request.body.compile.to(Chunk)).flatMap { body =>
      val username = usernameOf(body)
      val max = config.loginMaxAttempts
      val acquire: IO[Boolean] =
        limiters.loginsByIp.tryAcquire(ip, max).flatMap {
          case false => IO.pure(false)
          case true =>
            username.fold(IO.pure(true))(limiters.loginsByUser.tryAcquire(_, max)).flatTap { userOk =>
              if (userOk) IO.unit else limiters.loginsByIp.release(ip)
            }
        }
      def refund(succeeded: Boolean): IO[Unit] =
        limiters.loginsByIp.release(ip) *>
          username.fold(IO.unit)(u => if (succeeded) limiters.loginsByUser.reset(u) else limiters.loginsByUser.release(u))

      OptionT.liftF(acquire).flatMap { allowed =>
        if (!allowed) OptionT.liftF(tooManyRequests("Too many failed login attempts", max, config.loginWindow))
        else
          OptionT(
            routes(request.withBodyStream(Stream.chunk(body))).value.flatTap {
              case Some(response) if response.status == Status.Unauthorized => IO.unit
              case Some(response) => refund(response.status.isSuccess)
              case None => refund(succeeded = false)
            }
          )
      }
    }

  /** The `username` of a login body, normalised, if the body is JSON with one. */
  private def usernameOf(body: Chunk[Byte]): Option[String] =
    parse(new String(body.toArray, java.nio.charset.StandardCharsets.UTF_8)).toOption
      .flatMap(_.hcursor.get[String]("username").toOption)
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)

  private def isExempt(request: Request[IO]): Boolean = {
    val path = request.pathInfo.renderString
    path == "/health" || path == "/health/ready" || path == "/health/live" || path == "/metrics"
  }

  /**
   * 429 Too Many Requests, as the standard [[ErrorResponse]]: `details` carries the numbers as
   * `limit=<n>; window=<seconds>s; retryAfter=<seconds>`, and the `Retry-After` and
   * `X-RateLimit-*` headers are set.
   */
  private def tooManyRequests(message: String, limit: Int, window: FiniteDuration): IO[Response[IO]] = {
    val retryAfter = window.toSeconds.toString
    val body = ErrorResponse(
      error = "rate_limited",
      message = message,
      details = Some(s"limit=$limit; window=${window.toSeconds}s; retryAfter=$retryAfter")
    )
    IO.pure(
      Response[IO](Status.TooManyRequests)
        .withHeaders(
          Header.Raw(ci"Retry-After", retryAfter),
          Header.Raw(ci"X-RateLimit-Limit", limit.toString),
          Header.Raw(ci"X-RateLimit-Remaining", "0")
        )
        .withEntity(body.asJson)
    )
  }
}

/**
 * Fixed-window counters per key, holding at most `maxKeys` keys.
 *
 * Keys are kept in the order their window started (a `VectorMap`; a new window re-inserts the
 * key at the end), so the oldest windows are at the head: expired ones are dropped from there
 * whenever a key is added, and beyond `maxKeys` the oldest are evicted.
 */
final class WindowCounter private (
  state: Ref[IO, VectorMap[String, WindowCounter.Window]],
  window: FiniteDuration,
  maxKeys: Int
) {
  import WindowCounter.Window

  private val windowMs = window.toMillis

  private def now: IO[Long] = Clock[IO].realTime.map(_.toMillis)

  private def current(m: VectorMap[String, Window], key: String, t: Long): Option[Window] =
    m.get(key).filter(w => t - w.start < windowMs)

  /** Start a new window for `key` with `count`, dropping expired and over-limit entries. */
  private def startWindow(m: VectorMap[String, Window], key: String, count: Int, t: Long): VectorMap[String, Window] = {
    var rest = m - key
    while (rest.nonEmpty && (rest.size >= maxKeys || t - rest.head._2.start >= windowMs)) rest = rest.tail
    rest.updated(key, Window(count, t))
  }

  /** Count a request for `key` if it is under `max` in the current window. */
  def tryAcquire(key: String, max: Int): IO[Boolean] =
    now.flatMap { t =>
      state.modify { m =>
        current(m, key, t) match {
          case Some(w) if w.count >= max => (m, false)
          case Some(w) => (m.updated(key, w.copy(count = w.count + 1)), true)
          case None => (startWindow(m, key, 1, t), true)
        }
      }
    }

  /** Take back one count of `key` in the current window. */
  def release(key: String): IO[Unit] =
    now.flatMap { t =>
      state.update { m =>
        current(m, key, t) match {
          case Some(w) if w.count > 0 => m.updated(key, w.copy(count = w.count - 1))
          case _ => m
        }
      }
    }

  def reset(key: String): IO[Unit] = state.update(_ - key)

  /** Number of keys currently tracked. */
  def size: IO[Int] = state.get.map(_.size)
}

object WindowCounter {

  private[middleware] final case class Window(count: Int, start: Long)

  def create(window: FiniteDuration, maxKeys: Int): IO[WindowCounter] =
    Ref.of[IO, VectorMap[String, Window]](VectorMap.empty).map(new WindowCounter(_, window, math.max(1, maxKeys)))
}
