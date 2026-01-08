package org.llm4s.ragbox.integration

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.http4s.ember.client.EmberClientBuilder
import org.llm4s.ragbox.auth.*
import org.llm4s.ragbox.testkit.{KeycloakAdminClient, MockPrincipalStore}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.testcontainers.containers.wait.strategy.Wait

import java.time.Duration

/**
 * Integration tests for OAuth2/OIDC flow using Keycloak in Docker.
 *
 * These tests require Docker to be running and may take longer due to
 * container startup time.
 *
 * Note: Docker Desktop 4.55+ with Docker 29.x may have compatibility issues
 * with testcontainers due to API version requirements. These tests work in
 * GitHub Actions CI and with Docker Desktop versions that support older APIs.
 * If tests fail with "Could not find a valid Docker environment", try:
 *   - Using an older Docker Desktop version
 *   - Running in CI/CD environment
 *   - Enabling Docker socket compatibility in Docker Desktop settings
 *
 * To run only these tests:
 *   sbt "testOnly *OAuthIntegrationSpec"
 */
class OAuthIntegrationSpec
    extends AsyncWordSpec
    with AsyncIOSpec
    with Matchers
    with TestContainerForAll:

  override val containerDef: GenericContainer.Def[GenericContainer] = GenericContainer.Def(
    dockerImage = "quay.io/keycloak/keycloak:24.0",
    exposedPorts = Seq(8080),
    command = Seq("start-dev"),
    env = Map(
      "KEYCLOAK_ADMIN" -> "admin",
      "KEYCLOAK_ADMIN_PASSWORD" -> "admin",
      "KC_HEALTH_ENABLED" -> "true"
    ),
    waitStrategy = Wait.forHttp("/health/ready")
      .forPort(8080)
      .withStartupTimeout(Duration.ofMinutes(2))
  )

  private def keycloakBaseUrl(container: GenericContainer): String =
    s"http://${container.container.getHost}:${container.container.getMappedPort(8080)}"

  private def keycloakIssuer(container: GenericContainer): String =
    s"${keycloakBaseUrl(container)}/realms/$testRealmName"

  private val testRealmName = "ragbox-test"
  private val testClientId = "ragbox-integration-client"
  private val testClientSecret = "integration-test-secret"
  private val testUsername = "testuser"
  private val testEmail = "testuser@example.com"
  private val testPassword = "testpassword123"

  "OAuth integration with Keycloak" should {

    "validate tokens issued by Keycloak" in {
      withContainers { container =>
        val baseUrl = keycloakBaseUrl(container)
        val issuer = keycloakIssuer(container)

        EmberClientBuilder.default[IO].build.use { httpClient =>
          val adminClient = KeycloakAdminClient(baseUrl, httpClient)

          for
            // Setup Keycloak
            _ <- adminClient.authenticate()
            _ <- adminClient.createRealm(testRealmName)
            _ <- adminClient.createClient(testRealmName, testClientId, testClientSecret, "http://localhost/callback")
            _ <- adminClient.createUser(testRealmName, testUsername, testEmail, testPassword)

            // Get a token via direct grant
            tokenResponse <- adminClient.getDirectGrantToken(
              testRealmName,
              testClientId,
              testClientSecret,
              testUsername,
              testPassword
            )

            // Create validator
            providerConfig = OidcProviderConfig(
              clientId = testClientId,
              clientSecret = testClientSecret,
              redirectUri = "http://localhost/callback",
              authorizationEndpoint = s"$issuer/protocol/openid-connect/auth",
              tokenEndpoint = s"$issuer/protocol/openid-connect/token",
              userinfoEndpoint = s"$issuer/protocol/openid-connect/userinfo",
              jwksUri = s"$issuer/protocol/openid-connect/certs",
              issuer = issuer
            )
            validator <- OidcTokenValidatorImpl.create(providerConfig)

            // Validate the ID token
            idToken = tokenResponse.idToken.getOrElse(fail("No ID token in response"))
            result <- validator.validate(idToken)
          yield
            result shouldBe a[Right[?, ?]]
            val validated = result.toOption.get
            validated.email shouldBe Some(testEmail)
        }
      }
    }

    "complete full session creation flow" in {
      withContainers { container =>
        val baseUrl = keycloakBaseUrl(container)
        val issuer = keycloakIssuer(container)

        EmberClientBuilder.default[IO].build.use { httpClient =>
          val adminClient = KeycloakAdminClient(baseUrl, httpClient)

          for
            // Setup Keycloak
            _ <- adminClient.authenticate()
            _ <- adminClient.createRealm(testRealmName)
            _ <- adminClient.createClient(testRealmName, testClientId, testClientSecret, "http://localhost/callback")
            _ <- adminClient.createUser(testRealmName, testUsername, testEmail, testPassword)

            // Get a token
            tokenResponse <- adminClient.getDirectGrantToken(
              testRealmName,
              testClientId,
              testClientSecret,
              testUsername,
              testPassword
            )

            // Create components
            providerConfig = OidcProviderConfig(
              clientId = testClientId,
              clientSecret = testClientSecret,
              redirectUri = "http://localhost/callback",
              authorizationEndpoint = s"$issuer/protocol/openid-connect/auth",
              tokenEndpoint = s"$issuer/protocol/openid-connect/token",
              userinfoEndpoint = s"$issuer/protocol/openid-connect/userinfo",
              jwksUri = s"$issuer/protocol/openid-connect/certs",
              issuer = issuer
            )
            validator <- OidcTokenValidatorImpl.create(providerConfig)
            principalStore = MockPrincipalStore()
            claimMapper <- ClaimMapperImpl.create(principalStore)
            sessionRegistry <- InMemorySessionRegistry.create

            // Validate token and create session
            idToken = tokenResponse.idToken.getOrElse(fail("No ID token in response"))
            validationResult <- validator.validate(idToken)
            validatedToken = validationResult.toOption.get

            sessionData <- claimMapper.mapToSessionData(
              validatedToken,
              sessionId = "test-session-123",
              provider = "keycloak",
              sessionMaxAge = 3600
            )
            _ <- sessionRegistry.createSession(sessionData)

            // Verify session
            retrievedSession <- sessionRegistry.getSession("test-session-123")
          yield
            retrievedSession should not be empty
            retrievedSession.get.userId shouldBe testEmail
            retrievedSession.get.email shouldBe Some(testEmail)
            retrievedSession.get.provider shouldBe "keycloak"
        }
      }
    }

    "map Keycloak user claims to internal authorization" in {
      withContainers { container =>
        val baseUrl = keycloakBaseUrl(container)
        val issuer = keycloakIssuer(container)

        EmberClientBuilder.default[IO].build.use { httpClient =>
          val adminClient = KeycloakAdminClient(baseUrl, httpClient)

          for
            // Setup Keycloak
            _ <- adminClient.authenticate()
            _ <- adminClient.createRealm(testRealmName)
            _ <- adminClient.createClient(testRealmName, testClientId, testClientSecret, "http://localhost/callback")
            _ <- adminClient.createUser(testRealmName, testUsername, testEmail, testPassword, "Integration", "Tester")

            // Get a token
            tokenResponse <- adminClient.getDirectGrantToken(
              testRealmName,
              testClientId,
              testClientSecret,
              testUsername,
              testPassword
            )

            // Create components
            providerConfig = OidcProviderConfig(
              clientId = testClientId,
              clientSecret = testClientSecret,
              redirectUri = "http://localhost/callback",
              authorizationEndpoint = s"$issuer/protocol/openid-connect/auth",
              tokenEndpoint = s"$issuer/protocol/openid-connect/token",
              userinfoEndpoint = s"$issuer/protocol/openid-connect/userinfo",
              jwksUri = s"$issuer/protocol/openid-connect/certs",
              issuer = issuer
            )
            validator <- OidcTokenValidatorImpl.create(providerConfig)
            principalStore = MockPrincipalStore()
            claimMapper <- ClaimMapperImpl.create(principalStore)

            // Validate and map claims
            idToken = tokenResponse.idToken.getOrElse(fail("No ID token in response"))
            validationResult <- validator.validate(idToken)
            validatedToken = validationResult.toOption.get

            authorization <- claimMapper.mapToAuthorization(validatedToken)
          yield
            authorization should not be null
            // User should have been created in the principal store
            principalStore.getCreatedUsers should contain(testEmail)
        }
      }
    }

    "handle session logout" in {
      withContainers { container =>
        val baseUrl = keycloakBaseUrl(container)
        val issuer = keycloakIssuer(container)

        EmberClientBuilder.default[IO].build.use { httpClient =>
          val adminClient = KeycloakAdminClient(baseUrl, httpClient)

          for
            // Setup Keycloak
            _ <- adminClient.authenticate()
            _ <- adminClient.createRealm(testRealmName)
            _ <- adminClient.createClient(testRealmName, testClientId, testClientSecret, "http://localhost/callback")
            _ <- adminClient.createUser(testRealmName, testUsername, testEmail, testPassword)

            // Get a token
            tokenResponse <- adminClient.getDirectGrantToken(
              testRealmName,
              testClientId,
              testClientSecret,
              testUsername,
              testPassword
            )

            // Create components
            providerConfig = OidcProviderConfig(
              clientId = testClientId,
              clientSecret = testClientSecret,
              redirectUri = "http://localhost/callback",
              authorizationEndpoint = s"$issuer/protocol/openid-connect/auth",
              tokenEndpoint = s"$issuer/protocol/openid-connect/token",
              userinfoEndpoint = s"$issuer/protocol/openid-connect/userinfo",
              jwksUri = s"$issuer/protocol/openid-connect/certs",
              issuer = issuer
            )
            validator <- OidcTokenValidatorImpl.create(providerConfig)
            principalStore = MockPrincipalStore()
            claimMapper <- ClaimMapperImpl.create(principalStore)
            sessionRegistry <- InMemorySessionRegistry.create

            // Create session
            idToken = tokenResponse.idToken.getOrElse(fail("No ID token in response"))
            validationResult <- validator.validate(idToken)
            validatedToken = validationResult.toOption.get
            sessionData <- claimMapper.mapToSessionData(validatedToken, "logout-test-session", "keycloak", 3600)
            _ <- sessionRegistry.createSession(sessionData)

            // Verify session exists
            beforeLogout <- sessionRegistry.getSession("logout-test-session")

            // Logout
            _ <- sessionRegistry.deleteSession("logout-test-session")

            // Verify session is gone
            afterLogout <- sessionRegistry.getSession("logout-test-session")
          yield
            beforeLogout should not be empty
            afterLogout shouldBe None
        }
      }
    }

    "reject tokens from wrong issuer" in {
      withContainers { container =>
        val baseUrl = keycloakBaseUrl(container)
        val issuer = keycloakIssuer(container)

        EmberClientBuilder.default[IO].build.use { httpClient =>
          val adminClient = KeycloakAdminClient(baseUrl, httpClient)

          for
            // Setup Keycloak
            _ <- adminClient.authenticate()
            _ <- adminClient.createRealm(testRealmName)
            _ <- adminClient.createClient(testRealmName, testClientId, testClientSecret, "http://localhost/callback")
            _ <- adminClient.createUser(testRealmName, testUsername, testEmail, testPassword)

            // Get a token
            tokenResponse <- adminClient.getDirectGrantToken(
              testRealmName,
              testClientId,
              testClientSecret,
              testUsername,
              testPassword
            )

            // Create validator expecting a DIFFERENT issuer
            providerConfig = OidcProviderConfig(
              clientId = testClientId,
              clientSecret = testClientSecret,
              redirectUri = "http://localhost/callback",
              authorizationEndpoint = s"$issuer/protocol/openid-connect/auth",
              tokenEndpoint = s"$issuer/protocol/openid-connect/token",
              userinfoEndpoint = s"$issuer/protocol/openid-connect/userinfo",
              jwksUri = s"$issuer/protocol/openid-connect/certs",
              issuer = "https://wrong-issuer.example.com" // Wrong issuer!
            )
            validator <- OidcTokenValidatorImpl.create(providerConfig)

            // Validate - should fail
            idToken = tokenResponse.idToken.getOrElse(fail("No ID token in response"))
            result <- validator.validate(idToken)
          yield
            result shouldBe a[Left[?, ?]]
            result.left.toOption.get shouldBe a[OidcError.InvalidIssuer]
        }
      }
    }
  }
