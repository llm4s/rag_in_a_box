package org.llm4s.ragbox.service

import cats.effect.IO
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.registry.{ExperimentAlreadyRunningError, ExperimentRegistry}

import java.time.{Duration, Instant}
import javax.sql.DataSource
import scala.util.Random

/** No experiment has this id. Routes map it to 404 Not Found. */
final case class ExperimentNotFoundError(id: String) extends RuntimeException(s"Experiment '$id' not found")

/**
 * The experiment is not in a status the operation applies to (starting one that is not a
 * draft, say). Routes map it to 409 Conflict.
 */
final case class ExperimentStateError(experiment: Experiment, operation: String, allowed: Set[ExperimentStatus])
    extends RuntimeException(
      s"Cannot $operation experiment '${experiment.name}': it is ${experiment.status.value}, and $operation applies only to " +
        s"${allowed.map(_.value).toSeq.sorted.mkString(" or ")} experiments"
    )

/** An experiment definition that is not valid. Routes map it to 400 Bad Request. */
final case class InvalidExperimentError(errors: Seq[String]) extends RuntimeException(errors.mkString("; "))

/**
 * Service for managing A/B testing experiments.
 *
 * Provides business logic for:
 * - Experiment lifecycle management (create, start, stop, archive)
 * - Traffic routing based on traffic split configuration
 * - Results calculation by aggregating query logs
 * - Two-sample significance tests of the results (see [[Statistics]])
 *
 * Operations on an experiment that does not exist fail with [[ExperimentNotFoundError]], and
 * ones its status does not allow with [[ExperimentStateError]] or
 * [[ExperimentAlreadyRunningError]].
 */
class ExperimentService(
  experimentRegistry: ExperimentRegistry,
  dataSource: DataSource
) {

  private val random = new Random()

  /**
   * Create a new experiment, after validating it (see [[ExperimentService.validate]]).
   */
  def createExperiment(request: CreateExperimentRequest): IO[Experiment] =
    ExperimentService.validate(request) match {
      case Nil => experimentRegistry.create(request.copy(name = request.name.trim))
      case errors => IO.raiseError(InvalidExperimentError(errors))
    }

  /**
   * Get an experiment by ID.
   */
  def getExperiment(id: String): IO[Option[Experiment]] =
    experimentRegistry.get(id)

  /**
   * List experiments, newest first, optionally filtered by status. `total` counts every
   * matching experiment, across pages.
   */
  def listExperiments(
    status: Option[ExperimentStatus] = None,
    limit: Int = ExperimentRegistry.DefaultListLimit,
    offset: Int = 0
  ): IO[ExperimentListResponse] =
    for {
      experiments <- experimentRegistry.list(status, limit, offset)
      total <- experimentRegistry.count(status)
    } yield ExperimentListResponse(experiments, total)

  /** The experiment `id`, in one of the `allowed` statuses for `operation`. */
  private def requireStatus(id: String, operation: String, allowed: Set[ExperimentStatus]): IO[Experiment] =
    experimentRegistry.get(id).flatMap {
      case None => IO.raiseError(ExperimentNotFoundError(id))
      case Some(exp) if !allowed.contains(exp.status) => IO.raiseError(ExperimentStateError(exp, operation, allowed))
      case Some(exp) => IO.pure(exp)
    }

  /**
   * Run a status transition that `requireStatus` allowed. It can still find the experiment in
   * another status when a concurrent request changed it in between: that is a conflict too.
   */
  private def transitioned(
    id: String,
    operation: String,
    allowed: Set[ExperimentStatus],
    done: String
  )(transition: IO[Option[Experiment]]): IO[ExperimentOperationResponse] =
    for {
      before <- requireStatus(id, operation, allowed)
      after <- transition
      exp <- after match {
        case Some(exp) => IO.pure(exp)
        case None => requireStatus(id, operation, allowed) *> IO.raiseError(ExperimentStateError(before, operation, allowed))
      }
    } yield ExperimentOperationResponse(success = true, message = s"Experiment '${exp.name}' $done successfully", experiment = Some(exp))

  /**
   * Start an experiment (transitions from draft to running).
   *
   * Only one experiment runs at a time. Fails with [[ExperimentAlreadyRunningError]] (409) if
   * another one is running - checked up front for a clear message, and enforced by the
   * database for two starts that race past the check.
   */
  def startExperiment(id: String): IO[ExperimentOperationResponse] =
    for {
      _ <- requireStatus(id, "start", Set(ExperimentStatus.Draft))
      running <- experimentRegistry.getRunning()
      _ <- running match {
        case Some(other) if other.id != id => IO.raiseError(ExperimentAlreadyRunningError(Some(other)))
        case _ => IO.unit
      }
      response <- transitioned(id, "start", Set(ExperimentStatus.Draft), "started")(experimentRegistry.start(id))
    } yield response

  /**
   * Stop a running experiment.
   */
  def stopExperiment(id: String): IO[ExperimentOperationResponse] =
    transitioned(id, "stop", Set(ExperimentStatus.Running), "stopped")(experimentRegistry.stop(id))

  /**
   * Archive a completed or draft experiment.
   */
  def archiveExperiment(id: String): IO[ExperimentOperationResponse] =
    transitioned(id, "archive", Set(ExperimentStatus.Draft, ExperimentStatus.Completed), "archived")(experimentRegistry.archive(id))

  /**
   * Update a draft experiment: each field the request gives replaces the experiment's, and the
   * result is validated like a new experiment. Only drafts can change, so a running experiment's
   * results always belong to one definition.
   */
  def updateExperiment(id: String, request: UpdateExperimentRequest): IO[ExperimentOperationResponse] =
    for {
      exp <- requireStatus(id, "update", Set(ExperimentStatus.Draft))
      merged = exp.copy(
        name = request.name.map(_.trim).getOrElse(exp.name),
        description = request.description.orElse(exp.description),
        baselineConfig = request.baselineConfig.getOrElse(exp.baselineConfig),
        variantConfig = request.variantConfig.getOrElse(exp.variantConfig),
        trafficSplit = request.trafficSplit.getOrElse(exp.trafficSplit)
      )
      _ <- ExperimentService.validate(CreateExperimentRequest(
        name = merged.name,
        description = merged.description,
        baselineConfig = merged.baselineConfig,
        variantConfig = merged.variantConfig,
        trafficSplit = Some(merged.trafficSplit),
        collection = merged.collection
      )) match {
        case Nil => IO.unit
        case errors => IO.raiseError(InvalidExperimentError(errors))
      }
      updated <- experimentRegistry.updateDraft(merged)
      result <- updated match {
        case Some(e) => IO.pure(e)
        case None => requireStatus(id, "update", Set(ExperimentStatus.Draft)) *> IO.raiseError(ExperimentStateError(exp, "update", Set(ExperimentStatus.Draft)))
      }
    } yield ExperimentOperationResponse(success = true, message = s"Experiment '${result.name}' updated successfully", experiment = Some(result))

  /**
   * Delete a draft experiment.
   */
  def deleteExperiment(id: String): IO[ExperimentOperationResponse] =
    for {
      exp <- requireStatus(id, "delete", Set(ExperimentStatus.Draft))
      deleted <- experimentRegistry.delete(id)
      _ <- IO.raiseUnless(deleted)(ExperimentStateError(exp, "delete", Set(ExperimentStatus.Draft)))
    } yield ExperimentOperationResponse(success = true, message = "Experiment deleted successfully", experiment = None)

  /**
   * Get the currently running experiment.
   */
  def getRunningExperiment(): IO[Option[Experiment]] =
    experimentRegistry.getRunning()

  /**
   * Route a query to baseline or variant based on traffic split.
   * Returns the experiment ID, the group ("baseline" or "variant") and its config.
   */
  def routeQuery(experiment: Experiment): (String, String, ExperimentConfigSnapshot) = {
    val roll = random.nextDouble()
    if (roll < experiment.trafficSplit) {
      (experiment.id, ExperimentService.Variant, experiment.variantConfig)
    } else {
      (experiment.id, ExperimentService.Baseline, experiment.baselineConfig)
    }
  }

  /**
   * Get experiment results with statistical analysis.
   * Aggregates query logs for baseline and variant, calculating metrics.
   */
  def getResults(experimentId: String): IO[Option[ExperimentResults]] =
    experimentRegistry.get(experimentId).flatMap {
      case None => IO.pure(None)
      case Some(experiment) => calculateResults(experiment).map(Some(_))
    }

  /**
   * Calculate results for an experiment by aggregating query logs.
   */
  private def calculateResults(experiment: Experiment): IO[ExperimentResults] =
    ConnectionPool.withConnection(dataSource) { conn =>
      val baseline = getVariantMetrics(conn, experiment.id, ExperimentService.Baseline)
      val variant = getVariantMetrics(conn, experiment.id, ExperimentService.Variant)

      val duration = experiment.startedAt.map { startTime =>
        val endTime = experiment.endedAt.getOrElse(Instant.now())
        formatDuration(Duration.between(startTime, endTime))
      }

      ExperimentResults(
        experimentId = experiment.id,
        experimentName = experiment.name,
        status = experiment.status,
        duration = duration,
        baseline = baseline.metrics,
        variant = variant.metrics,
        analysis = ExperimentService.analyze(baseline, variant)
      )
    }

  /**
   * Metrics for one group of an experiment, from the query log rows tagged with it. Latency,
   * chunks and ratings are over the queries that succeeded; the error rate is over all of them.
   */
  private def getVariantMetrics(conn: java.sql.Connection, experimentId: String, group: String): ExperimentService.GroupStats = {
    val sql =
      """SELECT
        |    COUNT(*) AS query_count,
        |    COUNT(*) FILTER (WHERE failed) AS failed_count,
        |    COUNT(*) FILTER (WHERE NOT failed) AS succeeded_count,
        |    AVG(total_latency_ms) FILTER (WHERE NOT failed) AS avg_latency,
        |    VAR_SAMP(total_latency_ms) FILTER (WHERE NOT failed) AS var_latency,
        |    AVG(user_rating) FILTER (WHERE NOT failed) AS avg_rating,
        |    VAR_SAMP(user_rating) FILTER (WHERE NOT failed) AS var_rating,
        |    COUNT(user_rating) FILTER (WHERE NOT failed) AS rated_count,
        |    COUNT(*) FILTER (WHERE NOT failed AND user_rating >= 4) AS positive_count,
        |    AVG(chunks_used) FILTER (WHERE NOT failed) AS avg_chunks_used,
        |    AVG(chunks_retrieved) FILTER (WHERE NOT failed) AS avg_chunks_retrieved
        |FROM query_logs
        |WHERE experiment_id = ? AND variant = ?""".stripMargin

    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, experimentId)
      stmt.setString(2, group)
      val rs = stmt.executeQuery()
      rs.next()
      def optDouble(column: String): Option[Double] = Option(rs.getDouble(column)).filterNot(_ => rs.wasNull())
      val queryCount = rs.getInt("query_count")
      val failedCount = rs.getInt("failed_count")
      val varLatency = optDouble("var_latency")
      val varRating = optDouble("var_rating")
      ExperimentService.GroupStats(
        metrics = ExperimentVariantMetrics(
          queryCount = queryCount,
          avgLatencyMs = optDouble("avg_latency").getOrElse(0.0),
          avgRating = optDouble("avg_rating"),
          ratedCount = rs.getInt("rated_count"),
          avgChunksUsed = optDouble("avg_chunks_used").getOrElse(0.0),
          avgChunksRetrieved = optDouble("avg_chunks_retrieved").getOrElse(0.0),
          failedCount = failedCount,
          errorRate = if (queryCount > 0) failedCount.toDouble / queryCount else 0.0,
          latencyStdDevMs = varLatency.map(math.sqrt),
          ratingStdDev = varRating.map(math.sqrt),
          positiveCount = rs.getInt("positive_count")
        ),
        succeededCount = rs.getInt("succeeded_count"),
        latencyVariance = varLatency,
        ratingVariance = varRating
      )
    } finally {
      stmt.close()
    }
  }

  /**
   * Format a duration into a human-readable string.
   */
  private def formatDuration(duration: Duration): String = {
    val days = duration.toDays
    val hours = duration.toHours % 24
    val minutes = duration.toMinutes % 60

    if (days > 0) {
      s"$days day${if (days != 1) "s" else ""}"
    } else if (hours > 0) {
      s"$hours hour${if (hours != 1) "s" else ""}"
    } else {
      s"$minutes minute${if (minutes != 1) "s" else ""}"
    }
  }

  /**
   * Close the service.
   */
  def close(): IO[Unit] = experimentRegistry.close()
}

object ExperimentService {

  /** The group names queries are logged under (`query_logs.variant`). */
  val Baseline: String = "baseline"
  val Variant: String = "variant"

  /** Longest experiment name (the `experiments.name` column). */
  val MaxNameLength: Int = 255

  private val FusionStrategies = Set("rrf", "weighted", "vector_only", "keyword_only")
  private val RerankingStrategies = Set("none", "cohere", "llm")

  /** One group's metrics and the summary statistics its tests need. */
  final case class GroupStats(
    metrics: ExperimentVariantMetrics,
    succeededCount: Int,
    latencyVariance: Option[Double],
    ratingVariance: Option[Double]
  )

  /** What is wrong with an experiment definition (empty when it is valid). */
  def validate(request: CreateExperimentRequest): Seq[String] = {
    val name = request.name.trim
    val nameErrors =
      if (name.isEmpty) Seq("name must not be empty")
      else if (name.length > MaxNameLength) Seq(s"name must be at most $MaxNameLength characters")
      else Nil
    val splitErrors = request.trafficSplit.toSeq.collect {
      case s if s.isNaN || s < 0 || s > 1 => s"trafficSplit must be between 0 and 1 (got $s)"
    }
    val collectionErrors = request.collection.toSeq.collect {
      case c if c.length > 255 => "collection must be at most 255 characters"
    }
    nameErrors ++ splitErrors ++ collectionErrors ++
      validateConfig("baselineConfig", request.baselineConfig) ++
      validateConfig("variantConfig", request.variantConfig)
  }

  /** What is wrong with a group's config (empty when it is valid). */
  def validateConfig(field: String, config: ExperimentConfigSnapshot): Seq[String] =
    Seq(
      config.topK.collect { case k if k < 1 || k > 100 => s"$field.topK must be between 1 and 100" },
      config.fusionStrategy.collect {
        case f if !FusionStrategies.contains(f) => s"$field.fusionStrategy must be one of ${FusionStrategies.toSeq.sorted.mkString(", ")}"
      },
      config.rrfK.collect { case k if k < 1 || k > 1000 => s"$field.rrfK must be between 1 and 1000" },
      config.reranking.collect {
        case r if !RerankingStrategies.contains(r.trim.toLowerCase) =>
          s"$field.reranking must be one of ${RerankingStrategies.toSeq.sorted.mkString(", ")}"
      },
      config.llmTemperature.collect { case t if t.isNaN || t < 0 || t > 2 => s"$field.llmTemperature must be between 0 and 2" }
    ).flatten

  private def percentChange(baseVal: Double, varVal: Double): String =
    if (baseVal == 0) {
      if (varVal == 0) "0.0%" else "N/A"
    } else {
      val change = ((varVal - baseVal) / baseVal) * 100
      val sign = if (change > 0) "+" else ""
      f"$sign$change%.1f%%"
    }

  /**
   * Compare the variant with the baseline: relative changes, and a two-sample test per metric
   * with enough data - Welch's t-test for mean latency and mean rating, a two-proportion z-test
   * for the error rate and the share of positive (4-5) ratings.
   */
  def analyze(baseline: GroupStats, variant: GroupStats): ExperimentAnalysis = {
    val b = baseline.metrics
    val v = variant.metrics

    def test(metric: String, name: String, baseValue: Double, varValue: Double)(result: Option[Statistics.TestResult]): Option[SignificanceTest] =
      result.map(r => SignificanceTest(metric, name, baseValue, varValue, r.statistic, r.pValue, r.pValue < ExperimentAnalysis.Alpha))

    val latency = test("latencyMs", "welch-t", b.avgLatencyMs, v.avgLatencyMs)(
      for {
        varB <- baseline.latencyVariance
        varV <- variant.latencyVariance
        r <- Statistics.welchTTest(baseline.succeededCount, b.avgLatencyMs, varB, variant.succeededCount, v.avgLatencyMs, varV)
      } yield r
    )
    val rating = (b.avgRating, v.avgRating) match {
      case (Some(rb), Some(rv)) =>
        test("rating", "welch-t", rb, rv)(
          for {
            varB <- baseline.ratingVariance
            varV <- variant.ratingVariance
            r <- Statistics.welchTTest(b.ratedCount, rb, varB, v.ratedCount, rv, varV)
          } yield r
        )
      case _ => None
    }
    val errorRate = test("errorRate", "two-proportion-z", b.errorRate, v.errorRate)(
      Statistics.twoProportionZTest(b.failedCount, b.queryCount, v.failedCount, v.queryCount)
    )
    def positiveRate(m: ExperimentVariantMetrics) = if (m.ratedCount > 0) m.positiveCount.toDouble / m.ratedCount else 0.0
    val positive = test("positiveRate", "two-proportion-z", positiveRate(b), positiveRate(v))(
      Statistics.twoProportionZTest(b.positiveCount, b.ratedCount, v.positiveCount, v.ratedCount)
    )

    ExperimentAnalysis(
      latencyChange = percentChange(b.avgLatencyMs, v.avgLatencyMs),
      ratingChange = (b.avgRating, v.avgRating) match {
        case (Some(base), Some(var_)) => Some(percentChange(base, var_))
        case _ => None
      },
      chunksUsedChange = percentChange(b.avgChunksUsed, v.avgChunksUsed),
      errorRateChange = percentChange(b.errorRate, v.errorRate),
      tests = Seq(latency, rating, errorRate, positive).flatten
    )
  }

  /**
   * Create an experiment service.
   */
  def apply(experimentRegistry: ExperimentRegistry, dataSource: DataSource): ExperimentService =
    new ExperimentService(experimentRegistry, dataSource)
}
