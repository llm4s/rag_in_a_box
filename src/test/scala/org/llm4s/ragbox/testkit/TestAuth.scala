package org.llm4s.ragbox.testkit

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.{AuthedRoutes, ContextRequest, HttpRoutes}
import org.llm4s.ragbox.auth.{AuthMethod, Principal}

/**
 * Run protected routes in unit tests without the authenticator, as a fixed principal.
 *
 * Tests of the authenticator itself, and of the whole middleware stack, go through
 * `RagboxApp` instead (see `AuthStackSpec`).
 */
object TestAuth {

  /** An administrator (what open mode gives every request). */
  val Admin: Principal = Principal.OpenModeAdmin

  /** A non-admin user. */
  def user(name: String, groups: Set[String] = Set.empty): Principal =
    Principal(
      id = s"user:$name",
      name = name,
      method = AuthMethod.Password,
      isAdmin = false,
      externalId = Some(name),
      groups = groups
    )

  /** A `rat_` access token principal. */
  def token(scopes: Set[String], collections: Option[Set[String]] = None): Principal =
    Principal(
      id = "token:test",
      name = "test-token",
      method = AuthMethod.AccessToken,
      isAdmin = scopes.contains("admin"),
      scopes = Some(scopes),
      collections = collections
    )

  def as(principal: Principal)(routes: AuthedRoutes[Principal, IO]): HttpRoutes[IO] =
    Kleisli(req => routes(ContextRequest(principal, req)))

  extension (routes: AuthedRoutes[Principal, IO]) {
    def asAdmin: HttpRoutes[IO] = as(Admin)(routes)
    def asPrincipal(principal: Principal): HttpRoutes[IO] = as(principal)(routes)
  }
}
