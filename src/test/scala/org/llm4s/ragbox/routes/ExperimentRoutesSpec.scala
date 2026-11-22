package org.llm4s.ragbox.routes

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.ExperimentRegistry
import org.llm4s.ragbox.service.ExperimentService
import org.llm4s.ragbox.testkit.{PgVectorFixture, TestAuth}
import org.llm4s.ragbox.testkit.TestAuth.{asAdmin, asPrincipal}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * The experiment API's responses (`ExperimentRoutes`), over a real database. The status codes of
 * invalid transitions and definitions are also covered by `ExperimentIntegrationSpec`.
 */
class ExperimentRoutesSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

  private final case class Api(service: ExperimentService, app: HttpApp[IO]) {
    def call(method: Method, path: String, body: Option[Json] = None): IO[(Status, Json)] = {
      val req = Request[IO](method, Uri.unsafeFromString(path))
      app.run(body.fold(req)(req.withEntity(_))).flatMap(r => r.as[Json].map(r.status -> _))
    }
    def create(name: String, split: Double = 0.5): IO[Experiment] =
      service.createExperiment(CreateExperimentRequest(name, variantConfig = ExperimentConfigSnapshot(topK = Some(3)), trafficSplit = Some(split)))
  }

  private def api(db: DatabaseConfig): Resource[IO, Api] =
    pool(db).evalMap(ds => ExperimentRegistry(ds).map(r => ExperimentService(r, ds))).map { svc =>
      Api(svc, ExperimentRoutes.routes(svc).asAdmin.orNotFound)
    }

  "The experiment API" should "be admin-only" in {
    // The admin check comes before the service is used
    val routes = ExperimentRoutes.routes(ExperimentService(null, null)).asPrincipal(TestAuth.user("bob")).orNotFound
    val statuses = Seq(
      Request[IO](Method.GET, uri"/api/v1/experiments"),
      Request[IO](Method.GET, uri"/api/v1/experiments/exp-1/results"),
      Request[IO](Method.POST, uri"/api/v1/experiments").withEntity(Json.obj("name" -> "x".asJson, "variantConfig" -> Json.obj())),
      Request[IO](Method.PATCH, uri"/api/v1/experiments/exp-1").withEntity(Json.obj())
    ).map(r => run(routes.run(r)).status)
    statuses.distinct shouldBe Seq(Status.Forbidden)
  }

  "POST /api/v1/experiments" should "create a draft and describe it" in withContainers { pg =>
    val (status, body) = run(api(freshDatabase(pg)).use(_.call(Method.POST, "/api/v1/experiments", Some(Json.obj(
      "name" -> " prompts ".asJson,
      "description" -> "shorter answers".asJson,
      "variantConfig" -> Json.obj("systemPrompt" -> "Be brief.".asJson),
      "trafficSplit" -> 0.2.asJson
    )))))
    status shouldBe Status.Created
    body.hcursor.get[String]("message") shouldBe Right("Experiment 'prompts' created successfully")
    val exp = body.hcursor.downField("experiment")
    exp.get[String]("status") shouldBe Right("draft")
    exp.get[Double]("trafficSplit") shouldBe Right(0.2)
    exp.downField("variantConfig").get[String]("systemPrompt") shouldBe Right("Be brief.")
    exp.get[String]("id").toOption.get should not be empty
  }

  it should "reject a body that is not an experiment with 400" in withContainers { pg =>
    val statuses = run(api(freshDatabase(pg)).use { a =>
      for {
        notJson <- a.app.run(Request[IO](Method.POST, uri"/api/v1/experiments").withEntity("{nope")).map(_.status)
        noName <- a.call(Method.POST, "/api/v1/experiments", Some(Json.obj("variantConfig" -> Json.obj()))).map(_._1)
        listed <- a.service.listExperiments()
      } yield (notJson, noName, listed.total)
    })
    statuses shouldBe (Status.BadRequest, Status.BadRequest, 0)
  }

  "GET /api/v1/experiments" should "list newest first, filter by status and page with a total" in withContainers { pg =>
    val results = run(api(freshDatabase(pg)).use { a =>
      for {
        first <- a.create("first")
        second <- a.create("second")
        third <- a.create("third")
        _ <- a.service.startExperiment(second.id)
        all <- a.call(Method.GET, "/api/v1/experiments")
        drafts <- a.call(Method.GET, "/api/v1/experiments?status=draft")
        page <- a.call(Method.GET, "/api/v1/experiments?limit=1&offset=1")
        badLimit <- a.call(Method.GET, "/api/v1/experiments?limit=abc")
      } yield (Seq(first, second, third).map(_.id), all, drafts, page, badLimit._1)
    })
    val (ids, (allStatus, all), (_, drafts), (_, page), badLimit) = results
    def listed(j: Json) = j.hcursor.downField("experiments").values.toList.flatten.flatMap(_.hcursor.get[String]("id").toOption)
    allStatus shouldBe Status.Ok
    listed(all) shouldBe ids.reverse
    all.hcursor.get[Int]("total") shouldBe Right(3)
    listed(drafts) shouldBe Seq(ids(2), ids(0))
    drafts.hcursor.get[Int]("total") shouldBe Right(2)
    listed(page) shouldBe Seq(ids(1))
    page.hcursor.get[Int]("total") shouldBe Right(3)
    badLimit shouldBe Status.BadRequest
  }

  "GET /api/v1/experiments/running and /{id}" should "return the experiment, or 404" in withContainers { pg =>
    val (noneRunning, running, byId, unknown) = run(api(freshDatabase(pg)).use { a =>
      for {
        exp <- a.create("run me")
        noneRunning <- a.call(Method.GET, "/api/v1/experiments/running")
        _ <- a.service.startExperiment(exp.id)
        running <- a.call(Method.GET, "/api/v1/experiments/running")
        byId <- a.call(Method.GET, s"/api/v1/experiments/${exp.id}")
        unknown <- a.call(Method.GET, "/api/v1/experiments/exp-missing")
      } yield (noneRunning, running, byId, unknown)
    })
    noneRunning._1 shouldBe Status.NotFound
    running._1 shouldBe Status.Ok
    running._2.hcursor.get[String]("name") shouldBe Right("run me")
    running._2.hcursor.get[String]("status") shouldBe Right("running")
    byId._2 shouldBe running._2
    unknown._1 shouldBe Status.NotFound
    unknown._2.hcursor.get[String]("message") shouldBe Right("Experiment 'exp-missing' not found")
  }

  "The status operations" should "return the changed experiment, and on a conflict the experiment as it is" in withContainers { pg =>
    val (started, stopped, startStopped, archived) = run(api(freshDatabase(pg)).use { a =>
      for {
        exp <- a.create("ops")
        started <- a.call(Method.PUT, s"/api/v1/experiments/${exp.id}/start")
        stopped <- a.call(Method.PUT, s"/api/v1/experiments/${exp.id}/stop")
        startStopped <- a.call(Method.PUT, s"/api/v1/experiments/${exp.id}/start")
        archived <- a.call(Method.PUT, s"/api/v1/experiments/${exp.id}/archive")
      } yield (started, stopped, startStopped, archived)
    })
    def status(j: Json) = j.hcursor.downField("experiment").get[String]("status")
    started._1 shouldBe Status.Ok
    started._2.hcursor.get[Boolean]("success") shouldBe Right(true)
    started._2.hcursor.get[String]("message") shouldBe Right("Experiment 'ops' started successfully")
    status(started._2) shouldBe Right("running")
    status(stopped._2) shouldBe Right("completed")
    stopped._2.hcursor.downField("experiment").get[String]("endedAt").isRight shouldBe true

    startStopped._1 shouldBe Status.Conflict
    startStopped._2.hcursor.get[Boolean]("success") shouldBe Right(false)
    startStopped._2.hcursor.get[String]("message").toOption.get should include("it is completed")
    status(startStopped._2) shouldBe Right("completed")

    archived._1 shouldBe Status.Ok
    status(archived._2) shouldBe Right("archived")
  }

  it should "name the running experiment when starting a second one" in withContainers { pg =>
    val (status, body) = run(api(freshDatabase(pg)).use { a =>
      for {
        first <- a.create("first")
        second <- a.create("second")
        _ <- a.service.startExperiment(first.id)
        response <- a.call(Method.PUT, s"/api/v1/experiments/${second.id}/start")
      } yield response
    })
    status shouldBe Status.Conflict
    body.hcursor.get[String]("message").toOption.get should include("first")
  }

  "PATCH /api/v1/experiments/{id}" should "answer 409 with the experiment for a non-draft, and 400 for a malformed body" in withContainers { pg =>
    val (running, malformed, split) = run(api(freshDatabase(pg)).use { a =>
      for {
        exp <- a.create("patch")
        malformed <- a.app.run(Request[IO](Method.PATCH, Uri.unsafeFromString(s"/api/v1/experiments/${exp.id}"))
          .withEntity(Json.obj("trafficSplit" -> "half".asJson))).map(_.status)
        split <- a.call(Method.PATCH, s"/api/v1/experiments/${exp.id}", Some(Json.obj("trafficSplit" -> 0.9.asJson)))
        _ <- a.service.startExperiment(exp.id)
        running <- a.call(Method.PATCH, s"/api/v1/experiments/${exp.id}", Some(Json.obj("name" -> "late".asJson)))
      } yield (running, malformed, split)
    })
    malformed shouldBe Status.BadRequest
    split._1 shouldBe Status.Ok
    split._2.hcursor.downField("experiment").get[Double]("trafficSplit") shouldBe Right(0.9)
    split._2.hcursor.get[String]("message") shouldBe Right("Experiment 'patch' updated successfully")
    running._1 shouldBe Status.Conflict
    running._2.hcursor.downField("experiment").get[String]("name") shouldBe Right("patch")
  }

  "DELETE /api/v1/experiments/{id}" should "delete a draft" in withContainers { pg =>
    val (deleted, after) = run(api(freshDatabase(pg)).use { a =>
      for {
        exp <- a.create("gone")
        deleted <- a.call(Method.DELETE, s"/api/v1/experiments/${exp.id}")
        after <- a.call(Method.GET, s"/api/v1/experiments/${exp.id}")
      } yield (deleted, after._1)
    })
    deleted._1 shouldBe Status.Ok
    deleted._2.hcursor.get[Boolean]("success") shouldBe Right(true)
    after shouldBe Status.NotFound
  }

  "GET /api/v1/experiments/{id}/results" should "return the results, or 404" in withContainers { pg =>
    val (results, unknown) = run(api(freshDatabase(pg)).use { a =>
      for {
        exp <- a.create("results")
        results <- a.call(Method.GET, s"/api/v1/experiments/${exp.id}/results")
        unknown <- a.call(Method.GET, "/api/v1/experiments/exp-missing/results")
      } yield (results, unknown._1)
    })
    results._1 shouldBe Status.Ok
    results._2.hcursor.get[String]("experimentName") shouldBe Right("results")
    results._2.hcursor.downField("baseline").get[Int]("queryCount") shouldBe Right(0)
    results._2.hcursor.downField("analysis").downField("tests").values.map(_.size) shouldBe Some(0)
    unknown shouldBe Status.NotFound
  }
}
