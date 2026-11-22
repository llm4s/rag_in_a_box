package org.llm4s.ragbox.routes

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.llm4s.ragbox.auth.Principal
import org.llm4s.ragbox.model.ErrorResponse
import org.llm4s.ragbox.model.Codecs._

/**
 * Authorization checks for routes that receive an authenticated [[Principal]].
 *
 * Each combinator runs `operation` when the principal is allowed and answers
 * `403 Forbidden` otherwise.
 */
object Authz {

  def forbidden(message: String): IO[Response[IO]] =
    Forbidden(ErrorResponse("forbidden", message).asJson)

  /** Administrators only (admin users, the API key, admin-scoped tokens, open mode). */
  def requireAdmin(principal: Principal)(operation: => IO[Response[IO]]): IO[Response[IO]] =
    if (principal.isAdmin) operation else forbidden("Admin access required")

  /**
   * The principal must hold `scope`. Only access tokens are scope-limited, so this only ever
   * rejects `rat_` tokens; a token with the `admin` scope has every scope.
   */
  def requireScope(principal: Principal, scope: String)(operation: => IO[Response[IO]]): IO[Response[IO]] =
    if (principal.hasScope(scope)) operation
    else forbidden(s"Access token is missing the '$scope' scope")

  /** The principal may touch documents in `collection` (access-token collection restriction). */
  def requireCollection(principal: Principal, collection: String)(operation: => IO[Response[IO]]): IO[Response[IO]] =
    if (principal.canAccessCollection(collection)) operation
    else forbidden(s"Access token is not allowed to use collection '$collection'")

  /**
   * Require `scope` for every request whose path starts with one of `prefixes`, before the
   * routes run. Use it for route groups that all need the same scope (e.g. chat needs `query`);
   * the prefixes must belong to `routes` alone, since other requests pass straight through.
   */
  def scopeForPaths(scope: String, prefixes: String*)(routes: AuthedRoutes[Principal, IO]): AuthedRoutes[Principal, IO] =
    Kleisli { (ar: AuthedRequest[IO, Principal]) =>
      val path = ar.req.pathInfo.renderString
      if (prefixes.exists(p => path == p || path.startsWith(p + "/")) && !ar.context.hasScope(scope))
        OptionT.liftF(forbidden(s"Access token is missing the '$scope' scope"))
      else routes(ar)
    }

  /** 403 if a query `pattern` reaches collections outside the principal's restriction. */
  def patternDenied(principal: Principal, pattern: String): Option[IO[Response[IO]]] =
    Option.when(!principal.canAccessPattern(pattern))(requirePattern(principal, pattern)(IO.never))

  /** Every collection a query `pattern` can match must be allowed for the principal. */
  def requirePattern(principal: Principal, pattern: String)(operation: => IO[Response[IO]]): IO[Response[IO]] =
    if (principal.canAccessPattern(pattern)) operation
    else forbidden(
      s"Access token is restricted to collections ${principal.collections.getOrElse(Set.empty).toSeq.sorted.mkString(", ")}; " +
        s"pattern '$pattern' is not within them"
    )
}
