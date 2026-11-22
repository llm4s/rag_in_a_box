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

    // pbkdf2-sha256$<iterations>$<salt>$<hash>
    val parts = hash.split('$')
    parts.length shouldBe 4
    parts(0) shouldBe "pbkdf2-sha256"
    parts(1) shouldBe testConfig.passwordHashIterations.toString

    // Salt and hash should be base64 encoded
    parts(2) should not be empty
    parts(3) should not be empty
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

  // ------------------------------------------------------------------ JWT claims and header

  private val someUser = User(7, "claims", "hash", UserRole.User, java.time.Instant.now())

  private def b64(s: String): String =
    java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(s.getBytes("UTF-8"))

  private def hs256(secret: String, data: String): String = {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(mac.doFinal(data.getBytes("UTF-8")))
  }

  /** A token signed with the test secret, with the given header and payload JSON. */
  private def signed(header: String, payload: String): String = {
    val data = s"${b64(header)}.${b64(payload)}"
    s"$data.${hs256(testConfig.jwtSecret, data)}"
  }

  private def payload(iss: Option[String], aud: Option[String]): String = {
    val exp = java.time.Instant.now().getEpochSecond + 3600
    val claims = Seq(
      Some(s""""sub":"7""""), Some(""""username":"claims""""), Some(""""role":"admin""""),
      Some(s""""exp":$exp"""), Some(s""""iat":${exp - 3600}"""),
      iss.map(i => s""""iss":"$i""""), aud.map(a => s""""aud":"$a"""")
    ).flatten
    claims.mkString("{", ",", "}")
  }

  private val hs256Header = """{"alg":"HS256","typ":"JWT"}"""

  it should "issue tokens with this server's iss and aud claims" in {
    val token = authService.generateToken(someUser)
    val json = new String(java.util.Base64.getUrlDecoder.decode(token.split('.')(1)), "UTF-8")
    json should include(s""""iss":"${testConfig.jwtIssuer}"""")
    json should include(s""""aud":"${testConfig.jwtAudience}"""")
  }

  it should "reject correctly signed tokens with a wrong or missing issuer or audience" in {
    val (iss, aud) = (testConfig.jwtIssuer, testConfig.jwtAudience)
    authService.validateToken(signed(hs256Header, payload(Some(iss), Some(aud)))) shouldBe a[TokenValidationResult.Valid]
    authService.validateToken(signed(hs256Header, payload(Some("someone-else"), Some(aud)))) shouldBe
      TokenValidationResult.Invalid("Invalid token issuer")
    authService.validateToken(signed(hs256Header, payload(Some(iss), Some("another-api")))) shouldBe
      TokenValidationResult.Invalid("Invalid token audience")
    // Tokens issued before iss/aud existed
    authService.validateToken(signed(hs256Header, payload(None, None))) shouldBe a[TokenValidationResult.Invalid]
  }

  it should "reject tokens of another deployment that shares the secret but not the audience" in {
    val other = new AuthService(mockRegistry, testConfig.copy(jwtAudience = "other-deployment"))
    authService.validateToken(other.generateToken(someUser)) shouldBe TokenValidationResult.Invalid("Invalid token audience")
  }

  it should "reject tokens whose header names another algorithm, including alg=none" in {
    val claims = payload(Some(testConfig.jwtIssuer), Some(testConfig.jwtAudience))
    val unsupported = TokenValidationResult.Invalid("Unsupported token algorithm")
    authService.validateToken(signed("""{"alg":"none","typ":"JWT"}""", claims)) shouldBe unsupported
    authService.validateToken(s"${b64("""{"alg":"none"}""")}.${b64(claims)}.") shouldBe unsupported
    authService.validateToken(signed("""{"alg":"RS256","typ":"JWT"}""", claims)) shouldBe unsupported
    authService.validateToken(signed("""{"typ":"JWT"}""", claims)) shouldBe unsupported
    authService.validateToken(signed("not json", claims)) shouldBe unsupported
  }

  // ------------------------------------------------------------------ password hashing

  it should "not throw on an empty password" in {
    noException should be thrownBy authService.hashPassword("")
    authService.verifyPassword("", authService.hashPassword("non-empty-password")) shouldBe false
    authService.verifyPassword("", PasswordHashing.legacyFormat("x")) shouldBe false
  }

  it should "still verify hashes in the legacy (pre-PBKDF2) format" in {
    val legacy = PasswordHashing.legacyFormat("legacy-password")
    legacy should not include "$"
    authService.verifyPassword("legacy-password", legacy) shouldBe true
    authService.verifyPassword("wrong", legacy) shouldBe false
  }

  it should "refuse passwords that break the policy on create and reset" in {
    import cats.effect.unsafe.implicits.global
    authService.createUser("bob", "", UserRole.User).unsafeRunSync() shouldBe Left("Password is required")
    authService.createUser("bob", "short", UserRole.User).unsafeRunSync() shouldBe
      Left(s"Password must be at least ${testConfig.passwordMinLength} characters")
    authService.createUser("bob", "x" * 2000, UserRole.User).unsafeRunSync().isLeft shouldBe true
    authService.createUser(" ", "long-enough-password", UserRole.User).unsafeRunSync() shouldBe Left("Username is required")
    authService.resetPassword(1, "").unsafeRunSync() shouldBe Left("Password is required")
    authService.createUser("bob", "long-enough-password", UserRole.User).unsafeRunSync().isRight shouldBe true
  }
}

class PasswordHashingSpec extends AnyFlatSpec with Matchers {

  "PasswordHashing" should "derive real PBKDF2-HMAC-SHA256 (RFC 7914 test vector)" in {
    // RFC 7914 section 11: PBKDF2-HMAC-SHA256, P="passwd", S="salt", c=1, dkLen=64 (first 32 bytes)
    val salt = java.util.Base64.getEncoder.withoutPadding().encodeToString("salt".getBytes("UTF-8"))
    val expected = "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
      .grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    val hash = java.util.Base64.getEncoder.withoutPadding().encodeToString(expected)
    PasswordHashing.verify("passwd", s"pbkdf2-sha256$$1$$$salt$$$hash") shouldBe true
    PasswordHashing.verify("passwd2", s"pbkdf2-sha256$$1$$$salt$$$hash") shouldBe false
  }

  it should "ask for a rehash of legacy hashes and of hashes with fewer iterations" in {
    PasswordHashing.needsRehash(PasswordHashing.legacyFormat("pw"), 1000) shouldBe true
    PasswordHashing.needsRehash(PasswordHashing.hash("pw", 1000), 2000) shouldBe true
    PasswordHashing.needsRehash(PasswordHashing.hash("pw", 2000), 2000) shouldBe false
  }

  it should "reject malformed stored hashes without throwing" in {
    Seq("", "garbage", "a:b:c", "pbkdf2-sha256$x$y$z", "pbkdf2-sha256$0$AAAA$AAAA", "pbkdf2-sha256$-5$AAAA$AAAA", "!!:!!")
      .foreach(h => PasswordHashing.verify("pw", h) shouldBe false)
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
