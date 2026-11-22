package org.llm4s.ragbox.auth

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

/** Configuration for an OIDC provider */
case class OidcProviderConfig(
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    authorizationEndpoint: String,
    tokenEndpoint: String,
    userinfoEndpoint: String,
    jwksUri: String,
    issuer: String,
    scopes: List[String] = List("openid", "profile", "email")
)

object OidcProviderConfig:
  given Encoder[OidcProviderConfig] = deriveEncoder
  given Decoder[OidcProviderConfig] = deriveDecoder

/** Configuration for mapping OIDC claims to internal user attributes */
case class ClaimMappingConfig(
    userId: String = "sub",
    email: String = "email",
    groups: String = "groups",
    name: String = "name"
)

object ClaimMappingConfig:
  given Encoder[ClaimMappingConfig] = deriveEncoder
  given Decoder[ClaimMappingConfig] = deriveDecoder

/**
 * Session configuration for OAuth.
 *
 * `cookieSecure` applies to both the session cookie and the short-lived pre-auth
 * (login binding) cookie; set it to false only for local HTTP development.
 */
case class OAuthSessionConfig(
    cookieName: String = "ragbox_session",
    cookieSecure: Boolean = true,
    cookieMaxAge: Int = 86400, // 24 hours in seconds
    preAuthCookieName: String = "ragbox_oauth_state"
)

object OAuthSessionConfig:
  given Encoder[OAuthSessionConfig] = deriveEncoder
  given Decoder[OAuthSessionConfig] = deriveDecoder

/** Where OAuth sessions and in-flight authorization states are stored */
enum OAuthSessionStorage:
  case Memory, Postgres

object OAuthSessionStorage:
  def fromString(s: String): Either[String, OAuthSessionStorage] =
    s.trim.toLowerCase match
      case "memory"                   => Right(Memory)
      case "postgres" | "postgresql" => Right(Postgres)
      case other                      => Left(s"Unknown OAuth session storage: $other. Use: memory or postgres")

  given Encoder[OAuthSessionStorage] = Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Decoder[OAuthSessionStorage] = Decoder.decodeString.emap(fromString)

/**
 * Complete OAuth configuration.
 *
 * There is no PKCE switch: every login uses PKCE (S256), as OAuth 2.1 and the OAuth security
 * BCP (RFC 9700) require of every client, and every supported provider accepts it.
 */
case class OAuthConfig(
    provider: OidcProviderConfig,
    claimMapping: ClaimMappingConfig = ClaimMappingConfig(),
    session: OAuthSessionConfig = OAuthSessionConfig(),
    stateTtl: Int = 300, // 5 minutes in seconds
    sessionStorage: OAuthSessionStorage = OAuthSessionStorage.Memory,
    adminGroups: Set[String] = Set.empty // members of these groups are ragbox administrators
)

object OAuthConfig:
  given Encoder[OAuthConfig] = deriveEncoder
  given Decoder[OAuthConfig] = deriveDecoder

/** Validated ID token after OIDC verification */
case class ValidatedIdToken(
    subject: String,
    email: Option[String],
    name: Option[String],
    groups: List[String],
    issuedAt: Long,
    expiresAt: Long,
    rawClaims: Map[String, Any],
    issuer: String,
    emailVerified: Boolean
)

/**
 * Server-side state for an in-flight login (PKCE + nonce + browser binding).
 *
 * @param browserBindingHash SHA-256 (base64url) of the random value placed in the
 *                           browser's pre-auth cookie; the callback must present a
 *                           cookie whose hash matches.
 */
case class AuthorizationState(
    state: String,
    codeVerifier: String,
    redirectAfterLogin: Option[String],
    createdAt: Long,
    nonce: String,
    browserBindingHash: String
)

/** Session data stored after successful authentication */
case class OAuthSessionData(
    sessionId: String,
    userId: String,
    email: Option[String],
    name: Option[String],
    groups: List[String],
    provider: String,
    expiresAt: Long,
    createdAt: Long
)

object OAuthSessionData:
  given Encoder[OAuthSessionData] = deriveEncoder
  given Decoder[OAuthSessionData] = deriveDecoder

/**
 * Result of initiating a login.
 *
 * Deliberately has no JSON codec: `browserBinding` must only ever be sent to the
 * browser in an HttpOnly cookie, never in a response body.
 *
 * @param browserBinding random value to set in the pre-auth cookie
 */
case class OAuthLoginResponse(
    authorizationUrl: String,
    state: String,
    browserBinding: String
)

/**
 * Result of a successful OAuth callback.
 *
 * @param redirectAfterLogin the `redirect_after` the login was started with. It is client
 *                           input: callers must only redirect to it after checking it is a
 *                           safe relative path (see `OAuthRoutes.safeRedirect`).
 */
case class OAuthCallbackResult(
    session: OAuthSessionData,
    redirectAfterLogin: Option[String]
)

/** OAuth userinfo response */
case class OAuthUserInfo(
    userId: String,
    email: Option[String],
    name: Option[String],
    groups: List[String]
)

object OAuthUserInfo:
  given Encoder[OAuthUserInfo] = deriveEncoder
  given Decoder[OAuthUserInfo] = deriveDecoder

/** OAuth errors */
sealed trait OidcError extends Exception:
  def message: String
  override def getMessage: String = message

object OidcError:
  case class InvalidToken(message: String) extends OidcError
  case class TokenExpired(message: String) extends OidcError
  case class InvalidIssuer(expected: String, actual: String) extends OidcError:
    val message = s"Invalid issuer: expected $expected, got $actual"
  case class InvalidAudience(expected: String, actual: String) extends OidcError:
    val message = s"Invalid audience: expected $expected, got $actual"
  case class JwksError(message: String) extends OidcError
  case class TokenExchangeError(message: String) extends OidcError
  case class InvalidState(message: String) extends OidcError
  case class StateExpired(message: String) extends OidcError
  case class InvalidNonce(message: String) extends OidcError
  /** The token is valid but no acceptable user identity could be derived from it */
  case class IdentityError(message: String) extends OidcError
  case class ConfigurationError(message: String) extends OidcError
