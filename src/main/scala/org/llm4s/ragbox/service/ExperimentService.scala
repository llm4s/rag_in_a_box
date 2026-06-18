package org.llm4s.ragbox.service

import cats.effect.IO
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{ExperimentRegistry, QueryLogRegistryBase}

import java.sql.Connection
import java.time.{Duration, Instant}
import javax.sql.DataSource
import scala.util.Random

/**
 * Service for managing A/B testing experiments.
 *
 * Provides business logic for:
 * - Experiment lifecycle management (create, start, stop, archive)
 * - Traffic routing based on traffic split configuration
 * - Results calculation by aggregating query logs
 * - Statistical analysis of experiment outcomes
 */
class ExperimentService(
  experimentRegistry: ExperimentRegistry,
  queryLogRegistry: QueryLogRegistryBase,
  dataSource: DataSource
) {

  private val random = new Random()

  /**
   * Create a new experiment.
   */
  def createExperiment(request: CreateExperimentRequest): IO[Experiment] =
    experimentRegistry.create(request)

  /**
   * Get an experiment by ID.
   */
  def getExperiment(id: String): IO[Option[Experiment]] =
    experimentRegistry.get(id)

  /**
   * List all experiments, optionally filtered by status.
   */
  def listExperiments(status: Option[String] = None): IO[ExperimentListResponse] =
    experimentRegistry.list(status).map { experiments =>
      ExperimentListResponse(experiments, experiments.size)
    }

  /**
   * Start an experiment (transitions from draft to running).
   * Only one experiment should be running at a time.
   */
  def startExperiment(id: String): IO[ExperimentOperationResponse] = {
    for {
      // Check if another experiment is already running
      running <- experimentRegistry.getRunning()
      result <- running match {
        case Some(other) if other.id != id =>
          IO.pure(ExperimentOperationResponse(
            success = false,
            message = s"Another experiment '${other.name}' (${other.id}) is already running. Stop it first.",
            experiment = None
          ))
        case _ =>
          experimentRegistry.start(id).map {
            case Some(exp) =>
              ExperimentOperationResponse(
                success = true,
                message = s"Experiment '${exp.name}' started successfully",
                experiment = Some(exp)
              )
            case None =>
              ExperimentOperationResponse(
                success = false,
                message = "Experiment not found or not in draft status",
                experiment = None
              )
          }
      }
    } yield result
  }

  /**
   * Stop a running experiment.
   */
  def stopExperiment(id: String): IO[ExperimentOperationResponse] =
    experimentRegistry.stop(id).map {
      case Some(exp) =>
        ExperimentOperationResponse(
          success = true,
          message = s"Experiment '${exp.name}' stopped successfully",
          experiment = Some(exp)
        )
      case None =>
        ExperimentOperationResponse(
          success = false,
          message = "Experiment not found or not in running status",
          experiment = None
        )
    }

  /**
   * Archive a completed or draft experiment.
   */
  def archiveExperiment(id: String): IO[ExperimentOperationResponse] =
    experimentRegistry.archive(id).map {
      case Some(exp) =>
        ExperimentOperationResponse(
          success = true,
          message = s"Experiment '${exp.name}' archived successfully",
          experiment = Some(exp)
        )
      case None =>
        ExperimentOperationResponse(
          success = false,
          message = "Experiment not found or in running status (stop first)",
          experiment = None
        )
    }

  /**
   * Delete a draft experiment.
   */
  def deleteExperiment(id: String): IO[ExperimentOperationResponse] =
    experimentRegistry.delete(id).map { deleted =>
      if (deleted) {
        ExperimentOperationResponse(
          success = true,
          message = "Experiment deleted successfully",
          experiment = None
        )
      } else {
        ExperimentOperationResponse(
          success = false,
          message = "Experiment not found or not in draft status",
          experiment = None
        )
      }
    }

  /**
   * Get the currently running experiment.
   */
  def getRunningExperiment(): IO[Option[Experiment]] =
    experimentRegistry.getRunning()

  /**
   * Route a query to baseline or variant based on traffic split.
   * Returns the experiment ID and variant name ("baseline" or "variant").
   */
  def routeQuery(experiment: Experiment): (String, String, ExperimentConfigSnapshot) = {
    val roll = random.nextDouble()
    if (roll < experiment.trafficSplit) {
      (experiment.id, "variant", experiment.variantConfig)
    } else {
      (experiment.id, "baseline", experiment.baselineConfig)
    }
  }

  /**
   * Get experiment results with statistical analysis.
   * Aggregates query logs for baseline and variant, calculating metrics.
   */
  def getResults(experimentId: String): IO[Option[ExperimentResults]] = {
    for {
      experimentOpt <- experimentRegistry.get(experimentId)
      result <- experimentOpt match {
        case None => IO.pure(None)
        case Some(experiment) =>
          calculateResults(experiment).map(Some(_))
      }
    } yield result
  }

  /**
   * Calculate results for an experiment by aggregating query logs.
   */
  private def calculateResults(experiment: Experiment): IO[ExperimentResults] = IO {
    val conn = dataSource.getConnection()
    try {
      // Get metrics for baseline variant
      val baselineMetrics = getVariantMetrics(conn, experiment.id, isVariant = false)
      // Get metrics for test variant
      val variantMetrics = getVariantMetrics(conn, experiment.id, isVariant = true)

      // Calculate analysis
      val analysis = calculateAnalysis(baselineMetrics, variantMetrics)

      // Calculate duration
      val duration = experiment.startedAt.map { startTime =>
        val endTime = experiment.endedAt.getOrElse(Instant.now())
        formatDuration(Duration.between(startTime, endTime))
      }

      ExperimentResults(
        experimentId = experiment.id,
        experimentName = experiment.name,
        status = experiment.status,
        duration = duration,
        baseline = baselineMetrics,
        variant = variantMetrics,
        analysis = analysis
      )
    } finally {
      conn.close()
    }
  }

  /**
   * Get metrics for a specific variant of an experiment.
   * Uses config_snapshot to identify baseline vs variant queries.
   */
  private def getVariantMetrics(
    conn: java.sql.Connection,
    experimentId: String,
    isVariant: Boolean
  ): ExperimentVariantMetrics = {
    // For simplicity, we use a heuristic: if the query has topK matching variant config, it's variant
    // In a full implementation, we'd track variant assignment explicitly
    val sql =
      """SELECT
        |    COUNT(*) as query_count,
        |    AVG(total_latency_ms) as avg_latency,
        |    AVG(user_rating) as avg_rating,
        |    COUNT(user_rating) as rated_count,
        |    AVG(chunks_used) as avg_chunks_used,
        |    AVG(chunks_retrieved) as avg_chunks_retrieved
        |FROM query_logs
        |WHERE experiment_id = ?""".stripMargin

    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, if (isVariant) s"$experimentId:variant" else s"$experimentId:baseline")
      val rs = stmt.executeQuery()

      if (rs.next()) {
        ExperimentVariantMetrics(
          queryCount = rs.getInt("query_count"),
          avgLatencyMs = Option(rs.getDouble("avg_latency")).filterNot(_ => rs.wasNull()).getOrElse(0.0),
          avgRating = Option(rs.getDouble("avg_rating")).filterNot(_ => rs.wasNull()),
          ratedCount = rs.getInt("rated_count"),
          avgChunksUsed = Option(rs.getDouble("avg_chunks_used")).filterNot(_ => rs.wasNull()).getOrElse(0.0),
          avgChunksRetrieved = Option(rs.getDouble("avg_chunks_retrieved")).filterNot(_ => rs.wasNull()).getOrElse(0.0)
        )
      } else {
        ExperimentVariantMetrics(
          queryCount = 0,
          avgLatencyMs = 0.0,
          avgRating = None,
          ratedCount = 0,
          avgChunksUsed = 0.0,
          avgChunksRetrieved = 0.0
        )
      }
    } finally {
      stmt.close()
    }
  }

  /**
   * Calculate statistical analysis comparing baseline to variant.
   */
  private def calculateAnalysis(
    baseline: ExperimentVariantMetrics,
    variant: ExperimentVariantMetrics
  ): ExperimentAnalysis = {
    def percentChange(baseVal: Double, varVal: Double): String = {
      if (baseVal == 0) {
        if (varVal == 0) "0.0%"
        else "N/A"
      } else {
        val change = ((varVal - baseVal) / baseVal) * 100
        val sign = if (change > 0) "+" else ""
        f"$sign$change%.1f%%"
      }
    }

    val latencyChange = percentChange(baseline.avgLatencyMs, variant.avgLatencyMs)

    val ratingChange = (baseline.avgRating, variant.avgRating) match {
      case (Some(base), Some(var_)) => Some(percentChange(base, var_))
      case _ => None
    }

    val chunksUsedChange = percentChange(baseline.avgChunksUsed, variant.avgChunksUsed)

    // Calculate statistical significance using a simple approximation
    // In production, use a proper statistical library
    val statisticalSignificance = calculateSignificance(baseline, variant)

    ExperimentAnalysis(
      latencyChange = latencyChange,
      ratingChange = ratingChange,
      chunksUsedChange = chunksUsedChange,
      statisticalSignificance = statisticalSignificance
    )
  }

  /**
   * Calculate statistical significance between baseline and variant.
   * Uses a simplified approximation based on sample size and effect size.
   * Returns a value between 0 and 1 (p-value approximation).
   */
  private def calculateSignificance(
    baseline: ExperimentVariantMetrics,
    variant: ExperimentVariantMetrics
  ): Option[Double] = {
    // Need at least some rated queries in both groups
    if (baseline.ratedCount < 5 || variant.ratedCount < 5) {
      None
    } else {
      (baseline.avgRating, variant.avgRating) match {
        case (Some(baseRating), Some(varRating)) =>
          // Simplified significance calculation based on effect size and sample size
          val n1 = baseline.ratedCount.toDouble
          val n2 = variant.ratedCount.toDouble
          val pooledN = (n1 + n2) / 2

          // Effect size (Cohen's d approximation)
          val effectSize = Math.abs(varRating - baseRating) / 2.0  // Assume std dev ~ 2 for 1-5 ratings

          // Approximate p-value based on effect size and sample size
          // This is a rough approximation; use a proper statistical test in production
          val significance = if (effectSize < 0.2) {
            0.5  // Small effect, low significance
          } else if (effectSize < 0.5) {
            Math.min(0.9, 0.5 + pooledN / 100)  // Medium effect
          } else {
            Math.min(0.99, 0.7 + pooledN / 50)  // Large effect
          }

          Some(significance)
        case _ => None
      }
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

  /**
   * Create an experiment service.
   */
  def apply(
    experimentRegistry: ExperimentRegistry,
    queryLogRegistry: QueryLogRegistryBase,
    dataSource: DataSource
  ): ExperimentService = {
    new ExperimentService(experimentRegistry, queryLogRegistry, dataSource)
  }

  /**
   * Create and initialize an experiment service with its dependencies.
   */
  def create(
    dataSource: DataSource,
    queryLogRegistry: QueryLogRegistryBase
  ): IO[ExperimentService] = {
    for {
      registry <- ExperimentRegistry(dataSource)
    } yield new ExperimentService(registry, queryLogRegistry, dataSource)
  }
}
