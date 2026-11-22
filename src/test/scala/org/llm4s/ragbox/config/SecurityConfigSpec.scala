package org.llm4s.ragbox.config

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Parsing and validation of the security settings (#15). */
class SecurityConfigSpec extends AnyFlatSpec with Matchers {

  private def load(overrides: String): AppConfig =
    AppConfig.parseConfig(
      ConfigFactory.parseString(overrides + "\napi-keys.openai = \"sk-test\"").withFallback(ConfigFactory.load()).resolve()
    )

  private val GoodSecret = "0123456789abcdef0123456789abcdef-secret"

  private def basic(extra: String = "", secret: String = GoodSecret, password: String = "a-long-admin-password"): AppConfig =
    load(
      s"""security.auth.mode = basic
         |security.auth.jwt-secret = "$secret"
         |security.auth.basic.admin-password = "$password"
         |$extra""".stripMargin
    )

  // ---------------------------------------------------------------- CORS

  "CORS" should "deny cross-origin requests by default" in {
    val cors = load("").production.cors
    cors.isDenyAll shouldBe true
    cors.isAllowAll shouldBe false
    cors.allows("https://evil.example") shouldBe false
  }

  it should "read ALLOWED_ORIGINS as a comma-separated list" in {
    val cors = load("""production.cors.allowed-origins = "https://app.example.com, admin.example.com/" """).production.cors
    cors.allowedOrigins shouldBe Seq("https://app.example.com", "admin.example.com/")
    cors.isDenyAll shouldBe false
    cors.allows("https://app.example.com") shouldBe true
    cors.allows("https://APP.example.com/") shouldBe true
    cors.allows("http://app.example.com") shouldBe false
    cors.allows("https://admin.example.com") shouldBe true
    cors.allows("https://evil.example") shouldBe false
    cors.allows("https://app.example.com.evil.example") shouldBe false
  }

  it should "let an explicit list take precedence over allow-all" in {
    val cors = load(
      """production.cors.allowed-origins = "https://app.example.com"
        |production.cors.allow-all-origins = true""".stripMargin
    ).production.cors
    cors.isAllowAll shouldBe false
    cors.allows("https://evil.example") shouldBe false
  }

  it should "allow every origin for * or allow-all without a list" in {
    load("""production.cors.allowed-origins = "*" """).production.cors.isAllowAll shouldBe true
    load("production.cors.allow-all-origins = true").production.cors.isAllowAll shouldBe true
  }

  it should "reject entries that are not origins" in {
    val errors = load("""production.cors.allowed-origins = "https://ok.example, https://x.example/path, *.example.com" """).validate()
    errors.exists(_.contains("https://x.example/path")) shouldBe true
    errors.exists(_.contains("*.example.com")) shouldBe true
    errors.exists(_.contains("https://ok.example'")) shouldBe false
  }

  // ---------------------------------------------------------------- secrets and the admin password

  "Basic mode validation" should "accept real secrets" in {
    basic().validate() shouldBe Nil
  }

  it should "reject placeholder JWT secrets, including the shipped templates' values" in {
    Seq(
      "CHANGE_ME_TO_32_CHAR_RANDOM_STRING",
      "REPLACE-ME-REPLACE-ME-REPLACE-ME-REPLACE-ME",
      "your-32-char-random-secret-goes-in-here",
      "change-me-change-me-change-me-change-me"
    ).foreach { secret =>
      withClue(secret) {
        basic(secret = secret).validate().exists(_.contains("JWT_SECRET is still a placeholder")) shouldBe true
      }
    }
    // The Kubernetes template's REPLACE-ME is short as well
    basic(secret = "REPLACE-ME").validate().exists(_.contains("JWT_SECRET must be at least 32")) shouldBe true
  }

  it should "reject the old default, placeholder and too-short admin passwords" in {
    basic(password = "admin").validate().exists(_.contains("ADMIN_PASSWORD")) shouldBe true
    basic(password = "REPLACE-ME").validate().exists(_.contains("ADMIN_PASSWORD")) shouldBe true
    basic(password = "short").validate() shouldBe List("ADMIN_PASSWORD: Password must be at least 12 characters")
    basic(password = "short", extra = "security.auth.password-min-length = 5").validate() shouldBe Nil
  }

  it should "reject a placeholder API_KEY in any mode" in {
    load("""security.api-key = "CHANGE_ME" """).validate().exists(_.contains("API_KEY")) shouldBe true
  }

  "JWT and password settings" should "have safe defaults and be configurable" in {
    val auth = load("").security.auth
    auth.jwtIssuer shouldBe AuthConfig.DefaultJwtIssuer
    auth.jwtAudience shouldBe AuthConfig.DefaultJwtAudience
    auth.passwordMinLength shouldBe 12
    auth.passwordHashIterations shouldBe 600000

    val custom = load(
      """security.auth.jwt-issuer = "iss-x"
        |security.auth.jwt-audience = "aud-x"
        |security.auth.password-hash-iterations = 10""".stripMargin
    )
    custom.security.auth.jwtIssuer shouldBe "iss-x"
    custom.security.auth.jwtAudience shouldBe "aud-x"
    custom.validate().exists(_.contains("PASSWORD_HASH_ITERATIONS")) shouldBe true
  }

  // ---------------------------------------------------------------- OAUTH_SCOPES

  private val oauthBase =
    """security.auth.mode = oauth
      |security.auth.oauth.client-id = "cid"
      |security.auth.oauth.client-secret = "csecret"
      |security.auth.oauth.redirect-uri = "https://app.example.com/cb"""".stripMargin

  "OAUTH_SCOPES" should "replace the provider preset's scopes" in {
    load(oauthBase).security.auth.oauth.get.provider.scopes shouldBe List("openid", "profile", "email")
    load(oauthBase + "\nsecurity.auth.oauth.scopes = \"openid profile email groups\"")
      .security.auth.oauth.get.provider.scopes shouldBe List("openid", "profile", "email", "groups")
    load(oauthBase + "\nsecurity.auth.oauth.scopes = \"openid,email , offline_access\"")
      .security.auth.oauth.get.provider.scopes shouldBe List("openid", "email", "offline_access")
  }

  it should "have to include openid" in {
    load(oauthBase + "\nsecurity.auth.oauth.scopes = \"profile email\"").validate()
      .exists(_.contains("OAUTH_SCOPES must include 'openid'")) shouldBe true
  }

  // ---------------------------------------------------------------- rate limiting and metrics

  "Rate limit settings" should "parse trusted proxies and login throttling" in {
    val rl = load(
      """production.rate-limit.trusted-proxies = "10.0.0.0/8, 192.168.1.5,::1"
        |production.rate-limit.login.max-attempts = 3""".stripMargin
    ).production.rateLimit
    rl.trustedProxies shouldBe Seq("10.0.0.0/8", "192.168.1.5", "::1")
    rl.trustedProxyErrors shouldBe Nil
    rl.loginMaxAttempts shouldBe 3
    rl.loginWindowSeconds shouldBe RateLimitConfig.DefaultLoginWindowSeconds
    rl.maxClients shouldBe RateLimitConfig.DefaultMaxClients
  }

  it should "reject trusted proxies that are not addresses or CIDRs" in {
    load("""production.rate-limit.trusted-proxies = "10.0.0.0/8, proxy.local, 10.0.0.0/99" """).validate()
      .filter(_.contains("RATE_LIMIT_TRUSTED_PROXIES")) should have size 2
  }

  "Metrics" should "require authentication unless METRICS_REQUIRE_AUTH=false" in {
    load("").metrics.requireAuth shouldBe true
    load("metrics.require-auth = false").metrics.requireAuth shouldBe false
  }
}
