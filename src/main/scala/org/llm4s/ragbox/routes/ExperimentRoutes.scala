package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.Principal
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.{ExperimentAlreadyRunningError, ExperimentRegistry}
import org.llm4s.ragbox.service.{ExperimentNotFoundError, ExperimentService, ExperimentStateError, InvalidExperimentError}

/**
 * HTTP routes for experiment management.
 *
 * Provides endpoints for:
 * - Creating and managing A/B test experiments
 * - Starting and stopping experiments
 * - Viewing experiment results with statistical analysis
 *
 * An unknown experiment is 404, an operation its status does not allow (or a second running
 * experiment) is 409, and an invalid definition is 400.
 */
object ExperimentRoutes {

  object StatusQueryParam extends OptionalQueryParamDecoderMatcher[String]("status")

  /** The response for an operation's failure: 404, 409 or 400, with the operation response shape. */
  private val operationError: PartialFunction[Throwable, IO[Response[IO]]] = {
    case e: ExperimentNotFoundError =>
      NotFound(ExperimentOperationResponse(success = false, message = e.getMessage, experiment = None).asJson)
    case e: ExperimentStateError =>
      Conflict(ExperimentOperationResponse(success = false, message = e.getMessage, experiment = Some(e.experiment)).asJson)
    case e: ExperimentAlreadyRunningError =>
      Conflict(ExperimentOperationResponse(success = false, message = e.getMessage, experiment = None).asJson)
  }

  // Experiments are admin-only: they change how queries are answered and expose query statistics.
  def routes(experimentService: ExperimentService): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(Authz.under("/api/v1/experiments"))(AuthedRoutes.of[Principal, IO] {

    // POST /api/v1/experiments - Create a new experiment
    case (req @ POST -> Root / "api" / "v1" / "experiments") as principal =>
      for {
        body <- req.as[CreateExperimentRequest]
        response <- experimentService.createExperiment(body).flatMap { experiment =>
          Created(CreateExperimentResponse(
            experiment = experiment,
            message = s"Experiment '${experiment.name}' created successfully"
          ).asJson)
        }.recoverWith { case e: InvalidExperimentError =>
          BadRequest(ErrorResponse.badRequest(e.getMessage).asJson)
        }
      } yield response

    // GET /api/v1/experiments?status=&limit=&offset= - List experiments, newest first
    case GET -> Root / "api" / "v1" / "experiments" :? StatusQueryParam(status) +& Params.Limit(limit) +& Params.Offset(offset) as _ =>
      Params.withValid((Params.opt(limit), Params.opt(offset)).tupled) { case (limit, offset) =>
        status.map(s => ExperimentStatus.fromString(s).toRight(s)) match {
          case Some(Left(unknown)) =>
            BadRequest(ErrorResponse.badRequest(
              s"Unknown status '$unknown'. Use one of: ${ExperimentStatus.values.map(_.value).mkString(", ")}"
            ).asJson)
          case parsed =>
            for {
              experiments <- experimentService.listExperiments(
                parsed.flatMap(_.toOption),
                Params.limit(limit, ExperimentRegistry.DefaultListLimit, ExperimentRegistry.MaxListLimit),
                Params.offset(offset)
              )
              response <- Ok(experiments.asJson)
            } yield response
        }
      }

    // GET /api/v1/experiments/running - Get the currently running experiment
    case GET -> Root / "api" / "v1" / "experiments" / "running" as principal =>
      for {
        running <- experimentService.getRunningExperiment()
        response <- running match {
          case Some(exp) => Ok(exp.asJson)
          case None => NotFound(ErrorResponse.notFound("No experiment is currently running").asJson)
        }
      } yield response

    // GET /api/v1/experiments/:id - Get experiment by ID
    case GET -> Root / "api" / "v1" / "experiments" / id as principal =>
      for {
        experimentOpt <- experimentService.getExperiment(id)
        response <- experimentOpt match {
          case Some(exp) => Ok(exp.asJson)
          case None => NotFound(ErrorResponse.notFound(s"Experiment '$id' not found").asJson)
        }
      } yield response

    // PUT /api/v1/experiments/:id/start - Start an experiment
    case PUT -> Root / "api" / "v1" / "experiments" / id / "start" as principal =>
      experimentService.startExperiment(id).flatMap(result => Ok(result.asJson)).recoverWith(operationError)

    // PUT /api/v1/experiments/:id/stop - Stop an experiment
    case PUT -> Root / "api" / "v1" / "experiments" / id / "stop" as principal =>
      experimentService.stopExperiment(id).flatMap(result => Ok(result.asJson)).recoverWith(operationError)

    // PUT /api/v1/experiments/:id/archive - Archive an experiment
    case PUT -> Root / "api" / "v1" / "experiments" / id / "archive" as principal =>
      experimentService.archiveExperiment(id).flatMap(result => Ok(result.asJson)).recoverWith(operationError)

    // DELETE /api/v1/experiments/:id - Delete a draft experiment
    case DELETE -> Root / "api" / "v1" / "experiments" / id as principal =>
      experimentService.deleteExperiment(id).flatMap(result => Ok(result.asJson)).recoverWith(operationError)

    // GET /api/v1/experiments/:id/results - Get experiment results
    case GET -> Root / "api" / "v1" / "experiments" / id / "results" as principal =>
      for {
        resultsOpt <- experimentService.getResults(id)
        response <- resultsOpt match {
          case Some(results) => Ok(results.asJson)
          case None => NotFound(ErrorResponse.notFound(s"Experiment '$id' not found").asJson)
        }
      } yield response
  })
}
