package org.llm4s.ragbox.routes

import cats.effect.IO
import fs2.Stream
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.middleware.UserContextMiddleware
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.registry.QueryLogRegistryBase
import org.llm4s.ragbox.service.RAGService
import org.llm4s.ragbox.validation.InputValidation

import java.util.UUID

/**
 * HTTP routes for query operations.
 *
 * Supports permission-aware queries when SearchIndex is available.
 * User authorization is extracted from headers:
 * - X-User-Id: External user identifier
 * - X-Group-Ids: Comma-separated group names
 * - X-Admin: Set to "true" for admin access (bypasses permissions)
 *   NOTE: X-Admin is only honored if allowAdminHeader is true in security config
 *
 * Collection patterns are supported in requests:
 * - "*" - All collections (default)
 * - "exact" - Exact collection match
 * - "parent/wildcard" - Direct children of parent
 * - "parent/deep-wildcard" - All descendants of parent
 */
object QueryRoutes {

  def routes(ragService: RAGService, queryLogRegistry: QueryLogRegistryBase, allowAdminHeader: Boolean = false): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // POST /api/v1/query - Search and generate answer
    // Supports permission-aware queries when SearchIndex is available
    case req @ POST -> Root / "api" / "v1" / "query" =>
      for {
        body <- req.as[QueryRequest]
        // Validate query input
        validation = InputValidation.validateQuery(body.question)
        response <- InputValidation.toResponse(validation) match {
          case Some(errorResponse) => errorResponse
          case None =>
            for {
              startTime <- IO.realTimeInstant
              collectionPattern = body.collection.getOrElse("*")
              // Extract user ID from headers if available
              userId: Option[String] = req.headers.get(org.typelevel.ci.CIString("X-User-Id"))
                .map(_.head.value)
              result <- ((ragService.hasPermissions, ragService.principals) match {
                // Permission-aware query when SearchIndex is available
                case (true, Some(principals)) =>
                  for {
                    auth <- UserContextMiddleware.extractAuthWithAdmin(req, principals, allowAdminHeader)
                    response <- ragService.queryWithPermissionsAndAnswer(
                      question = body.question,
                      auth = auth,
                      collectionPattern = collectionPattern,
                      topK = body.topK
                    )
                  } yield response
                // Fall back to legacy query (no permissions)
                case _ =>
                  ragService.queryWithAnswer(body.question, body.topK)
              }).attempt
              endTime <- IO.realTimeInstant
              totalLatencyMs = (endTime.toEpochMilli - startTime.toEpochMilli).toInt
              // Log the query (fire and forget - don't block response)
              _ <- result match {
                case Right(queryResponse) =>
                  queryLogRegistry.logQuery(
                    queryText = body.question,
                    collectionPattern = Some(collectionPattern),
                    userId = userId,
                    embeddingLatencyMs = None,
                    searchLatencyMs = None,
                    llmLatencyMs = None,
                    totalLatencyMs = totalLatencyMs,
                    chunksRetrieved = queryResponse.contexts.size,
                    chunksUsed = queryResponse.contexts.size,
                    answerTokens = queryResponse.usage.map(_.completionTokens)
                  ).attempt.void
                case Left(_) => IO.unit
              }
              resp <- result match {
                case Right(queryResponse) =>
                  Ok(queryResponse.asJson)
                case Left(e) if e.getMessage != null && e.getMessage.contains("LLM client required") =>
                  BadRequest(ErrorResponse.configError(
                    "LLM client not configured. Set OPENAI_API_KEY or ANTHROPIC_API_KEY."
                  ).asJson)
                case Left(e) =>
                  InternalServerError(ErrorResponse.internalError(
                    "Query failed",
                    Option(e.getMessage)
                  ).asJson)
              }
            } yield resp
        }
      } yield response

    // POST /api/v1/search - Search without answer generation
    // Supports permission-aware search when SearchIndex is available
    case req @ POST -> Root / "api" / "v1" / "search" =>
      for {
        body <- req.as[SearchRequest]
        // Validate query input
        validation = InputValidation.validateQuery(body.query)
        response <- InputValidation.toResponse(validation) match {
          case Some(errorResponse) => errorResponse
          case None =>
            val searchIO = (ragService.hasPermissions, ragService.principals) match {
              // Permission-aware search when SearchIndex is available
              case (true, Some(principals)) =>
                for {
                  auth <- UserContextMiddleware.extractAuthWithAdmin(req, principals, allowAdminHeader)
                  collectionPattern = body.collection.getOrElse("*")
                  response <- ragService.searchWithPermissions(
                    query = body.query,
                    auth = auth,
                    collectionPattern = collectionPattern,
                    topK = body.topK
                  )
                } yield response
              // Fall back to legacy search (no permissions)
              case _ =>
                ragService.search(body.query, body.topK)
            }
            searchIO.attempt.flatMap {
              case Right(searchResponse) =>
                Ok(searchResponse.asJson)
              case Left(e) =>
                InternalServerError(ErrorResponse.internalError(
                  "Search failed",
                  Option(e.getMessage)
                ).asJson)
            }
        }
      } yield response

    // POST /api/v1/query/stream - Search and generate answer with SSE streaming
    // Returns Server-Sent Events for real-time progress
    case req @ POST -> Root / "api" / "v1" / "query" / "stream" =>
      for {
        body <- req.as[QueryRequest]
        // Validate query input
        validation = InputValidation.validateQuery(body.question)
        response <- InputValidation.toResponse(validation) match {
          case Some(errorResponse) => errorResponse
          case None =>
            val queryId = UUID.randomUUID().toString
            val collectionPattern = body.collection.getOrElse("*")
            val userId: Option[String] = req.headers.get(org.typelevel.ci.CIString("X-User-Id"))
              .map(_.head.value)

            // Create the SSE stream
            val sseStream: Stream[IO, String] = Stream.eval(IO.realTimeInstant).flatMap { startTime =>
              // Start event
              val startEvent = formatSSE("start", QueryStartEvent(queryId).asJson.noSpaces)

              // Execute query and stream results
              val queryStream = Stream.eval {
                ((ragService.hasPermissions, ragService.principals) match {
                  case (true, Some(principals)) =>
                    for {
                      auth <- UserContextMiddleware.extractAuthWithAdmin(req, principals, allowAdminHeader)
                      response <- ragService.queryWithPermissionsAndAnswer(
                        question = body.question,
                        auth = auth,
                        collectionPattern = collectionPattern,
                        topK = body.topK
                      )
                    } yield response
                  case _ =>
                    ragService.queryWithAnswer(body.question, body.topK)
                }).attempt
              }.flatMap {
                case Right(queryResponse) =>
                  // Stream contexts
                  val contextEvents = Stream.emits(queryResponse.contexts.zipWithIndex.map { case (ctx, idx) =>
                    formatSSE("context", QueryContextEvent(ctx, idx).asJson.noSpaces)
                  })

                  // Answer event
                  val answerEvent = Stream.emit(formatSSE("answer", QueryAnswerEvent(queryResponse.answer).asJson.noSpaces))

                  // Usage event (if available)
                  val usageEvent = queryResponse.usage match {
                    case Some(usage) => Stream.emit(formatSSE("usage", QueryUsageEvent(usage).asJson.noSpaces))
                    case None => Stream.empty
                  }

                  // Complete event
                  val completeEvent = Stream.emit(formatSSE("complete", QueryCompleteEvent(queryId, queryResponse.contexts.size).asJson.noSpaces))

                  // Log the query asynchronously
                  val logEffect = Stream.eval {
                    IO.realTimeInstant.flatMap { endTime =>
                      val totalLatencyMs = (endTime.toEpochMilli - startTime.toEpochMilli).toInt
                      queryLogRegistry.logQuery(
                        queryText = body.question,
                        collectionPattern = Some(collectionPattern),
                        userId = userId,
                        embeddingLatencyMs = None,
                        searchLatencyMs = None,
                        llmLatencyMs = None,
                        totalLatencyMs = totalLatencyMs,
                        chunksRetrieved = queryResponse.contexts.size,
                        chunksUsed = queryResponse.contexts.size,
                        answerTokens = queryResponse.usage.map(_.completionTokens)
                      ).attempt.void
                    }
                  }.drain

                  contextEvents ++ answerEvent ++ usageEvent ++ completeEvent ++ logEffect

                case Left(e) =>
                  val errorMessage = if (e.getMessage != null && e.getMessage.contains("LLM client required")) {
                    "LLM client not configured. Set OPENAI_API_KEY or ANTHROPIC_API_KEY."
                  } else {
                    Option(e.getMessage).getOrElse("Unknown error")
                  }
                  Stream.emit(formatSSE("error", QueryErrorEvent("query_failed", errorMessage).asJson.noSpaces))
              }

              Stream.emit(startEvent) ++ queryStream
            }

            // Return SSE response
            Ok(sseStream).map(_.withContentType(`Content-Type`(MediaType.`text/event-stream`)))
        }
      } yield response
  }

  /**
   * Format a message as a Server-Sent Event.
   */
  private def formatSSE(event: String, data: String): String =
    s"event: $event\ndata: $data\n\n"
}
