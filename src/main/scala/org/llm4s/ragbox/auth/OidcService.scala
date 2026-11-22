package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.parser.decode
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri, UrlForm}
import org.http4s.circe.*
import org.llm4s.rag.permissions.UserAuthorization
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/**
 * Main orchestrator for OAuth2/OIDC authentication flow.
 *
 * Handles:
 * - Login initiation with PKCE
 * - Authorization callback handling
 * - Token exchange and validation
 * - Session management
 * - Logout
 */
trait OidcService[F[_]]:
  /**
   * Initiate OAuth login flow. Returns the authorization URL, the state, and a
   * random browser-binding value that the caller must set in an HttpOnly cookie.
   */
  def initiateLogin(redirectAfterLogin: Option[String]): F[OAuthLoginResponse]

  /**
   * Handle authorization callback, exchange code for tokens.
   *
   * @param browserBinding value of the pre-auth cookie presented by the browser; it must
   *                       match the binding stored for `state`, otherwise the callback is
   *                       rejected (login CSRF protection).
   */
  def handleCallback(code: String, state: String, browserBinding: String): F[Either[OidcError, OAuthSessionData]]

  /** Validate an existing session */
  def validateSession(sessionId: String): F[Option[OAuthSessionData]]

  /** Get UserAuthorization for a session */
  def getAuthorization(sessionId: String): F[Option[UserAuthorization]]

  /** End a session (logout) */
  def logout(sessionId: String): F[Unit]

/**
 * Token response from the authorization server.
 */
private case class TokenResponse(
    access_token: String,
    token_type: String,
    expires_in: Option[Long],
    id_token: Option[String],
    refresh_token: Option[String]
)

private object TokenResponse:
  given Decoder[TokenResponse] = Decoder.instance { c =>
    for
      accessToken <- c.get[String]("access_token")
      tokenType <- c.get[String]("token_type")
      expiresIn <- c.get[Option[Long]]("expires_in")
      idToken <- c.get[Option[String]]("id_token")
      refreshToken <- c.get[Option[String]]("refresh_token")
    yield TokenResponse(accessToken, tokenType, expiresIn, idToken, refreshToken)
  }

/**
 * Implementation of OidcService.
 */
class OidcServiceImpl private (
    config: OAuthConfig,
    httpClient: Client[IO],
    tokenValidator: OidcTokenValidator[IO],
    sessionRegistry: SessionRegistry[IO],
    claimMapper: ClaimMapper[IO],
    logger: Logger[IO]
) extends OidcService[IO]:

  import OidcServiceImpl.sha256Base64Url

  private val random = new SecureRandom()
  private val providerConfig = config.provider

  override def initiateLogin(redirectAfterLogin: Option[String]): IO[OAuthLoginResponse] =
    for
      _ <- logger.info("Initiating OAuth login flow")

      // Generate state, PKCE code verifier, nonce and browser binding
      state <- generateSecureString(32)
      codeVerifier <- generateSecureString(64)
      nonce <- generateSecureString(32)
      browserBinding <- generateSecureString(32)
      codeChallenge = sha256Base64Url(codeVerifier)
      now <- IO.realTime

      // Store authorization state (only a hash of the browser binding is kept server-side)
      authState = AuthorizationState(
        state = state,
        codeVerifier = codeVerifier,
        redirectAfterLogin = redirectAfterLogin,
        createdAt = now.toMillis,
        nonce = nonce,
        browserBindingHash = sha256Base64Url(browserBinding)
      )
      _ <- sessionRegistry.storeAuthState(authState)

      // Build authorization URL
      authUrl = buildAuthorizationUrl(state, codeChallenge, nonce)
    yield OAuthLoginResponse(authUrl, state, browserBinding)

  override def handleCallback(
      code: String,
      state: String,
      browserBinding: String
  ): IO[Either[OidcError, OAuthSessionData]] =
    (for
      _ <- logger.info(s"Handling OAuth callback with state: ${state.take(8)}...")

      // Retrieve and validate stored state
      authStateOpt <- sessionRegistry.getAndRemoveAuthState(state)
      authState <- authStateOpt match
        case Some(s) => IO.pure(s)
        case None =>
          logger.warn(s"Invalid or expired state: ${state.take(8)}...")
          IO.raiseError(OidcError.InvalidState("State not found or already used"))

      // The state must belong to the browser that started the login
      _ <-
        if MessageDigest.isEqual(
            sha256Base64Url(browserBinding).getBytes(StandardCharsets.US_ASCII),
            authState.browserBindingHash.getBytes(StandardCharsets.US_ASCII)
          )
        then IO.unit
        else
          logger.warn(s"OAuth callback state ${state.take(8)}... not bound to this browser") *>
            IO.raiseError(OidcError.InvalidState("Login was not initiated by this browser"))

      // Check state expiration
      now <- IO.realTime.map(_.toMillis)
      _ <-
        if (now - authState.createdAt) > (config.stateTtl * 1000L) then
          IO.raiseError(OidcError.StateExpired("Authorization state has expired"))
        else IO.unit

      // Exchange code for tokens
      _ <- logger.debug("Exchanging authorization code for tokens")
      tokenResponse <- exchangeCodeForTokens(code, authState.codeVerifier)

      // Extract and validate ID token
      idToken <- tokenResponse.id_token match
        case Some(token) => IO.pure(token)
        case None =>
          IO.raiseError(OidcError.TokenExchangeError("No ID token in response"))

      _ <- logger.debug("Validating ID token")
      validatedToken <- tokenValidator.validate(idToken, Some(authState.nonce)).flatMap {
        case Right(t)  => IO.pure(t)
        case Left(err) => IO.raiseError(err)
      }

      // Create session
      sessionId <- generateSecureString(32)
      sessionData <- claimMapper.mapToSessionData(
        validatedToken,
        sessionId,
        providerConfig.issuer,
        config.session.cookieMaxAge
      )
      _ <- sessionRegistry.createSession(sessionData)

      _ <- logger.info(
        s"OAuth login successful for user: ${sessionData.userId}, session: ${sessionId.take(8)}..."
      )
    yield sessionData).attempt.map {
      case Right(data)              => Right(data)
      case Left(e: OidcError) => Left(e)
      case Left(e) =>
        Left(OidcError.TokenExchangeError(s"Callback handling failed: ${e.getMessage}"))
    }

  override def validateSession(sessionId: String): IO[Option[OAuthSessionData]] =
    sessionRegistry.getSession(sessionId).flatMap {
      case Some(session) if session.expiresAt > System.currentTimeMillis() =>
        IO.pure(Some(session))
      case Some(_) =>
        // Session expired, clean it up
        sessionRegistry.deleteSession(sessionId).as(None)
      case None =>
        IO.pure(None)
    }

  override def getAuthorization(sessionId: String): IO[Option[UserAuthorization]] =
    validateSession(sessionId).flatMap {
      case Some(session) =>
        // The session already holds the resolved identity; don't re-derive it from claims
        claimMapper.authorizationFor(session.userId, session.groups).map(Some(_))
      case None =>
        IO.pure(None)
    }

  override def logout(sessionId: String): IO[Unit] =
    for
      _ <- logger.info(s"Logging out session: ${sessionId.take(8)}...")
      _ <- sessionRegistry.deleteSession(sessionId)
    yield ()

  // ============================================================
  // Private helpers
  // ============================================================

  private def generateSecureString(length: Int): IO[String] = IO {
    val bytes = new Array[Byte](length)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def buildAuthorizationUrl(state: String, codeChallenge: String, nonce: String): String =
    val params = List(
      "response_type" -> "code",
      "client_id" -> providerConfig.clientId,
      "redirect_uri" -> providerConfig.redirectUri,
      "scope" -> providerConfig.scopes.mkString(" "),
      "state" -> state,
      "nonce" -> nonce,
      "code_challenge" -> codeChallenge,
      "code_challenge_method" -> "S256"
    )

    val queryString = params
      .map { case (k, v) =>
        s"${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
      }
      .mkString("&")

    s"${providerConfig.authorizationEndpoint}?$queryString"

  private def exchangeCodeForTokens(code: String, codeVerifier: String): IO[TokenResponse] =
    val form = UrlForm(
      "grant_type" -> "authorization_code",
      "code" -> code,
      "redirect_uri" -> providerConfig.redirectUri,
      "client_id" -> providerConfig.clientId,
      "client_secret" -> providerConfig.clientSecret,
      "code_verifier" -> codeVerifier
    )

    val request = Request[IO](
      method = Method.POST,
      uri = Uri.unsafeFromString(providerConfig.tokenEndpoint)
    ).withEntity(form)

    httpClient.expect[Json](request).flatMap { json =>
      json.as[TokenResponse] match
        case Right(response) => IO.pure(response)
        case Left(err) =>
          // Check for error response from provider
          val errorMsg = json.hcursor
            .get[String]("error_description")
            .orElse(json.hcursor.get[String]("error"))
            .getOrElse(err.getMessage)
          IO.raiseError(OidcError.TokenExchangeError(s"Token exchange failed: $errorMsg"))
    }

object OidcServiceImpl:
  /** base64url(SHA-256(value)) - used for the PKCE S256 challenge and the browser binding hash */
  private[auth] def sha256Base64Url(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding().encodeToString(digest)

  def create(
      config: OAuthConfig,
      httpClient: Client[IO],
      tokenValidator: OidcTokenValidator[IO],
      sessionRegistry: SessionRegistry[IO],
      claimMapper: ClaimMapper[IO]
  ): IO[OidcServiceImpl] =
    for
      logger <- Slf4jLogger.create[IO]
      _ <- logger.info(s"Created OidcService for provider: ${config.provider.issuer}")
    yield new OidcServiceImpl(
      config,
      httpClient,
      tokenValidator,
      sessionRegistry,
      claimMapper,
      logger
    )
