package org.llm4s.ragbox.routes

import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.llm4s.ragbox.auth.Principal
import org.http4s.circe._
import org.http4s.dsl.io._
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.service.RAGService

/**
 * HTTP routes for the effective (static) configuration. It comes from application.conf and the
 * environment and changes only with a restart; what can change at runtime is served by
 * [[RuntimeConfigRoutes]] (`/api/v1/config/runtime`).
 */
object ConfigRoutes {

  // Changing the configuration is admin-only.
  def routes(ragService: RAGService): AuthedRoutes[Principal, IO] = Authz.adminOnlyWhen(req => Authz.mutating(req) && Authz.under("/api/v1/config")(req))(AuthedRoutes.of[Principal, IO] {

    // GET /api/v1/config - Get current configuration
    case GET -> Root / "api" / "v1" / "config" as principal =>
      for {
        config <- ragService.getConfigInfo
        response <- Ok(config.asJson)
      } yield response

    // GET /api/v1/config/providers - List available providers
    case GET -> Root / "api" / "v1" / "config" / "providers" as principal =>
      for {
        providers <- ragService.getProviders
        response <- Ok(providers.asJson)
      } yield response
  })
}
