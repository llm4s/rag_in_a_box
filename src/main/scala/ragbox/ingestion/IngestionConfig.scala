package ragbox.ingestion

import com.typesafe.config.Config

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Configuration for ingestion sources.
 */
sealed trait SourceConfig {
  def name: String
  def enabled: Boolean
  def metadata: Map[String, String]
}

/**
 * Directory source configuration.
 */
final case class DirectorySourceConfig(
  name: String,
  path: String,
  patterns: Set[String] = Set("*.md", "*.txt", "*.pdf"),
  recursive: Boolean = true,
  maxDepth: Int = 10,
  metadata: Map[String, String] = Map.empty,
  enabled: Boolean = true
) extends SourceConfig {

  /**
   * Convert glob patterns to extensions.
   * "*.md" -> "md", "*.txt" -> "txt"
   */
  def extensions: Set[String] =
    patterns.map(_.stripPrefix("*.").stripPrefix("."))
}

/**
 * URL source configuration.
 */
final case class UrlSourceConfig(
  name: String,
  urls: Seq[String],
  metadata: Map[String, String] = Map.empty,
  enabled: Boolean = true
) extends SourceConfig

/**
 * Database source configuration for SQL ingestion.
 *
 * @param name Source name
 * @param url JDBC connection URL
 * @param user Database user
 * @param password Database password
 * @param query SQL query to fetch documents. Must return: id (text), content (text), and optionally updated_at (timestamp)
 * @param idColumn Column name for document ID (default: "id")
 * @param contentColumn Column name for document content (default: "content")
 * @param updatedAtColumn Optional column for tracking updates (enables incremental sync)
 * @param metadata Additional metadata to attach to all documents
 * @param enabled Whether this source is enabled
 */
final case class DatabaseSourceConfig(
  name: String,
  url: String,
  user: String,
  password: String,
  query: String,
  idColumn: String = "id",
  contentColumn: String = "content",
  updatedAtColumn: Option[String] = None,
  metadata: Map[String, String] = Map.empty,
  enabled: Boolean = true
) extends SourceConfig

/**
 * Overall ingestion configuration.
 */
final case class IngestionConfig(
  enabled: Boolean = false,
  runOnStartup: Boolean = false,
  schedule: Option[String] = None, // Cron expression
  sources: Seq[SourceConfig] = Seq.empty
)

object IngestionConfig {

  /**
   * Load ingestion configuration from Typesafe Config.
   */
  def fromConfig(config: Config): IngestionConfig = {
    val ingestionPath = "ingestion"

    if (!config.hasPath(ingestionPath)) {
      return IngestionConfig()
    }

    val ingestion = config.getConfig(ingestionPath)

    val enabled = Try(ingestion.getBoolean("enabled")).getOrElse(false)
    val runOnStartup = Try(ingestion.getBoolean("run-on-startup")).getOrElse(false)
    val schedule = Try(ingestion.getString("schedule")).toOption

    val sources = if (ingestion.hasPath("sources")) {
      ingestion.getConfigList("sources").asScala.toSeq.flatMap(parseSource)
    } else {
      Seq.empty
    }

    IngestionConfig(
      enabled = enabled,
      runOnStartup = runOnStartup,
      schedule = schedule,
      sources = sources
    )
  }

  /**
   * Load from environment variables.
   * INGEST_DIR=/path/to/docs
   * INGEST_PATTERNS=*.md,*.txt
   * INGEST_RECURSIVE=true
   */
  def fromEnv(): IngestionConfig = {
    val dirPath = sys.env.get("INGEST_DIR")
    val patterns = sys.env.get("INGEST_PATTERNS")
      .map(_.split(",").map(_.trim).toSet)
      .getOrElse(Set("*.md", "*.txt", "*.pdf"))
    val recursive = sys.env.get("INGEST_RECURSIVE")
      .map(_.toLowerCase == "true")
      .getOrElse(true)
    val runOnStartup = sys.env.get("INGEST_ON_STARTUP")
      .map(_.toLowerCase == "true")
      .getOrElse(false)
    val schedule = sys.env.get("INGEST_SCHEDULE").filter(_.nonEmpty)

    val sources = dirPath.map { path =>
      DirectorySourceConfig(
        name = "env-directory",
        path = path,
        patterns = patterns,
        recursive = recursive
      )
    }.toSeq

    IngestionConfig(
      enabled = sources.nonEmpty,
      runOnStartup = runOnStartup,
      schedule = schedule,
      sources = sources
    )
  }

  private def parseSource(config: Config): Option[SourceConfig] = {
    val sourceType = Try(config.getString("type")).getOrElse("directory")
    val name = Try(config.getString("name")).getOrElse(sourceType)
    val enabled = Try(config.getBoolean("enabled")).getOrElse(true)
    val metadata = Try {
      config.getConfig("metadata").entrySet().asScala.map { entry =>
        entry.getKey -> entry.getValue.unwrapped().toString
      }.toMap
    }.getOrElse(Map.empty)

    sourceType match {
      case "directory" =>
        val path = config.getString("path")
        val patterns = Try(config.getStringList("patterns").asScala.toSet)
          .getOrElse(Set("*.md", "*.txt", "*.pdf"))
        val recursive = Try(config.getBoolean("recursive")).getOrElse(true)
        val maxDepth = Try(config.getInt("max-depth")).getOrElse(10)

        Some(DirectorySourceConfig(
          name = name,
          path = path,
          patterns = patterns,
          recursive = recursive,
          maxDepth = maxDepth,
          metadata = metadata,
          enabled = enabled
        ))

      case "url" =>
        val urls = Try(config.getStringList("urls").asScala.toSeq)
          .getOrElse(Seq.empty)

        Some(UrlSourceConfig(
          name = name,
          urls = urls,
          metadata = metadata,
          enabled = enabled
        ))

      case "database" =>
        val url = Try(config.getString("url")).getOrElse("")
        val user = Try(config.getString("user")).getOrElse("")
        val password = Try(config.getString("password")).getOrElse("")
        val query = Try(config.getString("query")).getOrElse("")
        val idColumn = Try(config.getString("id-column")).getOrElse("id")
        val contentColumn = Try(config.getString("content-column")).getOrElse("content")
        val updatedAtColumn = Try(config.getString("updated-at-column")).toOption

        if (url.nonEmpty && query.nonEmpty) {
          Some(DatabaseSourceConfig(
            name = name,
            url = url,
            user = user,
            password = password,
            query = query,
            idColumn = idColumn,
            contentColumn = contentColumn,
            updatedAtColumn = updatedAtColumn,
            metadata = metadata,
            enabled = enabled
          ))
        } else {
          None
        }

      case _ =>
        None
    }
  }
}
