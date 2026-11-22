package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.Principal
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

  // Changing the runtime configuration (including the system prompt) and its change history are admin-only.
  def routes(configManager: RuntimeConfigManager): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(req => Authz.under("/api/v1/config/runtime")(req) && (Authz.mutating(req) || req.pathInfo.renderString.endsWith("/history")))(AuthedRoutes.of[Principal, IO] {

    // GET /api/v1/config/runtime - Get current runtime-modifiable settings
    case GET -> Root / "api" / "v1" / "config" / "runtime" as principal =>
      for {
        config <- configManager.get
        response <- Ok(config.asJson)
      } yield response

    // PUT /api/v1/config/runtime - Update runtime settings
    case (req @ PUT -> Root / "api" / "v1" / "config" / "runtime") as principal =>
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
    case (req @ POST -> Root / "api" / "v1" / "config" / "runtime" / "validate") as principal =>
      for {
        request <- req.as[RuntimeConfigValidateRequest]
        result <- configManager.validate(request)
        response <- Ok(result.asJson)
      } yield response

    // GET /api/v1/config/runtime/history - Get config change history
    case GET -> Root / "api" / "v1" / "config" / "runtime" / "history"
        :? PageParam(page) +& PageSizeParam(pageSize) as principal =>
      for {
        result <- configManager.getHistory(page.getOrElse(1), pageSize.getOrElse(50))
        response <- Ok(result.asJson)
      } yield response

    // GET /api/v1/config/runtime/meta - Get metadata about configurable settings
    case GET -> Root / "api" / "v1" / "config" / "runtime" / "meta" as principal =>
      for {
        current <- configManager.get
        // Each description ends with where the setting does not apply, when there is such a case
        described = (key: String, text: String) => (text +: configManager.noteFor(key).toSeq).mkString(". ")
        meta: Seq[ConfigMetaItem] = Seq(
          ConfigMetaItem("topK", current.config.topK.toString, "hot", described("topK", "Number of chunks to retrieve for context (a request's topK takes precedence)"), requiresRestart = false),
          ConfigMetaItem("fusionStrategy", current.config.fusionStrategy, "hot", described("fusionStrategy", "Strategy for combining vector and keyword results (rrf, weighted, vector_only, keyword_only)"), requiresRestart = false),
          ConfigMetaItem("rrfK", current.config.rrfK.toString, "hot", described("rrfK", "RRF constant for ranking fusion"), requiresRestart = false),
          ConfigMetaItem("systemPrompt", current.config.systemPrompt, "hot", described("systemPrompt", "System prompt for answers"), requiresRestart = false),
          ConfigMetaItem("llmTemperature", current.config.llmTemperature.toString, "hot", described("llmTemperature", "LLM temperature (0.0-2.0)"), requiresRestart = false),
          ConfigMetaItem("chunkingStrategy", current.config.chunkingStrategy, "warm", described("chunkingStrategy", "Strategy for splitting documents (simple, sentence, markdown, semantic)"), requiresRestart = false),
          ConfigMetaItem("chunkSize", current.config.chunkSize.toString, "warm", described("chunkSize", "Target chunk size in characters"), requiresRestart = false),
          ConfigMetaItem("chunkOverlap", current.config.chunkOverlap.toString, "warm", described("chunkOverlap", "Overlap between chunks in characters"), requiresRestart = false)
        )
        response <- Ok(meta.asJson)
      } yield response
  })
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
