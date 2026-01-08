package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.llm4s.ragbox.testkit.MockOidcServer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class OidcTokenValidatorSpec extends AsyncWordSpec with AsyncIOSpec with Matchers:

  "OidcTokenValidator" should {

    "validate a correctly signed token" in {
      MockOidcServer.resource().use { mockServer =>
        val config = OidcProviderConfig(
          clientId = "test-client-id",
          clientSecret = "test-secret",
          redirectUri = "http://localhost/callback",
          authorizationEndpoint = mockServer.authorizationEndpoint,
          tokenEndpoint = mockServer.tokenEndpoint,
          userinfoEndpoint = mockServer.userinfoEndpoint,
          jwksUri = mockServer.jwksUri,
          issuer = mockServer.issuer
        )

        for
          validator <- OidcTokenValidatorImpl.create(config)
          token = mockServer.generateIdToken(
            subject = "user123",
            email = Some("user@example.com"),
            name = Some("Test User"),
            groups = List("users", "admins")
          )
          result <- validator.validate(token)
        yield
          result shouldBe a[Right[_, _]]
          val validatedToken = result.toOption.get
          validatedToken.subject shouldBe "user123"
          validatedToken.email shouldBe Some("user@example.com")
          validatedToken.name shouldBe Some("Test User")
          validatedToken.groups should contain theSameElementsAs List("users", "admins")
      }
    }

    "reject expired token" in {
      MockOidcServer.resource().use { mockServer =>
        val config = OidcProviderConfig(
          clientId = "test-client-id",
          clientSecret = "test-secret",
          redirectUri = "http://localhost/callback",
          authorizationEndpoint = mockServer.authorizationEndpoint,
          tokenEndpoint = mockServer.tokenEndpoint,
          userinfoEndpoint = mockServer.userinfoEndpoint,
          jwksUri = mockServer.jwksUri,
          issuer = mockServer.issuer
        )

        for
          validator <- OidcTokenValidatorImpl.create(config)
          token = mockServer.generateExpiredIdToken(subject = "user123")
          result <- validator.validate(token)
        yield
          result shouldBe a[Left[_, _]]
          result.left.toOption.get shouldBe a[OidcError.TokenExpired]
      }
    }

    "reject token with wrong issuer" in {
      MockOidcServer.resource().use { mockServer =>
        val config = OidcProviderConfig(
          clientId = "test-client-id",
          clientSecret = "test-secret",
          redirectUri = "http://localhost/callback",
          authorizationEndpoint = mockServer.authorizationEndpoint,
          tokenEndpoint = mockServer.tokenEndpoint,
          userinfoEndpoint = mockServer.userinfoEndpoint,
          jwksUri = mockServer.jwksUri,
          issuer = mockServer.issuer
        )

        for
          validator <- OidcTokenValidatorImpl.create(config)
          token = mockServer.generateTokenWithWrongIssuer(
            subject = "user123",
            wrongIssuer = "http://wrong-issuer.com"
          )
          result <- validator.validate(token)
        yield
          result shouldBe a[Left[_, _]]
          // The error type may vary based on which validation fails first
          result.left.toOption.get shouldBe a[OidcError]
      }
    }

    "reject token with wrong audience" in {
      MockOidcServer.resource().use { mockServer =>
        val config = OidcProviderConfig(
          clientId = "expected-client-id", // Different from what token will have
          clientSecret = "test-secret",
          redirectUri = "http://localhost/callback",
          authorizationEndpoint = mockServer.authorizationEndpoint,
          tokenEndpoint = mockServer.tokenEndpoint,
          userinfoEndpoint = mockServer.userinfoEndpoint,
          jwksUri = mockServer.jwksUri,
          issuer = mockServer.issuer
        )

        for
          validator <- OidcTokenValidatorImpl.create(config)
          token = mockServer.generateIdToken(
            subject = "user123",
            audience = "wrong-client-id"
          )
          result <- validator.validate(token)
        yield
          result shouldBe a[Left[_, _]]
      }
    }

    "handle missing optional claims gracefully" in {
      MockOidcServer.resource().use { mockServer =>
        val config = OidcProviderConfig(
          clientId = "test-client-id",
          clientSecret = "test-secret",
          redirectUri = "http://localhost/callback",
          authorizationEndpoint = mockServer.authorizationEndpoint,
          tokenEndpoint = mockServer.tokenEndpoint,
          userinfoEndpoint = mockServer.userinfoEndpoint,
          jwksUri = mockServer.jwksUri,
          issuer = mockServer.issuer
        )

        for
          validator <- OidcTokenValidatorImpl.create(config)
          token = mockServer.generateIdToken(
            subject = "user123"
            // No email, name, or groups
          )
          result <- validator.validate(token)
        yield
          result shouldBe a[Right[_, _]]
          val validatedToken = result.toOption.get
          validatedToken.subject shouldBe "user123"
          validatedToken.email shouldBe None
          validatedToken.name shouldBe None
          validatedToken.groups shouldBe empty
      }
    }

    "extract claims using custom claim mapping" in {
      MockOidcServer.resource().use { mockServer =>
        val config = OidcProviderConfig(
          clientId = "test-client-id",
          clientSecret = "test-secret",
          redirectUri = "http://localhost/callback",
          authorizationEndpoint = mockServer.authorizationEndpoint,
          tokenEndpoint = mockServer.tokenEndpoint,
          userinfoEndpoint = mockServer.userinfoEndpoint,
          jwksUri = mockServer.jwksUri,
          issuer = mockServer.issuer
        )

        val customMapping = ClaimMappingConfig(
          userId = "sub",
          email = "email",
          groups = "groups",
          name = "name"
        )

        for
          validator <- OidcTokenValidatorImpl.create(config, customMapping)
          token = mockServer.generateIdToken(
            subject = "custom-user",
            email = Some("custom@example.com"),
            groups = List("group1", "group2")
          )
          result <- validator.validate(token)
        yield
          result shouldBe a[Right[_, _]]
          val validatedToken = result.toOption.get
          validatedToken.subject shouldBe "custom-user"
          validatedToken.email shouldBe Some("custom@example.com")
          validatedToken.groups should contain theSameElementsAs List("group1", "group2")
      }
    }
  }
