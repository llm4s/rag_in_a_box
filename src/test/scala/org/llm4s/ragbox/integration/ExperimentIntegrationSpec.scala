package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{ExperimentRegistry, QueryLogRegistry}
import org.llm4s.ragbox.routes.{ExperimentRoutes, QueryRoutes}
import org.llm4s.ragbox.service.{ExperimentService, RAGService}
import org.llm4s.ragbox.testkit.{FakeLLM, LiveService, PgVectorFixture}
import org.llm4s.ragbox.testkit.TestAuth.asAdmin
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * A/B experiments against a real pgvector database (issue #20): variants run with their whole
 * config, failed queries count, groups are logged in their own column, and the HTTP statuses.
 */
class ExperimentIntegrationSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private def run[A](io: IO[A]): A = io.timeout(120.seconds).unsafeRunSync()

  private final case class Stack(live: LiveService, logs: QueryLogRegistry, experiments: ExperimentService) {
    val queryRoutes: HttpApp[IO] = QueryRoutes.routes(live.service, logs, None, Some(experiments)).asAdmin.orNotFound
    val experimentRoutes: HttpApp[IO] = ExperimentRoutes.routes(experiments).asAdmin.orNotFound
  }

  private def stack(db: org.llm4s.ragbox.config.DatabaseConfig, llm: Option[FakeLLM]) =
    liveService(db, permissions = false, llm = llm).evalMap { live =>
      for {
        logs <- QueryLogRegistry(live.dataSource)
        registry <- ExperimentRegistry(live.dataSource)
      } yield Stack(live, logs, ExperimentService(registry, live.dataSource))
    }

  private def ingestAnimals(service: RAGService): IO[Unit] =
    Seq("zebra", "penguin", "lion", "otter", "eagle", "shark").foldLeft(IO.unit) { (acc, animal) =>
      acc *> service.ingestDocument(s"The $animal is an animal that lives in the wild.", animal, Map.empty).void
    }

  private def started(s: Stack, variant: ExperimentConfigSnapshot, split: Double): IO[Experiment] =
    for {
      exp <- s.experiments.createExperiment(CreateExperimentRequest("prompt test", variantConfig = variant, trafficSplit = Some(split)))
      _ <- s.experiments.startExperiment(exp.id)
    } yield exp

  private def query(s: Stack, question: String): IO[(Status, Json)] =
    s.queryRoutes.run(Request[IO](Method.POST, uri"/api/v1/query").withEntity(QueryRequest(question).asJson))
      .flatMap(r => r.as[Json].map(r.status -> _))

  "A running experiment's variant" should "run with its system prompt, topK and fusion, and be logged in its group" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    val variant = ExperimentConfigSnapshot(topK = Some(1), fusionStrategy = Some("keyword_only"), systemPrompt = Some("Variant prompt."))
    val (exp, (status, body), logged) = run(stack(db, Some(llm)).use { s =>
      for {
        _ <- ingestAnimals(s.live.service)
        exp <- started(s, variant, split = 1.0)
        response <- query(s, "zebra")
        logged <- s.logs.list(None, None, None)
      } yield (exp, response, logged._1)
    })
    status shouldBe Status.Ok
    llm.requests.last._1.messages.head.content shouldBe "Variant prompt."
    body.hcursor.downField("contexts").values.map(_.size) shouldBe Some(1)
    // keyword_only: the one context is the document that contains the word
    body.hcursor.downField("contexts").downArray.get[String]("documentId") shouldBe Right("zebra")
    logged.map(e => (e.experimentId, e.variant, e.failed)) shouldBe Seq((Some(exp.id), Some("variant"), false))
    logged.head.configSnapshot.map(c => (c.topK, c.fusionStrategy, c.systemPrompt, c.rrfK, c.reranking, c.searchMode)) shouldBe
      Some((1, Some("keyword_only"), Some("Variant prompt."), None, Some("none"), Some("hybrid")))
  }

  "The baseline" should "run with the runtime settings when its config sets nothing" in withContainers { pg =>
    val db = freshDatabase(pg)
    val llm = FakeLLM()
    val (status, logged) = run(stack(db, Some(llm)).use { s =>
      for {
        _ <- ingestAnimals(s.live.service)
        _ <- started(s, ExperimentConfigSnapshot(systemPrompt = Some("Variant prompt.")), split = 0.0)
        response <- query(s, "zebra")
        logged <- s.logs.list(None, None, None)
      } yield (response._1, logged._1)
    })
    status shouldBe Status.Ok
    llm.requests.last._1.messages.head.content shouldBe "You are a helpful assistant."
    logged.flatMap(_.variant) shouldBe Seq("baseline")
  }

  "A failed query" should "be logged with its group, and count in the experiment's error rate" in withContainers { pg =>
    val db = freshDatabase(pg)
    // No LLM: every answer fails
    val (status, results, logged) = run(stack(db, None).use { s =>
      for {
        _ <- ingestAnimals(s.live.service)
        exp <- started(s, ExperimentConfigSnapshot(topK = Some(2)), split = 1.0)
        response <- query(s, "zebra")
        _ <- query(s, "lion")
        results <- s.experiments.getResults(exp.id)
        logged <- s.logs.list(None, None, None)
      } yield (response._1, results.get, logged._1)
    })
    status shouldBe Status.BadRequest
    logged.map(e => (e.variant, e.failed)) shouldBe Seq((Some("variant"), true), (Some("variant"), true))
    logged.head.errorMessage.get should include("LLM client required")
    results.variant.queryCount shouldBe 2
    results.variant.failedCount shouldBe 2
    results.variant.errorRate shouldBe 1.0
  }

  "Experiment results" should "test the groups' metrics from the query log" in withContainers { pg =>
    val db = freshDatabase(pg)
    val results = run(stack(db, None).use { s =>
      def log(group: String, latency: Int, rating: Option[Int], failed: Boolean, expId: String): IO[Unit] =
        s.logs.logQuery("q", None, None, None, None, None, latency, 3, 3, None,
          experimentId = Some(expId), variant = Some(group), failed = failed).flatMap { id =>
          rating.fold(IO.unit)(r => s.logs.addFeedback(id, r, None, None).void)
        }
      for {
        exp <- s.experiments.createExperiment(CreateExperimentRequest("stats", variantConfig = ExperimentConfigSnapshot(topK = Some(3))))
        // R: t.test(c(110, 120, 130, 140), c(100, 110, 120, 130)) -> t = 1.0954, df = 6, p = 0.3153
        _ <- Seq(100, 110, 120, 130).foldLeft(IO.unit)((acc, l) => acc *> log("baseline", l, Some(3), failed = false, exp.id))
        _ <- Seq(110, 120, 130, 140).foldLeft(IO.unit)((acc, l) => acc *> log("variant", l, Some(5), failed = false, exp.id))
        _ <- log("variant", 5000, None, failed = true, exp.id)
        results <- s.experiments.getResults(exp.id)
      } yield results.get
    })
    results.baseline.queryCount shouldBe 4
    results.variant.queryCount shouldBe 5
    results.variant.avgLatencyMs shouldBe 125.0 // the failed query's latency is not averaged in
    results.variant.errorRate shouldBe 0.2
    val latency = results.analysis.tests.find(_.metric == "latencyMs").get
    latency.test shouldBe "welch-t"
    latency.statistic shouldBe 1.0954 +- 1e-4
    latency.pValue shouldBe 0.3153 +- 1e-3
    latency.significant shouldBe false
    // Constant ratings in each group: no variance, so no t-test on ratings
    results.analysis.tests.map(_.metric) should not contain "rating"
    results.analysis.tests.find(_.metric == "positiveRate").map(_.variant) shouldBe Some(1.0)
  }

  "The experiment API" should "answer 404 for unknown experiments, 409 for invalid transitions and 400 for invalid definitions" in withContainers { pg =>
    val db = freshDatabase(pg)
    val statuses = run(stack(db, None).use { s =>
      def call(method: Method, uri: Uri, body: Option[Json] = None): IO[Status] =
        s.experimentRoutes.run(body.fold(Request[IO](method, uri))(b => Request[IO](method, uri).withEntity(b))).map(_.status)
      def create(json: Json): IO[Status] = call(Method.POST, uri"/api/v1/experiments", Some(json))
      for {
        exp <- s.experiments.createExperiment(CreateExperimentRequest("a", variantConfig = ExperimentConfigSnapshot(topK = Some(3))))
        other <- s.experiments.createExperiment(CreateExperimentRequest("b", variantConfig = ExperimentConfigSnapshot(topK = Some(4))))
        unknownStart <- call(Method.PUT, uri"/api/v1/experiments/exp-nope/start")
        unknownStop <- call(Method.PUT, uri"/api/v1/experiments/exp-nope/stop")
        unknownDelete <- call(Method.DELETE, uri"/api/v1/experiments/exp-nope")
        stopDraft <- call(Method.PUT, Uri.unsafeFromString(s"/api/v1/experiments/${exp.id}/stop"))
        start <- call(Method.PUT, Uri.unsafeFromString(s"/api/v1/experiments/${exp.id}/start"))
        startAgain <- call(Method.PUT, Uri.unsafeFromString(s"/api/v1/experiments/${exp.id}/start"))
        startOther <- call(Method.PUT, Uri.unsafeFromString(s"/api/v1/experiments/${other.id}/start"))
        deleteRunning <- call(Method.DELETE, Uri.unsafeFromString(s"/api/v1/experiments/${exp.id}"))
        archiveRunning <- call(Method.PUT, Uri.unsafeFromString(s"/api/v1/experiments/${exp.id}/archive"))
        badSplit <- create(Json.obj("name" -> "x".asJson, "baselineConfig" -> Json.obj(), "variantConfig" -> Json.obj(), "trafficSplit" -> 1.5.asJson))
        longName <- create(Json.obj("name" -> ("n" * 256).asJson, "baselineConfig" -> Json.obj(), "variantConfig" -> Json.obj()))
        badFusion <- create(Json.obj("name" -> "f".asJson, "baselineConfig" -> Json.obj(), "variantConfig" -> Json.obj("fusionStrategy" -> "best".asJson)))
        fine <- create(Json.obj("name" -> ("n" * 255).asJson, "variantConfig" -> Json.obj("reranking" -> "llm".asJson), "trafficSplit" -> 1.0.asJson))
        badStatus <- call(Method.GET, uri"/api/v1/experiments?status=paused")
        running <- s.experiments.listExperiments(Some(ExperimentStatus.Running))
      } yield (unknownStart, unknownStop, unknownDelete, stopDraft, start, startAgain, startOther, deleteRunning,
        archiveRunning, badSplit, longName, badFusion, fine, badStatus, running.experiments.map(_.id))
    })
    val (unknownStart, unknownStop, unknownDelete, stopDraft, start, startAgain, startOther, deleteRunning,
      archiveRunning, badSplit, longName, badFusion, fine, badStatus, running) = statuses
    (unknownStart, unknownStop, unknownDelete) shouldBe (Status.NotFound, Status.NotFound, Status.NotFound)
    stopDraft shouldBe Status.Conflict
    start shouldBe Status.Ok
    (startAgain, startOther, deleteRunning, archiveRunning) shouldBe (Status.Conflict, Status.Conflict, Status.Conflict, Status.Conflict)
    (badSplit, longName, badFusion) shouldBe (Status.BadRequest, Status.BadRequest, Status.BadRequest)
    fine shouldBe Status.Created
    badStatus shouldBe Status.BadRequest
    running should have size 1
  }

  "PATCH /api/v1/experiments/{id}" should "update a draft, validate it, and refuse other statuses" in withContainers { pg =>
    val db = freshDatabase(pg)
    val results = run(stack(db, None).use { s =>
      def patch(id: String, body: Json): IO[(Status, Json)] =
        s.experimentRoutes.run(Request[IO](Method.PATCH, Uri.unsafeFromString(s"/api/v1/experiments/$id")).withEntity(body))
          .flatMap(r => r.as[Json].map(r.status -> _))
      for {
        draft <- s.experiments.createExperiment(CreateExperimentRequest("draft", Some("first"), variantConfig = ExperimentConfigSnapshot(topK = Some(3))))
        updated <- patch(draft.id, Json.obj(
          "name" -> " renamed ".asJson,
          "variantConfig" -> Json.obj("topK" -> 8.asJson, "systemPrompt" -> "Be brief.".asJson),
          "trafficSplit" -> 0.25.asJson
        ))
        stored <- s.experiments.getExperiment(draft.id)
        invalid <- patch(draft.id, Json.obj("trafficSplit" -> 2.0.asJson, "variantConfig" -> Json.obj("topK" -> 0.asJson)))
        afterInvalid <- s.experiments.getExperiment(draft.id)
        unknown <- patch("exp-missing", Json.obj("name" -> "x".asJson))
        _ <- s.experiments.startExperiment(draft.id)
        running <- patch(draft.id, Json.obj("name" -> "too late".asJson))
      } yield (draft, updated, stored, invalid, afterInvalid, unknown, running)
    })
    val (draft, (updatedStatus, updatedBody), stored, (invalidStatus, invalidBody), afterInvalid, (unknownStatus, _), (runningStatus, _)) = results

    updatedStatus shouldBe Status.Ok
    updatedBody.hcursor.downField("experiment").get[String]("name") shouldBe Right("renamed")
    val exp = stored.get
    (exp.name, exp.description, exp.trafficSplit) shouldBe ("renamed", Some("first"), 0.25)
    exp.variantConfig shouldBe ExperimentConfigSnapshot(topK = Some(8), systemPrompt = Some("Be brief."))
    exp.baselineConfig shouldBe draft.baselineConfig

    invalidStatus shouldBe Status.BadRequest
    invalidBody.hcursor.get[String]("message").toOption.get should (include("trafficSplit") and include("topK"))
    afterInvalid shouldBe stored
    unknownStatus shouldBe Status.NotFound
    runningStatus shouldBe Status.Conflict
  }

  "An experiment's status" should "keep its JSON string" in {
    val json = Experiment("exp-1", "n", status = ExperimentStatus.Running, createdAt = java.time.Instant.EPOCH).asJson
    json.hcursor.get[String]("status") shouldBe Right("running")
    json.as[Experiment].map(_.status) shouldBe Right(ExperimentStatus.Running)
    Json.fromString("paused").as[ExperimentStatus].isLeft shouldBe true
  }
}
