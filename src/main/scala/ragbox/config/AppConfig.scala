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
 * Security configuration for API authentication.
 */
final case class SecurityConfig(
  apiKey: Option[String]
) {
  /**
   * Check if API key authentication is enabled.
   */
  def isEnabled: Boolean = apiKey.isDefined && apiKey.exists(_.nonEmpty)

  /**
   * Validate a provided API key.
   */
  def validateKey(key: String): Boolean = apiKey.contains(key)
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
        apiKey = getOptionalString(config, "security.api-key")
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
