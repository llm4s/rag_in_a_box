package org.llm4s.ragbox.auth

/**
 * How a request was authenticated.
 *
 * The `name` is what `GET /api/v1/auth/me` reports as `authMethod`.
 */
enum AuthMethod(val name: String):
  /** Open mode without an API key: no credentials are checked */
  case Open extends AuthMethod("open")

  /** The static `API_KEY` */
  case ApiKey extends AuthMethod("api-key")

  /** A JWT issued by `POST /api/v1/auth/login` (basic mode) */
  case Password extends AuthMethod("password")

  /** A `rat_` access token from `POST /api/v1/tokens` */
  case AccessToken extends AuthMethod("access-token")

  /** An OAuth/OIDC session cookie (oauth mode) */
  case Oidc extends AuthMethod("oidc")

/**
 * The authenticated caller of a request.
 *
 * Built by [[org.llm4s.ragbox.middleware.Authenticator]] and handed to every protected route as
 * the context of an `AuthedRoutes[Principal, IO]` request.
 *
 * @param id          stable identifier, unique across methods (`user:alice`, `token:<uuid>`, ...)
 * @param name        display name (username, token name, e-mail, ...)
 * @param method      how the request authenticated
 * @param isAdmin     may use administrative endpoints and sees every collection
 * @param externalId  the identity used for collection permissions (the external user id a
 *                    [[org.llm4s.rag.permissions.PrincipalStore]] resolves), when this
 *                    principal is a person rather than a key/token
 * @param groups      external group names (OIDC groups)
 * @param scopes      access-token scopes; `None` for every other method (not scope-limited)
 * @param collections access-token collection restriction; `None` = all collections
 * @param userId      the ragbox user id, for password (JWT) logins
 */
final case class Principal(
  id: String,
  name: String,
  method: AuthMethod,
  isAdmin: Boolean,
  externalId: Option[String] = None,
  groups: Set[String] = Set.empty,
  scopes: Option[Set[String]] = None,
  collections: Option[Set[String]] = None,
  userId: Option[Int] = None
) {

  /** Role name reported to clients. */
  def role: String = if (isAdmin) UserRole.Admin.name else UserRole.User.name

  /**
   * Whether this principal may perform an operation guarded by `scope`.
   *
   * Only access tokens are scope-limited; a token holding the `admin` scope has every scope.
   */
  def hasScope(scope: String): Boolean =
    scopes.forall(s => s.contains(scope) || s.contains(TokenScope.Admin))

  /**
   * Whether this principal may touch documents in `collection`.
   *
   * Only access tokens can be restricted to collections. A restriction entry also covers its
   * descendants: a token limited to `confluence/ENG` may use `confluence/ENG/api`.
   */
  def canAccessCollection(collection: String): Boolean =
    collections.forall(_.exists(allowed => collection == allowed || collection.startsWith(allowed + "/")))

  /**
   * Whether every collection matched by a query `pattern` is one this principal may access.
   *
   * The all-collections pattern (a single star) is only allowed without a collection
   * restriction. Child and descendant wildcards under `a` (a slash then one or two stars) are
   * allowed when `a` itself is allowed, since its descendants then are too.
   */
  def canAccessPattern(pattern: String): Boolean =
    collections.isEmpty || {
      val trimmed = pattern.trim
      if (trimmed.isEmpty || trimmed == "*" || trimmed == "**") false
      else {
        val base = trimmed.stripSuffix("/**").stripSuffix("/*")
        !base.contains('*') && canAccessCollection(base)
      }
    }

  /** True for access tokens that are limited to some collections. */
  def isCollectionRestricted: Boolean = collections.isDefined
}

object Principal {

  /**
   * The principal of every request in open mode without an API key.
   *
   * Open mode performs no authentication, so every caller may use every endpoint - the same as
   * before authentication was enforced. It is meant for local development only.
   */
  val OpenModeAdmin: Principal = Principal(
    id = "anonymous",
    name = "anonymous",
    method = AuthMethod.Open,
    isAdmin = true
  )

  /** The holder of the static `API_KEY`: an administrator (it is the server's master key). */
  val ApiKeyAdmin: Principal = Principal(
    id = "api-key",
    name = "api-key",
    method = AuthMethod.ApiKey,
    isAdmin = true
  )

  /** A user logged in with a password (JWT). */
  def passwordUser(user: User): Principal = Principal(
    id = s"user:${user.username}",
    name = user.username,
    method = AuthMethod.Password,
    isAdmin = user.role == UserRole.Admin,
    externalId = Some(user.username),
    userId = Some(user.id)
  )

  /** A `rat_` access token. It is an administrator only if it holds the `admin` scope. */
  def accessToken(token: AccessToken): Principal = Principal(
    id = s"token:${token.id}",
    name = token.name,
    method = AuthMethod.AccessToken,
    isAdmin = token.scopes.contains(TokenScope.Admin),
    scopes = Some(token.scopes),
    collections = token.collections
  )

  /**
   * A user with an OAuth/OIDC session. Administrators are the members of `adminGroups`
   * (`OAUTH_ADMIN_GROUPS`).
   */
  def oidcUser(session: OAuthSessionData, adminGroups: Set[String]): Principal = Principal(
    id = s"oidc:${session.userId}",
    name = session.email.orElse(session.name).getOrElse(session.userId),
    method = AuthMethod.Oidc,
    isAdmin = session.groups.exists(adminGroups.contains),
    externalId = Some(session.userId),
    groups = session.groups.toSet
  )
}
