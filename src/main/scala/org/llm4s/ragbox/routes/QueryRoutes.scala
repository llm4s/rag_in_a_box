package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.{PermissionContext, Principal, TokenScope}
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.llm4s.rag.permissions._
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{QueryLogRegistryBase, QueryLogging}
import org.llm4s.ragbox.service.{AnswerEvent, ExperimentService, InvalidRequestError, RAGService}
import org.llm4s.ragbox.validation.InputValidation

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
          .orElse(overridesError(body))
          .orElse(Authz.patternDenied(principal, body.collection.getOrElse("*"))) match {
          case Some(errorResponse) => errorResponse
          case None =>
            for {
              // The query-log id, returned as `queryId` for POST /feedback
              queryId <- IO.randomUUID.map(_.toString)
              startTime <- IO.realTimeInstant
              collectionPattern = body.collection.getOrElse("*")
              userId: Option[String] = Some(PermissionContext.userId(principal))

              // The experiment group (if an experiment applies) and the settings the query runs with
              plan <- planQuery(ragService, experimentService, body)
              result <- runQuery(ragService, principal, body.question, collectionPattern, plan).attempt
              endTime <- IO.realTimeInstant
              _ <- logOutcome(queryLogRegistry, queryId, body.question, collectionPattern, userId,
                (endTime.toEpochMilli - startTime.toEpochMilli).toInt, plan, result)
              // A failure is answered by ErrorHandling (400 invalid / no LLM, else a generic 500)
              resp <- IO.fromEither(result).flatMap(queryResponse => Ok(queryResponse.copy(queryId = Some(queryId)).asJson))
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

    // POST /api/v1/query/stream - Search and generate answer, streamed as Server-Sent Events:
    // the contexts, then the answer as the LLM generates it (see AnswerSse)
    case (req @ POST -> Root / "api" / "v1" / "query" / "stream") as principal =>
      for {
        body <- req.as[QueryRequest]
        // Validate query input
        validation = InputValidation.validateQuery(body.question)
        response <- InputValidation.toResponse(validation)
          .orElse(overridesError(body))
          .orElse(Authz.patternDenied(principal, body.collection.getOrElse("*"))) match {
          case Some(errorResponse) => errorResponse
          case None =>
            val collectionPattern = body.collection.getOrElse("*")
            val userId: Option[String] = Some(PermissionContext.userId(principal))
            for {
              // The query-log id: the `start`/`complete` events' queryId, for POST /feedback
              queryId <- IO.randomUUID.map(_.toString)
              // Experiment routing is resolved first, so the query is assigned to its group
              // before it runs
              plan <- planQuery(ragService, experimentService, body)
            } yield AnswerSse.response(
              start = QueryStartEvent(queryId).asJson,
              queryId = queryId,
              events = ragService.answerStream(principal, body.question, collectionPattern, plan.topK, plan.overrides).map {
                case AnswerEvent.Contexts(contexts) => AnswerEvent.Contexts(contexts.map(shownContext(plan)))
                case AnswerEvent.Completed(answer) => AnswerEvent.Completed(shownResponse(plan)(answer))
                case delta => delta
              }
            )(
              onCompleted = (answer, latencyMs) =>
                logOutcome(queryLogRegistry, queryId, body.question, collectionPattern, userId, latencyMs, plan, Right(answer)),
              onFailed = (error, latencyMs) =>
                logOutcome(queryLogRegistry, queryId, body.question, collectionPattern, userId, latencyMs, plan, Left(error))
            )
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
    snapshot: Option[QueryConfigSnapshot],
    includeMetadata: Boolean = true
  )

  /** 400 for an override out of range: `llmTemperature` outside 0 to 2, `similarityThreshold` outside 0 to 1. */
  private def overridesError(body: QueryRequest): Option[IO[Response[IO]]] =
    body.overrides.flatMap { o =>
      o.llmTemperature.collect {
        case t if t.isNaN || t < 0 || t > 2 => s"overrides.llmTemperature must be between 0 and 2 (got $t)"
      }.orElse(o.similarityThreshold.collect {
        case t if t.isNaN || t < 0 || t > 1 => s"overrides.similarityThreshold must be between 0 and 1 (got $t)"
      })
    }.map(message => BadRequest(ErrorResponse.badRequest(message).asJson))

  /**
   * Plan a query: assign it to an experiment group if an experiment applies (see
   * [[resolveExperimentRouting]]), then layer the group's config over the request's overrides.
   * Every setting in the plan is applied, in permission mode too: topK, the temperature and the
   * similarity threshold are passed with the call, the rest select the pipeline (see
   * `RAGService.queryWithAnswer`). The snapshot records them all.
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
        reranking = group.flatMap(_.reranking),
        similarityThreshold = requested.flatMap(_.similarityThreshold),
        llmTemperature = group.flatMap(_.llmTemperature).orElse(requested.flatMap(_.llmTemperature))
      )
      val current = ragService.currentSettings
      val snapshot = ragService.pipelineSettingsFor(overrides).toOption.map { settings =>
        QueryConfigSnapshot(
          topK = topK.getOrElse(current.topK),
          fusionStrategy = Some(settings.fusionStrategy),
          systemPrompt = Some(settings.systemPrompt),
          llmTemperature = Some(overrides.llmTemperature.getOrElse(current.llmTemperature)),
          similarityThreshold = overrides.similarityThreshold,
          rrfK = Option.when(settings.fusionStrategy == "rrf")(settings.rrfK),
          reranking = Some(settings.reranking.normalizedStrategy.getOrElse(settings.reranking.strategy)),
          searchMode = Some(QueryConfigSnapshot.HybridSearch)
        )
      }
      QueryPlan(
        experimentId = routing.map(_._1).orElse(body.experimentId),
        variant = routing.map(_._2),
        topK = topK,
        overrides = overrides,
        snapshot = snapshot,
        includeMetadata = body.includeMetadata.getOrElse(true)
      )
    }

  /**
   * Answer the query as `plan` says, permission-filtered when a SearchIndex is available. Without
   * `includeMetadata` the contexts carry no metadata (`documentId` and `chunkIndex` remain).
   */
  private def runQuery(
    ragService: RAGService,
    principal: Principal,
    question: String,
    collectionPattern: String,
    plan: QueryPlan
  ): IO[QueryResponse] =
    ragService.answer(principal, question, collectionPattern, plan.topK, plan.overrides).map(shownResponse(plan))

  /** A context as the response shows it: without its metadata unless the request asked for it. */
  private def shownContext(plan: QueryPlan)(context: ContextItem): ContextItem =
    if (plan.includeMetadata) context else context.copy(metadata = Map.empty)

  private def shownResponse(plan: QueryPlan)(response: QueryResponse): QueryResponse =
    response.copy(contexts = response.contexts.map(shownContext(plan)))

  /**
   * Log a query's outcome: answered with its metrics, or failed - so experiment results count
   * failures in the error rate. A rejected request (invalid input) is not a query and is not
   * logged. Logging never fails the request.
   */
  private def logOutcome(
    queryLogRegistry: QueryLogRegistryBase,
    queryId: String,
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
          variant = plan.variant,
          id = Some(queryId)
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
          variant = plan.variant,
          id = Some(queryId)
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
}