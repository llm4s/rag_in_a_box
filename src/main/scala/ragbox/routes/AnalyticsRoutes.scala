package ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.QueryParamDecoder
import ragbox.model._
import ragbox.model.Codecs._
import ragbox.registry.QueryLogRegistry

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * HTTP routes for query analytics.
 *
 * Provides access to query logs and analytics summaries for
 * RAGA evaluation and optimization.
 */
object AnalyticsRoutes {

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
  object PageParam extends OptionalQueryParamDecoderMatcher[Int]("page")
  object PageSizeParam extends OptionalQueryParamDecoderMatcher[Int]("pageSize")

  def routes(queryLogRegistry: QueryLogRegistry): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /api/v1/analytics/queries - List query logs with pagination
    // Query params: from, to, collection, page, pageSize
    case GET -> Root / "api" / "v1" / "analytics" / "queries"
        :? FromParam(fromOpt) +& ToParam(toOpt) +& CollectionParam(collOpt)
        +& PageParam(pageOpt) +& PageSizeParam(pageSizeOpt) =>
      for {
        result <- queryLogRegistry.list(
          from = fromOpt,
          to = toOpt,
          collection = collOpt,
          page = pageOpt.getOrElse(1),
          pageSize = pageSizeOpt.getOrElse(50).min(100)  // Cap at 100
        ).attempt
        response <- result match {
          case Right((queries, total)) =>
            Ok(QueryLogListResponse(
              queries = queries,
              total = total,
              page = pageOpt.getOrElse(1),
              pageSize = pageSizeOpt.getOrElse(50).min(100)
            ).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to list queries",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/analytics/queries/summary - Get analytics summary
    // Query params: from, to (defaults to last 7 days)
    case GET -> Root / "api" / "v1" / "analytics" / "queries" / "summary"
        :? FromParam(fromOpt) +& ToParam(toOpt) =>
      for {
        now <- IO.realTimeInstant
        from = fromOpt.getOrElse(now.minusSeconds(7 * 24 * 3600)) // Default: last 7 days
        to = toOpt.getOrElse(now)
        result <- queryLogRegistry.getSummary(from, to).attempt
        response <- result match {
          case Right(summary) =>
            Ok(summary.asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to get analytics summary",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // GET /api/v1/analytics/queries/:id - Get single query log
    case GET -> Root / "api" / "v1" / "analytics" / "queries" / id =>
      for {
        result <- queryLogRegistry.get(id).attempt
        response <- result match {
          case Right(Some(entry)) =>
            Ok(entry.asJson)
          case Right(None) =>
            NotFound(ErrorResponse.notFound(s"Query $id not found").asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              s"Failed to get query $id",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // POST /api/v1/feedback - Submit feedback for a query
    case req @ POST -> Root / "api" / "v1" / "feedback" =>
      for {
        body <- req.as[QueryFeedbackRequest]
        result <- (
          if (body.rating < 1 || body.rating > 5) {
            IO.pure(Left(new IllegalArgumentException("Rating must be between 1 and 5")))
          } else {
            queryLogRegistry.addFeedback(
              queryId = body.queryId,
              rating = body.rating,
              relevantChunks = body.relevantChunks,
              comment = body.comment
            ).map(Right(_))
          }
        ).attempt.map(_.flatten)
        response <- result match {
          case Right(true) =>
            Ok(QueryFeedbackResponse(success = true, message = "Feedback recorded").asJson)
          case Right(false) =>
            NotFound(ErrorResponse.notFound(s"Query ${body.queryId} not found").asJson)
          case Left(e: IllegalArgumentException) =>
            BadRequest(ErrorResponse.badRequest(e.getMessage).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Failed to submit feedback",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response
  }
}
