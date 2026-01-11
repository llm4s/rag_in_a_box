package org.llm4s.ragbox.ingestion

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.llm4s.rag.RAG
import org.llm4s.rag.loader.{CrawlerConfig, DirectoryLoader, UrlLoader, WebCrawlerLoader, DocumentLoader, LoadStats, SyncStats}
import org.llm4s.rag.loader.s3.S3Loader
import org.llm4s.ragbox.model._

import java.io.File
import java.sql.{Connection, DriverManager, ResultSet}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.atomic.AtomicReference
import scala.util.Using

/**
 * Result of an ingestion operation.
 */
final case class IngestionResult(
  sourceName: String,
  sourceType: String,
  documentsAdded: Int,
  documentsUpdated: Int,
  documentsDeleted: Int,
  documentsUnchanged: Int,
  documentsFailed: Int,
  startTime: Instant,
  endTime: Instant,
  error: Option[String] = None
) {
  def duration: Long = endTime.toEpochMilli - startTime.toEpochMilli
  def totalProcessed: Int = documentsAdded + documentsUpdated + documentsUnchanged + documentsFailed
  def isSuccess: Boolean = error.isEmpty
}

/**
 * Overall status of ingestion.
 */
final case class IngestionStatus(
  running: Boolean,
  lastRun: Option[Instant],
  lastResults: Seq[IngestionResult],
  nextScheduledRun: Option[Instant]
)

/**
 * Service for managing document ingestion from configured sources.
 */
class IngestionService(
  rag: IO[RAG],
  config: IngestionConfig
) {

  private val status: AtomicReference[IngestionStatus] = new AtomicReference(
    IngestionStatus(
      running = false,
      lastRun = None,
      lastResults = Seq.empty,
      nextScheduledRun = None
    )
  )

  /**
   * Calculate the next scheduled run time based on the schedule configuration.
   * Returns None if no schedule is configured.
   */
  private def calculateNextRun(): Option[Instant] = {
    config.schedule.flatMap(parseScheduleToMillis).map { millis =>
      Instant.now().plusMillis(millis)
    }
  }

  /**
   * Parse a schedule string to milliseconds.
   * Supports: "hourly", "daily", "weekly", simple durations like "5m", "1h", "6h", "1d",
   * and basic cron patterns.
   */
  private def parseScheduleToMillis(schedule: String): Option[Long] = {
    val trimmed = schedule.trim.toLowerCase
    trimmed match {
      case "hourly" => Some(60L * 60 * 1000)
      case "daily" => Some(24L * 60 * 60 * 1000)
      case "weekly" => Some(7L * 24 * 60 * 60 * 1000)
      case _ =>
        // Try parsing as duration (e.g., "5m", "1h", "6h", "1d")
        val pattern = """(\d+)\s*(s|m|h|d)""".r
        trimmed match {
          case pattern(num, unit) =>
            val n = num.toLong
            unit match {
              case "s" => Some(n * 1000)
              case "m" => Some(n * 60 * 1000)
              case "h" => Some(n * 60 * 60 * 1000)
              case "d" => Some(n * 24 * 60 * 60 * 1000)
              case _ => None
            }
          case _ =>
            // Try basic cron patterns
            parseCronToMillis(schedule)
        }
    }
  }

  /**
   * Parse simple cron expressions to milliseconds.
   */
  private def parseCronToMillis(cron: String): Option[Long] = {
    val parts = cron.split("\\s+")
    if (parts.length >= 5) {
      (parts(0), parts(1), parts(2), parts(3), parts(4)) match {
        case ("0", "*", "*", "*", "*") => Some(60L * 60 * 1000)      // Every hour
        case ("0", "0", "*", "*", "*") => Some(24L * 60 * 60 * 1000) // Daily at midnight
        case ("0", "*/2", "*", "*", "*") => Some(2L * 60 * 60 * 1000)
        case ("0", "*/4", "*", "*", "*") => Some(4L * 60 * 60 * 1000)
        case ("0", "*/6", "*", "*", "*") => Some(6L * 60 * 60 * 1000)
        case ("0", "*/12", "*", "*", "*") => Some(12L * 60 * 60 * 1000)
        case _ => None
      }
    } else {
      None
    }
  }

  /**
   * Run ingestion from all configured sources.
   */
  def runAll(): IO[Seq[IngestionResult]] = {
    if (!config.enabled) {
      return IO.pure(Seq.empty)
    }

    for {
      _ <- IO(status.updateAndGet(_.copy(running = true)))
      results <- config.sources.filter(_.enabled).traverse(runSourceConfig)
      now = Instant.now()
      _ <- IO(status.set(IngestionStatus(
        running = false,
        lastRun = Some(now),
        lastResults = results,
        nextScheduledRun = calculateNextRun()
      )))
    } yield results
  }

  /**
   * Run ingestion from a single source by name.
   */
  def runSource(sourceName: String): IO[Option[IngestionResult]] = {
    config.sources.find(_.name == sourceName) match {
      case Some(source) => runSourceConfig(source).map(Some(_))
      case None => IO.pure(None)
    }
  }

  /**
   * Run ingestion from a specific source configuration.
   */
  private def runSourceConfig(source: SourceConfig): IO[IngestionResult] = {
    val startTime = Instant.now()

    source match {
      case dir: DirectorySourceConfig =>
        runDirectoryIngestion(dir, startTime)
      case url: UrlSourceConfig =>
        runUrlIngestion(url, startTime)
      case db: DatabaseSourceConfig =>
        runDatabaseIngestion(db, startTime)
      case web: WebCrawlerSourceConfig =>
        runWebCrawlerIngestion(web, startTime)
      case s3: S3SourceConfig =>
        runS3Ingestion(s3, startTime)
    }
  }

  /**
   * Run directory ingestion with sync (incremental).
   */
  private def runDirectoryIngestion(
    config: DirectorySourceConfig,
    startTime: Instant
  ): IO[IngestionResult] = {
    rag.flatMap { r =>
      val loader = DirectoryLoader(
        path = new File(config.path).toPath,
        extensions = config.extensions,
        recursive = config.recursive,
        metadata = config.metadata + ("source" -> config.name),
        maxDepth = config.maxDepth
      )

      IO.fromEither(
        r.sync(loader).map { stats =>
          IngestionResult(
            sourceName = config.name,
            sourceType = "directory",
            documentsAdded = stats.added,
            documentsUpdated = stats.updated,
            documentsDeleted = stats.deleted,
            documentsUnchanged = stats.unchanged,
            documentsFailed = 0,
            startTime = startTime,
            endTime = Instant.now()
          )
        }.left.map(e => new RuntimeException(e.message))
      ).handleError { e =>
        IngestionResult(
          sourceName = config.name,
          sourceType = "directory",
          documentsAdded = 0,
          documentsUpdated = 0,
          documentsDeleted = 0,
          documentsUnchanged = 0,
          documentsFailed = 0,
          startTime = startTime,
          endTime = Instant.now(),
          error = Some(e.getMessage)
        )
      }
    }
  }

  /**
   * Run URL ingestion (full ingest, not incremental).
   */
  private def runUrlIngestion(
    config: UrlSourceConfig,
    startTime: Instant
  ): IO[IngestionResult] = {
    rag.flatMap { r =>
      val loader = UrlLoader(
        urls = config.urls,
        metadata = config.metadata + ("source" -> config.name)
      )

      IO.fromEither(
        r.ingest(loader).map { stats =>
          IngestionResult(
            sourceName = config.name,
            sourceType = "url",
            documentsAdded = stats.successful,
            documentsUpdated = 0,
            documentsDeleted = 0,
            documentsUnchanged = 0,
            documentsFailed = stats.failed,
            startTime = startTime,
            endTime = Instant.now()
          )
        }.left.map(e => new RuntimeException(e.message))
      ).handleError { e =>
        IngestionResult(
          sourceName = config.name,
          sourceType = "url",
          documentsAdded = 0,
          documentsUpdated = 0,
          documentsDeleted = 0,
          documentsUnchanged = 0,
          documentsFailed = 0,
          startTime = startTime,
          endTime = Instant.now(),
          error = Some(e.getMessage)
        )
      }
    }
  }

  /**
   * Run database ingestion.
   *
   * Fetches rows from a SQL query and ingests each row as a document.
   * Uses document ID from the query results for idempotent upserts.
   */
  private def runDatabaseIngestion(
    config: DatabaseSourceConfig,
    startTime: Instant
  ): IO[IngestionResult] = {
    IO.blocking {
      // Load JDBC driver (PostgreSQL is bundled, others need to be on classpath)
      Class.forName("org.postgresql.Driver")

      val props = new Properties()
      if (config.user.nonEmpty) props.setProperty("user", config.user)
      if (config.password.nonEmpty) props.setProperty("password", config.password)

      var added = 0
      var updated = 0
      var failed = 0

      Using(DriverManager.getConnection(config.url, props)) { conn =>
        Using(conn.prepareStatement(config.query)) { stmt =>
          Using(stmt.executeQuery()) { rs =>
            val ragInstance = rag.unsafeRunSync()

            while (rs.next()) {
              val docId = rs.getString(config.idColumn)
              val content = rs.getString(config.contentColumn)

              if (docId != null && content != null && content.nonEmpty) {
                val metadata = config.metadata +
                  ("source" -> config.name) +
                  ("source_type" -> "database") +
                  ("db_id" -> docId)

                try {
                  ragInstance.ingestText(content, s"${config.name}:$docId", metadata) match {
                    case Right(chunks) =>
                      added += 1
                    case Left(e) =>
                      failed += 1
                  }
                } catch {
                  case e: Exception =>
                    failed += 1
                }
              } else {
                failed += 1
              }
            }
          }
        }
      }

      IngestionResult(
        sourceName = config.name,
        sourceType = "database",
        documentsAdded = added,
        documentsUpdated = updated,
        documentsDeleted = 0,
        documentsUnchanged = 0,
        documentsFailed = failed,
        startTime = startTime,
        endTime = Instant.now()
      )
    }.handleError { e =>
      IngestionResult(
        sourceName = config.name,
        sourceType = "database",
        documentsAdded = 0,
        documentsUpdated = 0,
        documentsDeleted = 0,
        documentsUnchanged = 0,
        documentsFailed = 0,
        startTime = startTime,
        endTime = Instant.now(),
        error = Some(e.getMessage)
      )
    }
  }

  /**
   * Run web crawler ingestion.
   *
   * Crawls websites starting from seed URLs and ingests discovered pages.
   * Uses sync for incremental updates based on URL and content hash.
   */
  private def runWebCrawlerIngestion(
    config: WebCrawlerSourceConfig,
    startTime: Instant
  ): IO[IngestionResult] = {
    rag.flatMap { r =>
      // Build CrawlerConfig from source config
      val crawlerConfig = CrawlerConfig(
        maxDepth = config.maxDepth,
        maxPages = config.maxPages,
        followPatterns = config.followPatterns,
        excludePatterns = config.excludePatterns,
        respectRobotsTxt = config.respectRobotsTxt,
        delayMs = config.delayMs,
        timeoutMs = config.timeoutMs,
        sameDomainOnly = config.sameDomainOnly
      )

      // Create WebCrawlerLoader with seed URLs and config
      val loader = WebCrawlerLoader(
        seedUrls = config.seedUrls,
        config = crawlerConfig,
        metadata = config.metadata + ("source" -> config.name)
      )

      IO.fromEither(
        r.sync(loader).map { stats =>
          IngestionResult(
            sourceName = config.name,
            sourceType = "web",
            documentsAdded = stats.added,
            documentsUpdated = stats.updated,
            documentsDeleted = stats.deleted,
            documentsUnchanged = stats.unchanged,
            documentsFailed = 0,
            startTime = startTime,
            endTime = Instant.now()
          )
        }.left.map(e => new RuntimeException(e.message))
      ).handleError { e =>
        IngestionResult(
          sourceName = config.name,
          sourceType = "web",
          documentsAdded = 0,
          documentsUpdated = 0,
          documentsDeleted = 0,
          documentsUnchanged = 0,
          documentsFailed = 0,
          startTime = startTime,
          endTime = Instant.now(),
          error = Some(e.getMessage)
        )
      }
    }
  }

  /**
   * Run S3 ingestion.
   *
   * Downloads documents from an S3 bucket and ingests them using the llm4s S3Loader.
   * Supports multi-format document extraction (PDF, DOCX, text formats, etc.)
   * Uses sync for incremental updates based on S3 object ETags.
   */
  private def runS3Ingestion(
    config: S3SourceConfig,
    startTime: Instant
  ): IO[IngestionResult] = {
    rag.flatMap { r =>
      // Build S3Loader from llm4s with credentials if provided
      val loader = (config.accessKeyId, config.secretAccessKey) match {
        case (Some(keyId), Some(secret)) =>
          S3Loader.withCredentials(
            bucket = config.bucket,
            accessKeyId = keyId,
            secretAccessKey = secret,
            region = config.region,
            prefix = config.prefix
          )
        case _ =>
          S3Loader(
            bucket = config.bucket,
            prefix = config.prefix,
            region = config.region,
            extensions = config.extensions,
            metadata = config.metadata + ("source" -> config.name)
          )
      }

      IO.fromEither(
        r.sync(loader).map { stats =>
          IngestionResult(
            sourceName = config.name,
            sourceType = "s3",
            documentsAdded = stats.added,
            documentsUpdated = stats.updated,
            documentsDeleted = stats.deleted,
            documentsUnchanged = stats.unchanged,
            documentsFailed = 0,
            startTime = startTime,
            endTime = Instant.now()
          )
        }.left.map(e => new RuntimeException(e.message))
      ).handleError { e =>
        IngestionResult(
          sourceName = config.name,
          sourceType = "s3",
          documentsAdded = 0,
          documentsUpdated = 0,
          documentsDeleted = 0,
          documentsUnchanged = 0,
          documentsFailed = 0,
          startTime = startTime,
          endTime = Instant.now(),
          error = Some(e.getMessage)
        )
      }
    }
  }

  /**
   * Ingest a single directory (one-shot, not using config).
   */
  def ingestDirectory(
    path: String,
    patterns: Set[String] = Set("*.md", "*.txt", "*.pdf"),
    recursive: Boolean = true,
    metadata: Map[String, String] = Map.empty
  ): IO[IngestionResult] = {
    val startTime = Instant.now()
    val extensions = patterns.map(_.stripPrefix("*.").stripPrefix("."))

    rag.flatMap { r =>
      val loader = DirectoryLoader(
        path = new File(path).toPath,
        extensions = extensions,
        recursive = recursive,
        metadata = metadata + ("source" -> "api")
      )

      IO.fromEither(
        r.sync(loader).map { stats =>
          IngestionResult(
            sourceName = path,
            sourceType = "directory",
            documentsAdded = stats.added,
            documentsUpdated = stats.updated,
            documentsDeleted = stats.deleted,
            documentsUnchanged = stats.unchanged,
            documentsFailed = 0,
            startTime = startTime,
            endTime = Instant.now()
          )
        }.left.map(e => new RuntimeException(e.message))
      )
    }
  }

  /**
   * Ingest URLs (one-shot).
   */
  def ingestUrls(
    urls: Seq[String],
    metadata: Map[String, String] = Map.empty
  ): IO[IngestionResult] = {
    val startTime = Instant.now()

    rag.flatMap { r =>
      val loader = UrlLoader(
        urls = urls,
        metadata = metadata + ("source" -> "api")
      )

      IO.fromEither(
        r.ingest(loader).map { stats =>
          IngestionResult(
            sourceName = "url-ingest",
            sourceType = "url",
            documentsAdded = stats.successful,
            documentsUpdated = 0,
            documentsDeleted = 0,
            documentsUnchanged = 0,
            documentsFailed = stats.failed,
            startTime = startTime,
            endTime = Instant.now()
          )
        }.left.map(e => new RuntimeException(e.message))
      )
    }
  }

  /**
   * Get current ingestion status.
   */
  def getStatus: IO[IngestionStatus] = IO(status.get())

  /**
   * List configured sources.
   */
  def listSources: IO[Seq[SourceConfig]] = IO.pure(config.sources)
}

object IngestionService {

  def apply(rag: IO[RAG], config: IngestionConfig): IngestionService =
    new IngestionService(rag, config)
}
