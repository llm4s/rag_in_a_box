package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.llm4s.ragbox.testkit.MockPrincipalStore
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class ClaimMapperSpec extends AsyncWordSpec with AsyncIOSpec with Matchers:

  private val issuer = "https://idp.example.com"

  private def token(
      subject: String = "sub-123",
      email: Option[String] = Some("user@example.com"),
      emailVerified: Boolean = true,
      name: Option[String] = Some("Test User"),
      groups: List[String] = List.empty,
      rawClaims: Map[String, Any] = Map.empty
  ): ValidatedIdToken =
    ValidatedIdToken(
      subject = subject,
      email = email,
      name = name,
      groups = groups,
      issuedAt = System.currentTimeMillis(),
      expiresAt = System.currentTimeMillis() + 3600000,
      rawClaims = rawClaims,
      issuer = issuer,
      emailVerified = emailVerified
    )

  "ClaimMapper with default (sub) user-id mapping" should {

    "key the user principal on issuer + subject, not email" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore)
        auth <- mapper.mapToAuthorization(token())
      yield
        auth should not be null
        principalStore.getCreatedUsers shouldBe List(s"$issuer|sub-123")
        principalStore.getCreatedGroups shouldBe empty
    }

    "use issuer + subject when email is not available" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore)
        _ <- mapper.mapToAuthorization(token(subject = "sub-456", email = None, emailVerified = false))
      yield principalStore.getCreatedUsers shouldBe List(s"$issuer|sub-456")
    }

    "not let two users with the same (unverified) email collide" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore)
        a <- mapper.mapToAuthorization(token(subject = "victim"))
        b <- mapper.mapToAuthorization(token(subject = "attacker", emailVerified = false))
      yield
        a should not equal b
        principalStore.getCreatedUsers should contain theSameElementsAs List(
          s"$issuer|victim",
          s"$issuer|attacker"
        )
    }

    "map groups to group principals" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore)
        _ <- mapper.mapToAuthorization(token(groups = List("admins", "developers", "users")))
      yield principalStore.getCreatedGroups should contain theSameElementsAs List("admins", "developers", "users")
    }

    "reuse existing principal IDs for the same user" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore)
        _ <- mapper.mapToAuthorization(token())
        _ <- mapper.mapToAuthorization(token())
      yield principalStore.getCreatedUsers.size shouldBe 1
    }

    "map token to session data with issuer-qualified userId" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore)
        sessionData <- mapper.mapToSessionData(
          token(groups = List("users", "developers")),
          sessionId = "session-abc123",
          provider = "google",
          sessionMaxAge = 86400
        )
      yield
        sessionData.sessionId shouldBe "session-abc123"
        sessionData.userId shouldBe s"$issuer|sub-123"
        sessionData.email shouldBe Some("user@example.com")
        sessionData.name shouldBe Some("Test User")
        sessionData.groups should contain theSameElementsAs List("users", "developers")
        sessionData.provider shouldBe "google"
        sessionData.expiresAt should be > sessionData.createdAt
    }
  }

  "ClaimMapper with email user-id mapping" should {

    "use the email when email_verified is true" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore, ClaimMappingConfig(userId = "email"))
        _ <- mapper.mapToAuthorization(token(emailVerified = true))
      yield principalStore.getCreatedUsers shouldBe List("user@example.com")
    }

    "reject the login when email_verified is false" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore, ClaimMappingConfig(userId = "email"))
        result <- mapper.mapToAuthorization(token(emailVerified = false)).attempt
        sessionResult <- mapper.mapToSessionData(token(emailVerified = false), "s", "p", 60).attempt
      yield
        result.left.toOption.get shouldBe a[OidcError.IdentityError]
        sessionResult.left.toOption.get shouldBe a[OidcError.IdentityError]
        principalStore.getCreatedUsers shouldBe empty
    }

    "honour a renamed email claim" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore, ClaimMappingConfig(userId = "mail", email = "mail"))
        result <- mapper.mapToAuthorization(token(emailVerified = false)).attempt
      yield result.left.toOption.get shouldBe a[OidcError.IdentityError]
    }
  }

  "ClaimMapper with a custom user-id claim" should {

    "use the configured claim value" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore, ClaimMappingConfig(userId = "oid"))
        _ <- mapper.mapToAuthorization(token(rawClaims = Map("oid" -> "object-id-1")))
      yield principalStore.getCreatedUsers shouldBe List("object-id-1")
    }

    "reject the login when the configured claim is missing" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore, ClaimMappingConfig(userId = "oid"))
        result <- mapper.mapToAuthorization(token()).attempt
      yield result.left.toOption.get shouldBe a[OidcError.IdentityError]
    }
  }

  "ClaimMapper.authorizationFor" should {

    "build authorization from a stored session identity without re-deriving it" in {
      val principalStore = MockPrincipalStore()
      for
        mapper <- ClaimMapperImpl.create(principalStore)
        _ <- mapper.authorizationFor(s"$issuer|sub-123", List("g1"))
      yield
        principalStore.getCreatedUsers shouldBe List(s"$issuer|sub-123")
        principalStore.getCreatedGroups shouldBe List("g1")
    }
  }
