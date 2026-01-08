package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import com.auth0.jwk.{Jwk, JwkProvider, JwkProviderBuilder}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.*
import com.auth0.jwt.interfaces.DecodedJWT
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** Validates OIDC ID tokens using JWKS */
trait OidcTokenValidator[F[_]]:
  /** Validate an ID token and extract claims */
  def validate(idToken: String): F[Either[OidcError, ValidatedIdToken]]

  /** Force refresh of JWKS cache */
  def refreshJwks(): F[Unit]

/** Implementation using Auth0 JWT library with JWKS */
class OidcTokenValidatorImpl private (
    config: OidcProviderConfig,
    claimMapping: ClaimMappingConfig,
    jwkProviderRef: Ref[IO, JwkProvider],
    logger: Logger[IO]
) extends OidcTokenValidator[IO]:

  override def validate(
      idToken: String
  ): IO[Either[OidcError, ValidatedIdToken]] =
    jwkProviderRef.get.flatMap { jwkProvider =>
      IO.blocking {
        validateToken(idToken, jwkProvider)
      }
    }

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

    ValidatedIdToken(
      subject = jwt.getSubject,
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

  override def refreshJwks(): IO[Unit] =
    for
      _ <- logger.info(s"Refreshing JWKS from ${config.jwksUri}")
      newProvider <- IO.blocking(createJwkProvider(config.jwksUri))
      _ <- jwkProviderRef.set(newProvider)
      _ <- logger.info("JWKS refresh complete")
    yield ()

  private def createJwkProvider(jwksUri: String): JwkProvider =
    new JwkProviderBuilder(new URL(jwksUri))
      .cached(10, 24, TimeUnit.HOURS) // Cache up to 10 keys for 24 hours
      .rateLimited(10, 1, TimeUnit.MINUTES) // Rate limit: 10 requests per minute
      .build()

object OidcTokenValidatorImpl:
  def create(
      config: OidcProviderConfig,
      claimMapping: ClaimMappingConfig = ClaimMappingConfig()
  ): IO[OidcTokenValidatorImpl] =
    for
      logger <- Slf4jLogger.create[IO]
      _ <- logger.info(s"Creating OIDC token validator for issuer: ${config.issuer}")
      initialProvider <- IO.blocking {
        new JwkProviderBuilder(new URL(config.jwksUri))
          .cached(10, 24, TimeUnit.HOURS)
          .rateLimited(10, 1, TimeUnit.MINUTES)
          .build()
      }
      providerRef <- Ref.of[IO, JwkProvider](initialProvider)
    yield new OidcTokenValidatorImpl(config, claimMapping, providerRef, logger)
