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
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.middleware.UserContextMiddleware
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.registry.QueryLogRegistryBase
import org.llm4s.ragbox.service.{ExperimentService, RAGService}
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

  def routes(ragService: RAGService, queryLogRegistry: QueryLogRegistryBase, allowAdminHeader: Boolean = false, config: Option[AppConfig] = None, experimentService: Option[ExperimentService] = None): HttpRoutes[IO] = HttpRoutes.of[IO] {

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

              // Assign this query to a baseline/variant group if an experiment is running
              routing <- resolveExperimentRouting(experimentService, body.experimentId, body.collection)
              variantConfig = routing.map(_._2)
              storedExperimentId = routing.map(_._1).orElse(body.experimentId)

              // Calculate effective topK: experiment variant > request override > request topK
              effectiveTopK = variantConfig.flatMap(_.topK)
                .orElse(body.overrides.flatMap(_.topK))
                .orElse(body.topK)

              // Build config snapshot for experiment tracking
              configSnapshot = buildConfigSnapshot(config, effectiveTopK, variantConfig, body.overrides)

              result <- ((ragService.hasPermissions, ragService.principals) match {
                // Permission-aware query when SearchIndex is available
                case (true, Some(principals)) =>
                  for {
                    auth <- UserContextMiddleware.extractAuthWithAdmin(req, principals, allowAdminHeader)
                    response <- ragService.queryWithPermissionsAndAnswer(
                      question = body.question,
                      auth = auth,
                      collectionPattern = collectionPattern,
                      topK = effectiveTopK
                    )
                  } yield response
                // Fall back to legacy query (no permissions)
                case _ =>
                  ragService.queryWithAnswer(body.question, effectiveTopK)
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
                    answerTokens = queryResponse.usage.map(_.completionTokens),
                    experimentId = storedExperimentId,
                    configSnapshot = configSnapshot
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

            // Create the SSE stream. Experiment routing is resolved first so the
            // query is assigned to a baseline/variant group before execution.
            val sseStream: Stream[IO, String] =
              Stream.eval(resolveExperimentRouting(experimentService, body.experimentId, body.collection)).flatMap { routing =>
              val variantConfig = routing.map(_._2)
              val storedExperimentId = routing.map(_._1).orElse(body.experimentId)
              // Effective topK: experiment variant > request override > request topK
              val effectiveTopK = variantConfig.flatMap(_.topK)
                .orElse(body.overrides.flatMap(_.topK))
                .orElse(body.topK)
              val configSnapshot = buildConfigSnapshot(config, effectiveTopK, variantConfig, body.overrides)

              Stream.eval(IO.realTimeInstant).flatMap { startTime =>
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
                        topK = effectiveTopK
                      )
                    } yield response
                  case _ =>
                    ragService.queryWithAnswer(body.question, effectiveTopK)
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
                        answerTokens = queryResponse.usage.map(_.completionTokens),
                        experimentId = storedExperimentId,
                        configSnapshot = configSnapshot
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
            }

            // Return SSE response
            Ok(sseStream).map(_.withContentType(`Content-Type`(MediaType.`text/event-stream`)))
        }
      } yield response
  }

  /**
   * Resolve experiment traffic routing for a query.
   *
   * If an experiment is running (either the one named by `requestedId`, or the
   * single currently-running experiment) and its collection filter matches the
   * query, the query is assigned to the baseline or variant group according to
   * the experiment's traffic split.
   *
   * @return the tagged experiment id (`"<id>:baseline"` / `"<id>:variant"`) and
   *         the chosen variant's config, or None when no experiment applies.
   */
  private def resolveExperimentRouting(
    experimentService: Option[ExperimentService],
    requestedId: Option[String],
    collection: Option[String]
  ): IO[Option[(String, ExperimentConfigSnapshot)]] =
    experimentService match {
      case None => IO.pure(None)
      case Some(svc) =>
        val candidate = requestedId match {
          case Some(id) => svc.getExperiment(id)
          case None     => svc.getRunningExperiment()
        }
        candidate.map {
          case Some(exp)
              if exp.status == ExperimentStatus.Running &&
                exp.collection.forall(c => collection.contains(c)) =>
            val (expId, variant, cfg) = svc.routeQuery(exp)
            Some((s"$expId:$variant", cfg))
          case _ => None
        }
    }

  /**
   * Build the effective config snapshot recorded against a query, layering
   * experiment variant config over explicit request overrides over defaults.
   */
  private def buildConfigSnapshot(
    config: Option[AppConfig],
    effectiveTopK: Option[Int],
    variantConfig: Option[ExperimentConfigSnapshot],
    overrides: Option[QueryOverrides]
  ): Option[QueryConfigSnapshot] =
    config.map { c =>
      QueryConfigSnapshot(
        topK = effectiveTopK.getOrElse(c.rag.search.topK),
        fusionStrategy = variantConfig.flatMap(_.fusionStrategy)
          .orElse(overrides.flatMap(_.fusionStrategy))
          .getOrElse(c.rag.search.fusionStrategy),
        systemPrompt = variantConfig.flatMap(_.systemPrompt)
          .orElse(overrides.flatMap(_.systemPrompt))
          .orElse(Some(c.rag.systemPrompt)),
        llmTemperature = variantConfig.flatMap(_.llmTemperature)
          .orElse(overrides.flatMap(_.llmTemperature))
          .orElse(Some(c.llm.temperature))
      )
    }

  /**
   * Format a message as a Server-Sent Event.
   */
  private def formatSSE(event: String, data: String): String =
    s"event: $event\ndata: $data\n\n"
}
