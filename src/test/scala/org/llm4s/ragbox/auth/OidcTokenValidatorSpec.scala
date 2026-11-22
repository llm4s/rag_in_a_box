package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.llm4s.ragbox.testkit.MockOidcServer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*

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
          result shouldBe a[Right[?, ?]]
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
          result shouldBe a[Left[?, ?]]
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
          result shouldBe a[Left[?, ?]]
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
          result shouldBe a[Left[?, ?]]
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
          result shouldBe a[Right[?, ?]]
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
          result shouldBe a[Right[?, ?]]
          val validatedToken = result.toOption.get
          validatedToken.subject shouldBe "custom-user"
          validatedToken.email shouldBe Some("custom@example.com")
          validatedToken.groups should contain theSameElementsAs List("group1", "group2")
      }
    }
    "extract issuer and email_verified" in {
      MockOidcServer.resource().use { mockServer =>
        for
          validator <- OidcTokenValidatorImpl.create(configFor(mockServer))
          verified <- validator.validate(
            mockServer.generateIdToken(subject = "u1", email = Some("a@b.c"), emailVerified = Some(true))
          )
          unverified <- validator.validate(
            mockServer.generateIdToken(subject = "u2", email = Some("a@b.c"), emailVerified = Some(false))
          )
          absent <- validator.validate(mockServer.generateIdToken(subject = "u3", email = Some("a@b.c")))
        yield
          verified.toOption.get.issuer shouldBe mockServer.issuer
          verified.toOption.get.emailVerified shouldBe true
          unverified.toOption.get.emailVerified shouldBe false
          absent.toOption.get.emailVerified shouldBe false
      }
    }

    "accept a token whose nonce matches the expected nonce" in {
      MockOidcServer.resource().use { mockServer =>
        for
          validator <- OidcTokenValidatorImpl.create(configFor(mockServer))
          result <- validator.validate(
            mockServer.generateIdToken(subject = "u1", nonce = Some("nonce-abc")),
            expectedNonce = Some("nonce-abc")
          )
        yield result shouldBe a[Right[?, ?]]
      }
    }

    "reject a token whose nonce does not match" in {
      MockOidcServer.resource().use { mockServer =>
        for
          validator <- OidcTokenValidatorImpl.create(configFor(mockServer))
          result <- validator.validate(
            mockServer.generateIdToken(subject = "u1", nonce = Some("attacker-nonce")),
            expectedNonce = Some("nonce-abc")
          )
        yield result.left.toOption.get shouldBe a[OidcError.InvalidNonce]
      }
    }

    "reject a token with no nonce when one is expected" in {
      MockOidcServer.resource().use { mockServer =>
        for
          validator <- OidcTokenValidatorImpl.create(configFor(mockServer))
          result <- validator.validate(
            mockServer.generateIdToken(subject = "u1"),
            expectedNonce = Some("nonce-abc")
          )
        yield result.left.toOption.get shouldBe a[OidcError.InvalidNonce]
      }
    }

    "accept tokens signed with a rotated-in key, fetching the JWKS only for an unknown kid" in {
      MockOidcServer.resource().use { mockServer =>
        for
          validator <- OidcTokenValidatorImpl.create(configFor(mockServer))
          first <- validator.validate(mockServer.generateIdToken(subject = "before"))
          again <- validator.validate(mockServer.generateIdToken(subject = "before-again"))
          fetchesBefore = mockServer.jwksFetches.get
          _ = mockServer.rotateKey(retirePrevious = false)
          rotated <- validator.validate(mockServer.generateIdToken(subject = "after"))
        yield
          first.map(_.subject) shouldBe Right("before")
          again.map(_.subject) shouldBe Right("before-again")
          fetchesBefore shouldBe 1 // the cached key served the second token
          rotated.map(_.subject) shouldBe Right("after")
          mockServer.jwksFetches.get shouldBe 2 // the new kid was fetched once
      }
    }

    "stop accepting a retired key once its cache entry expires" in {
      MockOidcServer.resource().use { mockServer =>
        val oldToken = mockServer.generateIdToken(subject = "old-key")
        for
          validator <- OidcTokenValidatorImpl.create(configFor(mockServer), keyCacheTtl = 300.millis)
          before <- validator.validate(oldToken)
          _ = mockServer.rotateKey(retirePrevious = true)
          newKey <- validator.validate(mockServer.generateIdToken(subject = "new-key"))
          _ <- IO.sleep(600.millis)
          after <- validator.validate(oldToken)
        yield
          before.map(_.subject) shouldBe Right("old-key")
          newKey.map(_.subject) shouldBe Right("new-key")
          after.left.toOption.get shouldBe a[OidcError.JwksError]
      }
    }
  }

  private def configFor(mockServer: MockOidcServer): OidcProviderConfig =
    OidcProviderConfig(
      clientId = "test-client-id",
      clientSecret = "test-secret",
      redirectUri = "http://localhost/callback",
      authorizationEndpoint = mockServer.authorizationEndpoint,
      tokenEndpoint = mockServer.tokenEndpoint,
      userinfoEndpoint = mockServer.userinfoEndpoint,
      jwksUri = mockServer.jwksUri,
      issuer = mockServer.issuer
    )
