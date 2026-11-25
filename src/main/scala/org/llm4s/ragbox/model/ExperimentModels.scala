package org.llm4s.ragbox.model

import io.circe.{Codec, Decoder, Encoder, Json, JsonObject}
import io.circe.syntax._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

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

  given Encoder[ExperimentStatus] = Encoder.encodeString.contramap(_.value)
  given Decoder[ExperimentStatus] = Decoder.decodeString.emap { s =>
    fromString(s).toRight(s"Unknown experiment status: $s")
  }
}

/**
 * Configuration for one group of an experiment: the settings that can be tested. Each one left
 * out is the runtime setting; each one given is applied to the group's queries, through a
 * pipeline built for (and cached by) the group's settings.
 *
 * @param llmTemperature the temperature the group's answers are generated at, 0 to 2
 * @param reranking      `none`, `cohere` or `llm` (see `RAG_RERANKING_STRATEGY`)
 */
final case class ExperimentConfigSnapshot(
  topK: Option[Int] = None,
  fusionStrategy: Option[String] = None,
  systemPrompt: Option[String] = None,
  llmTemperature: Option[Double] = None,
  rrfK: Option[Int] = None,
  reranking: Option[String] = None
) derives Codec.AsObject

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
) derives Codec.AsObject

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

object CreateExperimentRequest {
  given Encoder[CreateExperimentRequest] = deriveEncoder
  // A request without a baselineConfig (the admin UI leaves an empty one out) gets the default
  given Decoder[CreateExperimentRequest] =
    deriveDecoder[CreateExperimentRequest].prepare(_.withFocus(_.mapObject { o =>
      if (o.contains("baselineConfig")) o else o.add("baselineConfig", Json.obj())
    }))
}

/**
 * Request to update a draft experiment (`PATCH /api/v1/experiments/{id}`). Each field given
 * replaces the experiment's; the ones left out are unchanged.
 *
 * `description` and `collection` can be cleared with an explicit JSON `null` (no description;
 * the experiment applies to queries on every collection). The other fields cannot be cleared:
 * `null` for one of them is a 400.
 */
final case class UpdateExperimentRequest(
  name: Option[String] = None,
  description: FieldPatch[String] = FieldPatch.Unchanged,
  baselineConfig: Option[ExperimentConfigSnapshot] = None,
  variantConfig: Option[ExperimentConfigSnapshot] = None,
  trafficSplit: Option[Double] = None,
  collection: FieldPatch[String] = FieldPatch.Unchanged
)

object UpdateExperimentRequest {
  given Decoder[UpdateExperimentRequest] = Decoder.instance { c =>
    for {
      name <- FieldPatch.notNull[String](c, "name")
      description <- c.downField("description").as[FieldPatch[String]]
      baselineConfig <- FieldPatch.notNull[ExperimentConfigSnapshot](c, "baselineConfig")
      variantConfig <- FieldPatch.notNull[ExperimentConfigSnapshot](c, "variantConfig")
      trafficSplit <- FieldPatch.notNull[Double](c, "trafficSplit")
      collection <- c.downField("collection").as[FieldPatch[String]]
    } yield UpdateExperimentRequest(name, description, baselineConfig, variantConfig, trafficSplit, collection)
  }

  /** The request as a client sends it: fields left unchanged are left out, cleared ones are `null`. */
  given Encoder.AsObject[UpdateExperimentRequest] = Encoder.AsObject.instance { r =>
    JsonObject.fromIterable(
      r.name.map(n => "name" -> Json.fromString(n)).toList ++
        r.description.toJson(Json.fromString).map("description" -> _) ++
        r.baselineConfig.map(b => "baselineConfig" -> b.asJson) ++
        r.variantConfig.map(v => "variantConfig" -> v.asJson) ++
        r.trafficSplit.map(t => "trafficSplit" -> Json.fromDoubleOrNull(t)) ++
        r.collection.toJson(Json.fromString).map("collection" -> _)
    )
  }
}

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
) derives Codec.AsObject

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
) derives Codec.AsObject

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
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * Response for listing experiments.
 */
final case class ExperimentListResponse(
  experiments: Seq[Experiment],
  total: Int
) derives Codec.AsObject

/**
 * Response after creating an experiment.
 */
final case class CreateExperimentResponse(
  experiment: Experiment,
  message: String
) derives Codec.AsObject

/**
 * Response for experiment operations.
 */
final case class ExperimentOperationResponse(
  success: Boolean,
  message: String,
  experiment: Option[Experiment] = None
) derives Codec.AsObject
