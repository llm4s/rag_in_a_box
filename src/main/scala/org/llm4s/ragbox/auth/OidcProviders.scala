package org.llm4s.ragbox.auth

/** Pre-configured OIDC provider endpoints for common identity providers */
object OidcProviders:

  /** Google OIDC provider */
  def google(
      clientId: String,
      clientSecret: String,
      redirectUri: String
  ): OidcProviderConfig =
    OidcProviderConfig(
      clientId = clientId,
      clientSecret = clientSecret,
      redirectUri = redirectUri,
      authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
      tokenEndpoint = "https://oauth2.googleapis.com/token",
      userinfoEndpoint = "https://openidconnect.googleapis.com/v1/userinfo",
      jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
      issuer = "https://accounts.google.com",
      scopes = List("openid", "profile", "email")
    )

  /** Azure AD OIDC provider */
  def azureAd(
      tenantId: String,
      clientId: String,
      clientSecret: String,
      redirectUri: String
  ): OidcProviderConfig =
    OidcProviderConfig(
      clientId = clientId,
      clientSecret = clientSecret,
      redirectUri = redirectUri,
      authorizationEndpoint =
        s"https://login.microsoftonline.com/$tenantId/oauth2/v2.0/authorize",
      tokenEndpoint =
        s"https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token",
      userinfoEndpoint = "https://graph.microsoft.com/oidc/userinfo",
      jwksUri =
        s"https://login.microsoftonline.com/$tenantId/discovery/v2.0/keys",
      issuer = s"https://login.microsoftonline.com/$tenantId/v2.0",
      scopes = List("openid", "profile", "email", "User.Read")
    )

  /** Okta OIDC provider */
  def okta(
      domain: String,
      clientId: String,
      clientSecret: String,
      redirectUri: String,
      authorizationServerId: String = "default"
  ): OidcProviderConfig =
    val baseUrl = s"https://$domain/oauth2/$authorizationServerId"
    OidcProviderConfig(
      clientId = clientId,
      clientSecret = clientSecret,
      redirectUri = redirectUri,
      authorizationEndpoint = s"$baseUrl/v1/authorize",
      tokenEndpoint = s"$baseUrl/v1/token",
      userinfoEndpoint = s"$baseUrl/v1/userinfo",
      jwksUri = s"$baseUrl/v1/keys",
      issuer = baseUrl,
      scopes = List("openid", "profile", "email", "groups")
    )

  /** Keycloak OIDC provider */
  def keycloak(
      baseUrl: String,
      realm: String,
      clientId: String,
      clientSecret: String,
      redirectUri: String
  ): OidcProviderConfig =
    val realmUrl = s"${baseUrl.stripSuffix("/")}/realms/$realm"
    OidcProviderConfig(
      clientId = clientId,
      clientSecret = clientSecret,
      redirectUri = redirectUri,
      authorizationEndpoint = s"$realmUrl/protocol/openid-connect/auth",
      tokenEndpoint = s"$realmUrl/protocol/openid-connect/token",
      userinfoEndpoint = s"$realmUrl/protocol/openid-connect/userinfo",
      jwksUri = s"$realmUrl/protocol/openid-connect/certs",
      issuer = realmUrl,
      scopes = List("openid", "profile", "email")
    )

  /** Custom provider from explicit endpoints */
  def custom(
      clientId: String,
      clientSecret: String,
      redirectUri: String,
      authorizationEndpoint: String,
      tokenEndpoint: String,
      userinfoEndpoint: String,
      jwksUri: String,
      issuer: String,
      scopes: List[String] = List("openid", "profile", "email")
  ): OidcProviderConfig =
    OidcProviderConfig(
      clientId = clientId,
      clientSecret = clientSecret,
      redirectUri = redirectUri,
      authorizationEndpoint = authorizationEndpoint,
      tokenEndpoint = tokenEndpoint,
      userinfoEndpoint = userinfoEndpoint,
      jwksUri = jwksUri,
      issuer = issuer,
      scopes = scopes
    )

  /** Supported provider names */
  val supportedProviders: Set[String] =
    Set("google", "azure", "okta", "keycloak", "custom")
