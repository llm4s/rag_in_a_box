package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.{PermissionContext, Principal, TokenScope}
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.QueryLogRegistryBase
import org.llm4s.ragbox.routes.Params.{opt, withValid, From, Page, PageSize, Paging, To}

/**
 * HTTP routes for query analytics.
 *
 * Provides access to query logs and analytics summaries for
 * RAGA evaluation and optimization.
 */
object AnalyticsRoutes {

  object CollectionParam extends OptionalQueryParamDecoderMatcher[String]("collection")

  /** Default and maximum page sizes of GET /analytics/queries. */
  val DefaultPageSize: Int = 50
  val MaxPageSize: Int = 100

  // Query analytics contain every user's query text: admin-only. POST /feedback is for the user
  // who asked the query (or an administrator); access tokens need the `query` scope.
  def routes(queryLogRegistry: QueryLogRegistryBase): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(Authz.under("/api/v1/analytics"))(AuthedRoutes.of[Principal, IO] {

    // GET /api/v1/analytics/queries - List query logs with pagination
    // Query params: from, to, collection, page, pageSize
    case GET -> Root / "api" / "v1" / "analytics" / "queries"
        :? From(fromOpt) +& To(toOpt) +& CollectionParam(collOpt)
        +& Page(pageOpt) +& PageSize(pageSizeOpt) as _ =>
      withValid((opt(fromOpt), opt(toOpt), opt(pageOpt), opt(pageSizeOpt)).tupled) { case (from, to, page, pageSize) =>
        val paging = Paging(page, pageSize, DefaultPageSize, MaxPageSize)
        queryLogRegistry.list(from = from, to = to, collection = collOpt, page = paging.page, pageSize = paging.pageSize)
          .flatMap { case (queries, total) =>
            Ok(QueryLogListResponse(queries = queries, total = total, page = paging.page, pageSize = paging.pageSize).asJson)
          }
      }

    // GET /api/v1/analytics/queries/summary - Get analytics summary
    // Query params: from, to (defaults to last 7 days)
    case GET -> Root / "api" / "v1" / "analytics" / "queries" / "summary"
        :? From(fromOpt) +& To(toOpt) as _ =>
      withValid((opt(fromOpt), opt(toOpt)).tupled) { case (fromParam, toParam) =>
        for {
          now <- IO.realTimeInstant
          from = fromParam.getOrElse(now.minusSeconds(7 * 24 * 3600)) // Default: last 7 days
          to = toParam.getOrElse(now)
          summary <- queryLogRegistry.getSummary(from, to)
          response <- Ok(summary.asJson)
        } yield response
      }

    // GET /api/v1/analytics/queries/:id - Get single query log (ids are UUIDs)
    case GET -> Root / "api" / "v1" / "analytics" / "queries" / id as _ =>
      Params.withUuid(id, s"Query $id not found") { uuid =>
        queryLogRegistry.get(uuid.toString).flatMap {
          case Some(entry) => Ok(entry.asJson)
          case None => NotFound(ErrorResponse.notFound(s"Query $id not found").asJson)
        }
      }

    // POST /api/v1/feedback - Submit feedback for a query
    // The query id is the `queryId` of the /query response (or of the query stream's events).
    // Only the user who asked the query, or an administrator, may rate it; anyone else gets the
    // same 404 as for an unknown id, so query ids of other users are not revealed.
    case (req @ POST -> Root / "api" / "v1" / "feedback") as principal =>
      Authz.requireScope(principal, TokenScope.Query) { req.as[QueryFeedbackRequest].flatMap { body =>
        val notFound = NotFound(ErrorResponse.notFound(s"Query ${body.queryId} not found").asJson)
        if (body.rating < 1 || body.rating > 5)
          BadRequest(ErrorResponse.badRequest("Rating must be between 1 and 5").asJson)
        else
          Params.withUuid(body.queryId, s"Query ${body.queryId} not found") { uuid =>
            queryLogRegistry.get(uuid.toString).map(_.exists(entry => ownsQuery(principal, entry))).flatMap {
              case false => notFound
              case true => queryLogRegistry.addFeedback(
              queryId = uuid.toString,
              rating = body.rating,
              relevantChunks = body.relevantChunks,
              comment = body.comment
            ).flatMap {
              case true => Ok(QueryFeedbackResponse(success = true, message = "Feedback recorded").asJson)
              case false => notFound
            }
            }
          }
      } }
  })

  /** Whether `principal` may rate the logged query `entry`: an administrator, or who asked it. */
  private def ownsQuery(principal: Principal, entry: QueryLogEntry): Boolean =
    principal.isAdmin || entry.userId.contains(PermissionContext.userId(principal))
}
