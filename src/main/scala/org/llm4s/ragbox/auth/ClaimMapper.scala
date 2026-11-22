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
 * - Derives a stable user identity from the configured `claimMapping.userId` claim
 * - Extracts group memberships from configured claim
 * - Creates/retrieves principals in the PrincipalStore
 * - Returns UserAuthorization for use in permission checks
 *
 * Identity rules (see docs/oauth-security.md):
 * - `sub` (default): the principal is `"<iss>|<sub>"`; `sub` is only unique per issuer.
 * - the email claim: used only when the token asserts `email_verified = true`,
 *   otherwise the login is rejected with [[OidcError.IdentityError]].
 * - any other claim: its string value is used as-is; a missing claim rejects the login.
 */
trait ClaimMapper[F[_]]:
  /**
   * Map a validated ID token to internal UserAuthorization.
   * Creates user/group principals if they don't exist.
   */
  def mapToAuthorization(token: ValidatedIdToken): F[UserAuthorization]

  /**
   * Build UserAuthorization for an already-established identity (e.g. a stored session).
   */
  def authorizationFor(userId: String, groups: List[String]): F[UserAuthorization]

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

  /** Derive the principal identifier for a token according to `claimMapping.userId`. */
  private[auth] def resolveUserId(token: ValidatedIdToken): Either[OidcError, String] =
    val claim = claimMapping.userId
    if claim == "sub" then Right(s"${token.issuer}|${token.subject}")
    else if claim == claimMapping.email || claim == "email" then
      token.email match
        case Some(email) if token.emailVerified && email.nonEmpty => Right(email)
        case Some(_) =>
          Left(OidcError.IdentityError("Email is configured as the user id but the provider did not verify it"))
        case None => Left(OidcError.IdentityError(s"ID token is missing the '$claim' claim"))
    else
      token.rawClaims.get(claim) match
        case Some(value) if value != null && value.toString.nonEmpty => Right(value.toString)
        case _ => Left(OidcError.IdentityError(s"ID token is missing the '$claim' claim"))

  override def mapToAuthorization(token: ValidatedIdToken): IO[UserAuthorization] =
    for
      _ <- logger.debug(s"Mapping OIDC claims for subject: ${token.subject}")
      userId <- IO.fromEither(resolveUserId(token))
      auth <- authorizationFor(userId, token.groups)
    yield auth

  override def authorizationFor(userId: String, groups: List[String]): IO[UserAuthorization] =
    for
      userPrincipal <- IO.fromEither(
        principals
          .getOrCreate(ExternalPrincipal.User(userId))
          .left.map(e => new RuntimeException(s"Failed to create user principal: ${e.message}"))
      )
      groupPrincipals <- groups.traverse { groupName =>
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
    IO.fromEither(resolveUserId(token)).flatMap { userId =>
      IO.realTime.map { now =>
        val nowMs = now.toMillis
        OAuthSessionData(
          sessionId = sessionId,
          userId = userId,
          email = token.email,
          name = token.name,
          groups = token.groups,
          provider = provider,
          expiresAt = nowMs + (sessionMaxAge * 1000L),
          createdAt = nowMs
        )
      }
    }

object ClaimMapperImpl:
  def create(
      principals: PrincipalStore,
      claimMapping: ClaimMappingConfig = ClaimMappingConfig()
  ): IO[ClaimMapperImpl] =
    for
      logger <- Slf4jLogger.create[IO]
      _ <- logger.info(s"Created ClaimMapper for OIDC claim mapping (user id claim: ${claimMapping.userId})")
    yield new ClaimMapperImpl(principals, claimMapping, logger)
