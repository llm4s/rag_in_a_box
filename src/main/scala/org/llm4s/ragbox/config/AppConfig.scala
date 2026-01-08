package org.llm4s.ragbox.config

import cats.effect.{IO, Resource}
import com.typesafe.config.{Config, ConfigFactory}
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.rag.EmbeddingProvider
import org.llm4s.vectorstore.FusionStrategy
import org.llm4s.ragbox.auth.{ClaimMappingConfig, OAuthConfig, OAuthSessionConfig, OidcProviderConfig, OidcProviders}
import org.llm4s.ragbox.ingestion.IngestionConfig

import scala.util.Try

/**
 * Application configuration for RAG in a Box.
 *
 * Loaded from application.conf with environment variable overrides.
 */
final case class AppConfig(
  server: ServerConfig,
  database: DatabaseConfig,
  embedding: EmbeddingConfig,
  llm: LLMConfig,
  rag: RAGSettings,
  apiKeys: ApiKeysConfig,
  ingestion: IngestionConfig,
  security: SecurityConfig,
  metrics: MetricsConfig,
  production: ProductionConfig
) {
  /**
   * Validate the configuration.
   * Returns a list of validation errors, or empty if valid.
   */
  def validate(): List[String] = {
    val errors = scala.collection.mutable.ListBuffer.empty[String]

    // JWT secret is required when auth mode is Basic
    if (security.auth.mode == AuthMode.Basic) {
      if (!security.auth.jwtSecretExplicitlySet) {
        errors += "JWT_SECRET must be explicitly set when auth mode is 'basic' (for multi-instance deployments)"
      } else if (security.auth.jwtSecret.length < 32) {
        errors += "JWT_SECRET must be at least 32 characters for security"
      }
      if (security.auth.basic.adminPassword.isEmpty) {
        errors += "ADMIN_PASSWORD must be set when auth mode is 'basic'"
      }
    }

    // OAuth config validation when mode is OAuth
    if (security.auth.mode == AuthMode.OAuth) {
      security.auth.oauth match {
        case None =>
          errors += "OAuth configuration required when auth mode is 'oauth'"
        case Some(oauth) =>
          if (oauth.provider.clientId.isEmpty) {
            errors += "OAUTH_CLIENT_ID required when auth mode is 'oauth'"
          }
          if (oauth.provider.clientSecret.isEmpty) {
            errors += "OAUTH_CLIENT_SECRET required when auth mode is 'oauth'"
          }
          if (oauth.provider.redirectUri.isEmpty) {
            errors += "OAUTH_REDIRECT_URI required when auth mode is 'oauth'"
          }
      }
    }

    // Database connection required
    if (database.connectionString.isEmpty) {
      errors += "Database connection not configured (set DATABASE_URL or database host/port/database)"
    }

    // Embedding provider requires API key
    embedding.provider.toLowerCase match {
      case "openai" if apiKeys.openai.isEmpty =>
        errors += "OPENAI_API_KEY required when using OpenAI embedding provider"
      case "voyage" if apiKeys.voyage.isEmpty =>
        errors += "VOYAGE_API_KEY required when using Voyage embedding provider"
      case _ => // OK
    }

    errors.toList
  }

  /**
   * Check if configuration is valid.
   */
  def isValid: Boolean = validate().isEmpty
}

final case class ServerConfig(
  host: String,
  port: Int
)

final case class DatabaseConfig(
  host: String,
  port: Int,
  database: String,
  user: String,
  password: String,
  tableName: String,
  url: Option[String]
) {
  /**
   * Build JDBC connection string.
   * Uses DATABASE_URL if provided, otherwise constructs from individual settings.
   */
  def connectionString: String = url.getOrElse {
    s"jdbc:postgresql://$host:$port/$database"
  }

  /**
   * Get effective user (parsed from URL or from config).
   */
  def effectiveUser: String = url.flatMap(parseUserFromUrl).getOrElse(user)

  /**
   * Get effective password (parsed from URL or from config).
   */
  def effectivePassword: String = url.flatMap(parsePasswordFromUrl).getOrElse(password)

  private def parseUserFromUrl(url: String): Option[String] = {
    // Parse: postgresql://user:password@host:port/database
    val pattern = """postgresql://([^:]+):([^@]+)@.*""".r
    url match {
      case pattern(user, _) => Some(user)
      case _ => None
    }
  }

  private def parsePasswordFromUrl(url: String): Option[String] = {
    val pattern = """postgresql://([^:]+):([^@]+)@.*""".r
    url match {
      case pattern(_, password) => Some(password)
      case _ => None
    }
  }
}

final case class EmbeddingConfig(
  provider: String,
  model: String,
  dimensions: Option[Int]
) {
  def toEmbeddingProvider: EmbeddingProvider = provider.toLowerCase match {
    case "openai" => EmbeddingProvider.OpenAI
    case "voyage" => EmbeddingProvider.Voyage
    case "ollama" => EmbeddingProvider.Ollama
    case other => throw new IllegalArgumentException(s"Unknown embedding provider: $other")
  }
}

final case class LLMConfig(
  model: String,
  temperature: Double
)

final case class RAGSettings(
  chunking: ChunkingSettings,
  search: SearchSettings,
  systemPrompt: String
)

final case class ChunkingSettings(
  strategy: String,
  size: Int,
  overlap: Int
) {
  def toChunkerStrategy: ChunkerFactory.Strategy = strategy.toLowerCase match {
    case "simple" => ChunkerFactory.Strategy.Simple
    case "sentence" => ChunkerFactory.Strategy.Sentence
    case "markdown" => ChunkerFactory.Strategy.Markdown
    case "semantic" => ChunkerFactory.Strategy.Semantic
    case other => throw new IllegalArgumentException(s"Unknown chunking strategy: $other")
  }
}

final case class SearchSettings(
  topK: Int,
  fusionStrategy: String,
  rrfK: Int
) {
  def toFusionStrategy: FusionStrategy = fusionStrategy.toLowerCase match {
    case "rrf" => FusionStrategy.RRF(rrfK)
    case "weighted" => FusionStrategy.WeightedScore(0.5, 0.5)
    case "vector_only" => FusionStrategy.VectorOnly
    case "keyword_only" => FusionStrategy.KeywordOnly
    case other => throw new IllegalArgumentException(s"Unknown fusion strategy: $other")
  }
}

final case class ApiKeysConfig(
  openai: Option[String],
  anthropic: Option[String],
  voyage: Option[String]
) {
  /**
   * Get API key for the specified provider.
   */
  def getKey(provider: String): Option[String] = provider.toLowerCase match {
    case "openai" => openai
    case "anthropic" => anthropic
    case "voyage" => voyage
    case _ => None
  }

  /**
   * Check if any API key is configured.
   */
  def hasAnyKey: Boolean = openai.isDefined || anthropic.isDefined || voyage.isDefined
}

/**
 * Authentication mode.
 */
sealed trait AuthMode
object AuthMode {
  case object Open extends AuthMode     // No authentication
  case object Basic extends AuthMode    // Username/password with JWT
  case object OAuth extends AuthMode    // OAuth2/OIDC (future)

  def fromString(s: String): AuthMode = s.toLowerCase match {
    case "open" => Open
    case "basic" => Basic
    case "oauth" => OAuth
    case other => throw new IllegalArgumentException(s"Unknown auth mode: $other. Use: open, basic, or oauth")
  }
}

/**
 * Authentication configuration.
 */
final case class AuthConfig(
  mode: AuthMode,
  basic: BasicAuthConfig,
  oauth: Option[OAuthConfig],  // OAuth config when mode = oauth
  jwtSecret: String,
  jwtSecretExplicitlySet: Boolean,  // True if JWT_SECRET was explicitly configured
  jwtExpiration: Long  // Expiration in seconds (default: 24 hours)
)

/**
 * Basic authentication configuration.
 */
final case class BasicAuthConfig(
  adminUsername: String,
  adminPassword: Option[String]  // Required if mode=basic
)

/**
 * Security configuration for API authentication.
 */
final case class SecurityConfig(
  apiKey: Option[String],
  allowAdminHeader: Boolean = false,
  auth: AuthConfig
) {
  /**
   * Check if API key authentication is enabled (legacy mode).
   */
  def isEnabled: Boolean = auth.mode != AuthMode.Open || (apiKey.isDefined && apiKey.exists(_.nonEmpty))

  /**
   * Check if basic auth is enabled.
   */
  def isBasicAuthEnabled: Boolean = auth.mode == AuthMode.Basic

  /**
   * Validate a provided API key.
   */
  def validateKey(key: String): Boolean = apiKey.contains(key)

  /**
   * Check if X-Admin header is allowed for admin bypass.
   * Disabled by default for security. Enable only in development
   * or when API key authentication is also enabled.
   */
  def isAdminHeaderAllowed: Boolean = allowAdminHeader
}

/**
 * Metrics and observability configuration.
 */
final case class MetricsConfig(
  enabled: Boolean
)

/**
 * Production hardening configuration.
 */
final case class ProductionConfig(
  rateLimit: RateLimitConfig,
  requestSize: RequestSizeConfig,
  shutdown: ShutdownConfig,
  cors: CorsConfig
)

/**
 * CORS configuration.
 */
final case class CorsConfig(
  allowedOrigins: Seq[String],
  allowAllOrigins: Boolean
) {
  /**
   * Check if CORS should allow all origins.
   * True if explicitly enabled or if no specific origins configured.
   */
  def isAllowAll: Boolean = allowAllOrigins || allowedOrigins.isEmpty
}

/**
 * Rate limiting configuration.
 */
final case class RateLimitConfig(
  enabled: Boolean,
  maxRequests: Int,
  windowSeconds: Int
)

/**
 * Request size limiting configuration.
 */
final case class RequestSizeConfig(
  enabled: Boolean,
  maxBodySizeMb: Int
) {
  def maxBodySizeBytes: Long = maxBodySizeMb.toLong * 1024 * 1024
}

/**
 * Graceful shutdown configuration.
 */
final case class ShutdownConfig(
  timeoutSeconds: Int,
  drainConnectionsSeconds: Int
)

object AppConfig {

  /**
   * Load configuration from application.conf with environment variable overrides.
   */
  def load: IO[AppConfig] = IO.delay {
    val config = ConfigFactory.load()
    parseConfig(config)
  }

  /**
   * Load configuration as a cats-effect Resource.
   */
  def loadResource: Resource[IO, AppConfig] =
    Resource.eval(load)

  private def parseConfig(config: Config): AppConfig = {
    AppConfig(
      server = ServerConfig(
        host = config.getString("server.host"),
        port = config.getInt("server.port")
      ),
      database = DatabaseConfig(
        host = config.getString("database.host"),
        port = config.getInt("database.port"),
        database = config.getString("database.database"),
        user = config.getString("database.user"),
        password = config.getString("database.password"),
        tableName = config.getString("database.table-name"),
        url = getOptionalString(config, "database.url")
      ),
      embedding = EmbeddingConfig(
        provider = config.getString("embedding.provider"),
        model = config.getString("embedding.model"),
        dimensions = getOptionalInt(config, "embedding.dimensions")
      ),
      llm = LLMConfig(
        model = config.getString("llm.model"),
        temperature = config.getDouble("llm.temperature")
      ),
      rag = RAGSettings(
        chunking = ChunkingSettings(
          strategy = config.getString("rag.chunking.strategy"),
          size = config.getInt("rag.chunking.size"),
          overlap = config.getInt("rag.chunking.overlap")
        ),
        search = SearchSettings(
          topK = config.getInt("rag.search.top-k"),
          fusionStrategy = config.getString("rag.search.fusion-strategy"),
          rrfK = config.getInt("rag.search.rrf-k")
        ),
        systemPrompt = config.getString("rag.system-prompt")
      ),
      apiKeys = ApiKeysConfig(
        openai = getOptionalString(config, "api-keys.openai"),
        anthropic = getOptionalString(config, "api-keys.anthropic"),
        voyage = getOptionalString(config, "api-keys.voyage")
      ),
      ingestion = {
        // Load from config file first, then merge with environment variables
        val configIngestion = IngestionConfig.fromConfig(config)
        val envIngestion = IngestionConfig.fromEnv()
        // Env takes precedence if configured
        if (envIngestion.enabled) envIngestion else configIngestion
      },
      security = SecurityConfig(
        apiKey = getOptionalString(config, "security.api-key"),
        allowAdminHeader = Try(config.getBoolean("security.allow-admin-header")).getOrElse(false),
        auth = AuthConfig(
          mode = AuthMode.fromString(Try(config.getString("security.auth.mode")).getOrElse("open")),
          basic = BasicAuthConfig(
            adminUsername = Try(config.getString("security.auth.basic.admin-username")).getOrElse("admin"),
            adminPassword = getOptionalString(config, "security.auth.basic.admin-password")
          ),
          oauth = parseOAuthConfig(config),
          jwtSecret = {
            val explicitSecret = Try(config.getString("security.auth.jwt-secret")).toOption.filter(_.nonEmpty)
            explicitSecret.getOrElse {
              // Generate a random secret if not configured
              // For basic mode, validation will fail because jwtSecretExplicitlySet = false
              java.util.UUID.randomUUID().toString
            }
          },
          jwtSecretExplicitlySet = Try(config.getString("security.auth.jwt-secret")).toOption.exists(_.nonEmpty),
          jwtExpiration = Try(config.getLong("security.auth.jwt-expiration")).getOrElse(86400L)  // 24 hours
        )
      ),
      metrics = MetricsConfig(
        enabled = Try(config.getBoolean("metrics.enabled")).getOrElse(false)
      ),
      production = ProductionConfig(
        rateLimit = RateLimitConfig(
          enabled = Try(config.getBoolean("production.rate-limit.enabled")).getOrElse(false),
          maxRequests = Try(config.getInt("production.rate-limit.max-requests")).getOrElse(100),
          windowSeconds = Try(config.getInt("production.rate-limit.window-seconds")).getOrElse(60)
        ),
        requestSize = RequestSizeConfig(
          enabled = Try(config.getBoolean("production.request-size.enabled")).getOrElse(true),
          maxBodySizeMb = Try(config.getInt("production.request-size.max-body-size-mb")).getOrElse(10)
        ),
        shutdown = ShutdownConfig(
          timeoutSeconds = Try(config.getInt("production.shutdown.timeout-seconds")).getOrElse(30),
          drainConnectionsSeconds = Try(config.getInt("production.shutdown.drain-connections-seconds")).getOrElse(5)
        ),
        cors = CorsConfig(
          allowedOrigins = Try {
            import scala.jdk.CollectionConverters._
            config.getStringList("production.cors.allowed-origins").asScala.toSeq
          }.getOrElse(Seq.empty),
          allowAllOrigins = Try(config.getBoolean("production.cors.allow-all-origins")).getOrElse(true)
        )
      )
    )
  }

  private def getOptionalString(config: Config, path: String): Option[String] =
    Try(config.getString(path)).toOption.filter(_.nonEmpty)

  private def getOptionalInt(config: Config, path: String): Option[Int] =
    Try(config.getInt(path)).toOption

  /**
   * Parse OAuth configuration from config.
   * Returns None if OAuth is not configured.
   */
  private def parseOAuthConfig(config: Config): Option[OAuthConfig] = {
    val provider = Try(config.getString("security.auth.oauth.provider")).getOrElse("google")
    val clientId = getOptionalString(config, "security.auth.oauth.client-id").getOrElse("")
    val clientSecret = getOptionalString(config, "security.auth.oauth.client-secret").getOrElse("")
    val redirectUri = getOptionalString(config, "security.auth.oauth.redirect-uri").getOrElse("")

    // Only create OAuth config if at least client-id is configured
    if (clientId.isEmpty) {
      None
    } else {
      val providerConfig = provider.toLowerCase match {
        case "google" =>
          OidcProviders.google(clientId, clientSecret, redirectUri)

        case "azure" =>
          val tenantId = getOptionalString(config, "security.auth.oauth.azure-tenant-id")
            .getOrElse(throw new IllegalArgumentException("OAUTH_AZURE_TENANT_ID required for Azure AD provider"))
          OidcProviders.azureAd(tenantId, clientId, clientSecret, redirectUri)

        case "okta" =>
          val domain = getOptionalString(config, "security.auth.oauth.okta-domain")
            .getOrElse(throw new IllegalArgumentException("OAUTH_OKTA_DOMAIN required for Okta provider"))
          OidcProviders.okta(domain, clientId, clientSecret, redirectUri)

        case "keycloak" =>
          val baseUrl = getOptionalString(config, "security.auth.oauth.keycloak-base-url")
            .getOrElse(throw new IllegalArgumentException("OAUTH_KEYCLOAK_BASE_URL required for Keycloak provider"))
          val realm = getOptionalString(config, "security.auth.oauth.keycloak-realm")
            .getOrElse(throw new IllegalArgumentException("OAUTH_KEYCLOAK_REALM required for Keycloak provider"))
          OidcProviders.keycloak(baseUrl, realm, clientId, clientSecret, redirectUri)

        case "custom" =>
          OidcProviders.custom(
            clientId = clientId,
            clientSecret = clientSecret,
            redirectUri = redirectUri,
            authorizationEndpoint = getOptionalString(config, "security.auth.oauth.authorization-endpoint")
              .getOrElse(throw new IllegalArgumentException("OAUTH_AUTHORIZATION_ENDPOINT required for custom provider")),
            tokenEndpoint = getOptionalString(config, "security.auth.oauth.token-endpoint")
              .getOrElse(throw new IllegalArgumentException("OAUTH_TOKEN_ENDPOINT required for custom provider")),
            userinfoEndpoint = getOptionalString(config, "security.auth.oauth.userinfo-endpoint")
              .getOrElse(throw new IllegalArgumentException("OAUTH_USERINFO_ENDPOINT required for custom provider")),
            jwksUri = getOptionalString(config, "security.auth.oauth.jwks-uri")
              .getOrElse(throw new IllegalArgumentException("OAUTH_JWKS_URI required for custom provider")),
            issuer = getOptionalString(config, "security.auth.oauth.issuer")
              .getOrElse(throw new IllegalArgumentException("OAUTH_ISSUER required for custom provider"))
          )

        case other =>
          throw new IllegalArgumentException(s"Unknown OAuth provider: $other. Use: google, azure, okta, keycloak, or custom")
      }

      // Parse claim mapping
      val claimMapping = ClaimMappingConfig(
        userId = Try(config.getString("security.auth.oauth.claim-mapping.user-id")).getOrElse("sub"),
        email = Try(config.getString("security.auth.oauth.claim-mapping.email")).getOrElse("email"),
        groups = Try(config.getString("security.auth.oauth.claim-mapping.groups")).getOrElse("groups"),
        name = Try(config.getString("security.auth.oauth.claim-mapping.name")).getOrElse("name")
      )

      // Parse session config
      val sessionConfig = OAuthSessionConfig(
        cookieName = Try(config.getString("security.auth.oauth.session.cookie-name")).getOrElse("ragbox_session"),
        cookieSecure = Try(config.getBoolean("security.auth.oauth.session.cookie-secure")).getOrElse(true),
        cookieMaxAge = Try(config.getInt("security.auth.oauth.session.cookie-max-age")).getOrElse(86400)
      )

      Some(OAuthConfig(
        provider = providerConfig,
        claimMapping = claimMapping,
        session = sessionConfig,
        pkceEnabled = Try(config.getBoolean("security.auth.oauth.pkce-enabled")).getOrElse(true),
        stateTtl = Try(config.getInt("security.auth.oauth.state-ttl")).getOrElse(300)
      ))
    }
  }
}
