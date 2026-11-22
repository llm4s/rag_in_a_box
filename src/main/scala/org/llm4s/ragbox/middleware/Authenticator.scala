package org.llm4s.ragbox.middleware

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.headers.{Authorization, `WWW-Authenticate`}
import org.http4s.server.AuthMiddleware
import org.llm4s.ragbox.auth._
import org.llm4s.ragbox.config.{AuthMode, SecurityConfig}
import org.llm4s.ragbox.model.ErrorResponse
import org.typelevel.ci.CIStringSyntax

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The single authenticator in front of every protected route.
 *
 * It turns the credentials of a request into a [[Principal]] and hands it to the routes as the
 * context of an `AuthedRoutes[Principal, IO]` request ([[middleware]]). Which credentials are
 * accepted depends on the configured mode:
 *
 * | credential                                   | open | open + `API_KEY` | basic | oauth |
 * |----------------------------------------------|------|------------------|-------|-------|
 * | none (anonymous administrator)               | yes  |                  |       |       |
 * | static `API_KEY`                             |      | yes              | if set| if set|
 * | `rat_` access token                          |      | yes              | yes   | yes   |
 * | JWT from `POST /api/v1/auth/login`           |      |                  | yes   |       |
 * | OIDC session cookie                          |      |                  |       | yes   |
 *
 * Credentials are read from (first match wins):
 *  - `X-API-Key: <API_KEY>`
 *  - `Authorization: Bearer <API_KEY | rat_token | JWT>`
 *  - `?access_token=<API_KEY | rat_token | JWT>`, only on the WebSocket endpoint (`/ws`),
 *    because browsers cannot set headers on a WebSocket handshake
 *  - the OIDC session cookie (oauth mode)
 *
 * (`?api_key=` is no longer accepted: query strings end up in access logs and browser history.)
 *
 * A request that presents a credential which is not valid is rejected (401) even if another
 * credential would have been valid; credentials are never silently ignored.
 *
 * JWTs are only accepted in basic mode, the only mode with password login.
 *
 * Public routes (health, login, OAuth, admin UI assets, public metrics) are not wrapped by this
 * middleware; `Main` mounts them in front of it.
 *
 * '''Trusted proxy headers.''' With `AUTH_TRUSTED_PROXY_HEADERS=true`, a request authenticated
 * as an administrator (typically a gateway holding the `API_KEY`) may act for an end user by
 * sending `X-User-Id` and `X-Group-Ids`: the principal becomes that (non-admin) user, so queries
 * are filtered by their collection permissions and chat sessions are theirs. `X-Admin: true`
 * keeps administrator rights as well, if `ALLOW_ADMIN_HEADER=true`. Non-admin credentials can
 * never assert another identity. Without the setting these headers are ignored
 * ([[stripIdentityHeaders]] removes them from every request).
 *
 * @param validateAccessToken validates a `rat_` token (and records its last use)
 * @param validateJwt         checks a JWT's signature and expiry
 * @param lookupUser          loads the current user record for a JWT subject, so a deleted
 *                            user's or demoted admin's token stops working immediately
 * @param validateSession     looks up an OIDC session by id (oauth mode)
 * @param oauthAdminGroups    OIDC groups whose members are administrators
 */
final class Authenticator(
  security: SecurityConfig,
  validateAccessToken: String => IO[Option[AccessToken]],
  validateJwt: String => TokenValidationResult,
  lookupUser: Int => IO[Option[User]],
  validateSession: Option[String => IO[Option[OAuthSessionData]]] = None,
  oauthAdminGroups: Set[String] = Set.empty,
  sessionCookieName: String = OAuthSessionConfig().cookieName
) {

  import Authenticator._

  private val mode: AuthMode = security.auth.mode
  private val apiKey: Option[String] = security.apiKey.filter(_.nonEmpty)
  private val apiKeyDigest: Option[Array[Byte]] = apiKey.map(digest)

  /** False only in open mode without an API key, where every request is anonymous admin. */
  val authRequired: Boolean = mode != AuthMode.Open || apiKey.isDefined

  /**
   * Authenticate a request.
   *
   * @return the principal, or why the request is not authenticated
   */
  def authenticate(req: Request[IO]): IO[Either[AuthFailure, Principal]] = {
    val authenticated =
      if (!authRequired) IO.pure(Right(Principal.OpenModeAdmin))
      else
        credentialOf(req) match {
          case Some(Credential.Key(key))       => IO.pure(checkApiKey(key))
          case Some(Credential.Bearer(token))  => bearer(token)
          case Some(Credential.Session(id))    => session(id)
          case None                            => IO.pure(Left(AuthFailure.Missing))
        }
    authenticated.map(_.map(asProxiedUser(req, _)))
  }

  /** In trusted-proxy mode, the end user an administrator credential acts for (see above). */
  private def asProxiedUser(req: Request[IO], principal: Principal): Principal =
    if (!security.auth.trustedProxyHeaders || !principal.isAdmin) principal
    else
      req.headers.get(UserIdHeader).map(_.head.value.trim).filter(_.nonEmpty) match {
        case None => principal
        case Some(userId) =>
          val groups = req.headers.get(GroupIdsHeader)
            .map(_.head.value.split(",").map(_.trim).filter(_.nonEmpty).toSet)
            .getOrElse(Set.empty[String])
          val keepAdmin = security.allowAdminHeader &&
            req.headers.get(AdminHeader).exists(_.head.value.trim.equalsIgnoreCase("true"))
          Principal(
            id = s"user:$userId",
            name = userId,
            method = principal.method,
            isAdmin = keepAdmin,
            externalId = Some(userId),
            groups = groups
          )
      }

  /** The http4s middleware that runs [[authenticate]] in front of `AuthedRoutes[Principal, IO]`. */
  lazy val middleware: AuthMiddleware[IO, Principal] =
    AuthMiddleware(Kleisli(authenticate), onFailure)

  /** Shorthand: protect `routes`. */
  def apply(routes: AuthedRoutes[Principal, IO]): HttpRoutes[IO] = middleware(routes)

  private def credentialOf(req: Request[IO]): Option[Credential] = {
    val header = req.headers.get(ci"X-API-Key").map(h => Credential.Key(h.head.value))
    lazy val bearerHeader = req.headers.get[Authorization].flatMap(_.credentials match {
      case Credentials.Token(AuthScheme.Bearer, token) => Some(Credential.Bearer(token))
      case _ => None
    })
    lazy val wsToken =
      if (req.pathInfo.renderString == WebSocketPath) req.params.get("access_token").map(Credential.Bearer(_))
      else None
    lazy val sessionCookie =
      if (mode == AuthMode.OAuth && validateSession.isDefined)
        req.cookies.find(_.name == sessionCookieName).map(c => Credential.Session(c.content))
      else None

    header.orElse(bearerHeader).orElse(wsToken).orElse(sessionCookie)
  }

  private def checkApiKey(key: String): Either[AuthFailure, Principal] =
    if (apiKeyMatches(key)) Right(Principal.ApiKeyAdmin)
    else Left(AuthFailure.Invalid("Invalid API key"))

  private def apiKeyMatches(candidate: String): Boolean =
    apiKeyDigest.exists(expected => MessageDigest.isEqual(expected, digest(candidate)))

  private def bearer(token: String): IO[Either[AuthFailure, Principal]] =
    if (token.startsWith(AccessTokenPrefix))
      validateAccessToken(token).map {
        case Some(accessToken) => Right(Principal.accessToken(accessToken))
        case None => Left(AuthFailure.Invalid("Invalid, revoked or expired access token"))
      }
    else if (apiKeyMatches(token)) IO.pure(Right(Principal.ApiKeyAdmin))
    else if (mode == AuthMode.Basic && token.count(_ == '.') == 2) jwt(token)
    else IO.pure(Left(AuthFailure.Invalid("Invalid credentials")))

  private def jwt(token: String): IO[Either[AuthFailure, Principal]] =
    validateJwt(token) match {
      case TokenValidationResult.Valid(userId, _, _) =>
        lookupUser(userId).map {
          case Some(user) => Right(Principal.passwordUser(user))
          case None => Left(AuthFailure.Invalid("User no longer exists"))
        }
      case TokenValidationResult.Expired => IO.pure(Left(AuthFailure.Invalid("Token expired")))
      case TokenValidationResult.Invalid(_) => IO.pure(Left(AuthFailure.Invalid("Invalid token")))
    }

  private def session(sessionId: String): IO[Either[AuthFailure, Principal]] =
    validateSession match {
      case Some(validate) =>
        validate(sessionId).map {
          case Some(data) => Right(Principal.oidcUser(data, oauthAdminGroups))
          case None => Left(AuthFailure.Invalid("Session expired or invalid. Please log in again."))
        }
      case None => IO.pure(Left(AuthFailure.Missing))
    }

  private val onFailure: AuthedRoutes[AuthFailure, IO] = Kleisli { (ar: AuthedRequest[IO, AuthFailure]) =>
    OptionT.liftF(unauthorized(ar.context.message))
  }
}

object Authenticator {

  val UserIdHeader = ci"X-User-Id"
  val GroupIdsHeader = ci"X-Group-Ids"
  val AdminHeader = ci"X-Admin"

  /**
   * Remove the identity headers (`X-User-Id`, `X-Group-Ids`, `X-Admin`) from every request
   * unless trusted-proxy mode is on, so nothing downstream can be influenced by them.
   */
  def stripIdentityHeaders(trusted: Boolean)(app: HttpApp[IO]): HttpApp[IO] =
    if (trusted) app
    else Kleisli((req: Request[IO]) => app(req.removeHeader(UserIdHeader).removeHeader(GroupIdsHeader).removeHeader(AdminHeader)))

  /** The WebSocket endpoint, the only path that accepts `?access_token=`. */
  val WebSocketPath = "/ws"

  private val AccessTokenPrefix = "rat_"

  /** Why a request could not be authenticated. Both cases answer 401. */
  sealed trait AuthFailure {
    def message: String
  }

  object AuthFailure {
    case object Missing extends AuthFailure {
      val message = "Authentication required"
    }
    final case class Invalid(message: String) extends AuthFailure
  }

  private enum Credential:
    case Key(value: String)
    case Bearer(value: String)
    case Session(id: String)

  /** SHA-256 so the constant-time comparison does not leak the key's length either. */
  private def digest(value: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))

  /** 401 with a `WWW-Authenticate` challenge and the standard error body. */
  def unauthorized(message: String): IO[Response[IO]] =
    Unauthorized(
      `WWW-Authenticate`(Challenge("Bearer", "ragbox")),
      ErrorResponse.unauthorized(message).asJson
    )

  /** Build the authenticator from the application's registries and services. */
  def apply(
    security: SecurityConfig,
    authService: AuthService,
    userRegistry: UserRegistry,
    tokenRegistry: AccessTokenRegistry,
    oidc: Option[OidcService[IO]]
  ): Authenticator = {
    val oauth = security.auth.oauth
    new Authenticator(
      security = security,
      validateAccessToken = tokenRegistry.validate,
      validateJwt = authService.validateToken,
      lookupUser = userRegistry.getById,
      validateSession = oidc.map(service => (id: String) => service.validateSession(id)),
      oauthAdminGroups = oauth.map(_.adminGroups).getOrElse(Set.empty),
      sessionCookieName = oauth.map(_.session.cookieName).getOrElse(OAuthSessionConfig().cookieName)
    )
  }
}
