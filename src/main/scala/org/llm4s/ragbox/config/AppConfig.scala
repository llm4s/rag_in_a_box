package org.llm4s.ragbox.config

import cats.effect.{IO, Resource}
import com.typesafe.config.{Config, ConfigFactory}
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.security.Secret
import org.llm4s.vectorstore.FusionStrategy
import org.llm4s.ragbox.auth.{ClaimMappingConfig, OAuthConfig, OAuthSessionConfig, OAuthSessionStorage, OidcProviderConfig, OidcProviders}
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
  production: ProductionConfig,
  permissions: PermissionsConfig = PermissionsConfig()
) {
  /**
   * Validate the configuration.
   * Returns a list of validation errors, or empty if valid.
   */
  def validate(): List[String] = {
    val errors = scala.collection.mutable.ListBuffer.empty[String]

    if (database.poolSize < 1) {
      errors += s"DB_POOL_SIZE must be at least 1 (got ${database.poolSize})"
    }

    // JWT secret is required when auth mode is Basic
    if (security.auth.mode == AuthMode.Basic) {
      if (!security.auth.jwtSecretExplicitlySet) {
        errors += "JWT_SECRET must be explicitly set when auth mode is 'basic' (for multi-instance deployments)"
      } else if (security.auth.jwtSecret.length < 32) {
        errors += "JWT_SECRET must be at least 32 characters for security"
      } else if (Placeholders.isPlaceholder(security.auth.jwtSecret)) {
        errors += "JWT_SECRET is still a placeholder value (CHANGE_ME / REPLACE-ME); generate one, e.g. `openssl rand -base64 32`"
      }
      security.auth.basic.adminPassword match {
        case None =>
          errors += "ADMIN_PASSWORD must be set when auth mode is 'basic'"
        case Some(pw) if Placeholders.isPlaceholder(pw) || pw == "admin" =>
          errors += "ADMIN_PASSWORD is a placeholder or the old default 'admin'; set a real password"
        case Some(pw) =>
          PasswordPolicy(security.auth.passwordMinLength).check(pw).left.foreach(e => errors += s"ADMIN_PASSWORD: $e")
      }
    }
    if (security.auth.passwordMinLength < 1) {
      errors += s"PASSWORD_MIN_LENGTH must be at least 1 (got ${security.auth.passwordMinLength})"
    }
    if (security.auth.passwordHashIterations < PasswordHashParams.MinIterations) {
      errors += s"PASSWORD_HASH_ITERATIONS must be at least ${PasswordHashParams.MinIterations} (got ${security.auth.passwordHashIterations})"
    }
    security.apiKey.filter(Placeholders.isPlaceholder).foreach { _ =>
      errors += "API_KEY is still a placeholder value (CHANGE_ME / REPLACE-ME)"
    }

    // CORS origins must be origins (scheme://host[:port]), bare host names or *
    production.cors.allowedOrigins.filterNot(CorsConfig.isValidOrigin).foreach { o =>
      errors += s"ALLOWED_ORIGINS entry '$o' is not an origin (expected e.g. https://app.example.com or *)"
    }

    // Rate limiting
    production.rateLimit.trustedProxyErrors.foreach(errors += _)
    if (production.rateLimit.maxRequests < 1) {
      errors += s"RATE_LIMIT_MAX_REQUESTS must be at least 1 (got ${production.rateLimit.maxRequests})"
    }
    if (production.rateLimit.windowSeconds < 1) {
      errors += s"RATE_LIMIT_WINDOW_SECONDS must be at least 1 (got ${production.rateLimit.windowSeconds})"
    }
    if (production.rateLimit.maxClients < 1) {
      errors += s"RATE_LIMIT_MAX_CLIENTS must be at least 1 (got ${production.rateLimit.maxClients})"
    }
    if (production.rateLimit.loginMaxAttempts < 0) {
      errors += s"RATE_LIMIT_LOGIN_MAX_ATTEMPTS must be non-negative (got ${production.rateLimit.loginMaxAttempts})"
    }
    if (production.rateLimit.loginWindowSeconds < 1) {
      errors += s"RATE_LIMIT_LOGIN_WINDOW_SECONDS must be at least 1 (got ${production.rateLimit.loginWindowSeconds})"
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
          if (!oauth.provider.scopes.contains("openid")) {
            errors += s"OAUTH_SCOPES must include 'openid' (got: ${oauth.provider.scopes.mkString(" ")})"
          }
      }
    }

    // DATABASE_URL, if set, must be parseable
    database.urlError.foreach { error =>
      errors += s"Invalid DATABASE_URL: $error"
    }

    // Embedding provider requires API key
    embedding.provider.toLowerCase match {
      case "openai" if apiKeys.openai.isEmpty =>
        errors += "OPENAI_API_KEY required when using OpenAI embedding provider"
      case "voyage" if apiKeys.voyage.isEmpty =>
        errors += "VOYAGE_API_KEY required when using Voyage embedding provider"
      case _ => // OK
    }

    // Unknown reranking / query-transform strategies fail at startup, not on the first query
    rag.reranking.normalizedStrategy.left.foreach(errors += _)
    rag.queryTransform.normalizedStrategy.left.foreach(errors += _)
    if (rag.reranking.normalizedStrategy.contains("cohere") && apiKeys.cohere.isEmpty) {
      errors += "COHERE_API_KEY required when RAG_RERANKING_STRATEGY=cohere"
    }
    if (rag.reranking.candidates < 1) {
      errors += s"RAG_RERANKING_CANDIDATES must be at least 1 (got ${rag.reranking.candidates})"
    }
    if (rag.chat.historyMessages < 0) {
      errors += s"RAG_CHAT_HISTORY_MESSAGES must be non-negative (got ${rag.chat.historyMessages})"
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
  password: Secret,
  tableName: String,
  url: Option[String],
  poolSize: Int = DatabaseConfig.DefaultPoolSize
) {
  /**
   * DATABASE_URL parsed once. None when no (non-blank) URL is configured;
   * Some(Left(error)) when it is configured but invalid.
   */
  lazy val parsedUrl: Option[Either[String, DatabaseUrl]] =
    url.map(_.trim).filter(_.nonEmpty).map(DatabaseUrl.parse)

  /** Error describing an invalid DATABASE_URL, if any. */
  def urlError: Option[String] = parsedUrl.flatMap(_.left.toOption)

  private def validUrl: Option[DatabaseUrl] = parsedUrl.flatMap {
    case Right(parsed) => Some(parsed)
    case Left(error) => throw new IllegalArgumentException(s"Invalid DATABASE_URL: $error")
  }

  /** Effective host: from DATABASE_URL if set, otherwise database.host. */
  def effectiveHost: String = validUrl.map(_.host).getOrElse(host)

  /** Effective port: from DATABASE_URL if set (default 5432 when it omits one), otherwise database.port. */
  def effectivePort: Int = validUrl.map(_.port.getOrElse(DatabaseUrl.DefaultPort)).getOrElse(port)

  /** Effective database name: from DATABASE_URL if it has one, otherwise database.database. */
  def effectiveDatabase: String = validUrl.flatMap(_.database).getOrElse(database)

  /**
   * JDBC connection string (never contains credentials).
   * Built from DATABASE_URL if provided, otherwise from the individual settings.
   */
  def connectionString: String = validUrl match {
    case Some(parsed) => parsed.jdbcUrl(DatabaseUrl.DefaultPort, database)
    case None => s"jdbc:postgresql://$host:$port/$database"
  }

  /** Effective user: from DATABASE_URL userinfo if present, otherwise database.user. */
  def effectiveUser: String = validUrl.flatMap(_.user).getOrElse(user)

  /** Effective password: from DATABASE_URL userinfo if present, otherwise database.password. */
  def effectivePassword: Secret = validUrl.flatMap(_.password).map(Secret(_)).getOrElse(password)
}

object DatabaseConfig {

  /** Default size of the shared HikariCP pool (`database.pool-size` / `DB_POOL_SIZE`). */
  val DefaultPoolSize: Int = 10
}

/**
 * Embedding settings.
 *
 * @param provider   an llm4s embedding provider id or alias, resolved through llm4s's
 *                   `ProviderRegistry` (any provider module on the classpath: openai, voyage,
 *                   ollama, ...)
 * @param dimensions the embedding dimensions, passed to llm4s; when unset llm4s takes them from
 *                   the provider's model table
 * @param baseUrl    the provider's endpoint, when it is not the provider's default - e.g. an
 *                   Ollama server that is not on `http://localhost:11434`
 */
final case class EmbeddingConfig(
  provider: String,
  model: String,
  dimensions: Option[Int],
  baseUrl: Option[String] = None
) {
  /** The provider id as configured, trimmed and lower-cased; the registry canonicalises aliases. */
  def providerId: String = provider.trim.toLowerCase
}

/**
 * LLM settings.
 *
 * @param model   `provider/model`, e.g. `openai/gpt-4o`, `anthropic/claude-sonnet-4-5` or
 *                `ollama/llama3.1`. The provider is resolved through llm4s's `ProviderRegistry`,
 *                so any registered provider works. A bare model name means OpenAI.
 * @param baseUrl the provider's endpoint, when it is not the provider's default (Ollama, an
 *                OpenAI-compatible gateway, ...)
 */
final case class LLMConfig(
  model: String,
  temperature: Double,
  baseUrl: Option[String] = None
) {
  /** The provider id part of [[model]] (`openai` when it names none). */
  def providerId: String = model.split("/", 2) match {
    case Array(provider, _) => provider.trim.toLowerCase
    case _ => "openai"
  }

  /** The model part of [[model]]. */
  def modelName: String = model.split("/", 2) match {
    case Array(_, name) => name.trim
    case _ => model.trim
  }
}

final case class RAGSettings(
  chunking: ChunkingSettings,
  search: SearchSettings,
  systemPrompt: String,
  reranking: RerankingSettings = RerankingSettings(),
  queryTransform: QueryTransformSettings = QueryTransformSettings(),
  chat: ChatSettings = ChatSettings()
)

/**
 * Reranking of retrieved candidates, through llm4s `RAGConfig.withCohereReranking` /
 * `withLLMReranking`. Off by default.
 *
 * @param strategy   `none`, `cohere` (needs `COHERE_API_KEY`) or `llm` (reranks with the answer LLM)
 * @param model      the Cohere rerank model
 * @param candidates how many hybrid-search candidates are reranked down to top-k
 */
final case class RerankingSettings(
  strategy: String = "none",
  model: String = "rerank-english-v3.0",
  candidates: Int = 30
) {
  def normalizedStrategy: Either[String, String] = strategy.trim.toLowerCase match {
    case s @ ("none" | "cohere" | "llm") => Right(s)
    case other => Left(s"Unknown reranking strategy: $other. Use: none, cohere or llm")
  }
  def enabled: Boolean = normalizedStrategy.exists(_ != "none")
}

/**
 * Pre-retrieval query transformation, through llm4s `RAGConfig.withQueryTransformer`. Off by
 * default.
 *
 * @param strategy `none` or `llm-rewrite` (llm4s `LLMQueryRewriter`, using the answer LLM)
 */
final case class QueryTransformSettings(
  strategy: String = "none"
) {
  def normalizedStrategy: Either[String, String] = strategy.trim.toLowerCase match {
    case s @ ("none" | "llm-rewrite") => Right(s)
    case other => Left(s"Unknown query transform: $other. Use: none or llm-rewrite")
  }
  def enabled: Boolean = normalizedStrategy.exists(_ != "none")
}

/**
 * Chat settings.
 *
 * @param historyMessages how many earlier messages of a chat session are sent to the LLM with
 *                        each new question (0 sends none)
 */
final case class ChatSettings(
  historyMessages: Int = 10
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
  openai: Option[Secret],
  anthropic: Option[Secret],
  voyage: Option[Secret],
  cohere: Option[Secret] = None
) {
  /**
   * Get API key for the specified provider.
   */
  def getKey(provider: String): Option[Secret] = provider.toLowerCase match {
    case "openai" => openai
    case "anthropic" => anthropic
    case "voyage" => voyage
    case "cohere" => cohere
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
  jwtExpiration: Long,  // Expiration in seconds (default: 24 hours)
  trustedProxyHeaders: Boolean = false,  // honour X-User-Id / X-Group-Ids from admin credentials
  jwtIssuer: String = AuthConfig.DefaultJwtIssuer,      // `iss` of issued JWTs, required on validation
  jwtAudience: String = AuthConfig.DefaultJwtAudience,  // `aud` of issued JWTs, required on validation
  passwordMinLength: Int = PasswordPolicy.DefaultMinLength,
  passwordHashIterations: Int = PasswordHashParams.DefaultIterations
)

object AuthConfig {
  val DefaultJwtIssuer: String = "rag-in-a-box"
  val DefaultJwtAudience: String = "rag-in-a-box"
}

/**
 * Recognises the placeholder secrets shipped in templates and examples (`CHANGE_ME...`,
 * `REPLACE-ME`, `your-...-secret`), so a deployment that forgot to fill them in fails at
 * startup instead of running with a publicly known secret.
 */
object Placeholders {
  private val Markers =
    Seq("changeme", "replaceme", "yoursecret", "your32char", "yourjwtsecret", "youradminpassword", "yoursecure")

  def isPlaceholder(value: String): Boolean = {
    val normalized = value.toLowerCase.filter(_.isLetterOrDigit)
    Markers.exists(normalized.contains)
  }
}

/**
 * Password rules for user creation, resets, password changes and `ADMIN_PASSWORD`.
 *
 * @param minLength minimum number of characters (`PASSWORD_MIN_LENGTH`)
 */
final case class PasswordPolicy(minLength: Int = PasswordPolicy.DefaultMinLength) {

  def check(password: String): Either[String, Unit] =
    if (password.isEmpty) Left("Password is required")
    else if (password.length < minLength) Left(s"Password must be at least $minLength characters")
    else if (password.length > PasswordPolicy.MaxLength) Left(s"Password must be at most ${PasswordPolicy.MaxLength} characters")
    else Right(())
}

object PasswordPolicy {
  val DefaultMinLength: Int = 12
  /** Upper bound, so a huge password cannot be used to burn CPU in the key derivation. */
  val MaxLength: Int = 1024
}

/** Parameters of the PBKDF2 password hash (see `org.llm4s.ragbox.auth.PasswordHashing`). */
object PasswordHashParams {
  /** OWASP's recommendation for PBKDF2-HMAC-SHA256. */
  val DefaultIterations: Int = 600000
  val MinIterations: Int = 1000
}

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
   * Whether `X-Admin: true` keeps administrator rights for a trusted-proxy request that names
   * an end user (`X-User-Id`). Only meaningful with `auth.trustedProxyHeaders`.
   */
  def isAdminHeaderAllowed: Boolean = allowAdminHeader
}

/**
 * Metrics and observability configuration.
 */
final case class MetricsConfig(
  enabled: Boolean,
  requireAuth: Boolean = true  // serve /metrics to administrators only (e.g. Bearer API_KEY)
)

/**
 * Permission-based RAG configuration.
 *
 * @param required when true, startup fails if the permission-aware SearchIndex cannot be
 *                 created or its schema cannot be initialized, instead of silently falling
 *                 back to non-permission mode (where queries ignore all permissions).
 */
final case class PermissionsConfig(
  required: Boolean = false
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
 *
 * Cross-origin requests are denied by default (no CORS headers are sent, so browsers block
 * cross-origin reads); the admin UI is served from the same origin and needs none. An explicit
 * `ALLOWED_ORIGINS` list allows exactly those origins and takes precedence over
 * `CORS_ALLOW_ALL_ORIGINS`; `ALLOWED_ORIGINS=*` (or `CORS_ALLOW_ALL_ORIGINS=true` with no list)
 * allows every origin.
 *
 * @param allowedOrigins origins such as `https://app.example.com` (a bare host name matches it
 *                       over any scheme), or `*`
 */
final case class CorsConfig(
  allowedOrigins: Seq[String] = Seq.empty,
  allowAllOrigins: Boolean = false
) {
  private val explicitOrigins: Seq[String] = allowedOrigins.filterNot(_ == "*")

  /** Every origin is allowed: `ALLOWED_ORIGINS=*`, or allow-all with no explicit list. */
  def isAllowAll: Boolean =
    allowedOrigins.contains("*") || (allowAllOrigins && allowedOrigins.isEmpty)

  /** No cross-origin access at all (the default). */
  def isDenyAll: Boolean = !isAllowAll && explicitOrigins.isEmpty

  /** Whether `origin` (as sent in the `Origin` header) may make cross-origin requests. */
  def allows(origin: String): Boolean = isAllowAll || {
    val o = origin.trim.toLowerCase.stripSuffix("/")
    explicitOrigins.exists { allowed =>
      val a = allowed.trim.toLowerCase.stripSuffix("/")
      if (a.contains("://")) o == a else o.endsWith(s"://$a")
    }
  }

  def describe: String =
    if (isAllowAll) "allow all origins"
    else if (isDenyAll) "same-origin only (no cross-origin access)"
    else s"restricted to ${explicitOrigins.mkString(", ")}"
}

object CorsConfig {
  /** `*`, `scheme://host[:port]` or a bare `host[:port]`: no path, query, user info or spaces. */
  def isValidOrigin(origin: String): Boolean = {
    val o = origin.trim.stripSuffix("/")
    o == "*" || (o.nonEmpty && !o.exists(_.isWhitespace) && {
      val host = o.split("://", 2) match {
        case Array(scheme, h) => Option.when(scheme.matches("[A-Za-z][A-Za-z0-9+.-]*"))(h)
        case _ => Some(o)
      }
      host.exists(h => h.nonEmpty && !h.exists(c => c == '/' || c == '?' || c == '#' || c == '@' || c == '*'))
    })
  }
}

/**
 * Rate limiting configuration.
 *
 * @param enabled            per-client limit on all requests (health checks and /metrics exempt)
 * @param maxRequests        requests a client may make per window
 * @param windowSeconds      window length
 * @param trustedProxies     IPs / CIDRs of reverse proxies whose `X-Forwarded-For` / `X-Real-IP`
 *                           identify the client; from anyone else those headers are ignored
 * @param maxClients         most clients tracked at once (the oldest windows are evicted beyond it)
 * @param loginMaxAttempts   failed `POST /api/v1/auth/login` attempts allowed per client IP and
 *                           per username per login window (0 disables); applies even when
 *                           `enabled` is false
 * @param loginWindowSeconds login throttling window
 */
final case class RateLimitConfig(
  enabled: Boolean,
  maxRequests: Int,
  windowSeconds: Int,
  trustedProxies: Seq[String] = Seq.empty,
  maxClients: Int = RateLimitConfig.DefaultMaxClients,
  loginMaxAttempts: Int = RateLimitConfig.DefaultLoginMaxAttempts,
  loginWindowSeconds: Int = RateLimitConfig.DefaultLoginWindowSeconds
) {
  /** Entries of [[trustedProxies]] that are not IP addresses or CIDRs. */
  def trustedProxyErrors: List[String] =
    trustedProxies.toList.filter(p => org.llm4s.ragbox.middleware.ClientIp.parseCidr(p).isEmpty).map { p =>
      s"RATE_LIMIT_TRUSTED_PROXIES entry '$p' is not an IP address or CIDR"
    }
}

object RateLimitConfig {
  val DefaultMaxClients: Int = 10000
  val DefaultLoginMaxAttempts: Int = 10
  val DefaultLoginWindowSeconds: Int = 300
}

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

  private[config] def parseConfig(config: Config): AppConfig = {
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
        password = Secret(config.getString("database.password")),
        tableName = config.getString("database.table-name"),
        url = getOptionalString(config, "database.url"),
        poolSize = getOptionalInt(config, "database.pool-size").getOrElse(DatabaseConfig.DefaultPoolSize)
      ),
      embedding = EmbeddingConfig(
        provider = config.getString("embedding.provider"),
        model = config.getString("embedding.model"),
        dimensions = getOptionalInt(config, "embedding.dimensions"),
        baseUrl = getOptionalString(config, "embedding.base-url")
      ),
      llm = LLMConfig(
        model = config.getString("llm.model"),
        temperature = config.getDouble("llm.temperature"),
        baseUrl = getOptionalString(config, "llm.base-url")
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
        systemPrompt = config.getString("rag.system-prompt"),
        reranking = RerankingSettings(
          strategy = getOptionalString(config, "rag.reranking.strategy").getOrElse("none"),
          model = getOptionalString(config, "rag.reranking.model").getOrElse(RerankingSettings().model),
          candidates = getOptionalInt(config, "rag.reranking.candidates").getOrElse(RerankingSettings().candidates)
        ),
        queryTransform = QueryTransformSettings(
          strategy = getOptionalString(config, "rag.query-transform.strategy").getOrElse("none")
        ),
        chat = ChatSettings(
          historyMessages = getOptionalInt(config, "rag.chat.history-messages").getOrElse(ChatSettings().historyMessages)
        )
      ),
      apiKeys = ApiKeysConfig(
        openai = getOptionalString(config, "api-keys.openai").map(Secret(_)),
        anthropic = getOptionalString(config, "api-keys.anthropic").map(Secret(_)),
        voyage = getOptionalString(config, "api-keys.voyage").map(Secret(_)),
        cohere = getOptionalString(config, "api-keys.cohere").map(Secret(_))
      ),
      ingestion = {
        // Load from config file first, then merge with environment variables
        val configIngestion = IngestionConfig.fromConfig(config)
        val envIngestion = IngestionConfig.fromEnv()
        // Env takes precedence if configured; the policy (allowed roots, URL rules) applies either way
        (if (envIngestion.enabled) envIngestion else configIngestion)
          .copy(policy = IngestionConfig.policyFromConfig(config))
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
          jwtExpiration = Try(config.getLong("security.auth.jwt-expiration")).getOrElse(86400L),  // 24 hours
          trustedProxyHeaders = config.hasPath("security.auth.trusted-proxy-headers") &&
            config.getBoolean("security.auth.trusted-proxy-headers"),
          jwtIssuer = getOptionalString(config, "security.auth.jwt-issuer").getOrElse(AuthConfig.DefaultJwtIssuer),
          jwtAudience = getOptionalString(config, "security.auth.jwt-audience").getOrElse(AuthConfig.DefaultJwtAudience),
          passwordMinLength = getOptionalInt(config, "security.auth.password-min-length")
            .getOrElse(PasswordPolicy.DefaultMinLength),
          passwordHashIterations = getOptionalInt(config, "security.auth.password-hash-iterations")
            .getOrElse(PasswordHashParams.DefaultIterations)
        )
      ),
      metrics = MetricsConfig(
        enabled = Try(config.getBoolean("metrics.enabled")).getOrElse(false),
        // Not wrapped in Try: an invalid METRICS_REQUIRE_AUTH must not silently expose /metrics
        requireAuth = !config.hasPath("metrics.require-auth") || config.getBoolean("metrics.require-auth")
      ),
      production = ProductionConfig(
        rateLimit = RateLimitConfig(
          enabled = Try(config.getBoolean("production.rate-limit.enabled")).getOrElse(false),
          maxRequests = Try(config.getInt("production.rate-limit.max-requests")).getOrElse(100),
          windowSeconds = Try(config.getInt("production.rate-limit.window-seconds")).getOrElse(60),
          trustedProxies = stringList(config, "production.rate-limit.trusted-proxies"),
          maxClients = getOptionalInt(config, "production.rate-limit.max-clients").getOrElse(RateLimitConfig.DefaultMaxClients),
          loginMaxAttempts = getOptionalInt(config, "production.rate-limit.login.max-attempts")
            .getOrElse(RateLimitConfig.DefaultLoginMaxAttempts),
          loginWindowSeconds = getOptionalInt(config, "production.rate-limit.login.window-seconds")
            .getOrElse(RateLimitConfig.DefaultLoginWindowSeconds)
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
          allowedOrigins = stringList(config, "production.cors.allowed-origins"),
          allowAllOrigins = Try(config.getBoolean("production.cors.allow-all-origins")).getOrElse(false)
        )
      ),
      permissions = PermissionsConfig(
        // Not wrapped in Try: an invalid PERMISSIONS_REQUIRED value should fail loudly
        required = config.hasPath("permissions.required") && config.getBoolean("permissions.required")
      )
    )
  }

  private def getOptionalString(config: Config, path: String): Option[String] =
    Try(config.getString(path)).toOption.filter(_.nonEmpty)

  private def getOptionalInt(config: Config, path: String): Option[Int] =
    Try(config.getInt(path)).toOption

  /**
   * A list setting given either as a HOCON list or as one string split on `separators` (a
   * regex; env vars can only carry strings).
   */
  private def stringList(config: Config, path: String, separators: String = ","): Seq[String] =
    if (!config.hasPath(path)) Seq.empty
    else {
      import scala.jdk.CollectionConverters._
      Try(config.getStringList(path).asScala.toSeq)
        .orElse(Try(config.getString(path).split(separators).toSeq))
        .getOrElse(Seq.empty)
        .map(_.trim)
        .filter(_.nonEmpty)
    }

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

      // OAUTH_SCOPES (space- or comma-separated) replaces the provider preset's scopes
      val scopes = stringList(config, "security.auth.oauth.scopes", "[,\\s]+").distinct.toList

      Some(OAuthConfig(
        provider = if (scopes.isEmpty) providerConfig else providerConfig.copy(scopes = scopes),
        claimMapping = claimMapping,
        session = sessionConfig,
        pkceEnabled = Try(config.getBoolean("security.auth.oauth.pkce-enabled")).getOrElse(true),
        stateTtl = Try(config.getInt("security.auth.oauth.state-ttl")).getOrElse(300),
        sessionStorage = getOptionalString(config, "security.auth.oauth.session-storage")
          .map(OAuthSessionStorage.fromString(_).fold(e => throw new IllegalArgumentException(e), identity))
          .getOrElse(OAuthSessionStorage.Memory),
        adminGroups = getOptionalString(config, "security.auth.oauth.admin-groups")
          .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)
          .getOrElse(Set.empty)
      ))
    }
  }
}
