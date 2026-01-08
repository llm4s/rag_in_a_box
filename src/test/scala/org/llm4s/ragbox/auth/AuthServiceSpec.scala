package org.llm4s.ragbox.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.config.{AuthConfig, AuthMode, BasicAuthConfig}

class AuthServiceSpec extends AnyFlatSpec with Matchers {

  // Create a minimal auth config for testing
  private val testConfig = AuthConfig(
    mode = AuthMode.Basic,
    basic = BasicAuthConfig("admin", Some("password")),
    oauth = None,
    jwtSecret = "test-secret-key-for-testing-only-32chars",
    jwtSecretExplicitlySet = true,
    jwtExpiration = 3600L  // 1 hour
  )

  // Create a mock user registry - we'll test password and JWT functionality only
  private class MockUserRegistry extends UserRegistry(null) {
    override def initialize() = cats.effect.IO.unit
    override def exists(username: String) = cats.effect.IO.pure(false)
    override def create(username: String, passwordHash: String, role: UserRole) =
      cats.effect.IO.pure(User(1, username, passwordHash, role, java.time.Instant.now()))
    override def getByUsername(username: String) = cats.effect.IO.pure(None)
    override def close() = cats.effect.IO.unit
  }

  private val mockRegistry = new MockUserRegistry()
  private val authService = new AuthService(mockRegistry, testConfig)

  "AuthService" should "hash passwords consistently" in {
    val password = "mySecurePassword123"
    val hash = authService.hashPassword(password)

    // Hash should be non-empty
    hash should not be empty

    // Hash should contain salt and hash separated by ':'
    hash should include(":")
    val parts = hash.split(":")
    parts.length shouldBe 2

    // Both parts should be base64 encoded
    parts(0) should not be empty
    parts(1) should not be empty
  }

  it should "verify passwords correctly" in {
    val password = "testPassword456"
    val hash = authService.hashPassword(password)

    // Correct password should verify
    authService.verifyPassword(password, hash) shouldBe true

    // Wrong password should not verify
    authService.verifyPassword("wrongPassword", hash) shouldBe false
    authService.verifyPassword("", hash) shouldBe false
  }

  it should "generate different hashes for the same password (due to salt)" in {
    val password = "samePassword"
    val hash1 = authService.hashPassword(password)
    val hash2 = authService.hashPassword(password)

    // Hashes should be different (different salts)
    hash1 should not equal hash2

    // But both should verify correctly
    authService.verifyPassword(password, hash1) shouldBe true
    authService.verifyPassword(password, hash2) shouldBe true
  }

  it should "generate valid JWT tokens" in {
    val user = User(
      id = 42,
      username = "testuser",
      passwordHash = "hash",
      role = UserRole.Admin,
      createdAt = java.time.Instant.now()
    )

    val token = authService.generateToken(user)

    // Token should be non-empty
    token should not be empty

    // Token should have 3 parts (header.payload.signature)
    val parts = token.split("\\.")
    parts.length shouldBe 3
  }

  it should "validate its own tokens" in {
    val user = User(
      id = 42,
      username = "testuser",
      passwordHash = "hash",
      role = UserRole.Admin,
      createdAt = java.time.Instant.now()
    )

    val token = authService.generateToken(user)
    val result = authService.validateToken(token)

    result shouldBe a[TokenValidationResult.Valid]
    val valid = result.asInstanceOf[TokenValidationResult.Valid]
    valid.userId shouldBe 42
    valid.username shouldBe "testuser"
    valid.role shouldBe UserRole.Admin
  }

  it should "reject invalid tokens" in {
    val result = authService.validateToken("invalid.token.here")
    result shouldBe a[TokenValidationResult.Invalid]
  }

  it should "reject tokens with wrong signature" in {
    val user = User(
      id = 1,
      username = "test",
      passwordHash = "hash",
      role = UserRole.User,
      createdAt = java.time.Instant.now()
    )

    val token = authService.generateToken(user)
    // Tamper with the signature
    val tamperedToken = token.dropRight(5) + "xxxxx"

    val result = authService.validateToken(tamperedToken)
    result shouldBe a[TokenValidationResult.Invalid]
  }

  it should "reject malformed tokens" in {
    authService.validateToken("") shouldBe a[TokenValidationResult.Invalid]
    authService.validateToken("only.two") shouldBe a[TokenValidationResult.Invalid]
    authService.validateToken("no-dots-here") shouldBe a[TokenValidationResult.Invalid]
  }

  it should "detect expired tokens" in {
    // Create a service with very short expiration
    val shortExpConfig = testConfig.copy(jwtExpiration = -1)  // Already expired
    val shortExpService = new AuthService(mockRegistry, shortExpConfig)

    val user = User(
      id = 1,
      username = "test",
      passwordHash = "hash",
      role = UserRole.User,
      createdAt = java.time.Instant.now()
    )

    val token = shortExpService.generateToken(user)
    val result = shortExpService.validateToken(token)

    result shouldBe TokenValidationResult.Expired
  }
}

class UserRoleSpec extends AnyFlatSpec with Matchers {

  "UserRole" should "parse admin role" in {
    UserRole.fromString("admin") shouldBe UserRole.Admin
    UserRole.fromString("ADMIN") shouldBe UserRole.Admin
    UserRole.fromString("Admin") shouldBe UserRole.Admin
  }

  it should "parse user role" in {
    UserRole.fromString("user") shouldBe UserRole.User
    UserRole.fromString("USER") shouldBe UserRole.User
    UserRole.fromString("User") shouldBe UserRole.User
  }

  it should "throw on unknown role" in {
    an[IllegalArgumentException] should be thrownBy {
      UserRole.fromString("superuser")
    }
  }

  it should "have correct name property" in {
    UserRole.Admin.name shouldBe "admin"
    UserRole.User.name shouldBe "user"
  }
}
