package org.llm4s.ragbox.service

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{ExperimentRegistry, QueryLogRegistry}
import org.llm4s.ragbox.testkit.PgVectorFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._
import scala.util.{Random, Using}

/**
 * [[ExperimentService]]: traffic split assignment, results aggregation from the query log, and
 * definition validation. The significance tests themselves are covered by `StatisticsSpec`, and
 * the HTTP statuses by `ExperimentIntegrationSpec` and `ExperimentRoutesSpec`.
 */
class ExperimentServiceSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

  private val experiment = Experiment(
    id = "exp-1",
    name = "split",
    variantConfig = ExperimentConfigSnapshot(topK = Some(3)),
    baselineConfig = ExperimentConfigSnapshot(topK = Some(5)),
    createdAt = Instant.EPOCH
  )

  /** A service for `routeQuery` only: it never touches the registry or the database. */
  private def offline(random: Random): ExperimentService = ExperimentService(null, null, random)

  private def groups(service: ExperimentService, exp: Experiment, n: Int): Seq[String] =
    Seq.fill(n)(service.routeQuery(exp)._2)

  // ------------------------------------------------------------ assignment

  "routeQuery" should "send every query to the baseline at a split of 0 and to the variant at 1" in {
    val service = offline(new Random(1))
    groups(service, experiment.copy(trafficSplit = 0.0), 1000).distinct shouldBe Seq(ExperimentService.Baseline)
    groups(service, experiment.copy(trafficSplit = 1.0), 1000).distinct shouldBe Seq(ExperimentService.Variant)
  }

  it should "return the experiment id and the chosen group's config" in {
    val service = offline(new Random(1))
    service.routeQuery(experiment.copy(trafficSplit = 1.0)) shouldBe ("exp-1", "variant", ExperimentConfigSnapshot(topK = Some(3)))
    service.routeQuery(experiment.copy(trafficSplit = 0.0)) shouldBe ("exp-1", "baseline", ExperimentConfigSnapshot(topK = Some(5)))
  }

  it should "assign the same sequence of groups for the same seed" in {
    val exp = experiment.copy(trafficSplit = 0.5)
    groups(offline(new Random(42)), exp, 200) shouldBe groups(offline(new Random(42)), exp, 200)
    groups(offline(new Random(42)), exp, 200) should not be groups(offline(new Random(43)), exp, 200)
  }

  it should "send about trafficSplit of the queries to the variant" in {
    val service = offline(new Random(7))
    Seq(0.1, 0.3, 0.5, 0.9).foreach { split =>
      val share = groups(service, experiment.copy(trafficSplit = split), 20000).count(_ == ExperimentService.Variant) / 20000.0
      // binomial sd at n = 20000 is at most 0.0036; 0.02 is over five of them
      share shouldBe split +- 0.02
    }
  }

  // ------------------------------------------------------------ validation

  "validate" should "accept a minimal definition and report every problem of a bad one" in {
    ExperimentService.validate(CreateExperimentRequest("ok", variantConfig = ExperimentConfigSnapshot())) shouldBe empty
    val errors = ExperimentService.validate(CreateExperimentRequest(
      name = "   ",
      variantConfig = ExperimentConfigSnapshot(topK = Some(0), fusionStrategy = Some("magic"), rrfK = Some(0), reranking = Some("x"), llmTemperature = Some(3.0)),
      baselineConfig = ExperimentConfigSnapshot(llmTemperature = Some(Double.NaN)),
      trafficSplit = Some(1.5),
      collection = Some("c" * 256)
    ))
    errors should contain allOf (
      "name must not be empty",
      "trafficSplit must be between 0 and 1 (got 1.5)",
      "collection must be at most 255 characters",
      "variantConfig.topK must be between 1 and 100",
      "variantConfig.rrfK must be between 1 and 1000",
      "variantConfig.llmTemperature must be between 0 and 2",
      "baselineConfig.llmTemperature must be between 0 and 2"
    )
    errors.exists(_.startsWith("variantConfig.fusionStrategy must be one of")) shouldBe true
    errors.exists(_.startsWith("variantConfig.reranking must be one of")) shouldBe true
    ExperimentService.validate(CreateExperimentRequest("n" * 256, variantConfig = ExperimentConfigSnapshot())) shouldBe
      Seq("name must be at most 255 characters")
  }

  it should "accept the reranking strategy case-insensitively and a NaN split as invalid" in {
    ExperimentService.validateConfig("v", ExperimentConfigSnapshot(reranking = Some(" LLM "))) shouldBe empty
    ExperimentService.validate(CreateExperimentRequest("x", variantConfig = ExperimentConfigSnapshot(), trafficSplit = Some(Double.NaN))) should have size 1
  }

  "createExperiment" should "trim the name, and not store an invalid definition" in withContainers { pg =>
    val db = freshDatabase(pg)
    val (created, invalid, listed) = run(service(db).use { case (svc, _) =>
      for {
        created <- svc.createExperiment(CreateExperimentRequest("  padded  ", variantConfig = ExperimentConfigSnapshot()))
        invalid <- svc.createExperiment(CreateExperimentRequest("", variantConfig = ExperimentConfigSnapshot())).attempt
        listed <- svc.listExperiments()
      } yield (created, invalid, listed)
    })
    created.name shouldBe "padded"
    created.status shouldBe ExperimentStatus.Draft
    invalid shouldBe Left(InvalidExperimentError(Seq("name must not be empty")))
    listed.total shouldBe 1
  }

  // ------------------------------------------------------------ results

  private def service(db: DatabaseConfig): Resource[IO, (ExperimentService, QueryLogRegistry)] =
    pool(db).evalMap { ds =>
      for {
        registry <- ExperimentRegistry(ds)
        logs <- QueryLogRegistry(ds)
      } yield (ExperimentService(registry, ds), logs)
    }

  /** Log one query of `experiment`'s `group`; rate it when `rating` is given. */
  private def logQuery(
    logs: QueryLogRegistry,
    experimentId: String,
    group: String,
    latency: Int,
    chunksRetrieved: Int = 4,
    chunksUsed: Int = 2,
    rating: Option[Int] = None,
    failed: Boolean = false
  ): IO[Unit] =
    logs.logQuery(
      "q", None, None, None, None, None, latency, chunksRetrieved, chunksUsed, None,
      experimentId = Some(experimentId), variant = Some(group), failed = failed
    ).flatMap(id => rating.traverse_(r => logs.addFeedback(id, r, None, None)))

  "getResults" should "aggregate each group's own successful queries, ratings and chunks" in withContainers { pg =>
    val db = freshDatabase(pg)
    val (results, missing) = run(service(db).use { case (svc, logs) =>
      for {
        exp <- svc.createExperiment(CreateExperimentRequest("agg", variantConfig = ExperimentConfigSnapshot(topK = Some(2))))
        other <- svc.createExperiment(CreateExperimentRequest("other", variantConfig = ExperimentConfigSnapshot()))
        _ <- svc.startExperiment(exp.id)
        // baseline: three successes, two rated (5 and 3), one failure whose figures are not averaged
        _ <- logQuery(logs, exp.id, "baseline", 100, chunksRetrieved = 6, chunksUsed = 3, rating = Some(5))
        _ <- logQuery(logs, exp.id, "baseline", 200, chunksRetrieved = 4, chunksUsed = 1, rating = Some(3))
        _ <- logQuery(logs, exp.id, "baseline", 300, chunksRetrieved = 2, chunksUsed = 2)
        _ <- logQuery(logs, exp.id, "baseline", 9000, chunksRetrieved = 0, chunksUsed = 0, rating = Some(1), failed = true)
        // variant: two successes rated 4 and 4
        _ <- logQuery(logs, exp.id, "variant", 50, rating = Some(4))
        _ <- logQuery(logs, exp.id, "variant", 70, rating = Some(4))
        // another experiment's queries do not count
        _ <- logQuery(logs, other.id, "baseline", 5000, rating = Some(1))
        results <- svc.getResults(exp.id)
        missing <- svc.getResults("exp-missing")
      } yield (results.get, missing)
    })
    missing shouldBe None
    results.status shouldBe ExperimentStatus.Running
    results.duration shouldBe Some("0 minutes")

    val b = results.baseline
    (b.queryCount, b.failedCount, b.errorRate) shouldBe (4, 1, 0.25)
    b.avgLatencyMs shouldBe 200.0
    b.latencyStdDevMs.get shouldBe 100.0 +- 1e-9
    (b.avgRating, b.ratedCount, b.positiveCount) shouldBe (Some(4.0), 2, 1)
    b.ratingStdDev.get shouldBe math.sqrt(2.0) +- 1e-9
    b.avgChunksRetrieved shouldBe 4.0
    b.avgChunksUsed shouldBe 2.0

    val v = results.variant
    (v.queryCount, v.failedCount, v.errorRate) shouldBe (2, 0, 0.0)
    v.avgLatencyMs shouldBe 60.0
    (v.avgRating, v.ratedCount, v.positiveCount) shouldBe (Some(4.0), 2, 2)
    v.ratingStdDev shouldBe Some(0.0)

    results.analysis.latencyChange shouldBe "-70.0%"
    results.analysis.ratingChange shouldBe Some("0.0%")
    results.analysis.chunksUsedChange shouldBe "0.0%"
    results.analysis.errorRateChange shouldBe "-100.0%"
    results.analysis.tests.map(_.metric) should contain allOf ("latencyMs", "errorRate", "positiveRate")
  }

  it should "report empty groups without averages or tests, and no duration before the start" in withContainers { pg =>
    val db = freshDatabase(pg)
    val results = run(service(db).use { case (svc, _) =>
      svc.createExperiment(CreateExperimentRequest("empty", variantConfig = ExperimentConfigSnapshot()))
        .flatMap(exp => svc.getResults(exp.id))
    }).get
    results.duration shouldBe None
    results.baseline shouldBe ExperimentVariantMetrics(0, 0.0, None, 0, 0.0, 0.0)
    results.variant shouldBe results.baseline
    results.analysis.tests shouldBe empty
    results.analysis.ratingChange shouldBe None
    results.analysis.latencyChange shouldBe "0.0%"
  }

  it should "measure the duration from the start to the end, in the largest whole unit" in withContainers { pg =>
    val db = freshDatabase(pg)
    def durationAfter(startedAgo: String, endedAgo: Option[String]): IO[Option[String]] = service(db).use { case (svc, _) =>
      for {
        exp <- svc.createExperiment(CreateExperimentRequest(s"d$startedAgo", variantConfig = ExperimentConfigSnapshot()))
        _ <- connect(db).use(conn => IO.blocking {
          Using.resource(conn.prepareStatement(
            s"UPDATE experiments SET status = 'completed', started_at = NOW() - INTERVAL '$startedAgo', " +
              s"ended_at = ${endedAgo.fold("NULL")(e => s"NOW() - INTERVAL '$e'")} WHERE id = ?"
          )) { stmt => stmt.setString(1, exp.id); stmt.executeUpdate() }
        })
        results <- svc.getResults(exp.id)
      } yield results.flatMap(_.duration)
    }
    run(durationAfter("3 days 5 hours", Some("1 hour"))) shouldBe Some("3 days")
    run(durationAfter("1 day 30 minutes", Some("10 minutes"))) shouldBe Some("1 day")
    run(durationAfter("2 hours 59 minutes", None)) shouldBe Some("2 hours")
    run(durationAfter("61 minutes", None)) shouldBe Some("1 hour")
    run(durationAfter("1 minute 10 seconds", None)) shouldBe Some("1 minute")
  }

  // ------------------------------------------------------------ lifecycle

  "The experiment lifecycle" should "go draft, running, completed, archived, with conflicts for anything else" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(service(db).use { case (svc, _) =>
      for {
        exp <- svc.createExperiment(CreateExperimentRequest("life", variantConfig = ExperimentConfigSnapshot()))
        archiveDraftOther <- svc.createExperiment(CreateExperimentRequest("archived draft", variantConfig = ExperimentConfigSnapshot()))
        started <- svc.startExperiment(exp.id)
        running <- svc.getRunningExperiment()
        update <- svc.updateExperiment(exp.id, UpdateExperimentRequest(name = Some("x"))).attempt
        stopped <- svc.stopExperiment(exp.id)
        stopAgain <- svc.stopExperiment(exp.id).attempt
        startAgain <- svc.startExperiment(exp.id).attempt
        archived <- svc.archiveExperiment(exp.id)
        draftArchived <- svc.archiveExperiment(archiveDraftOther.id)
        deleteArchived <- svc.deleteExperiment(exp.id).attempt
        missing <- svc.stopExperiment("exp-missing").attempt
        byStatus <- svc.listExperiments(Some(ExperimentStatus.Archived))
      } yield {
        started.experiment.map(_.status) shouldBe Some(ExperimentStatus.Running)
        started.message shouldBe "Experiment 'life' started successfully"
        running.map(_.id) shouldBe Some(exp.id)
        update.left.map(_.getMessage) shouldBe Left("Cannot update experiment 'life': it is running, and update applies only to draft experiments")
        stopped.experiment.flatMap(_.endedAt) should not be empty
        stopAgain.left.map(_.getClass) shouldBe Left(classOf[ExperimentStateError])
        startAgain.left.map(_.getClass) shouldBe Left(classOf[ExperimentStateError])
        archived.experiment.map(_.status) shouldBe Some(ExperimentStatus.Archived)
        draftArchived.experiment.map(_.status) shouldBe Some(ExperimentStatus.Archived)
        deleteArchived.left.map(_.getMessage) shouldBe
          Left("Cannot delete experiment 'life': it is archived, and delete applies only to draft experiments")
        missing shouldBe Left(ExperimentNotFoundError("exp-missing"))
        byStatus.total shouldBe 2
      }
    })
  }

  "updateExperiment" should "keep the fields the request leaves out, and validate the merged definition" in withContainers { pg =>
    val db = freshDatabase(pg)
    run(service(db).use { case (svc, _) =>
      for {
        exp <- svc.createExperiment(CreateExperimentRequest(
          "draft", description = Some("d"), variantConfig = ExperimentConfigSnapshot(topK = Some(3)), trafficSplit = Some(0.2), collection = Some("docs")
        ))
        updated <- svc.updateExperiment(exp.id, UpdateExperimentRequest(name = Some("  renamed "), trafficSplit = Some(0.7)))
        invalid <- svc.updateExperiment(exp.id, UpdateExperimentRequest(variantConfig = Some(ExperimentConfigSnapshot(topK = Some(500))))).attempt
        stored <- svc.getExperiment(exp.id)
        deleted <- svc.deleteExperiment(exp.id)
        afterDelete <- svc.getExperiment(exp.id)
      } yield {
        val e = updated.experiment.get
        (e.name, e.description, e.trafficSplit, e.variantConfig, e.collection) shouldBe
          ("renamed", Some("d"), 0.7, ExperimentConfigSnapshot(topK = Some(3)), Some("docs"))
        invalid shouldBe Left(InvalidExperimentError(Seq("variantConfig.topK must be between 1 and 100")))
        stored shouldBe Some(e)
        deleted.success shouldBe true
        afterDelete shouldBe None
      }
    })
  }
}
