package org.llm4s.ragbox.auth

import cats.effect.IO
import com.auth0.jwk.{JwkProvider, JwkProviderBuilder}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.*
import com.auth0.jwt.interfaces.DecodedJWT
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** Validates OIDC ID tokens using JWKS */
trait OidcTokenValidator[F[_]]:
  /**
   * Validate an ID token and extract claims.
   *
   * @param expectedNonce when set, the token's `nonce` claim must be present and equal
   *                      to it (authorization-code logins always pass the nonce that
   *                      was sent in the authorization request).
   */
  def validate(idToken: String, expectedNonce: Option[String]): F[Either[OidcError, ValidatedIdToken]]

  /** Validate an ID token without a nonce check (e.g. tokens not obtained via a browser login) */
  def validate(idToken: String): F[Either[OidcError, ValidatedIdToken]] = validate(idToken, None)

/**
 * Implementation using the Auth0 JWT library with the provider's JWKS.
 *
 * '''Key rotation.''' Signing keys are fetched from the JWKS endpoint on demand and cached by key
 * id (`kid`), so no scheduled refresh is needed:
 *  - a token signed with a new key has a `kid` that is not cached yet, which fetches the JWKS
 *    again (at most 10 fetches a minute, so unknown `kid`s cannot be used to flood the provider);
 *  - a cached key is forgotten after `keyCacheTtl`, so a key the provider has retired (removed
 *    from its JWKS) stops being accepted at most that long after it was last fetched.
 */
class OidcTokenValidatorImpl private (
    config: OidcProviderConfig,
    claimMapping: ClaimMappingConfig,
    jwkProvider: JwkProvider
) extends OidcTokenValidator[IO]:

  override def validate(
      idToken: String,
      expectedNonce: Option[String]
  ): IO[Either[OidcError, ValidatedIdToken]] =
    IO.blocking {
      validateToken(idToken, jwkProvider).flatMap(t => checkNonce(t, expectedNonce))
    }

  private def checkNonce(
      token: ValidatedIdToken,
      expectedNonce: Option[String]
  ): Either[OidcError, ValidatedIdToken] =
    expectedNonce match
      case None => Right(token)
      case Some(expected) =>
        token.rawClaims.get("nonce") match
          case Some(actual: String) if constantTimeEquals(actual, expected) => Right(token)
          case Some(_) => Left(OidcError.InvalidNonce("ID token nonce does not match the login request"))
          case None    => Left(OidcError.InvalidNonce("ID token is missing the nonce claim"))

  private def constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8))

  private def validateToken(
      idToken: String,
      jwkProvider: JwkProvider
  ): Either[OidcError, ValidatedIdToken] =
    Try {
      // Decode without verification to get key ID
      val decoded = JWT.decode(idToken)
      val keyId = decoded.getKeyId

      if keyId == null then
        throw new JWTVerificationException("Token missing key ID (kid)")

      // Get the public key from JWKS
      val jwk = jwkProvider.get(keyId)
      val publicKey = jwk.getPublicKey.asInstanceOf[RSAPublicKey]
      val algorithm = Algorithm.RSA256(publicKey, null)

      // Verify the token
      val verifier = JWT
        .require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.clientId)
        .build()

      val verified = verifier.verify(idToken)
      extractClaims(verified)
    } match
      case Success(token)                             => Right(token)
      case Failure(e: TokenExpiredException)          => Left(OidcError.TokenExpired(e.getMessage))
      case Failure(e: SignatureVerificationException) => Left(OidcError.InvalidToken(s"Invalid signature: ${e.getMessage}"))
      case Failure(e: InvalidClaimException) =>
        if e.getMessage.contains("issuer") then
          Left(OidcError.InvalidIssuer(config.issuer, "actual"))
        else if e.getMessage.contains("audience") then
          Left(OidcError.InvalidAudience(config.clientId, "actual"))
        else Left(OidcError.InvalidToken(e.getMessage))
      case Failure(e: JWTVerificationException) => Left(OidcError.InvalidToken(e.getMessage))
      case Failure(e: com.auth0.jwk.JwkException) => Left(OidcError.JwksError(e.getMessage))
      case Failure(e) => Left(OidcError.InvalidToken(s"Token validation failed: ${e.getMessage}"))

  private def extractClaims(jwt: DecodedJWT): ValidatedIdToken =
    val claims = jwt.getClaims.asScala

    // Extract groups - can be in various formats
    val groups: List[String] = claims.get(claimMapping.groups) match
      case Some(claim) if !claim.isNull =>
        Try(claim.asList(classOf[String]).asScala.toList)
          .orElse(Try(List(claim.asString)))
          .getOrElse(Nil)
      case _ => Nil

    // email_verified is a JSON boolean per the spec, but some providers send "true"/"false" strings
    val emailVerified = claims.get("email_verified").exists { c =>
      !c.isNull && (Option(c.asBoolean).exists(_.booleanValue) || Option(c.asString).exists(_.equalsIgnoreCase("true")))
    }

    ValidatedIdToken(
      subject = jwt.getSubject,
      issuer = jwt.getIssuer,
      emailVerified = emailVerified,
      email = Option(claims.get(claimMapping.email).flatMap(c => Option(c.asString)).orNull),
      name = Option(claims.get(claimMapping.name).flatMap(c => Option(c.asString)).orNull),
      groups = groups,
      issuedAt = jwt.getIssuedAtAsInstant.toEpochMilli,
      expiresAt = jwt.getExpiresAtAsInstant.toEpochMilli,
      rawClaims = claims.view.mapValues(c =>
        if c.isNull then null
        else if c.asString != null then c.asString
        else if c.asBoolean != null then c.asBoolean
        else if c.asInt != null then c.asInt
        else if c.asLong != null then c.asLong
        else if c.asDouble != null then c.asDouble
        else c.toString
      ).toMap.asInstanceOf[Map[String, Any]]
    )

object OidcTokenValidatorImpl:

  /** How long a fetched signing key is trusted before the JWKS is consulted again. */
  val DefaultKeyCacheTtl: FiniteDuration = 1.hour

  def create(
      config: OidcProviderConfig,
      claimMapping: ClaimMappingConfig = ClaimMappingConfig(),
      keyCacheTtl: FiniteDuration = DefaultKeyCacheTtl
  ): IO[OidcTokenValidatorImpl] =
    for
      logger <- Slf4jLogger.create[IO]
      _ <- logger.info(s"Creating OIDC token validator for issuer: ${config.issuer}")
      provider <- IO.blocking {
        new JwkProviderBuilder(URI.create(config.jwksUri).toURL)
          .cached(10, keyCacheTtl.toMillis, TimeUnit.MILLISECONDS) // up to 10 keys
          .rateLimited(10, 1, TimeUnit.MINUTES) // at most 10 JWKS fetches a minute
          .build()
      }
    yield new OidcTokenValidatorImpl(config, claimMapping, provider)
