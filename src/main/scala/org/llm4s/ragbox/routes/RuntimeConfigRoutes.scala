package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.dsl.io._
import org.llm4s.ragbox.config.RuntimeConfigManager
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._

/**
 * HTTP routes for runtime configuration management.
 *
 * Allows users to view and modify hot/warm settings without server restart.
 */
object RuntimeConfigRoutes {

  // Query parameter extractors
  object PageParam extends OptionalQueryParamDecoderMatcher[Int]("page")
  object PageSizeParam extends OptionalQueryParamDecoderMatcher[Int]("pageSize")

  def routes(configManager: RuntimeConfigManager): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /api/v1/config/runtime - Get current runtime-modifiable settings
    case GET -> Root / "api" / "v1" / "config" / "runtime" =>
      for {
        config <- configManager.get
        response <- Ok(config.asJson)
      } yield response

    // PUT /api/v1/config/runtime - Update runtime settings
    case req @ PUT -> Root / "api" / "v1" / "config" / "runtime" =>
      for {
        request <- req.as[RuntimeConfigUpdateRequest]
        // First validate
        validation <- configManager.validate(RuntimeConfigValidateRequest(
          topK = request.topK,
          fusionStrategy = request.fusionStrategy,
          rrfK = request.rrfK,
          systemPrompt = request.systemPrompt,
          llmTemperature = request.llmTemperature,
          chunkingStrategy = request.chunkingStrategy,
          chunkSize = request.chunkSize,
          chunkOverlap = request.chunkOverlap
        ))
        response <- if (validation.valid) {
          configManager.update(request).flatMap(result => Ok(result.asJson))
        } else {
          BadRequest(ErrorResponse(
            error = "validation_failed",
            message = "Configuration validation failed",
            details = Some(validation.validations.filter(!_.valid).map(v => s"${v.field}: ${v.error.getOrElse("invalid")}").mkString("; "))
          ).asJson)
        }
      } yield response

    // POST /api/v1/config/runtime/validate - Validate proposed changes
    case req @ POST -> Root / "api" / "v1" / "config" / "runtime" / "validate" =>
      for {
        request <- req.as[RuntimeConfigValidateRequest]
        result <- configManager.validate(request)
        response <- Ok(result.asJson)
      } yield response

    // GET /api/v1/config/runtime/history - Get config change history
    case GET -> Root / "api" / "v1" / "config" / "runtime" / "history"
        :? PageParam(page) +& PageSizeParam(pageSize) =>
      for {
        result <- configManager.getHistory(page.getOrElse(1), pageSize.getOrElse(50))
        response <- Ok(result.asJson)
      } yield response

    // GET /api/v1/config/runtime/meta - Get metadata about configurable settings
    case GET -> Root / "api" / "v1" / "config" / "runtime" / "meta" =>
      for {
        current <- configManager.get
        meta: Seq[ConfigMetaItem] = Seq(
          ConfigMetaItem("topK", current.config.topK.toString, "hot", "Number of chunks to retrieve for context", requiresRestart = false),
          ConfigMetaItem("fusionStrategy", current.config.fusionStrategy, "hot", "Strategy for combining search results (rrf, simple)", requiresRestart = false),
          ConfigMetaItem("rrfK", current.config.rrfK.toString, "hot", "RRF constant for ranking fusion", requiresRestart = false),
          ConfigMetaItem("systemPrompt", current.config.systemPrompt, "hot", "Custom system prompt for LLM", requiresRestart = false),
          ConfigMetaItem("llmTemperature", current.config.llmTemperature.toString, "hot", "LLM temperature (0.0-1.0)", requiresRestart = false),
          ConfigMetaItem("chunkingStrategy", current.config.chunkingStrategy, "warm", "Strategy for splitting documents (sentence, paragraph, fixed)", requiresRestart = false),
          ConfigMetaItem("chunkSize", current.config.chunkSize.toString, "warm", "Target chunk size in characters", requiresRestart = false),
          ConfigMetaItem("chunkOverlap", current.config.chunkOverlap.toString, "warm", "Overlap between chunks in characters", requiresRestart = false)
        )
        response <- Ok(meta.asJson)
      } yield response
  }
}

/**
 * Metadata about a configuration item.
 */
final case class ConfigMetaItem(
  key: String,
  value: String,
  `type`: String,  // "hot", "warm", or "cold"
  description: String,
  requiresRestart: Boolean
)

object ConfigMetaItem {
  import io.circe.generic.semiauto._
  implicit val encoder: io.circe.Encoder[ConfigMetaItem] = deriveEncoder
}
