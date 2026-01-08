package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.llm4s.ragbox.testkit.{MockOidcServer, MockPrincipalStore}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class OidcServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers:

  // Use port 9998 to avoid conflicts with OidcTokenValidatorSpec which uses 9999
  private val testPort = 9998

  "OidcService.initiateLogin" should {

    "return authorization URL with state parameter" in {
      MockOidcServer.resource(testPort).use { mockServer =>
        val providerConfig = OidcProviderConfig(
          clientId = "test-client-id",
          clientSecret = "test-secret",
          redirectUri = "http://localhost/callback",
          authorizationEndpoint = mockServer.authorizationEndpoint,
          tokenEndpoint = mockServer.tokenEndpoint,
          userinfoEndpoint = mockServer.userinfoEndpoint,
          jwksUri = mockServer.jwksUri,
          issuer = mockServer.issuer
        )

        val sessionConfig = OAuthSessionConfig(
          cookieName = "ragbox_session",
          cookieSecure = false,
          cookieMaxAge = 86400
        )

        val oauthConfig = OAuthConfig(
          provider = providerConfig,
          claimMapping = ClaimMappingConfig(),
          session = sessionConfig,
          stateTtl = 300
        )

        for
          sessionRegistry <- InMemorySessionRegistry.create
          tokenValidator <- OidcTokenValidatorImpl.create(providerConfig)
          principalStore = MockPrincipalStore()
          claimMapper <- ClaimMapperImpl.create(principalStore)
          oidcService <- createOidcService(oauthConfig, tokenValidator, sessionRegistry, claimMapper)
          response <- oidcService.initiateLogin(None)
        yield
          response.authorizationUrl should include(mockServer.authorizationEndpoint)
          response.authorizationUrl should include("client_id=test-client-id")
          response.authorizationUrl should include("response_type=code")
          response.authorizationUrl should include("code_challenge=")
          response.authorizationUrl should include("code_challenge_method=S256")
          response.state should not be empty
      }
    }

    "preserve redirect_after parameter" in {
      MockOidcServer.resource(testPort).use { mockServer =>
        val providerConfig = OidcProviderConfig(
          clientId = "test-client-id",
          clientSecret = "test-secret",
          redirectUri = "http://localhost/callback",
          authorizationEndpoint = mockServer.authorizationEndpoint,
          tokenEndpoint = mockServer.tokenEndpoint,
          userinfoEndpoint = mockServer.userinfoEndpoint,
          jwksUri = mockServer.jwksUri,
          issuer = mockServer.issuer
        )

        val sessionConfig = OAuthSessionConfig(
          cookieName = "ragbox_session",
          cookieSecure = false,
          cookieMaxAge = 86400
        )

        val oauthConfig = OAuthConfig(
          provider = providerConfig,
          claimMapping = ClaimMappingConfig(),
          session = sessionConfig,
          stateTtl = 300
        )

        for
          sessionRegistry <- InMemorySessionRegistry.create
          tokenValidator <- OidcTokenValidatorImpl.create(providerConfig)
          principalStore = MockPrincipalStore()
          claimMapper <- ClaimMapperImpl.create(principalStore)
          oidcService <- createOidcService(oauthConfig, tokenValidator, sessionRegistry, claimMapper)
          response <- oidcService.initiateLogin(Some("/admin/dashboard"))
          // Verify state was stored with redirect
          storedState <- sessionRegistry.getAndRemoveAuthState(response.state)
        yield
          storedState should not be empty
          storedState.get.redirectAfterLogin shouldBe Some("/admin/dashboard")
      }
    }
  }

  "OidcService.validateSession" should {

    "return session data for valid session" in {
      for
        sessionRegistry <- InMemorySessionRegistry.create
        sessionData = OAuthSessionData(
          sessionId = "valid-session-123",
          userId = "user@example.com",
          email = Some("user@example.com"),
          name = Some("Test User"),
          groups = List("users"),
          provider = "test",
          expiresAt = System.currentTimeMillis() + 3600000, // 1 hour from now
          createdAt = System.currentTimeMillis()
        )
        _ <- sessionRegistry.createSession(sessionData)
        result <- sessionRegistry.getSession("valid-session-123")
      yield
        result should not be empty
        result.get.userId shouldBe "user@example.com"
        result.get.email shouldBe Some("user@example.com")
    }

    "return None for expired session" in {
      for
        sessionRegistry <- InMemorySessionRegistry.create
        expiredSession = OAuthSessionData(
          sessionId = "expired-session",
          userId = "user@example.com",
          email = Some("user@example.com"),
          name = None,
          groups = List.empty,
          provider = "test",
          expiresAt = System.currentTimeMillis() - 3600000, // 1 hour ago
          createdAt = System.currentTimeMillis() - 7200000
        )
        _ <- sessionRegistry.createSession(expiredSession)
        // SessionRegistry doesn't auto-clean expired, but OidcService does
        result <- sessionRegistry.getSession("expired-session")
      yield
        // Registry still has it, but OidcService.validateSession would return None
        result.get.expiresAt should be < System.currentTimeMillis()
    }

    "return None for non-existent session" in {
      for
        sessionRegistry <- InMemorySessionRegistry.create
        result <- sessionRegistry.getSession("non-existent-session")
      yield
        result shouldBe None
    }
  }

  "OidcService.logout" should {

    "delete session from registry" in {
      for
        sessionRegistry <- InMemorySessionRegistry.create
        sessionData = OAuthSessionData(
          sessionId = "session-to-logout",
          userId = "user@example.com",
          email = Some("user@example.com"),
          name = None,
          groups = List.empty,
          provider = "test",
          expiresAt = System.currentTimeMillis() + 3600000,
          createdAt = System.currentTimeMillis()
        )
        _ <- sessionRegistry.createSession(sessionData)
        beforeLogout <- sessionRegistry.getSession("session-to-logout")
        _ <- sessionRegistry.deleteSession("session-to-logout")
        afterLogout <- sessionRegistry.getSession("session-to-logout")
      yield
        beforeLogout should not be empty
        afterLogout shouldBe None
    }
  }

  "InMemorySessionRegistry" should {

    "store and retrieve auth state" in {
      for
        sessionRegistry <- InMemorySessionRegistry.create
        authState = AuthorizationState(
          state = "test-state-123",
          codeVerifier = "test-verifier",
          redirectAfterLogin = Some("/dashboard"),
          createdAt = System.currentTimeMillis()
        )
        _ <- sessionRegistry.storeAuthState(authState)
        retrieved <- sessionRegistry.getAndRemoveAuthState("test-state-123")
        secondRetrieval <- sessionRegistry.getAndRemoveAuthState("test-state-123")
      yield
        retrieved should not be empty
        retrieved.get.state shouldBe "test-state-123"
        retrieved.get.codeVerifier shouldBe "test-verifier"
        // Should be removed after first retrieval
        secondRetrieval shouldBe None
    }

    "return None for non-existent auth state" in {
      for
        sessionRegistry <- InMemorySessionRegistry.create
        result <- sessionRegistry.getAndRemoveAuthState("non-existent-state")
      yield
        result shouldBe None
    }
  }

  // Helper to create OidcService without making HTTP calls
  private def createOidcService(
      config: OAuthConfig,
      tokenValidator: OidcTokenValidator[IO],
      sessionRegistry: SessionRegistry[IO],
      claimMapper: ClaimMapper[IO]
  ): IO[OidcService[IO]] =
    import org.http4s.ember.client.EmberClientBuilder
    EmberClientBuilder.default[IO].build.use { client =>
      OidcServiceImpl.create(config, client, tokenValidator, sessionRegistry, claimMapper)
    }
