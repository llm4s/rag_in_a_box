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

/** Session configuration for OAuth */
case class OAuthSessionConfig(
    cookieName: String = "ragbox_session",
    cookieSecure: Boolean = true,
    cookieMaxAge: Int = 86400 // 24 hours in seconds
)

object OAuthSessionConfig:
  given Encoder[OAuthSessionConfig] = deriveEncoder
  given Decoder[OAuthSessionConfig] = deriveDecoder

/** Complete OAuth configuration */
case class OAuthConfig(
    provider: OidcProviderConfig,
    claimMapping: ClaimMappingConfig = ClaimMappingConfig(),
    session: OAuthSessionConfig = OAuthSessionConfig(),
    pkceEnabled: Boolean = true,
    stateTtl: Int = 300 // 5 minutes in seconds
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
    rawClaims: Map[String, Any]
)

/** Authorization state for PKCE flow */
case class AuthorizationState(
    state: String,
    codeVerifier: String,
    redirectAfterLogin: Option[String],
    createdAt: Long
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

/** OAuth login response */
case class OAuthLoginResponse(
    authorizationUrl: String,
    state: String
)

object OAuthLoginResponse:
  given Encoder[OAuthLoginResponse] = deriveEncoder
  given Decoder[OAuthLoginResponse] = deriveDecoder

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
  case class ConfigurationError(message: String) extends OidcError
