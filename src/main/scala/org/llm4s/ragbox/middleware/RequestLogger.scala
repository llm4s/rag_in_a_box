package org.llm4s.ragbox.middleware

import cats.data.Kleisli
import cats.effect.{Clock, IO}
import org.http4s.{HttpApp, Request, Uri}
import org.typelevel.log4cats.Logger

/**
 * Logs one line per request: method, path, query string with credentials redacted, status
 * and duration. Headers and bodies are never logged.
 *
 * Query parameters that can carry a credential or an OAuth secret (`api_key`, `access_token`,
 * `token`, `code`, `state`, ...) are logged as `***`, so a WebSocket `?access_token=` or an
 * OAuth callback's `?code=` never reaches the logs.
 */
object RequestLogger {

  /** Query parameter names (case-insensitive) whose values are redacted. */
  val SensitiveParams: Set[String] = Set(
    "api_key", "apikey", "access_token", "id_token", "refresh_token", "token", "code", "state",
    "password", "secret", "client_secret", "session_state"
  )

  private val Redacted = "***"

  /** `uri`'s path and query string, with sensitive parameter values replaced by `***`. */
  def redact(uri: Uri): String = {
    val path = uri.path.renderString
    if (uri.query.isEmpty) path
    else {
      // Work on the raw query so everything else is logged exactly as received
      val query = uri.query.renderString.split("&", -1).map { pair =>
        pair.split("=", 2) match {
          case Array(k, _) if SensitiveParams.contains(Uri.decode(k).toLowerCase) => s"$k=$Redacted"
          case _ => pair
        }
      }
      s"$path?${query.mkString("&")}"
    }
  }

  def apply(logger: Logger[IO])(app: HttpApp[IO]): HttpApp[IO] =
    Kleisli { (req: Request[IO]) =>
      Clock[IO].monotonic.flatMap { start =>
        app(req).attempt.flatMap { result =>
          Clock[IO].monotonic.flatMap { end =>
            val ms = (end - start).toMillis
            val line = s"${req.method.name} ${redact(req.uri)}"
            result match {
              case Right(response) => logger.info(s"$line -> ${response.status.code} (${ms}ms)").as(response)
              case Left(e) => logger.warn(s"$line -> failed: ${e.getClass.getSimpleName} (${ms}ms)") *> IO.raiseError(e)
            }
          }
        }
      }
    }
}
