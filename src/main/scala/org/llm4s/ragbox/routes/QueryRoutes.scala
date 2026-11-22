package org.llm4s.ragbox.routes

import cats.effect.IO
import fs2.Stream
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.{PermissionContext, Principal, TokenScope}
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{QueryLogRegistryBase, QueryLogging}
import org.llm4s.ragbox.service.{ExperimentService, InvalidRequestError, RAGService}
import org.llm4s.ragbox.validation.InputValidation

import java.util.UUID

/**
 * HTTP routes for query operations.
 *
 * Supports permission-aware queries when SearchIndex is available. The permission identity is
 * the authenticated [[Principal]] (see [[PermissionContext]]); request headers are never
 * trusted for it.
 *
 * Collection patterns are supported in requests:
 * - "*" - All collections (default)
 * - "exact" - Exact collection match
 * - "parent/wildcard" - Direct children of parent
 * - "parent/deep-wildcard" - All descendants of parent
 */
object QueryRoutes {

  def routes(ragService: RAGService, queryLogRegistry: QueryLogRegistryBase, config: Option[AppConfig] = None, experimentService: Option[ExperimentService] = None): AuthedRoutes[Principal, IO] =
    // Access tokens need the `query` scope, and may only query collections they are restricted to
    Authz.scopeForPaths(TokenScope.Query, "/api/v1/query", "/api/v1/search")(AuthedRoutes.of[Principal, IO] {

    // POST /api/v1/query - Search and generate answer
    // Supports permission-aware queries when SearchIndex is available
    case (req @ POST -> Root / "api" / "v1" / "query") as principal =>
      for {
        body <- req.as[QueryRequest]
        // Validate query input
        validation = InputValidation.validateQuery(body.question)
        response <- InputValidation.toResponse(validation)
          .orElse(Authz.patternDenied(principal, body.collection.getOrElse("*"))) match {
          case Some(errorResponse) => errorResponse
          case None =>
            for {
              startTime <- IO.realTimeInstant
              collectionPattern = body.collection.getOrElse("*")
              userId: Option[String] = Some(PermissionContext.userId(principal))

              // The experiment group (if an experiment applies) and the settings the query runs with
              plan <- planQuery(ragService, experimentService, body)
              result <- runQuery(ragService, principal, body.question, collectionPattern, plan).attempt
              endTime <- IO.realTimeInstant
              _ <- logOutcome(queryLogRegistry, body.question, collectionPattern, userId,
                (endTime.toEpochMilli - startTime.toEpochMilli).toInt, plan, result)
              // A failure is answered by ErrorHandling (400 invalid / no LLM, else a generic 500)
              resp <- IO.fromEither(result).flatMap(queryResponse => Ok(queryResponse.asJson))
            } yield resp
        }
      } yield response

    // POST /api/v1/search - Search without answer generation
    // Supports permission-aware search when SearchIndex is available
    case (req @ POST -> Root / "api" / "v1" / "search") as principal =>
      for {
        body <- req.as[SearchRequest]
        // Validate query input
        validation = InputValidation.validateQuery(body.query)
        response <- InputValidation.toResponse(validation)
          .orElse(Authz.patternDenied(principal, body.collection.getOrElse("*"))) match {
          case Some(errorResponse) => errorResponse
          case None =>
            ragService.searchAs(principal, body.query, body.collection.getOrElse("*"), body.topK)
              .flatMap(searchResponse => Ok(searchResponse.asJson))
        }
      } yield response

    // POST /api/v1/query/stream - Search and generate answer with SSE streaming
    // Returns Server-Sent Events for real-time progress. The answer is not streamed token by
    // token: llm4s RAG has no streaming answer API yet (llm4s/llm4s#2717), so the full answer is
    // generated first and sent as one `answer` event after the contexts.
    case (req @ POST -> Root / "api" / "v1" / "query" / "stream") as principal =>
      for {
        body <- req.as[QueryRequest]
        // Validate query input
        validation = InputValidation.validateQuery(body.question)
        response <- InputValidation.toResponse(validation)
          .orElse(Authz.patternDenied(principal, body.collection.getOrElse("*"))) match {
          case Some(errorResponse) => errorResponse
          case None =>
            val queryId = UUID.randomUUID().toString
            val collectionPattern = body.collection.getOrElse("*")
            val userId: Option[String] = Some(PermissionContext.userId(principal))

            // Create the SSE stream. Experiment routing is resolved first so the
            // query is assigned to a baseline/variant group before execution.
            val sseStream: Stream[IO, String] =
              Stream.eval(planQuery(ragService, experimentService, body)).flatMap { plan =>
              Stream.eval(IO.realTimeInstant).flatMap { startTime =>
              // Start event
              val startEvent = formatSSE("start", QueryStartEvent(queryId).asJson.noSpaces)

              // Execute query and stream results
              val queryStream = Stream.eval {
                runQuery(ragService, principal, body.question, collectionPattern, plan).attempt.flatTap { result =>
                  IO.realTimeInstant.flatMap { endTime =>
                    logOutcome(queryLogRegistry, body.question, collectionPattern, userId,
                      (endTime.toEpochMilli - startTime.toEpochMilli).toInt, plan, result)
                  }
                }
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

                  contextEvents ++ answerEvent ++ usageEvent ++ completeEvent

                case Left(e) =>
                  Stream.eval(ErrorHandling.clientMessage(e, "Query failed")).map { message =>
                    formatSSE("error", QueryErrorEvent("query_failed", message).asJson.noSpaces)
                  }
              }

              Stream.emit(startEvent) ++ queryStream
            }
            }

            // Return SSE response
            // Raw UTF-8: Ok(sseStream) would pick the circe encoder and JSON-quote every event
            IO.pure(Response[IO](Status.Ok).withBodyStream(sseStream.through(fs2.text.utf8.encode)).withContentType(`Content-Type`(MediaType.`text/event-stream`)))
        }
      } yield response
  })

  /**
   * How a query runs and is logged: the experiment and group it was assigned to, and the
   * settings it runs with - the group's config over the request's overrides over the runtime
   * settings.
   */
  private final case class QueryPlan(
    experimentId: Option[String],
    variant: Option[String],
    topK: Option[Int],
    overrides: RAGService.SettingsOverride,
    snapshot: Option[QueryConfigSnapshot]
  )

  /**
   * Plan a query: assign it to an experiment group if an experiment applies (see
   * [[resolveExperimentRouting]]), then layer the group's config over the request's overrides.
   * Every setting in the plan is applied: topK is passed with the call, the rest select the
   * pipeline (see `RAGService.queryWithAnswer`). The LLM temperature is not: llm4s 0.6.0 answers
   * `/query` at temperature 0.0 (llm4s/llm4s#2715), so the snapshot records none.
   */
  private def planQuery(ragService: RAGService, experimentService: Option[ExperimentService], body: QueryRequest): IO[QueryPlan] =
    resolveExperimentRouting(experimentService, body.experimentId, body.collection).map { routing =>
      val group = routing.map(_._3)
      val requested = body.overrides
      val topK = group.flatMap(_.topK).orElse(requested.flatMap(_.topK)).orElse(body.topK)
      val overrides = RAGService.SettingsOverride(
        fusionStrategy = group.flatMap(_.fusionStrategy).orElse(requested.flatMap(_.fusionStrategy)),
        rrfK = group.flatMap(_.rrfK),
        systemPrompt = group.flatMap(_.systemPrompt).orElse(requested.flatMap(_.systemPrompt)),
        reranking = group.flatMap(_.reranking)
      )
      val current = ragService.currentSettings
      val snapshot = ragService.pipelineSettingsFor(overrides).toOption.map { settings =>
        QueryConfigSnapshot(
          topK = topK.getOrElse(current.topK),
          fusionStrategy = settings.fusionStrategy,
          systemPrompt = Some(settings.systemPrompt),
          llmTemperature = None
        )
      }
      QueryPlan(
        experimentId = routing.map(_._1).orElse(body.experimentId),
        variant = routing.map(_._2),
        topK = topK,
        overrides = overrides,
        snapshot = snapshot
      )
    }

  /** Answer the query as `plan` says, permission-filtered when a SearchIndex is available. */
  private def runQuery(
    ragService: RAGService,
    principal: Principal,
    question: String,
    collectionPattern: String,
    plan: QueryPlan
  ): IO[QueryResponse] =
    ragService.answer(principal, question, collectionPattern, plan.topK, plan.overrides)

  /**
   * Log a query's outcome: answered with its metrics, or failed - so experiment results count
   * failures in the error rate. A rejected request (invalid input) is not a query and is not
   * logged. Logging never fails the request.
   */
  private def logOutcome(
    queryLogRegistry: QueryLogRegistryBase,
    question: String,
    collectionPattern: String,
    userId: Option[String],
    totalLatencyMs: Int,
    plan: QueryPlan,
    result: Either[Throwable, QueryResponse]
  ): IO[Unit] =
    (result match {
      case Right(queryResponse) =>
        QueryLogging.logAnswer(
          queryLogRegistry,
          queryText = question,
          collectionPattern = Some(collectionPattern),
          userId = userId,
          totalLatencyMs = totalLatencyMs,
          response = queryResponse,
          experimentId = plan.experimentId,
          configSnapshot = plan.snapshot,
          variant = plan.variant
        ).void
      case Left(_: InvalidRequestError) => IO.unit
      case Left(e) =>
        QueryLogging.logFailure(
          queryLogRegistry,
          queryText = question,
          collectionPattern = Some(collectionPattern),
          userId = userId,
          totalLatencyMs = totalLatencyMs,
          error = e,
          experimentId = plan.experimentId,
          configSnapshot = plan.snapshot,
          variant = plan.variant
        ).void
    }).attempt.void

  /**
   * Resolve experiment traffic routing for a query.
   *
   * If an experiment is running (either the one named by `requestedId`, or the
   * single currently-running experiment) and its collection filter matches the
   * query, the query is assigned to the baseline or variant group according to
   * the experiment's traffic split.
   *
   * @return the experiment id, the group (`baseline` / `variant`) and its config, or None
   *         when no experiment applies.
   */
  private def resolveExperimentRouting(
    experimentService: Option[ExperimentService],
    requestedId: Option[String],
    collection: Option[String]
  ): IO[Option[(String, String, ExperimentConfigSnapshot)]] =
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
            Some(svc.routeQuery(exp))
          case _ => None
        }
    }

  /**
   * Format a message as a Server-Sent Event.
   */
  private def formatSSE(event: String, data: String): String =
    s"event: $event\ndata: $data\n\n"
}