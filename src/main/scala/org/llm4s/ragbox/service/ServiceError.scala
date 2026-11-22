package org.llm4s.ragbox.service

import scala.util.control.NoStackTrace

/**
 * An error a service raises for a request it rejects. Its message is written for API clients;
 * `routes.ErrorHandling` turns it into the matching status with an `ErrorResponse`. Any other
 * exception is a server error: it is logged and answered with a generic 500.
 */
sealed abstract class ServiceError(msg: String) extends RuntimeException(msg) with NoStackTrace {
  def message: String
}

/** The request is invalid, such as a malformed collection path or pattern (400). */
final case class InvalidRequestError(message: String) extends ServiceError(message)

/** What the request names does not exist (404). */
final case class NotFoundError(message: String) extends ServiceError(message)

/** The request conflicts with the current state (409). */
final case class ConflictError(message: String) extends ServiceError(message)

/**
 * Answer generation was requested but no LLM is available: it could not be built from the
 * configuration (the reason is logged at startup). Answered with a 400 configuration error.
 */
final case class LlmUnavailableError(message: String = LlmUnavailableError.Message) extends ServiceError(message)

object LlmUnavailableError {
  val Message: String = "LLM client required for answer generation"

  /** What API clients are told. */
  val ClientMessage: String =
    "LLM not available. Set LLM_MODEL to a registered provider and its API key; the server log says why it could not be built."
}
