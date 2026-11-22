package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.server.websocket.WebSocketBuilder2
import org.llm4s.ragbox.RagboxApp
import org.llm4s.ragbox.config._
import org.llm4s.ragbox.model.{CreateExperimentRequest, ExperimentConfigSnapshot}
import org.llm4s.ragbox.registry.QueryLogRegistry
import org.llm4s.ragbox.testkit.PgVectorFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import javax.sql.DataSource

/**
 * Averages over no data (#66), through the full application stack over a real database. An
 * average over zero rows (SQL `AVG` returns `NULL`, `0.0 / 0` is `NaN`) must come back as `0` or
 * as `null` in an `Option` field, never as `null` in a field the admin UI types as `number`
 * (circe encodes a `NaN` Double as `null`).
 *
 * Each response is checked two ways: the averages themselves, and - for the models in the API
 * contract fixture - that no field which is a number in the fixture is `null` in the response.
 */
class EmptyDataAveragesStackSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private val open = SecurityConfig(apiKey = None, auth = AuthConfig(
    mode = AuthMode.Open,
    basic = BasicAuthConfig("admin", None),
    oauth = None,
    jwtSecret = "jwt-secret-for-empty-data-averages-stack-spec-0123456789",
    jwtSecretExplicitlySet = true,
    jwtExpiration = 3600L,
    passwordHashIterations = 10000
  ))

  private def withApp[A](db: DatabaseConfig)(test: (HttpApp[IO], DataSource) => IO[A]): A =
    (for {
      ds <- pool(db)
      rag <- plainService(db)
      app <- RagboxApp.resource(testConfig(db).copy(security = open), ds, rag)
    } yield (app, ds)).use { case (app, ds) =>
      WebSocketBuilder2[IO].flatMap(wsb => test(app.httpApp(wsb), ds))
    }.unsafeRunSync()

  private def call(app: HttpApp[IO], req: Request[IO]): IO[(Status, Json)] =
    app.run(req).flatMap(r => r.as[Json].map(r.status -> _))

  private def get(app: HttpApp[IO], path: String): IO[Json] =
    call(app, Request[IO](Method.GET, Uri.unsafeFromString(path))).map { case (status, json) =>
      withClue(s"GET $path: $json\n")(status shouldBe Status.Ok)
      json
    }

  private def createExperiment(app: HttpApp[IO]): IO[String] =
    call(app, Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/experiments"))
      .withEntity(CreateExperimentRequest("empty", variantConfig = ExperimentConfigSnapshot(topK = Some(3))).asJson))
      .map { case (status, json) =>
        status shouldBe Status.Created
        json.hcursor.downField("experiment").get[String]("id").toOption.get
      }

  private def fixture(name: String): Map[String, Json] =
    parse(new String(Files.readAllBytes(Paths.get("admin-ui", "src", "types", "contract", name)), StandardCharsets.UTF_8))
      .toOption.flatMap(_.asObject).get.toMap

  /** The admin UI's contract samples by model name, and the same with every Option field null. */
  private lazy val contract: Map[String, Json] = fixture("api-contract.json")
  private lazy val contractNulls: Map[String, Json] = fixture("api-contract-nulls.json")

  /**
   * The fields of `json`, a `model`, that are `null` but a non-Option number in the contract (a
   * number in the sample that the nulls fixture does not null).
   */
  private def noNullNumbers(model: String, json: Json): Unit = {
    val sample = contract(model).asObject.get
    val nulls = contractNulls(model).asObject.get
    val numbers = sample.toList.collect { case (k, v) if v.isNumber && !nulls(k).exists(_.isNull) => k }
    withClue(s"$model numbers encoded as null in $json: ") {
      numbers.filter(k => json.hcursor.downField(k).focus.exists(_.isNull)) shouldBe empty
    }
  }

  private def num(json: Json, path: String*): Option[Double] =
    path.foldLeft(json.hcursor: io.circe.ACursor)(_.downField(_)).focus.flatMap(_.asNumber).map(_.toDouble)

  private def isNull(json: Json, path: String*): Boolean =
    path.foldLeft(json.hcursor: io.circe.ACursor)(_.downField(_)).focus.exists(_.isNull)

  "With no documents, the statistics" should "give zero averages, not NaN or null" in withContainers { pg =>
    withApp(freshDatabase(pg)) { (app, _) =>
      for {
        stats <- get(app, "/api/v1/stats")
        detailed <- get(app, "/api/v1/visibility/stats")
        collections <- get(app, "/api/v1/visibility/collections")
      } yield {
        stats.hcursor.get[Int]("documentCount") shouldBe Right(0)
        noNullNumbers("DetailedStatsResponse", detailed)
        noNullNumbers("ChunkSizeDistribution", detailed.hcursor.downField("chunkSizeDistribution").focus.get)
        num(detailed, "avgChunksPerDocument") shouldBe Some(0.0)
        num(detailed, "chunkSizeDistribution", "avg") shouldBe Some(0.0)
        num(detailed, "chunkSizeDistribution", "median") shouldBe Some(0.0)
        num(detailed, "chunkSizeDistribution", "p90") shouldBe Some(0.0)
        collections.hcursor.downField("collections").values.map(_.size) shouldBe Some(0)
      }
    }
  }

  "With no queries, the analytics summary" should "give zero averages and no rating" in withContainers { pg =>
    withApp(freshDatabase(pg)) { (app, _) =>
      get(app, "/api/v1/analytics/queries/summary").map { summary =>
        summary.hcursor.get[Int]("totalQueries") shouldBe Right(0)
        num(summary, "averageLatencyMs") shouldBe Some(0.0)
        num(summary, "averageChunksRetrieved") shouldBe Some(0.0)
        num(summary, "averageChunksUsed") shouldBe Some(0.0)
        isNull(summary, "averageRating") shouldBe true
        Seq("p50LatencyMs", "p95LatencyMs", "p99LatencyMs").map(num(summary, _)) shouldBe Seq.fill(3)(Some(0.0))
      }
    }
  }

  "With no queries, the suggestions" should "be generated without NaN evidence" in withContainers { pg =>
    withApp(freshDatabase(pg)) { (app, _) =>
      for {
        suggestions <- get(app, "/api/v1/analytics/suggestions")
        summary <- get(app, "/api/v1/analytics/suggestions/summary")
        collection <- get(app, "/api/v1/analytics/suggestions/collection/docs")
      } yield {
        suggestions.hcursor.get[Int]("analyzedQueries") shouldBe Right(0)
        // Every evidence figure is a number
        val evidence = suggestions.hcursor.downField("suggestions").values.toSeq.flatten.map(_.hcursor.downField("evidence"))
        evidence.flatMap(e => Seq(e.downField("currentValue").focus, e.downField("threshold").focus))
          .forall(_.exists(_.isNumber)) shouldBe true
        summary.hcursor.get[Int]("total").isRight shouldBe true
        collection.hcursor.get[Int]("analyzedQueries") shouldBe Right(0)
      }
    }
  }

  "An experiment with no queries" should "have zero averages, a zero error rate and no tests" in withContainers { pg =>
    withApp(freshDatabase(pg)) { (app, _) =>
      for {
        id <- createExperiment(app)
        results <- get(app, s"/api/v1/experiments/$id/results")
      } yield {
        noNullNumbers("ExperimentResults", results)
        for (group <- Seq("baseline", "variant"))
          noNullNumbers("ExperimentVariantMetrics", results.hcursor.downField(group).focus.get)
        for (group <- Seq("baseline", "variant")) {
          num(results, group, "queryCount") shouldBe Some(0.0)
          num(results, group, "avgLatencyMs") shouldBe Some(0.0)
          num(results, group, "avgChunksUsed") shouldBe Some(0.0)
          num(results, group, "avgChunksRetrieved") shouldBe Some(0.0)
          num(results, group, "errorRate") shouldBe Some(0.0)
          isNull(results, group, "avgRating") shouldBe true
          isNull(results, group, "latencyStdDevMs") shouldBe true
        }
        results.hcursor.downField("analysis").downField("tests").values.map(_.size) shouldBe Some(0)
      }
    }
  }

  "Only failed queries" should "leave the success averages at zero, in the summary and an experiment" in withContainers { pg =>
    withApp(freshDatabase(pg)) { (app, ds) =>
      for {
        id <- createExperiment(app)
        logs <- QueryLogRegistry(ds)
        _ <- List("baseline", "variant", "variant").traverse_ { group =>
          logs.logQuery(
            queryText = "q", collectionPattern = None, userId = None, embeddingLatencyMs = None, searchLatencyMs = None,
            llmLatencyMs = None, totalLatencyMs = 0, chunksRetrieved = 0, chunksUsed = 0, answerTokens = None,
            experimentId = Some(id), variant = Some(group), failed = true, errorMessage = Some("boom")
          )
        }
        summary <- get(app, "/api/v1/analytics/queries/summary")
        suggestions <- get(app, "/api/v1/analytics/suggestions")
        results <- get(app, s"/api/v1/experiments/$id/results")
      } yield {
        summary.hcursor.get[Int]("totalQueries") shouldBe Right(0)
        num(summary, "averageLatencyMs") shouldBe Some(0.0)
        suggestions.hcursor.get[Int]("analyzedQueries") shouldBe Right(0)
        noNullNumbers("ExperimentResults", results)
        for (group <- Seq("baseline", "variant"))
          noNullNumbers("ExperimentVariantMetrics", results.hcursor.downField(group).focus.get)
        num(results, "baseline", "queryCount") shouldBe Some(1.0)
        num(results, "baseline", "errorRate") shouldBe Some(1.0)
        num(results, "variant", "errorRate") shouldBe Some(1.0)
        num(results, "variant", "avgLatencyMs") shouldBe Some(0.0)
        num(results, "variant", "avgChunksUsed") shouldBe Some(0.0)
        isNull(results, "variant", "latencyStdDevMs") shouldBe true
      }
    }
  }
}
