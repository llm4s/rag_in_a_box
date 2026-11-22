package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.rag.permissions.{ExternalPrincipal, PrincipalStore, UserAuthorization}

/**
 * The collection-permission identity (`UserAuthorization`) of an authenticated [[Principal]].
 *
 * Identity always comes from the principal, never from request headers. (With
 * `AUTH_TRUSTED_PROXY_HEADERS=true` the authenticator itself may turn `X-User-Id` /
 * `X-Group-Ids` from an administrator credential into the principal; see
 * [[org.llm4s.ragbox.middleware.Authenticator]].)
 *
 *  - administrators: `UserAuthorization.Admin` (every collection)
 *  - users (password, OIDC, trusted-proxy): their user principal and groups, created in the
 *    principal store on first use
 *  - access tokens: `Admin`, because a token's reach is limited by its collection restriction
 *    and scopes instead (both checked by the routes)
 */
object PermissionContext {

  def authorization(principal: Principal, principals: PrincipalStore): IO[UserAuthorization] =
    if (principal.isAdmin || principal.method == AuthMethod.AccessToken) IO.pure(UserAuthorization.Admin)
    else
      principal.externalId match {
        case None => IO.pure(UserAuthorization.Anonymous)
        case Some(userId) =>
          for {
            user <- resolve(principals, ExternalPrincipal.User(userId))
            groups <- principal.groups.toList.sorted.traverse(g => resolve(principals, ExternalPrincipal.Group(g)))
          } yield UserAuthorization.forUser(user, groups.toSet)
      }

  /** The user id recorded with queries (analytics). */
  def userId(principal: Principal): String = principal.externalId.getOrElse(principal.id)

  private def resolve(principals: PrincipalStore, external: ExternalPrincipal) =
    IO.blocking(principals.getOrCreate(external)).flatMap(r =>
      IO.fromEither(r.left.map(e => new RuntimeException(s"Failed to resolve principal $external: ${e.message}")))
    )
}
