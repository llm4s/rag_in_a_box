package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.llm4s.ragbox.service.{ConflictError, InvalidRequestError, LlmUnavailableError, NotFoundError}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ErrorHandlingSpec extends AnyFlatSpec with Matchers {

  private def respond(error: Throwable): (Status, Json) = {
    val routes = ErrorHandling(HttpRoutes.of[IO] { case _ => IO.raiseError(error) })
    val response = routes.orNotFound.run(Request[IO](Method.GET, uri"/x")).unsafeRunSync()
    response.status -> response.as[Json].unsafeRunSync()
  }

  private def fields(json: Json): (String, String) =
    (json.hcursor.get[String]("error").toOption.get, json.hcursor.get[String]("message").toOption.get)

  "ErrorHandling" should "map service errors to their status with their message" in {
    respond(InvalidRequestError("bad pattern")) shouldBe (Status.BadRequest -> respond(InvalidRequestError("bad pattern"))._2)
    fields(respond(InvalidRequestError("bad pattern"))._2) shouldBe ("bad_request" -> "bad pattern")
    respond(NotFoundError("no such thing"))._1 shouldBe Status.NotFound
    fields(respond(ConflictError("exists"))._2) shouldBe ("conflict" -> "exists")
    respond(ConflictError("exists"))._1 shouldBe Status.Conflict
  }

  it should "answer a missing LLM with a configuration error" in {
    val (status, json) = respond(LlmUnavailableError())
    status shouldBe Status.BadRequest
    fields(json) shouldBe ("configuration_error" -> LlmUnavailableError.ClientMessage)
  }

  it should "answer any other error with a generic 500 that hides its message" in {
    val (status, json) = respond(new RuntimeException("ERROR: relation \"secret_table\" does not exist"))
    status shouldBe Status.InternalServerError
    fields(json) shouldBe ("internal_error" -> ErrorHandling.InternalErrorMessage)
    json.noSpaces should not include "secret_table"
  }

  it should "answer an undecodable body with a 400" in {
    val (status, json) = respond(MalformedMessageBodyFailure("Invalid JSON"))
    status shouldBe Status.BadRequest
    fields(json)._1 shouldBe "bad_request"
  }

  "clientMessage" should "pass service errors through and hide the rest" in {
    ErrorHandling.clientMessage(InvalidRequestError("bad"), "Query failed").unsafeRunSync() shouldBe "bad"
    ErrorHandling.clientMessage(LlmUnavailableError(), "Query failed").unsafeRunSync() shouldBe LlmUnavailableError.ClientMessage
    ErrorHandling.clientMessage(new RuntimeException("jdbc secret"), "Query failed").unsafeRunSync() shouldBe "Query failed"
  }
}
