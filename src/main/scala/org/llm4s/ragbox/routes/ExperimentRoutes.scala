package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.Principal
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.registry.{ExperimentAlreadyRunningError, ExperimentRegistry}
import org.llm4s.ragbox.service.ExperimentService

/**
 * HTTP routes for experiment management.
 *
 * Provides endpoints for:
 * - Creating and managing A/B test experiments
 * - Starting and stopping experiments
 * - Viewing experiment results with statistical analysis
 */
object ExperimentRoutes {

  object StatusQueryParam extends OptionalQueryParamDecoderMatcher[String]("status")
  object LimitParam extends OptionalQueryParamDecoderMatcher[Int]("limit")
  object OffsetParam extends OptionalQueryParamDecoderMatcher[Int]("offset")

  def routes(experimentService: ExperimentService): AuthedRoutes[Principal, IO] = AuthedRoutes.of[Principal, IO] {

    // POST /api/v1/experiments - Create a new experiment
    case (req @ POST -> Root / "api" / "v1" / "experiments") as principal =>
      for {
        body <- req.as[CreateExperimentRequest]
        experiment <- experimentService.createExperiment(body)
        response <- Created(CreateExperimentResponse(
          experiment = experiment,
          message = s"Experiment '${experiment.name}' created successfully"
        ).asJson)
      } yield response

    // GET /api/v1/experiments?status=&limit=&offset= - List experiments, newest first
    case GET -> Root / "api" / "v1" / "experiments" :? StatusQueryParam(status) +& LimitParam(limit) +& OffsetParam(offset) as principal =>
      for {
        experiments <- experimentService.listExperiments(
          status,
          math.min(math.max(limit.getOrElse(ExperimentRegistry.DefaultListLimit), 0), ExperimentRegistry.MaxListLimit),
          math.max(offset.getOrElse(0), 0)
        )
        response <- Ok(experiments.asJson)
      } yield response

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
      experimentService.startExperiment(id).flatMap { result =>
        if (result.success) Ok(result.asJson) else BadRequest(result.asJson)
      }.recoverWith { case e: ExperimentAlreadyRunningError =>
        Conflict(ExperimentOperationResponse(success = false, message = e.getMessage, experiment = None).asJson)
      }

    // PUT /api/v1/experiments/:id/stop - Stop an experiment
    case PUT -> Root / "api" / "v1" / "experiments" / id / "stop" as principal =>
      for {
        result <- experimentService.stopExperiment(id)
        response <- if (result.success) {
          Ok(result.asJson)
        } else {
          BadRequest(result.asJson)
        }
      } yield response

    // PUT /api/v1/experiments/:id/archive - Archive an experiment
    case PUT -> Root / "api" / "v1" / "experiments" / id / "archive" as principal =>
      for {
        result <- experimentService.archiveExperiment(id)
        response <- if (result.success) {
          Ok(result.asJson)
        } else {
          BadRequest(result.asJson)
        }
      } yield response

    // DELETE /api/v1/experiments/:id - Delete a draft experiment
    case DELETE -> Root / "api" / "v1" / "experiments" / id as principal =>
      for {
        result <- experimentService.deleteExperiment(id)
        response <- if (result.success) {
          Ok(result.asJson)
        } else {
          BadRequest(result.asJson)
        }
      } yield response

    // GET /api/v1/experiments/:id/results - Get experiment results
    case GET -> Root / "api" / "v1" / "experiments" / id / "results" as principal =>
      for {
        resultsOpt <- experimentService.getResults(id)
        response <- resultsOpt match {
          case Some(results) => Ok(results.asJson)
          case None => NotFound(ErrorResponse.notFound(s"Experiment '$id' not found").asJson)
        }
      } yield response
  }
}
