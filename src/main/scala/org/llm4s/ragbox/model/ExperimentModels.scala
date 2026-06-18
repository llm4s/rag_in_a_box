package org.llm4s.ragbox.model

import java.time.Instant

/**
 * Models for the experimentation framework.
 *
 * Supports A/B testing of RAG configurations with traffic splitting
 * and statistical analysis of results.
 */

/**
 * Status of an experiment.
 */
object ExperimentStatus {
  val Draft: String = "draft"
  val Running: String = "running"
  val Completed: String = "completed"
  val Archived: String = "archived"
}

/**
 * Configuration snapshot for an experiment variant.
 * These are the parameters that can be tested.
 */
final case class ExperimentConfigSnapshot(
  topK: Option[Int] = None,
  fusionStrategy: Option[String] = None,
  systemPrompt: Option[String] = None,
  llmTemperature: Option[Double] = None
)

/**
 * An experiment definition for A/B testing.
 *
 * @param id Unique experiment identifier
 * @param name Human-readable name
 * @param description Optional description
 * @param status Current status (draft, running, completed, archived)
 * @param baselineConfig Configuration for the baseline/control group
 * @param variantConfig Configuration for the variant/test group
 * @param trafficSplit Percentage of traffic to route to variant (0.0-1.0)
 * @param collection Optional collection filter for the experiment
 * @param createdAt When the experiment was created
 * @param startedAt When the experiment was started
 * @param endedAt When the experiment was ended
 */
final case class Experiment(
  id: String,
  name: String,
  description: Option[String] = None,
  status: String = ExperimentStatus.Draft,
  baselineConfig: ExperimentConfigSnapshot = ExperimentConfigSnapshot(),
  variantConfig: ExperimentConfigSnapshot = ExperimentConfigSnapshot(),
  trafficSplit: Double = 0.5,
  collection: Option[String] = None,
  createdAt: Instant,
  startedAt: Option[Instant] = None,
  endedAt: Option[Instant] = None
)

/**
 * Request to create a new experiment.
 */
final case class CreateExperimentRequest(
  name: String,
  description: Option[String] = None,
  baselineConfig: ExperimentConfigSnapshot = ExperimentConfigSnapshot(),
  variantConfig: ExperimentConfigSnapshot,
  trafficSplit: Option[Double] = None,
  collection: Option[String] = None
)

/**
 * Request to update an experiment.
 */
final case class UpdateExperimentRequest(
  name: Option[String] = None,
  description: Option[String] = None,
  baselineConfig: Option[ExperimentConfigSnapshot] = None,
  variantConfig: Option[ExperimentConfigSnapshot] = None,
  trafficSplit: Option[Double] = None
)

/**
 * Metrics for an experiment variant.
 */
final case class ExperimentVariantMetrics(
  queryCount: Int,
  avgLatencyMs: Double,
  avgRating: Option[Double],
  ratedCount: Int,
  avgChunksUsed: Double,
  avgChunksRetrieved: Double
)

/**
 * Statistical analysis of experiment comparison.
 */
final case class ExperimentAnalysis(
  latencyChange: String,
  ratingChange: Option[String],
  chunksUsedChange: String,
  statisticalSignificance: Option[Double]
)

/**
 * Results of an experiment.
 */
final case class ExperimentResults(
  experimentId: String,
  experimentName: String,
  status: String,
  duration: Option[String],
  baseline: ExperimentVariantMetrics,
  variant: ExperimentVariantMetrics,
  analysis: ExperimentAnalysis
)

/**
 * Response for listing experiments.
 */
final case class ExperimentListResponse(
  experiments: Seq[Experiment],
  total: Int
)

/**
 * Response after creating an experiment.
 */
final case class CreateExperimentResponse(
  experiment: Experiment,
  message: String
)

/**
 * Response for experiment operations.
 */
final case class ExperimentOperationResponse(
  success: Boolean,
  message: String,
  experiment: Option[Experiment] = None
)
