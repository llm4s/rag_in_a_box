package org.llm4s.ragbox.ingestion

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
 * S3 source configuration for ingesting documents from AWS S3.
 *
 * NOTE: S3 ingestion currently only supports text-based file formats.
 * Binary formats like PDF, DOCX, images etc. are NOT supported and will
 * produce garbage content if included. Use DirectorySourceConfig with
 * local files for binary format support.
 *
 * @param name Source name
 * @param bucket S3 bucket name
 * @param prefix Key prefix to filter objects (e.g., "documents/")
 * @param region AWS region (e.g., "us-east-1")
 * @param accessKeyId AWS access key ID (optional, uses default credentials if not set)
 * @param secretAccessKey AWS secret access key (optional, uses default credentials if not set)
 * @param roleArn IAM role ARN to assume (optional, for cross-account access)
 * @param patterns File patterns to include (glob syntax, e.g., "*.md", "*.txt").
 *                 Only text formats are supported (md, txt, json, xml, html, csv, etc.)
 * @param maxKeys Maximum number of objects to fetch per request
 * @param metadata Additional metadata to attach to all documents
 * @param enabled Whether this source is enabled
 */
final case class S3SourceConfig(
  name: String,
  bucket: String,
  prefix: String = "",
  region: String = "us-east-1",
  accessKeyId: Option[String] = None,
  secretAccessKey: Option[String] = None,
  roleArn: Option[String] = None,
  patterns: Set[String] = Set("*.md", "*.txt", "*.json", "*.xml", "*.html", "*.csv"),
  maxKeys: Int = 1000,
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
 * Web crawler source configuration for crawling websites.
 *
 * @param name Source name
 * @param seedUrls Starting URLs to crawl from
 * @param maxDepth Maximum link depth to follow (0 = seed URLs only)
 * @param maxPages Maximum total pages to crawl
 * @param followPatterns URL patterns to follow (glob syntax with * wildcards)
 * @param excludePatterns URL patterns to exclude
 * @param respectRobotsTxt Whether to respect robots.txt directives
 * @param delayMs Delay between requests in milliseconds (rate limiting)
 * @param timeoutMs HTTP request timeout in milliseconds
 * @param sameDomainOnly Whether to restrict crawling to seed domains
 * @param metadata Additional metadata to attach to all documents
 * @param enabled Whether this source is enabled
 */
final case class WebCrawlerSourceConfig(
  name: String,
  seedUrls: Seq[String],
  maxDepth: Int = 3,
  maxPages: Int = 500,
  followPatterns: Seq[String] = Seq("*"),
  excludePatterns: Seq[String] = Seq.empty,
  respectRobotsTxt: Boolean = true,
  delayMs: Int = 500,
  timeoutMs: Int = 30000,
  sameDomainOnly: Boolean = true,
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

      case "s3" =>
        val bucket = Try(config.getString("bucket")).getOrElse("")
        val prefix = Try(config.getString("prefix")).getOrElse("")
        val region = Try(config.getString("region")).getOrElse("us-east-1")
        val accessKeyId = Try(config.getString("access-key-id")).toOption
        val secretAccessKey = Try(config.getString("secret-access-key")).toOption
        val roleArn = Try(config.getString("role-arn")).toOption
        // Only text formats are supported - binary formats like PDF will produce garbage
        val patterns = Try(config.getStringList("patterns").asScala.toSet)
          .getOrElse(Set("*.md", "*.txt", "*.json", "*.xml", "*.html", "*.csv"))
        val maxKeys = Try(config.getInt("max-keys")).getOrElse(1000)

        if (bucket.nonEmpty) {
          Some(S3SourceConfig(
            name = name,
            bucket = bucket,
            prefix = prefix,
            region = region,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            roleArn = roleArn,
            patterns = patterns,
            maxKeys = maxKeys,
            metadata = metadata,
            enabled = enabled
          ))
        } else {
          None
        }

      case "web" | "crawler" | "web-crawler" =>
        val seedUrls = Try(config.getStringList("seed-urls").asScala.toSeq)
          .orElse(Try(config.getStringList("urls").asScala.toSeq))
          .getOrElse(Seq.empty)
        val maxDepth = Try(config.getInt("max-depth")).getOrElse(3)
        val maxPages = Try(config.getInt("max-pages")).getOrElse(500)
        val followPatterns = Try(config.getStringList("follow-patterns").asScala.toSeq)
          .getOrElse(Seq("*"))
        val excludePatterns = Try(config.getStringList("exclude-patterns").asScala.toSeq)
          .getOrElse(Seq.empty)
        val respectRobotsTxt = Try(config.getBoolean("respect-robots-txt")).getOrElse(true)
        val delayMs = Try(config.getInt("delay-ms")).getOrElse(500)
        val timeoutMs = Try(config.getInt("timeout-ms")).getOrElse(30000)
        val sameDomainOnly = Try(config.getBoolean("same-domain-only")).getOrElse(true)

        if (seedUrls.nonEmpty) {
          Some(WebCrawlerSourceConfig(
            name = name,
            seedUrls = seedUrls,
            maxDepth = maxDepth,
            maxPages = maxPages,
            followPatterns = followPatterns,
            excludePatterns = excludePatterns,
            respectRobotsTxt = respectRobotsTxt,
            delayMs = delayMs,
            timeoutMs = timeoutMs,
            sameDomainOnly = sameDomainOnly,
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
