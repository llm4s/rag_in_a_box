package org.llm4s.ragbox.ingestion

import com.typesafe.config.Config
import org.llm4s.security.Secret

import java.time.ZoneId
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
  patterns: Set[String] = DirectorySourceConfig.DefaultPatterns,
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

object DirectorySourceConfig {
  val DefaultPatterns: Set[String] = Set("*.md", "*.txt", "*.pdf")
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
 * S3 source configuration for ingesting documents from AWS S3.
 *
 * Supports multi-format document extraction including PDF, DOCX, DOC, and text formats
 * via the llm4s document extraction pipeline (Apache PDFBox, POI, Tika).
 *
 * @param name Source name
 * @param bucket S3 bucket name
 * @param prefix Key prefix to filter objects (e.g., "documents/")
 * @param region AWS region (e.g., "us-east-1")
 * @param accessKeyId AWS access key ID (optional, uses default credentials if not set)
 * @param secretAccessKey AWS secret access key (optional, uses default credentials if not set)
 * @param patterns File patterns to include (glob syntax, e.g., "*.md", "*.pdf", "*.docx")
 * @param metadata Additional metadata to attach to all documents
 * @param enabled Whether this source is enabled
 */
final case class S3SourceConfig(
  name: String,
  bucket: String,
  prefix: String = "",
  region: String = "us-east-1",
  accessKeyId: Option[Secret] = None,
  secretAccessKey: Option[Secret] = None,
  patterns: Set[String] = Set("*.md", "*.txt", "*.pdf", "*.docx", "*.doc", "*.json", "*.xml", "*.html", "*.csv"),
  metadata: Map[String, String] = Map.empty,
  enabled: Boolean = true
) extends SourceConfig {

  /**
   * Convert glob patterns to extensions for llm4s S3Loader.
   * "*.md" -> "md", "*.txt" -> "txt"
   */
  def extensions: Set[String] =
    patterns.map(_.stripPrefix("*.").stripPrefix("."))
}

/**
 * Overall ingestion configuration.
 */
final case class IngestionConfig(
  enabled: Boolean = false,
  runOnStartup: Boolean = false,
  schedule: Option[String] = None, // see IngestionSchedule.parse
  sources: Seq[SourceConfig] = Seq.empty,
  policy: IngestionPolicyConfig = IngestionPolicyConfig(),
  scheduleTimeZone: Option[String] = None // INGEST_SCHEDULE_TZ; the server's time zone when None
) {

  /**
   * The time zone cron schedules are evaluated in: [[scheduleTimeZone]] (an IANA name such as
   * `Europe/London`, `UTC`, or an offset such as `+02:00`), or the server's time zone when it is
   * not set. Left(reason) when it is not a valid time zone.
   */
  def scheduleZone: Either[String, ZoneId] =
    scheduleTimeZone.fold[Either[String, ZoneId]](Right(ZoneId.systemDefault())) { tz =>
      Try(ZoneId.of(tz)).toEither.left.map(_ =>
        s"Invalid INGEST_SCHEDULE_TZ '$tz': use an IANA time zone such as Europe/London or UTC, or an offset such as +02:00"
      )
    }

  /** What is wrong with the schedule settings (the time zone, then the schedule); empty when valid. */
  def scheduleErrors: Seq[String] =
    scheduleZone.left.toSeq ++ scheduleZone.toOption.flatMap(_ => parsedSchedule.flatMap(_.left.toOption))

  /**
   * The directories directory ingestion may read under: `policy.allowedRoots` when configured,
   * otherwise the paths of the configured directory sources (so `INGEST_DIR`, when set).
   */
  def allowedRoots: Seq[String] =
    policy.allowedRoots.getOrElse(sources.collect { case d: DirectorySourceConfig => d.path })

  /**
   * The parsed [[schedule]], cron schedules in [[scheduleZone]]: None without one,
   * Some(Left(reason)) when it (or the time zone) is invalid.
   */
  def parsedSchedule: Option[Either[String, IngestionSchedule]] =
    schedule.map(s => scheduleZone.flatMap(IngestionSchedule.parse(s, _)))

  /**
   * One error per source name used by more than one configured source (enabled or not). Each
   * source's documents are tracked by its name, so two sources sharing a name would prune each
   * other's documents on every sync. Empty when the names are unique.
   */
  def duplicateSourceNameErrors: Seq[String] =
    sources.groupBy(_.name).toSeq.filter(_._2.size > 1).sortBy(_._1).map { case (name, dups) =>
      val hint =
        if (name == IngestionConfig.EnvDirectorySourceName)
          s" ('$name' is the name of the INGEST_DIR source: rename the configured source or unset INGEST_DIR)"
        else if (IngestionConfig.SourceTypeNames.contains(name))
          s" (a source without a name is named after its type, '$name': give each source a unique name)"
        else ""
      s"Ingestion source name '$name' is used by ${dups.size} sources; source names must be unique$hint"
    }

  /** Sources a run ingests from. */
  def activeSources: Seq[SourceConfig] = if (enabled) sources.filter(_.enabled) else Seq.empty
}

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
    val schedule = Try(ingestion.getString("schedule")).toOption.map(_.trim).filter(_.nonEmpty)
    val scheduleTimeZone = Try(ingestion.getString("schedule-time-zone")).toOption.map(_.trim).filter(_.nonEmpty)

    val sources = if (ingestion.hasPath("sources")) {
      ingestion.getConfigList("sources").asScala.toSeq.flatMap(parseSource)
    } else {
      Seq.empty
    }

    IngestionConfig(
      enabled = enabled,
      runOnStartup = runOnStartup,
      schedule = schedule,
      sources = sources,
      policy = policyFromConfig(config),
      scheduleTimeZone = scheduleTimeZone
    )
  }

  /**
   * Load the ingestion policy (allowed roots, URL restrictions) from Typesafe Config.
   *
   * `ingestion.allowed-roots` and `ingestion.url.allowed-hosts` are lists or comma-separated
   * strings; a blank value means "not set".
   */
  def policyFromConfig(config: Config): IngestionPolicyConfig = {
    def list(path: String): Option[Seq[String]] =
      if (!config.hasPath(path)) None
      else
        Try(config.getStringList(path).asScala.toSeq)
          .orElse(Try(config.getString(path).split(",").toSeq))
          .toOption
          .map(_.map(_.trim).filter(_.nonEmpty))
          .filter(_.nonEmpty)

    IngestionPolicyConfig(
      allowedRoots = list("ingestion.allowed-roots"),
      url = UrlPolicyConfig(
        allowedHosts = list("ingestion.url.allowed-hosts").getOrElse(Seq.empty),
        allowPrivateNetworks = Try(config.getBoolean("ingestion.url.allow-private-networks")).getOrElse(false)
      )
    )
  }

  /**
   * Apply the environment variables to `base` (the HOCON `ingestion` section):
   *
   *  - `INGEST_DIR` adds a directory source named `env-directory` (with `INGEST_PATTERNS` and
   *    `INGEST_RECURSIVE`) to the configured sources and enables ingestion;
   *  - `INGEST_ON_STARTUP` and `INGEST_SCHEDULE` set `run-on-startup` and `schedule`, which apply
   *    to every configured source, whether or not `INGEST_DIR` is set;
   *  - `INGEST_SCHEDULE_TZ` sets `schedule-time-zone`, the time zone of a cron schedule.
   */
  def withEnv(base: IngestionConfig, env: Map[String, String] = sys.env): IngestionConfig = {
    def value(name: String): Option[String] = env.get(name).map(_.trim).filter(_.nonEmpty)

    val envDirectory = value("INGEST_DIR").map { path =>
      DirectorySourceConfig(
        name = EnvDirectorySourceName,
        path = path,
        patterns = value("INGEST_PATTERNS").map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)
          .getOrElse(DirectorySourceConfig.DefaultPatterns),
        recursive = value("INGEST_RECURSIVE").forall(_.equalsIgnoreCase("true"))
      )
    }

    base.copy(
      enabled = base.enabled || envDirectory.isDefined,
      runOnStartup = value("INGEST_ON_STARTUP").map(_.equalsIgnoreCase("true")).getOrElse(base.runOnStartup),
      schedule = value("INGEST_SCHEDULE").orElse(base.schedule),
      scheduleTimeZone = value("INGEST_SCHEDULE_TZ").orElse(base.scheduleTimeZone),
      // Appended, not replacing a configured source of the same name: that is a duplicate name,
      // reported by duplicateSourceNameErrors, rather than a HOCON source silently dropped
      sources = base.sources ++ envDirectory
    )
  }

  /** The `type` values of a configured source: an unnamed source is named after its type. */
  private[ingestion] val SourceTypeNames: Set[String] = Set("directory", "url", "database", "web", "crawler", "web-crawler", "s3")

  /** Name of the directory source `INGEST_DIR` adds. */
  val EnvDirectorySourceName: String = "env-directory"

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

      case "s3" =>
        val bucket = Try(config.getString("bucket")).getOrElse("")
        val prefix = Try(config.getString("prefix")).getOrElse("")
        val region = Try(config.getString("region")).getOrElse("us-east-1")
        val accessKeyId = Try(config.getString("access-key-id")).toOption.map(Secret(_))
        val secretAccessKey = Try(config.getString("secret-access-key")).toOption.map(Secret(_))
        val patterns = Try(config.getStringList("patterns").asScala.toSet)
          .getOrElse(Set("*.md", "*.txt", "*.pdf", "*.docx", "*.doc", "*.json", "*.xml", "*.html", "*.csv"))

        if (bucket.nonEmpty) {
          Some(S3SourceConfig(
            name = name,
            bucket = bucket,
            prefix = prefix,
            region = region,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            patterns = patterns,
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
