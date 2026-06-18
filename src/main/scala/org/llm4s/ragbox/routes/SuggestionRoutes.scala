package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.QueryParamDecoder
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.service.SuggestionService

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * HTTP routes for optimization suggestions.
 *
 * Provides access to AI-generated suggestions for improving
 * RAG system performance based on query analytics.
 */
object SuggestionRoutes {

  // Custom decoder for Instant
  implicit val instantQueryParamDecoder: QueryParamDecoder[Instant] =
    QueryParamDecoder[String].emap { str =>
      try Right(Instant.parse(str))
      catch {
        case _: DateTimeParseException =>
          Left(ParseFailure(s"Invalid timestamp format: $str", s"Expected ISO-8601 format like 2024-01-01T00:00:00Z"))
      }
    }

  object FromParam extends OptionalQueryParamDecoderMatcher[Instant]("from")
  object ToParam extends OptionalQueryParamDecoderMatcher[Instant]("to")
  object CollectionParam extends OptionalQueryParamDecoderMatcher[String]("collection")
  object TypeParam extends OptionalQueryParamDecoderMatcher[String]("type")
  object SeverityParam extends OptionalQueryParamDecoderMatcher[String]("severity")

  def routes(suggestionService: SuggestionService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /api/v1/analytics/suggestions - Get optimization suggestions
    // Query params: from, to, collection, type, severity
    case GET -> Root / "api" / "v1" / "analytics" / "suggestions"
        :? FromParam(fromOpt) +& ToParam(toOpt) +& CollectionParam(collOpt)
        +& TypeParam(typeOpt) +& SeverityParam(severityOpt) =>
      for {
        result <- suggestionService.generateSuggestions(
          from = fromOpt,
          to = toOpt,
          collection = collOpt
        ).attempt
        response <- result match {
          case Right(suggestionsResponse) =>
            // Apply optional filters
            val filtered = filterSuggestions(suggestionsResponse.suggestions, typeOpt, severityOpt)
            Ok(suggestionsResponse.copy(suggestions = filtered).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to generate suggestions",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/analytics/suggestions/summary - Get suggestions summary (counts)
    case GET -> Root / "api" / "v1" / "analytics" / "suggestions" / "summary"
        :? FromParam(fromOpt) +& ToParam(toOpt) =>
      for {
        result <- suggestionService.getSuggestionsSummary(fromOpt, toOpt).attempt
        response <- result match {
          case Right(summary) =>
            Ok(summary.asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to get suggestions summary",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/analytics/suggestions/collection/:name - Get suggestions for specific collection
    case GET -> Root / "api" / "v1" / "analytics" / "suggestions" / "collection" / collectionName
        :? FromParam(fromOpt) +& ToParam(toOpt) =>
      for {
        result <- suggestionService.generateSuggestions(
          from = fromOpt,
          to = toOpt,
          collection = Some(collectionName)
        ).attempt
        response <- result match {
          case Right(suggestionsResponse) =>
            // Filter to only collection-specific suggestions
            val collectionSuggestions = suggestionsResponse.suggestions.filter(
              _.collection.contains(collectionName)
            )
            Ok(suggestionsResponse.copy(suggestions = collectionSuggestions).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              s"Failed to get suggestions for collection $collectionName",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response
  }

  /**
   * Filter suggestions by type and/or severity.
   */
  private def filterSuggestions(
    suggestions: Seq[OptimizationSuggestion],
    typeFilter: Option[String],
    severityFilter: Option[String]
  ): Seq[OptimizationSuggestion] = {
    suggestions
      .filter(s => typeFilter.forall(t => s.suggestionType.equalsIgnoreCase(t)))
      .filter(s => severityFilter.forall(sev => s.severity.equalsIgnoreCase(sev)))
  }
}
