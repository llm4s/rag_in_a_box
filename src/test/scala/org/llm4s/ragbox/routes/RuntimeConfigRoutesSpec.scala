package org.llm4s.ragbox.routes

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.implicits._
import org.llm4s.ragbox.auth.Principal
import org.llm4s.ragbox.config.RuntimeConfigManager
import org.llm4s.ragbox.testkit.PgVectorFixture
import org.llm4s.ragbox.testkit.TestAuth._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * The real [[RuntimeConfigRoutes]] over a real [[RuntimeConfigManager]], on a migrated pgvector
 * database (each test its own).
 */
class RuntimeConfigRoutesSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  /** The routes, run as `principal`, over a fresh database. */
  private def withRoutes[A](principal: Principal = Admin)(f: HttpApp[IO] => IO[A]): A =
    withContainers { pg =>
      val db = freshDatabase(pg)
      (for {
        ds <- pool(db)
        manager <- Resource.make(RuntimeConfigManager(testConfig(db), ds))(_.close())
      } yield RuntimeConfigRoutes.routes(manager).asPrincipal(principal).orNotFound)
        .use(f).timeout(60.seconds).unsafeRunSync()
    }

  private def json(method: Method, uri: Uri, body: String): Request[IO] =
    Request[IO](method, uri).withEntity(body).withContentType(headers.`Content-Type`(MediaType.application.json))

  private def call(app: HttpApp[IO], request: Request[IO]): IO[(Status, String)] =
    app.run(request).flatMap(r => r.as[String].map(r.status -> _))

  private def get(app: HttpApp[IO], uri: Uri): IO[(Status, String)] = call(app, Request[IO](Method.GET, uri))

  "GET /api/v1/config/runtime" should "return the current runtime configuration and its hot and warm settings" in {
    val (status, body) = withRoutes()(get(_, uri"/api/v1/config/runtime"))
    status shouldBe Status.Ok
    body should (include("\"config\"") and include("\"topK\"") and include("\"fusionStrategy\""))
    body should (include("hotSettings") and include("warmSettings"))
  }

  "PUT /api/v1/config/runtime" should "apply a valid change, which the next read and the history show" in {
    val ((status, body), (_, after), (_, history)) = withRoutes() { app =>
      for {
        put <- call(app, json(Method.PUT, uri"/api/v1/config/runtime", """{"topK": 10}"""))
        after <- get(app, uri"/api/v1/config/runtime")
        history <- get(app, uri"/api/v1/config/runtime/history")
      } yield (put, after, history)
    }
    status shouldBe Status.Ok
    body should include("\"success\":true")
    after should include("\"topK\":10")
    history should (include("\"field\":\"topK\"") and include("\"total\":1"))
  }

  it should "reject an invalid topK" in {
    val (status, body) = withRoutes()(call(_, json(Method.PUT, uri"/api/v1/config/runtime", """{"topK": 0}""")))
    status shouldBe Status.BadRequest
    body should include("validation_failed")
  }

  it should "reject an invalid fusion strategy" in {
    val (status, body) =
      withRoutes()(call(_, json(Method.PUT, uri"/api/v1/config/runtime", """{"fusionStrategy": "invalid_strategy"}""")))
    status shouldBe Status.BadRequest
    body should include("validation_failed")
  }

  it should "be admin-only" in {
    val (status, _) = withRoutes(user("alice"))(call(_, json(Method.PUT, uri"/api/v1/config/runtime", """{"topK": 10}""")))
    status shouldBe Status.Forbidden
  }

  "POST /api/v1/config/runtime/validate" should "accept valid settings" in {
    val (status, body) =
      withRoutes()(call(_, json(Method.POST, uri"/api/v1/config/runtime/validate", """{"topK": 10, "fusionStrategy": "rrf"}""")))
    status shouldBe Status.Ok
    body should include("\"valid\":true")
  }

  it should "report invalid settings" in {
    val (status, body) = withRoutes()(call(_, json(Method.POST, uri"/api/v1/config/runtime/validate", """{"topK": -1}""")))
    status shouldBe Status.Ok
    body should (include("\"valid\":false") and include("topK must be at least 1"))
  }

  it should "check chunk size limits" in {
    val (status, body) = withRoutes()(call(_, json(Method.POST, uri"/api/v1/config/runtime/validate", """{"chunkSize": 50}""")))
    status shouldBe Status.Ok
    body should (include("\"valid\":false") and include("chunkSize must be at least 100"))
  }

  "GET /api/v1/config/runtime/history" should "be empty initially" in {
    val (status, body) = withRoutes()(get(_, uri"/api/v1/config/runtime/history"))
    status shouldBe Status.Ok
    body should (include("\"changes\":[]") and include("\"total\":0"))
  }

  "GET /api/v1/config/runtime/meta" should "describe every runtime setting" in {
    val (status, body) = withRoutes()(get(_, uri"/api/v1/config/runtime/meta"))
    status shouldBe Status.Ok
    Seq("topK", "fusionStrategy", "rrfK", "systemPrompt", "llmTemperature", "chunkingStrategy", "chunkSize", "chunkOverlap")
      .foreach(key => body should include(s"\"key\":\"$key\""))
    body should include("description")
  }
}
