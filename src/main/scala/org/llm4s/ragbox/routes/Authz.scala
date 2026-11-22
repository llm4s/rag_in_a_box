package org.llm4s.ragbox.routes

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.llm4s.ragbox.auth.{AuthMethod, Principal}
import org.llm4s.ragbox.model.ErrorResponse

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

  /**
   * Administrators only for every request matching `rule` (checked before `routes` run, so the
   * rule must only match requests `routes` would serve). Used to make whole endpoint groups or
   * all mutating methods of a group admin-only.
   */
  def adminOnlyWhen(rule: Request[IO] => Boolean)(routes: AuthedRoutes[Principal, IO]): AuthedRoutes[Principal, IO] =
    Kleisli { (ar: AuthedRequest[IO, Principal]) =>
      if (!ar.context.isAdmin && rule(ar.req)) OptionT.liftF(forbidden("Admin access required"))
      else routes(ar)
    }

  /** Requests whose path is `prefix` or below it. */
  def under(prefix: String)(req: Request[IO]): Boolean = {
    val path = req.pathInfo.renderString
    path == prefix || path.startsWith(prefix + "/")
  }

  /** Requests that are not reads (anything but GET/HEAD). */
  def mutating(req: Request[IO]): Boolean = req.method != Method.GET && req.method != Method.HEAD

  /**
   * Ingester APIs (sync): access tokens need `scope`; anyone else must be an administrator.
   */
  def requireIngester(principal: Principal, scope: String)(operation: => IO[Response[IO]]): IO[Response[IO]] =
    if (principal.method == AuthMethod.AccessToken) requireScope(principal, scope)(operation)
    else requireAdmin(principal)(operation)

  /**
   * Access tokens without the `admin` scope may only use the endpoints their scopes are for
   * (documents, sync, query/search, chat, `/auth/me`, `/ws`); every other path answers 403
   * before any route runs. The routes then check the specific scope.
   */
  def tokenAllowlist(routes: AuthedRoutes[Principal, IO]): AuthedRoutes[Principal, IO] =
    Kleisli { (ar: AuthedRequest[IO, Principal]) =>
      val p = ar.context
      val path = ar.req.pathInfo.renderString
      val allowed = TokenPaths.exists(prefix => path == prefix || path.startsWith(prefix + "/")) ||
        (ar.req.method == Method.GET && path == "/api/v1/collections")
      if (p.method != AuthMethod.AccessToken || p.isAdmin || allowed) routes(ar)
      else OptionT.liftF(forbidden("Access token needs the 'admin' scope for this endpoint"))
    }

  private val TokenPaths = Seq(
    "/api/v1/documents", "/api/v1/stats", "/api/v1/sync", "/api/v1/query", "/api/v1/search",
    "/api/v1/chat", "/api/v1/auth/me", "/ws"
  )

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
