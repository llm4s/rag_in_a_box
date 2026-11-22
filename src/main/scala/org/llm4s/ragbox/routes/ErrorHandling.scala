package org.llm4s.ragbox.routes

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.syntax.all._
import io.circe.DecodingFailure
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.llm4s.ragbox.model.ErrorResponse
import org.llm4s.ragbox.service.{ConflictError, InvalidRequestError, LlmUnavailableError, NotFoundError}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * The one place errors raised by routes become responses, always with an [[ErrorResponse]] body:
 *
 *  - a request body that cannot be decoded (`DecodeFailure`, such as malformed JSON or a missing
 *    field): 400, or 415 for a missing or unsupported media type
 *  - [[org.llm4s.ragbox.service.ServiceError]]s: 400 (invalid request, no LLM), 404 (not found),
 *    409 (conflict), with their client-facing message
 *  - anything else: a generic 500. The exception is logged with the request's method and path;
 *    its message is never sent to the client, as it can hold SQL or internal details.
 *
 * Routes let errors propagate instead of catching them. `RagboxApp` wraps every route in this;
 * so does `TestAuth` for route tests.
 */
object ErrorHandling {

  private val logger: Logger[IO] = Slf4jLogger.getLoggerFromName[IO]("org.llm4s.ragbox.errors")

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli { (req: Request[IO]) =>
      routes(req).handleErrorWith(e => OptionT.liftF(toResponse(req, e)))
    }

  /** The response for `error`, raised while handling `req`. */
  def toResponse(req: Request[IO], error: Throwable): IO[Response[IO]] =
    error match {
      case f: MediaTypeMissing => unsupportedMediaType(f.message)
      case f: MediaTypeMismatch => unsupportedMediaType(f.message)
      case f: DecodeFailure =>
        BadRequest(ErrorResponse.badRequest("Invalid request body", decodeDetails(f)).asJson)
      case e: InvalidRequestError =>
        BadRequest(ErrorResponse.badRequest(e.message).asJson)
      case e: NotFoundError =>
        NotFound(ErrorResponse.notFound(e.message).asJson)
      case e: ConflictError =>
        Conflict(ErrorResponse("conflict", e.message).asJson)
      case _: LlmUnavailableError =>
        BadRequest(ErrorResponse.configError(LlmUnavailableError.ClientMessage).asJson)
      case e =>
        logger.error(e)(s"${req.method} ${req.uri.path.renderString} failed") *>
          InternalServerError(ErrorResponse.internalError(InternalErrorMessage).asJson)
    }

  /**
   * What a client is told about `error` where it cannot get an error response - an SSE `error`
   * event or a chat reply: a [[org.llm4s.ragbox.service.ServiceError]]'s message, or `fallback`
   * for anything else, which is logged instead.
   */
  def clientMessage(error: Throwable, fallback: String): IO[String] =
    error match {
      case _: LlmUnavailableError => IO.pure(LlmUnavailableError.ClientMessage)
      case e: InvalidRequestError => IO.pure(e.message)
      case e: NotFoundError => IO.pure(e.message)
      case e: ConflictError => IO.pure(e.message)
      case e => logger.error(e)(fallback).as(fallback)
    }

  private def unsupportedMediaType(message: String): IO[Response[IO]] =
    UnsupportedMediaType(ErrorResponse("unsupported_media_type", message).asJson)

  /** The message of a generic 500. */
  val InternalErrorMessage: String = "Internal server error"

  /**
   * What is wrong with the body, when it is about the client's JSON (a missing field, a value of
   * the wrong type): circe's description, which names the field.
   */
  private def decodeDetails(failure: DecodeFailure): Option[String] =
    failure match {
      case InvalidMessageBodyFailure(_, Some(df: DecodingFailure)) => Some(df.show)
      case m: MalformedMessageBodyFailure => Some(m.details)
      case _ => None
    }
}
