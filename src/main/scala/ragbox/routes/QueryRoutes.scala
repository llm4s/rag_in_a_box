package ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import ragbox.model._
import ragbox.model.Codecs._
import ragbox.service.RAGService

/**
 * HTTP routes for query operations.
 */
object QueryRoutes {

  def routes(ragService: RAGService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // POST /api/v1/query - Search and generate answer
    case req @ POST -> Root / "api" / "v1" / "query" =>
      for {
        body <- req.as[QueryRequest]
        result <- ragService.queryWithAnswer(body.question, body.topK).attempt
        response <- result match {
          case Right(queryResponse) =>
            Ok(queryResponse.asJson)
          case Left(e) if e.getMessage.contains("LLM client required") =>
            BadRequest(ErrorResponse.configError(
              "LLM client not configured. Set OPENAI_API_KEY or ANTHROPIC_API_KEY."
            ).asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Query failed",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response

    // POST /api/v1/search - Search without answer generation
    case req @ POST -> Root / "api" / "v1" / "search" =>
      for {
        body <- req.as[SearchRequest]
        result <- ragService.search(body.query, body.topK).attempt
        response <- result match {
          case Right(searchResponse) =>
            Ok(searchResponse.asJson)
          case Left(e) =>
            InternalServerError(ErrorResponse.internalError(
              "Search failed",
              Some(e.getMessage)
            ).asJson)
        }
      } yield response
  }
}
