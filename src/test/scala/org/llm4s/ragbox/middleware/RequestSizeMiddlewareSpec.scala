package org.llm4s.ragbox.middleware

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.{Chunk, Stream}
import io.circe.Json
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Length`
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RequestSizeMiddlewareSpec extends AnyFlatSpec with Matchers {

  private val Limit = 1024L

  /** Echoes the body size; `/lenient` turns a failed read into a 400 like a careless route would. */
  private val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ (POST | PUT | DELETE | PATCH) -> Root / "echo" =>
      req.body.compile.count.flatMap(n => Ok(n.toString))
    case req @ POST -> Root / "lenient" =>
      req.body.compile.count.attempt.flatMap {
        case Right(n) => Ok(n.toString)
        case Left(_) => BadRequest("could not read body")
      }
  }

  private val app = RequestSizeMiddleware(RequestSizeMiddleware.Config(Limit, enabled = true))(routes).orNotFound

  /** A chunked body (no Content-Length) of `size` bytes, in 100-byte chunks. */
  private def chunked(method: Method, path: String, size: Int): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path))
      .withBodyStream(Stream.emits(Seq.fill(size)(1.toByte)).chunkN(100).flatMap(Stream.chunk))

  private def status(req: Request[IO]): Status = app.run(req).unsafeRunSync().status

  "RequestSizeMiddleware" should "refuse a declared Content-Length over the limit without reading the body" in {
    val req = Request[IO](Method.POST, uri"/echo")
      .withBodyStream(Stream.raiseError[IO](new RuntimeException("must not be read")))
      .putHeaders(`Content-Length`(Limit + 1))
    status(req) shouldBe Status.PayloadTooLarge
  }

  it should "count chunked bodies without a Content-Length, for every method" in {
    Seq(Method.POST, Method.PUT, Method.PATCH, Method.DELETE).foreach { m =>
      withClue(m) {
        status(chunked(m, "/echo", Limit.toInt + 1)) shouldBe Status.PayloadTooLarge
        status(chunked(m, "/echo", Limit.toInt)) shouldBe Status.Ok
      }
    }
  }

  it should "answer 413 even when the route swallowed the failed read" in {
    status(chunked(Method.POST, "/lenient", 5000)) shouldBe Status.PayloadTooLarge
    status(chunked(Method.POST, "/lenient", 10)) shouldBe Status.Ok
  }

  it should "catch a body longer than its (lying) Content-Length" in {
    val req = chunked(Method.POST, "/echo", 5000).putHeaders(`Content-Length`(10))
    status(req) shouldBe Status.PayloadTooLarge
  }

  it should "pass everything through when disabled" in {
    val off = RequestSizeMiddleware(RequestSizeMiddleware.Config(Limit, enabled = false))(routes).orNotFound
    off.run(chunked(Method.POST, "/echo", 5000)).unsafeRunSync().status shouldBe Status.Ok
  }

  it should "answer with the standard ErrorResponse, the limit in details (#54)" in {
    val response = app.run(chunked(Method.POST, "/echo", Limit.toInt + 1)).unsafeRunSync()
    response.status shouldBe Status.PayloadTooLarge
    val body = response.as[Json].unsafeRunSync().hcursor
    body.get[String]("error") shouldBe Right("payload_too_large")
    body.get[String]("message").toOption.get should include("1.0KB")
    body.get[String]("details") shouldBe Right(s"maxSizeBytes=$Limit")
    body.keys.get.toSet shouldBe Set("error", "message", "details")
    response.headers.get(org.typelevel.ci.CIString("X-Max-Body-Size")).map(_.head.value) shouldBe Some(Limit.toString)
  }

  "RequestSizeMiddleware.limit" should "emit the body unchanged under the limit" in {
    val bytes = Stream.chunk(Chunk.array("hello".getBytes)).covary[IO]
    RequestSizeMiddleware.limit(bytes, 5, IO.unit).compile.toVector.unsafeRunSync().map(_.toChar).mkString shouldBe "hello"
  }
}

class RequestLoggerSpec extends AnyFlatSpec with Matchers {

  "RequestLogger.redact" should "hide credentials and OAuth secrets in query strings" in {
    RequestLogger.redact(uri"/ws?access_token=rat_secret") shouldBe "/ws?access_token=***"
    RequestLogger.redact(uri"/api/v1/stats?api_key=k&limit=5") shouldBe "/api/v1/stats?api_key=***&limit=5"
    RequestLogger.redact(uri"/api/v1/oauth/callback?code=abc&state=xyz") shouldBe
      "/api/v1/oauth/callback?code=***&state=***"
    RequestLogger.redact(uri"/x?TOKEN=t&Access_Token=u") shouldBe "/x?TOKEN=***&Access_Token=***"
  }

  it should "leave other URIs alone" in {
    RequestLogger.redact(uri"/api/v1/documents") shouldBe "/api/v1/documents"
    RequestLogger.redact(uri"/api/v1/documents?collection=docs&page=2") shouldBe "/api/v1/documents?collection=docs&page=2"
  }
}
