package ragbox.config

import cats.effect.{IO, Resource}
import com.typesafe.config.{Config, ConfigFactory}
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.rag.EmbeddingProvider
import org.llm4s.vectorstore.FusionStrategy
import ragbox.ingestion.IngestionConfig

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
  metrics: MetricsConfig
)

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
  jwtSecret: String,
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
          jwtSecret = Try(config.getString("security.auth.jwt-secret")).getOrElse {
            // Generate a random secret if not configured (not recommended for production)
            java.util.UUID.randomUUID().toString
          },
          jwtExpiration = Try(config.getLong("security.auth.jwt-expiration")).getOrElse(86400L)  // 24 hours
        )
      ),
      metrics = MetricsConfig(
        enabled = Try(config.getBoolean("metrics.enabled")).getOrElse(false)
      )
    )
  }

  private def getOptionalString(config: Config, path: String): Option[String] =
    Try(config.getString(path)).toOption.filter(_.nonEmpty)

  private def getOptionalInt(config: Config, path: String): Option[Int] =
    Try(config.getInt(path)).toOption
}
