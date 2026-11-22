package org.llm4s.ragbox.model

import java.time.Instant

/**
 * Models for the experimentation framework.
 *
 * Supports A/B testing of RAG configurations with traffic splitting
 * and statistical analysis of results.
 */

/**
 * Status of an experiment. In JSON and the database it is its [[value]] (`"draft"`, ...).
 */
enum ExperimentStatus(val value: String) {
  case Draft extends ExperimentStatus("draft")
  case Running extends ExperimentStatus("running")
  case Completed extends ExperimentStatus("completed")
  case Archived extends ExperimentStatus("archived")
}

object ExperimentStatus {

  /** The status whose [[ExperimentStatus.value]] is `value` (case-insensitive). */
  def fromString(value: String): Option[ExperimentStatus] =
    values.find(_.value == value.trim.toLowerCase)
}

/**
 * Configuration for one group of an experiment: the settings that can be tested. Each one left
 * out is the runtime setting; each one given is applied to the group's queries, through a
 * pipeline built for (and cached by) the group's settings.
 *
 * @param llmTemperature recorded, but not applied to `/query` answers: llm4s 0.6.0
 *                       `RAG.queryWithAnswer` answers at temperature 0.0 (llm4s/llm4s#2715)
 * @param reranking      `none`, `cohere` or `llm` (see `RAG_RERANKING_STRATEGY`)
 */
final case class ExperimentConfigSnapshot(
  topK: Option[Int] = None,
  fusionStrategy: Option[String] = None,
  systemPrompt: Option[String] = None,
  llmTemperature: Option[Double] = None,
  rrfK: Option[Int] = None,
  reranking: Option[String] = None
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
  status: ExperimentStatus = ExperimentStatus.Draft,
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
 * Metrics for one group of an experiment. Latency, chunk and rating figures are over the
 * queries that succeeded; `queryCount` and `errorRate` include the ones that failed.
 *
 * @param queryCount        every query routed to the group, failed ones included
 * @param failedCount       queries that failed
 * @param errorRate         `failedCount / queryCount` (0 with no queries)
 * @param latencyStdDevMs   sample standard deviation of the successful queries' latency
 * @param ratingStdDev      sample standard deviation of the ratings
 * @param positiveCount     ratings of 4 or 5
 */
final case class ExperimentVariantMetrics(
  queryCount: Int,
  avgLatencyMs: Double,
  avgRating: Option[Double],
  ratedCount: Int,
  avgChunksUsed: Double,
  avgChunksRetrieved: Double,
  failedCount: Int = 0,
  errorRate: Double = 0.0,
  latencyStdDevMs: Option[Double] = None,
  ratingStdDev: Option[Double] = None,
  positiveCount: Int = 0
)

/**
 * A two-sample test of one metric, variant against baseline.
 *
 * @param metric      `latencyMs`, `rating`, `errorRate` or `positiveRate`
 * @param test        `welch-t` (Welch's unequal-variances t-test, for means) or
 *                    `two-proportion-z` (pooled two-proportion z-test, for rates)
 * @param statistic   the t or z statistic (positive when the variant's value is higher)
 * @param pValue      two-sided p-value: the probability of a difference at least this large if
 *                    the groups did not differ
 * @param significant whether `pValue` is below [[ExperimentAnalysis.Alpha]]
 */
final case class SignificanceTest(
  metric: String,
  test: String,
  baseline: Double,
  variant: Double,
  statistic: Double,
  pValue: Double,
  significant: Boolean
)

/**
 * Comparison of an experiment's variant with its baseline.
 *
 * @param tests a test per metric that has enough data for one (two values in each group for
 *              a t-test, one query in each group and both outcomes seen for a z-test)
 */
final case class ExperimentAnalysis(
  latencyChange: String,
  ratingChange: Option[String],
  chunksUsedChange: String,
  errorRateChange: String,
  tests: Seq[SignificanceTest]
)

object ExperimentAnalysis {

  /** The significance level tests are judged at. */
  val Alpha: Double = 0.05
}

/**
 * Results of an experiment.
 */
final case class ExperimentResults(
  experimentId: String,
  experimentName: String,
  status: ExperimentStatus,
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
