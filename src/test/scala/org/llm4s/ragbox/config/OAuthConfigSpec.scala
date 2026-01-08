package org.llm4s.ragbox.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.ragbox.auth._
import org.llm4s.ragbox.ingestion.IngestionConfig
import org.llm4s.ragbox.testkit.TestFixtures

class OAuthConfigSpec extends AnyFlatSpec with Matchers {

  "OidcProviders.google" should "create correct provider config" in {
    val config = OidcProviders.google(
      clientId = "test-client-id",
      clientSecret = "test-secret",
      redirectUri = "http://localhost/callback"
    )

    config.clientId shouldBe "test-client-id"
    config.clientSecret shouldBe "test-secret"
    config.redirectUri shouldBe "http://localhost/callback"
    config.issuer shouldBe "https://accounts.google.com"
    config.authorizationEndpoint shouldBe "https://accounts.google.com/o/oauth2/v2/auth"
    config.tokenEndpoint shouldBe "https://oauth2.googleapis.com/token"
    config.scopes should contain allOf ("openid", "profile", "email")
  }

  "OidcProviders.azureAd" should "create correct provider config with tenant" in {
    val config = OidcProviders.azureAd(
      tenantId = "my-tenant-id",
      clientId = "azure-client-id",
      clientSecret = "azure-secret",
      redirectUri = "http://localhost/callback"
    )

    config.clientId shouldBe "azure-client-id"
    config.clientSecret shouldBe "azure-secret"
    config.redirectUri shouldBe "http://localhost/callback"
    config.issuer shouldBe "https://login.microsoftonline.com/my-tenant-id/v2.0"
    config.authorizationEndpoint shouldBe "https://login.microsoftonline.com/my-tenant-id/oauth2/v2.0/authorize"
    config.tokenEndpoint shouldBe "https://login.microsoftonline.com/my-tenant-id/oauth2/v2.0/token"
  }

  "OidcProviders.okta" should "create correct provider config with domain" in {
    val config = OidcProviders.okta(
      domain = "my-company.okta.com",
      clientId = "okta-client-id",
      clientSecret = "okta-secret",
      redirectUri = "http://localhost/callback"
    )

    config.clientId shouldBe "okta-client-id"
    config.clientSecret shouldBe "okta-secret"
    config.issuer shouldBe "https://my-company.okta.com/oauth2/default"
    config.authorizationEndpoint shouldBe "https://my-company.okta.com/oauth2/default/v1/authorize"
    config.tokenEndpoint shouldBe "https://my-company.okta.com/oauth2/default/v1/token"
  }

  "OidcProviders.okta" should "support custom authorization server" in {
    val config = OidcProviders.okta(
      domain = "my-company.okta.com",
      clientId = "okta-client-id",
      clientSecret = "okta-secret",
      redirectUri = "http://localhost/callback",
      authorizationServerId = "my-auth-server"
    )

    config.issuer shouldBe "https://my-company.okta.com/oauth2/my-auth-server"
    config.authorizationEndpoint shouldBe "https://my-company.okta.com/oauth2/my-auth-server/v1/authorize"
  }

  "OidcProviders.keycloak" should "create correct provider config with realm" in {
    val config = OidcProviders.keycloak(
      baseUrl = "https://keycloak.example.com",
      realm = "my-realm",
      clientId = "keycloak-client-id",
      clientSecret = "keycloak-secret",
      redirectUri = "http://localhost/callback"
    )

    config.clientId shouldBe "keycloak-client-id"
    config.clientSecret shouldBe "keycloak-secret"
    config.issuer shouldBe "https://keycloak.example.com/realms/my-realm"
    config.authorizationEndpoint shouldBe "https://keycloak.example.com/realms/my-realm/protocol/openid-connect/auth"
    config.tokenEndpoint shouldBe "https://keycloak.example.com/realms/my-realm/protocol/openid-connect/token"
    config.jwksUri shouldBe "https://keycloak.example.com/realms/my-realm/protocol/openid-connect/certs"
  }

  "OidcProviders.custom" should "create provider config with custom endpoints" in {
    val config = OidcProviders.custom(
      clientId = "custom-client-id",
      clientSecret = "custom-secret",
      redirectUri = "http://localhost/callback",
      authorizationEndpoint = "https://custom.example.com/authorize",
      tokenEndpoint = "https://custom.example.com/token",
      userinfoEndpoint = "https://custom.example.com/userinfo",
      jwksUri = "https://custom.example.com/jwks",
      issuer = "https://custom.example.com"
    )

    config.clientId shouldBe "custom-client-id"
    config.authorizationEndpoint shouldBe "https://custom.example.com/authorize"
    config.tokenEndpoint shouldBe "https://custom.example.com/token"
    config.userinfoEndpoint shouldBe "https://custom.example.com/userinfo"
    config.jwksUri shouldBe "https://custom.example.com/jwks"
    config.issuer shouldBe "https://custom.example.com"
  }

  "ClaimMappingConfig" should "have sensible defaults" in {
    val config = ClaimMappingConfig()

    config.userId shouldBe "sub"
    config.email shouldBe "email"
    config.groups shouldBe "groups"
    config.name shouldBe "name"
  }

  "ClaimMappingConfig" should "allow custom claim names" in {
    val config = ClaimMappingConfig(
      userId = "preferred_username",
      email = "mail",
      groups = "roles",
      name = "display_name"
    )

    config.userId shouldBe "preferred_username"
    config.email shouldBe "mail"
    config.groups shouldBe "roles"
    config.name shouldBe "display_name"
  }

  "OAuthSessionConfig" should "have sensible defaults" in {
    val config = OAuthSessionConfig(
      cookieName = "ragbox_session",
      cookieSecure = true,
      cookieMaxAge = 86400
    )

    config.cookieName shouldBe "ragbox_session"
    config.cookieSecure shouldBe true
    config.cookieMaxAge shouldBe 86400
  }

  "OAuthConfig" should "combine all configuration components" in {
    val providerConfig = OidcProviders.google("id", "secret", "http://localhost/cb")
    val claimMapping = ClaimMappingConfig()
    val sessionConfig = OAuthSessionConfig("test_session", false, 3600)

    val config = OAuthConfig(
      provider = providerConfig,
      claimMapping = claimMapping,
      session = sessionConfig,
      stateTtl = 300
    )

    config.provider.clientId shouldBe "id"
    config.claimMapping.userId shouldBe "sub"
    config.session.cookieName shouldBe "test_session"
    config.stateTtl shouldBe 300
  }

  "AppConfig validation" should "fail when OAuth mode lacks client-id" in {
    val authConfig = AuthConfig(
      mode = AuthMode.OAuth,
      basic = BasicAuthConfig("admin", None),
      jwtSecret = "test-secret-that-is-long-enough",
      jwtSecretExplicitlySet = true,
      jwtExpiration = 86400,
      oauth = None  // Missing OAuth config
    )

    val securityConfig = SecurityConfig(
      auth = authConfig,
      apiKey = None
    )

    val appConfig = createTestAppConfig(securityConfig)
    val errors = appConfig.validate()

    errors should contain("OAuth configuration required when auth mode is 'oauth'")
  }

  "AppConfig validation" should "fail when OAuth has empty client-id" in {
    val providerConfig = OidcProviders.google("", "secret", "http://localhost/cb")
    val oauthConfig = OAuthConfig(
      provider = providerConfig,
      claimMapping = ClaimMappingConfig(),
      session = OAuthSessionConfig("session", false, 3600),
      stateTtl = 300
    )

    val authConfig = AuthConfig(
      mode = AuthMode.OAuth,
      basic = BasicAuthConfig("admin", None),
      jwtSecret = "test-secret-that-is-long-enough",
      jwtSecretExplicitlySet = true,
      jwtExpiration = 86400,
      oauth = Some(oauthConfig)
    )

    val securityConfig = SecurityConfig(
      auth = authConfig,
      apiKey = None
    )

    val appConfig = createTestAppConfig(securityConfig)
    val errors = appConfig.validate()

    errors should contain("OAUTH_CLIENT_ID required when auth mode is 'oauth'")
  }

  "AppConfig validation" should "fail when OAuth has empty client-secret" in {
    val providerConfig = OidcProviders.google("client-id", "", "http://localhost/cb")
    val oauthConfig = OAuthConfig(
      provider = providerConfig,
      claimMapping = ClaimMappingConfig(),
      session = OAuthSessionConfig("session", false, 3600),
      stateTtl = 300
    )

    val authConfig = AuthConfig(
      mode = AuthMode.OAuth,
      basic = BasicAuthConfig("admin", None),
      jwtSecret = "test-secret-that-is-long-enough",
      jwtSecretExplicitlySet = true,
      jwtExpiration = 86400,
      oauth = Some(oauthConfig)
    )

    val securityConfig = SecurityConfig(
      auth = authConfig,
      apiKey = None
    )

    val appConfig = createTestAppConfig(securityConfig)
    val errors = appConfig.validate()

    errors should contain("OAUTH_CLIENT_SECRET required when auth mode is 'oauth'")
  }

  "AppConfig validation" should "fail when OAuth has empty redirect-uri" in {
    val providerConfig = OidcProviders.google("client-id", "secret", "")
    val oauthConfig = OAuthConfig(
      provider = providerConfig,
      claimMapping = ClaimMappingConfig(),
      session = OAuthSessionConfig("session", false, 3600),
      stateTtl = 300
    )

    val authConfig = AuthConfig(
      mode = AuthMode.OAuth,
      basic = BasicAuthConfig("admin", None),
      jwtSecret = "test-secret-that-is-long-enough",
      jwtSecretExplicitlySet = true,
      jwtExpiration = 86400,
      oauth = Some(oauthConfig)
    )

    val securityConfig = SecurityConfig(
      auth = authConfig,
      apiKey = None
    )

    val appConfig = createTestAppConfig(securityConfig)
    val errors = appConfig.validate()

    errors should contain("OAUTH_REDIRECT_URI required when auth mode is 'oauth'")
  }

  "AppConfig validation" should "pass when OAuth is fully configured" in {
    val providerConfig = OidcProviders.google("client-id", "secret", "http://localhost/cb")
    val oauthConfig = OAuthConfig(
      provider = providerConfig,
      claimMapping = ClaimMappingConfig(),
      session = OAuthSessionConfig("session", false, 3600),
      stateTtl = 300
    )

    val authConfig = AuthConfig(
      mode = AuthMode.OAuth,
      basic = BasicAuthConfig("admin", None),
      jwtSecret = "test-secret-that-is-long-enough",
      jwtSecretExplicitlySet = true,
      jwtExpiration = 86400,
      oauth = Some(oauthConfig)
    )

    val securityConfig = SecurityConfig(
      auth = authConfig,
      apiKey = None
    )

    val appConfig = createTestAppConfig(securityConfig)
    val errors = appConfig.validate()

    // Should not contain any OAuth-related errors
    errors.filter(_.contains("OAUTH")) shouldBe empty
  }

  // Helper to create a test AppConfig with specific security settings
  private def createTestAppConfig(security: SecurityConfig): AppConfig = {
    TestFixtures.testAppConfig.copy(security = security)
  }
}
