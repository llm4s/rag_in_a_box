package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.testkit.MockRAGService

class HealthRoutesSpec extends AnyFlatSpec with Matchers {

  private def createRoutes(): HttpRoutes[IO] = {
    val mockService = MockRAGService()
    HealthRoutes.routes(mockService)
  }

  "GET /health" should "return healthy status" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/health")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("healthy")
    body should include("0.1.0") // version
  }

  it should "include uptime in response" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/health")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    val body = response.as[String].unsafeRunSync()
    body should include("uptime")
  }

  it should "include system info" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/health")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    val body = response.as[String].unsafeRunSync()
    body should include("system")
    body should include("memoryUsedMb")
    body should include("cpuCount")
  }

  "GET /health/ready" should "return ready when service is ready" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/health/ready")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("\"ready\":true")
  }

  it should "include database check" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/health/ready")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    val body = response.as[String].unsafeRunSync()
    body should include("database")
    body should include("\"status\":\"ok\"")
  }

  it should "include api_keys check" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/health/ready")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    val body = response.as[String].unsafeRunSync()
    body should include("api_keys")
  }

  "GET /health/live" should "return ok status" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/health/live")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok

    val body = response.as[String].unsafeRunSync()
    body should include("\"status\":\"ok\"")
  }

  it should "return minimal response for liveness" in {
    val routes = createRoutes()

    val request = Request[IO](Method.GET, uri"/health/live")
    val response = routes.orNotFound.run(request).unsafeRunSync()

    val body = response.as[String].unsafeRunSync()
    // Liveness should be lightweight
    body should not include "system"
    body should not include "uptime"
  }
}
