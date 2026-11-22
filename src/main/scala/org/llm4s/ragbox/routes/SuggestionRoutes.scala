package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.Principal
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.routes.Params.{opt, withValid, From, To}
import org.llm4s.ragbox.service.SuggestionService

/**
 * HTTP routes for optimization suggestions.
 *
 * Provides access to AI-generated suggestions for improving
 * RAG system performance based on query analytics.
 */
object SuggestionRoutes {

  object CollectionParam extends OptionalQueryParamDecoderMatcher[String]("collection")
  object TypeParam extends OptionalQueryParamDecoderMatcher[String]("type")
  object SeverityParam extends OptionalQueryParamDecoderMatcher[String]("severity")

  // Suggestions are derived from every user's queries: admin-only.
  def routes(suggestionService: SuggestionService): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(Authz.under("/api/v1/analytics"))(AuthedRoutes.of[Principal, IO] {

    // GET /api/v1/analytics/suggestions - Get optimization suggestions
    // Query params: from, to, collection, type, severity
    case GET -> Root / "api" / "v1" / "analytics" / "suggestions"
        :? From(fromOpt) +& To(toOpt) +& CollectionParam(collOpt)
        +& TypeParam(typeOpt) +& SeverityParam(severityOpt) as _ =>
      withValid((opt(fromOpt), opt(toOpt)).tupled) { case (from, to) =>
        suggestionService.generateSuggestions(from = from, to = to, collection = collOpt).flatMap { suggestions =>
          Ok(suggestions.copy(suggestions = filterSuggestions(suggestions.suggestions, typeOpt, severityOpt)).asJson)
        }
      }

    // GET /api/v1/analytics/suggestions/summary - Get suggestions summary (counts)
    case GET -> Root / "api" / "v1" / "analytics" / "suggestions" / "summary"
        :? From(fromOpt) +& To(toOpt) as _ =>
      withValid((opt(fromOpt), opt(toOpt)).tupled) { case (from, to) =>
        suggestionService.getSuggestionsSummary(from, to).flatMap(summary => Ok(summary.asJson))
      }

    // GET /api/v1/analytics/suggestions/collection/:name - Get suggestions for specific collection
    case GET -> Root / "api" / "v1" / "analytics" / "suggestions" / "collection" / collectionName
        :? From(fromOpt) +& To(toOpt) as _ =>
      withValid((opt(fromOpt), opt(toOpt)).tupled) { case (from, to) =>
        suggestionService.generateSuggestions(from = from, to = to, collection = Some(collectionName)).flatMap { suggestions =>
          // Only the collection-specific suggestions
          Ok(suggestions.copy(suggestions = suggestions.suggestions.filter(_.collection.contains(collectionName))).asJson)
        }
      }
  })

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
