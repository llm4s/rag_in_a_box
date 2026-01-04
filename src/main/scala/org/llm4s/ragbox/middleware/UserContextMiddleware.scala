package org.llm4s.ragbox.middleware

import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.typelevel.ci.CIStringSyntax
import org.llm4s.rag.permissions._

/**
 * Middleware to extract user authorization context from request headers.
 *
 * Headers:
 * - X-User-Id: External user identifier (e.g., email, username)
 * - X-Group-Ids: Comma-separated group names the user belongs to
 *
 * If no X-User-Id header is provided, returns Anonymous authorization.
 *
 * IMPORTANT DESIGN NOTE - Principal Auto-Creation:
 * When a user/group is encountered for the first time, it is automatically
 * created in the database via `getOrCreate`. This is intentional for:
 * - Seamless SSO integration where the IdP provides user info
 * - Zero-config user provisioning
 *
 * Security considerations:
 * - Any request with headers can create principals (potential for DB bloat)
 * - Consider enabling API key authentication to control access
 * - In production, consider pre-provisioning users or adding rate limiting
 */
object UserContextMiddleware {

  val UserIdHeader = ci"X-User-Id"
  val GroupIdsHeader = ci"X-Group-Ids"

  /**
   * Extract UserAuthorization from request headers.
   *
   * @param request The HTTP request
   * @param principals PrincipalStore to resolve external IDs to internal PrincipalIds
   * @return UserAuthorization for the request (Anonymous if no user header)
   */
  def extractAuth(request: Request[IO], principals: PrincipalStore): IO[UserAuthorization] = {
    val userIdOpt = request.headers.get(UserIdHeader).map(_.head.value)
    val groupNames = request.headers.get(GroupIdsHeader)
      .map(_.head.value.split(",").map(_.trim).filter(_.nonEmpty).toSeq)
      .getOrElse(Seq.empty)

    userIdOpt match {
      case None =>
        IO.pure(UserAuthorization.Anonymous)

      case Some(userId) =>
        for {
          // Get or create user principal
          userPrincipal <- IO.fromEither(
            principals.getOrCreate(ExternalPrincipal.User(userId))
              .left.map(e => new RuntimeException(s"Failed to get/create user principal: ${e.message}"))
          )
          // Get or create group principals
          groupPrincipals <- groupNames.toList.traverse { groupName =>
            IO.fromEither(
              principals.getOrCreate(ExternalPrincipal.Group(groupName))
                .left.map(e => new RuntimeException(s"Failed to get/create group principal: ${e.message}"))
            )
          }
        } yield UserAuthorization.forUser(userPrincipal, groupPrincipals.toSet)
    }
  }

  /**
   * Extract just the external user ID from headers (without resolving to PrincipalId).
   * Useful when PrincipalStore is not available.
   */
  def extractExternalUserId(request: Request[IO]): Option[String] =
    request.headers.get(UserIdHeader).map(_.head.value)

  /**
   * Extract external group names from headers.
   */
  def extractExternalGroupIds(request: Request[IO]): Seq[String] =
    request.headers.get(GroupIdsHeader)
      .map(_.head.value.split(",").map(_.trim).filter(_.nonEmpty).toSeq)
      .getOrElse(Seq.empty)

  /**
   * Check if request has admin authorization header.
   * Admin users bypass all permission checks.
   *
   * WARNING: This should only be enabled when allowAdminHeader is true
   * in security config. By default, admin header is ignored for security.
   */
  def isAdminRequest(request: Request[IO]): Boolean =
    request.headers.get(ci"X-Admin").exists(_.head.value.toLowerCase == "true")

  /**
   * Extract authorization, returning Admin for admin requests.
   *
   * @param request The HTTP request
   * @param principals PrincipalStore to resolve external IDs
   * @param allowAdminHeader If false, X-Admin header is ignored (secure default)
   */
  def extractAuthWithAdmin(
    request: Request[IO],
    principals: PrincipalStore,
    allowAdminHeader: Boolean = false
  ): IO[UserAuthorization] =
    if (allowAdminHeader && isAdminRequest(request)) {
      IO.pure(UserAuthorization.Admin)
    } else {
      extractAuth(request, principals)
    }
}
