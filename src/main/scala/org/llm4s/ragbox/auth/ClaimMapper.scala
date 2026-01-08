package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.syntax.all.*
import org.llm4s.rag.permissions.{ExternalPrincipal, PrincipalStore, UserAuthorization}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Maps OIDC claims from validated ID tokens to internal authorization.
 *
 * This component:
 * - Extracts user identity from the 'sub' claim
 * - Extracts group memberships from configured claim
 * - Creates/retrieves principals in the PrincipalStore
 * - Returns UserAuthorization for use in permission checks
 */
trait ClaimMapper[F[_]]:
  /**
   * Map a validated ID token to internal UserAuthorization.
   * Creates user/group principals if they don't exist.
   */
  def mapToAuthorization(token: ValidatedIdToken): F[UserAuthorization]

  /**
   * Map a validated ID token to OAuthSessionData for storage.
   */
  def mapToSessionData(
      token: ValidatedIdToken,
      sessionId: String,
      provider: String,
      sessionMaxAge: Int
  ): F[OAuthSessionData]

/**
 * Default implementation of ClaimMapper.
 */
class ClaimMapperImpl private (
    principals: PrincipalStore,
    claimMapping: ClaimMappingConfig,
    logger: Logger[IO]
) extends ClaimMapper[IO]:

  override def mapToAuthorization(token: ValidatedIdToken): IO[UserAuthorization] =
    for
      _ <- logger.debug(s"Mapping OIDC claims for subject: ${token.subject}")

      // Get or create user principal using the subject as external ID
      // Use email if available for more readable principal names
      userId = token.email.getOrElse(token.subject)
      userPrincipal <- IO.fromEither(
        principals
          .getOrCreate(ExternalPrincipal.User(userId))
          .left.map(e => new RuntimeException(s"Failed to create user principal: ${e.message}"))
      )

      // Get or create group principals
      groupPrincipals <- token.groups.traverse { groupName =>
        IO.fromEither(
          principals
            .getOrCreate(ExternalPrincipal.Group(groupName))
            .left.map(e => new RuntimeException(s"Failed to create group principal: ${e.message}"))
        )
      }

      _ <- logger.info(
        s"Mapped OIDC user '$userId' with ${groupPrincipals.size} groups to principal ${userPrincipal.value}"
      )
    yield UserAuthorization.forUser(userPrincipal, groupPrincipals.toSet)

  override def mapToSessionData(
      token: ValidatedIdToken,
      sessionId: String,
      provider: String,
      sessionMaxAge: Int
  ): IO[OAuthSessionData] =
    val now = System.currentTimeMillis()
    IO.pure(
      OAuthSessionData(
        sessionId = sessionId,
        userId = token.email.getOrElse(token.subject),
        email = token.email,
        name = token.name,
        groups = token.groups,
        provider = provider,
        expiresAt = now + (sessionMaxAge * 1000L),
        createdAt = now
      )
    )

object ClaimMapperImpl:
  def create(
      principals: PrincipalStore,
      claimMapping: ClaimMappingConfig = ClaimMappingConfig()
  ): IO[ClaimMapperImpl] =
    for
      logger <- Slf4jLogger.create[IO]
      _ <- logger.info("Created ClaimMapper for OIDC claim mapping")
    yield new ClaimMapperImpl(principals, claimMapping, logger)
