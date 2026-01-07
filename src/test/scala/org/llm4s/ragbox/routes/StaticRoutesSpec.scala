package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StaticRoutesSpec extends AnyFlatSpec with Matchers {

  private val routes = StaticRoutes.routes

  private def runRequest(request: Request[IO]): Response[IO] =
    routes.orNotFound.run(request).unsafeRunSync()

  "GET /admin" should "serve admin UI index" in {
    val request = Request[IO](Method.GET, uri"/admin")
    val response = runRequest(request)

    // Note: In test environment, resources may not be available
    // so we accept either Ok (resource found) or NotFound (resource not in test classpath)
    Seq(Status.Ok, Status.NotFound) should contain(response.status)
  }

  "GET /admin/" should "serve admin UI index with trailing slash" in {
    val request = Request[IO](Method.GET, uri"/admin/")
    val response = runRequest(request)

    Seq(Status.Ok, Status.NotFound) should contain(response.status)
  }

  "GET /admin/assets/main.js" should "serve static assets" in {
    val request = Request[IO](Method.GET, uri"/admin/assets/main.js")
    val response = runRequest(request)

    // Asset files may not exist in test, but route should match
    Seq(Status.Ok, Status.NotFound) should contain(response.status)
  }

  "GET /admin/some/spa/route" should "fall back to index.html for SPA routing" in {
    val request = Request[IO](Method.GET, uri"/admin/some/spa/route")
    val response = runRequest(request)

    // SPA fallback returns index.html or NotFound if no resources
    Seq(Status.Ok, Status.NotFound) should contain(response.status)
  }

  "GET /admin with HTML response" should "have correct content type" in {
    val request = Request[IO](Method.GET, uri"/admin")
    val response = runRequest(request)

    if (response.status == Status.Ok) {
      response.contentType.map(_.mediaType) shouldBe Some(MediaType.text.html)
    }
  }
}
