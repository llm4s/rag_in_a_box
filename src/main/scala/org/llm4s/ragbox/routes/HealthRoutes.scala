package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.service.RAGService

/**
 * HTTP routes for health checks.
 */
object HealthRoutes {

  def routes(ragService: RAGService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /health - Basic health check
    case GET -> Root / "health" =>
      for {
        health <- ragService.healthCheck
        response <- Ok(health.asJson)
      } yield response

    // GET /health/ready - Readiness check
    case GET -> Root / "health" / "ready" =>
      for {
        readiness <- ragService.readinessCheck
        response <- if (readiness.ready) {
          Ok(readiness.asJson)
        } else {
          ServiceUnavailable(readiness.asJson)
        }
      } yield response

    // GET /health/live - Liveness check (kubernetes style)
    case GET -> Root / "health" / "live" =>
      Ok(Map("status" -> "ok").asJson)
  }
}
