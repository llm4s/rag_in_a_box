package org.llm4s.ragbox.auth

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.llm4s.ragbox.testkit.MockPrincipalStore
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class ClaimMapperSpec extends AsyncWordSpec with AsyncIOSpec with Matchers:

  "ClaimMapper" should {

    "map token with email to UserAuthorization" in {
      val principalStore = MockPrincipalStore()
      val token = ValidatedIdToken(
        subject = "sub-123",
        email = Some("user@example.com"),
        name = Some("Test User"),
        groups = List.empty,
        issuedAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 3600000,
        rawClaims = Map.empty
      )

      for
        mapper <- ClaimMapperImpl.create(principalStore)
        auth <- mapper.mapToAuthorization(token)
      yield
        // Verify the authorization is not anonymous/admin
        auth should not be null
        // Verify user principal was created with email
        principalStore.getCreatedUsers should contain("user@example.com")
        // No groups should be created
        principalStore.getCreatedGroups shouldBe empty
    }

    "use subject when email is not available" in {
      val principalStore = MockPrincipalStore()
      val token = ValidatedIdToken(
        subject = "sub-456",
        email = None,
        name = None,
        groups = List.empty,
        issuedAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 3600000,
        rawClaims = Map.empty
      )

      for
        mapper <- ClaimMapperImpl.create(principalStore)
        auth <- mapper.mapToAuthorization(token)
      yield
        auth should not be null
        // Subject should be used when email is not available
        principalStore.getCreatedUsers should contain("sub-456")
    }

    "map token with groups to UserAuthorization with group principals" in {
      val principalStore = MockPrincipalStore()
      val token = ValidatedIdToken(
        subject = "sub-789",
        email = Some("admin@example.com"),
        name = Some("Admin User"),
        groups = List("admins", "developers", "users"),
        issuedAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 3600000,
        rawClaims = Map.empty
      )

      for
        mapper <- ClaimMapperImpl.create(principalStore)
        auth <- mapper.mapToAuthorization(token)
      yield
        auth should not be null
        // Verify user and groups were created
        principalStore.getCreatedUsers should contain("admin@example.com")
        principalStore.getCreatedGroups should contain theSameElementsAs List("admins", "developers", "users")
    }

    "reuse existing principal IDs for the same user" in {
      val principalStore = MockPrincipalStore()
      val token = ValidatedIdToken(
        subject = "sub-123",
        email = Some("user@example.com"),
        name = Some("Test User"),
        groups = List.empty,
        issuedAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 3600000,
        rawClaims = Map.empty
      )

      for
        mapper <- ClaimMapperImpl.create(principalStore)
        _ <- mapper.mapToAuthorization(token)
        _ <- mapper.mapToAuthorization(token)
      yield
        // Should only have created the user once (reused on second call)
        principalStore.getCreatedUsers.size shouldBe 1
    }

    "map token to session data correctly" in {
      val principalStore = MockPrincipalStore()
      val now = System.currentTimeMillis()
      val token = ValidatedIdToken(
        subject = "sub-123",
        email = Some("user@example.com"),
        name = Some("Test User"),
        groups = List("users", "developers"),
        issuedAt = now,
        expiresAt = now + 3600000,
        rawClaims = Map.empty
      )

      for
        mapper <- ClaimMapperImpl.create(principalStore)
        sessionData <- mapper.mapToSessionData(
          token,
          sessionId = "session-abc123",
          provider = "google",
          sessionMaxAge = 86400 // 24 hours
        )
      yield
        sessionData.sessionId shouldBe "session-abc123"
        sessionData.userId shouldBe "user@example.com"
        sessionData.email shouldBe Some("user@example.com")
        sessionData.name shouldBe Some("Test User")
        sessionData.groups should contain theSameElementsAs List("users", "developers")
        sessionData.provider shouldBe "google"
        sessionData.expiresAt should be > sessionData.createdAt
    }

    "use subject for userId in session data when email is not available" in {
      val principalStore = MockPrincipalStore()
      val token = ValidatedIdToken(
        subject = "sub-no-email",
        email = None,
        name = None,
        groups = List.empty,
        issuedAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 3600000,
        rawClaims = Map.empty
      )

      for
        mapper <- ClaimMapperImpl.create(principalStore)
        sessionData <- mapper.mapToSessionData(
          token,
          sessionId = "session-xyz",
          provider = "keycloak",
          sessionMaxAge = 3600
        )
      yield
        sessionData.userId shouldBe "sub-no-email"
        sessionData.email shouldBe None
        sessionData.name shouldBe None
    }
  }
