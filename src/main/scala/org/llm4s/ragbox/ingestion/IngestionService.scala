package org.llm4s.ragbox.ingestion

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import org.llm4s.rag.loader.{CrawlerConfig, DirectoryLoader, UrlLoader, WebCrawlerLoader, DocumentLoader, LoadResult}
import org.llm4s.rag.loader.s3.S3Loader
import org.llm4s.ragbox.service.RAGService
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}

import java.io.File
import java.sql.{DriverManager, ResultSet}
import java.time.Instant
import java.util.Properties
import scala.concurrent.duration._

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
 * Raised when an ingestion run is requested while another is still in progress.
 * The ingestion routes map it to 409 Conflict.
 */
final case class IngestionAlreadyRunning()
    extends RuntimeException("An ingestion run is already in progress")

/**
 * Service for managing document ingestion from configured sources.
 *
 * Every document is written through [[RAGService.upsertSourceDocument]], not `RAG.sync`:
 *
 *  - '''Source-scoped sync.''' Each document is recorded in ragbox's persistent registry with
 *    the id of the source that listed it. A source's sync removes only the documents it owns and
 *    no longer lists, so syncing one source never deletes another's documents (`RAG.sync`
 *    removes every registered document the current loader did not list).
 *  - '''Survives restarts.''' Change detection uses the registry's content hashes, which are
 *    in PostgreSQL, so after a restart unchanged documents are not re-embedded, and documents
 *    deleted at the source while the server was down are removed on the next sync (llm4s's
 *    `DocumentRegistry` is in-memory).
 *  - '''Permission mode.''' Documents go into a collection - the default one unless the
 *    source's metadata names another - so permission-filtered queries can retrieve them.
 *
 * Only one run of the configured sources happens at a time: [[runAll]] and [[runSource]] fail
 * with [[IngestionAlreadyRunning]] while a run is in progress.
 */
class IngestionService(
  ragService: RAGService,
  config: IngestionConfig
) {

  private val logger = LoggerFactory.getLogger(getClass)

  private val status: Ref[IO, IngestionStatus] = Ref.unsafe(
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
   * Run `run` while holding the "running" flag, or fail with [[IngestionAlreadyRunning]] if it
   * is held. The flag is cleared however `run` ends: success, error or cancellation.
   */
  private def exclusively[A](run: IO[A]): IO[A] =
    Resource.make(
      status
        .modify(s => if (s.running) (s, false) else (s.copy(running = true), true))
        .flatMap(acquired => IO.raiseUnless(acquired)(IngestionAlreadyRunning()))
    )(_ => status.update(_.copy(running = false))).surround(run)

  /**
   * Run ingestion from all configured sources.
   *
   * @return the result of each enabled source; fails with [[IngestionAlreadyRunning]] if a run
   *         is already in progress
   */
  def runAll(): IO[Seq[IngestionResult]] =
    if (!config.enabled) IO.pure(Seq.empty)
    else
      exclusively {
        config.sources.filter(_.enabled).traverse(runSourceConfig).flatTap { results =>
          IO.realTimeInstant.flatMap { now =>
            status.update(_.copy(lastRun = Some(now), lastResults = results, nextScheduledRun = calculateNextRun()))
          }
        }
      }

  /**
   * Run ingestion from a single source by name.
   *
   * @return None if there is no such source; fails with [[IngestionAlreadyRunning]] if a run is
   *         already in progress
   */
  def runSource(sourceName: String): IO[Option[IngestionResult]] =
    config.sources.find(_.name == sourceName) match {
      case Some(source) => exclusively(runSourceConfig(source)).map(Some(_))
      case None => IO.pure(None)
    }

  /**
   * Run ingestion from a specific source configuration.
   */
  private def runSourceConfig(source: SourceConfig): IO[IngestionResult] =
    IO.realTimeInstant.flatMap { startTime =>
      val run = source match {
        case dir: DirectorySourceConfig =>
          syncSource(dir.name, dir.name, "directory", directoryLoader(dir), removeUnlisted = true, startTime)
        case url: UrlSourceConfig =>
          val loader = UrlLoader(urls = url.urls, metadata = url.metadata + ("source" -> url.name))
          syncSource(url.name, url.name, "url", loader, removeUnlisted = false, startTime)
        case db: DatabaseSourceConfig =>
          runDatabaseIngestion(db, startTime)
        case web: WebCrawlerSourceConfig =>
          syncSource(web.name, web.name, "web", webLoader(web), removeUnlisted = true, startTime)
        case s3: S3SourceConfig =>
          syncSource(s3.name, s3.name, "s3", IngestionService.s3Loader(s3), removeUnlisted = true, startTime)
      }
      run.handleError(failedResult(source.name, sourceTypeOf(source), startTime))
    }

  private def sourceTypeOf(source: SourceConfig): String = source match {
    case _: DirectorySourceConfig => "directory"
    case _: UrlSourceConfig => "url"
    case _: DatabaseSourceConfig => "database"
    case _: WebCrawlerSourceConfig => "web"
    case _: S3SourceConfig => "s3"
  }

  private def directoryLoader(config: DirectorySourceConfig): DocumentLoader =
    DirectoryLoader(
      path = new File(config.path).toPath,
      extensions = config.extensions,
      recursive = config.recursive,
      metadata = config.metadata + ("source" -> config.name),
      maxDepth = config.maxDepth
    )

  /** Crawls websites from the seed URLs; sync is incremental by URL and content hash. */
  private def webLoader(config: WebCrawlerSourceConfig): DocumentLoader =
    WebCrawlerLoader(
      seedUrls = config.seedUrls,
      config = CrawlerConfig(
        maxDepth = config.maxDepth,
        maxPages = config.maxPages,
        followPatterns = config.followPatterns,
        excludePatterns = config.excludePatterns,
        respectRobotsTxt = config.respectRobotsTxt,
        delay = config.delayMs.millis,
        timeout = config.timeoutMs.millis,
        sameDomainOnly = config.sameDomainOnly
      ),
      metadata = config.metadata + ("source" -> config.name)
    )

  /**
   * Run database ingestion.
   *
   * Fetches rows from a SQL query and upserts each row as a document with id
   * `<source name>:<id column>`. Unchanged rows are not re-embedded. Rows no longer returned by
   * the query are not removed.
   */
  private def runDatabaseIngestion(
    config: DatabaseSourceConfig,
    startTime: Instant
  ): IO[IngestionResult] = {
    val rows: Resource[IO, ResultSet] =
      for {
        conn <- Resource.fromAutoCloseable(IO.blocking {
          // Load JDBC driver (PostgreSQL is bundled, others need to be on classpath)
          Class.forName("org.postgresql.Driver")
          val props = new Properties()
          if (config.user.nonEmpty) props.setProperty("user", config.user)
          if (config.password.nonEmpty) props.setProperty("password", config.password)
          DriverManager.getConnection(config.url, props)
        })
        stmt <- Resource.fromAutoCloseable(IO.blocking(conn.prepareStatement(config.query)))
        rs <- Resource.fromAutoCloseable(IO.blocking(stmt.executeQuery()))
      } yield rs

    // Each row is written as it is read, so a large table is never held in memory
    rows.use { rs =>
      def loop(state: SyncState): IO[SyncState] =
        IO.blocking {
          if (rs.next()) Some((Option(rs.getString(config.idColumn)), Option(rs.getString(config.contentColumn))))
          else None
        }.flatMap {
          case None => IO.pure(state)
          case Some((Some(docId), Some(content))) if content.nonEmpty =>
            val metadata = config.metadata +
              ("source" -> config.name) +
              ("source_type" -> "database") +
              ("db_id" -> docId)
            ragService.upsertSourceDocument(config.name, s"${config.name}:$docId", content, metadata).attempt.flatMap {
              case Right(response) => loop(state.record(response.action))
              case Left(_) => loop(state.fail)
            }
          case Some(_) => loop(state.fail)
        }
      loop(SyncState())
    }.map(_.toResult(config.name, "database", startTime, deleted = 0))
  }

  /**
   * Sync one source: upsert every document its loader lists and, with `removeUnlisted`, remove
   * the documents the source owns that it no longer lists.
   *
   * Like `RAG.sync`, removal never deletes what could still exist: a listing failure fails the
   * run and removes nothing; a document listed but unreadable keeps its indexed version; and a
   * read failure that does not name its document skips removal for the run.
   *
   * @param sourceId the owner recorded for the documents; removal only considers its documents
   */
  private def syncSource(
    sourceId: String,
    sourceName: String,
    sourceType: String,
    loader: DocumentLoader,
    removeUnlisted: Boolean,
    startTime: Instant
  ): IO[IngestionResult] =
    for {
      results <- IO.blocking(loader.load())
      state <- {
        def loop(state: SyncState): IO[SyncState] =
          IO.blocking(Option.when(results.hasNext)(results.next())).flatMap {
            case None => IO.pure(state)
            case Some(LoadResult.Success(doc)) if doc.content.trim.isEmpty => loop(state)
            case Some(LoadResult.Success(doc)) =>
              ragService.upsertSourceDocument(sourceId, doc.id, doc.content, doc.metadata).attempt.flatMap {
                case Right(response) => loop(state.seen(doc.id).record(response.action))
                // Not ingested, but still at the source: keep its previous version
                case Left(_) => loop(state.seen(doc.id).fail)
              }
            case Some(failure: LoadResult.Failure) =>
              loop(failure.documentId.fold(state.copy(unnamedFailures = state.unnamedFailures + 1))(state.seen).fail)
            case Some(_: LoadResult.Skipped) => loop(state)
            case Some(LoadResult.ListingFailure(source, error)) =>
              IO.raiseError(new RuntimeException(s"Could not list $source: ${error.message}"))
          }
        loop(SyncState())
      }
      deleted <-
        if (!removeUnlisted) IO.pure(0)
        else if (state.unnamedFailures > 0)
          IO(logger.warn(
            s"$sourceName: not removing unlisted documents: ${state.unnamedFailures} read failure(s) " +
              "do not name their document, so any unlisted document could be one of them"
          )).as(0)
        else removeUnlistedDocuments(sourceId, state.seenIds)
    } yield state.toResult(sourceName, sourceType, startTime, deleted)

  /** Delete the documents `sourceId` owns that are not in `seenIds`; returns how many were deleted. */
  private def removeUnlistedDocuments(sourceId: String, seenIds: Set[String]): IO[Int] =
    ragService.listSourceDocumentIds(sourceId).flatMap { owned =>
      owned.filterNot(seenIds.contains).toList.traverse { id =>
        ragService.deleteDocument(id).attempt.flatTap {
          case Left(e) => IO(logger.warn(s"$sourceId: failed to remove '$id': ${e.getMessage}"))
          case Right(_) => IO.unit
        }
      }.map(_.count(_.isRight))
    }

  /**
   * Ingest a single directory (one-shot, not using config).
   *
   * Synced as its own source, `api:directory:<path>`: files removed from the directory since the
   * last one-shot ingest of the same path are removed, and nothing else is.
   */
  def ingestDirectory(
    path: String,
    patterns: Set[String] = Set("*.md", "*.txt", "*.pdf"),
    recursive: Boolean = true,
    metadata: Map[String, String] = Map.empty
  ): IO[IngestionResult] =
    IO.realTimeInstant.flatMap { startTime =>
      val loader = DirectoryLoader(
        path = new File(path).toPath,
        extensions = patterns.map(_.stripPrefix("*.").stripPrefix(".")),
        recursive = recursive,
        metadata = metadata + ("source" -> "api")
      )
      syncSource(s"api:directory:$path", path, "directory", loader, removeUnlisted = true, startTime)
    }

  /**
   * Ingest URLs (one-shot). Unchanged pages are not re-embedded; nothing is removed.
   */
  def ingestUrls(
    urls: Seq[String],
    metadata: Map[String, String] = Map.empty
  ): IO[IngestionResult] =
    IO.realTimeInstant.flatMap { startTime =>
      val loader = UrlLoader(urls = urls, metadata = metadata + ("source" -> "api"))
      syncSource("api:url", "url-ingest", "url", loader, removeUnlisted = false, startTime)
    }

  /** The result of a source run that failed as a whole. */
  private def failedResult(sourceName: String, sourceType: String, startTime: Instant)(e: Throwable): IngestionResult =
    IngestionResult(
      sourceName = sourceName,
      sourceType = sourceType,
      documentsAdded = 0,
      documentsUpdated = 0,
      documentsDeleted = 0,
      documentsUnchanged = 0,
      documentsFailed = 0,
      startTime = startTime,
      endTime = Instant.now(),
      error = Some(e.getMessage)
    )

  /** Progress of one source run: per-document outcomes and the ids the source listed. */
  private final case class SyncState(
    added: Int = 0,
    updated: Int = 0,
    unchanged: Int = 0,
    failed: Int = 0,
    seenIds: Set[String] = Set.empty,
    unnamedFailures: Int = 0
  ) {
    def seen(id: String): SyncState = copy(seenIds = seenIds + id)

    def record(action: String): SyncState = action match {
      case "updated" => copy(updated = updated + 1)
      case "unchanged" => copy(unchanged = unchanged + 1)
      case _ => copy(added = added + 1)
    }

    def fail: SyncState = copy(failed = failed + 1)

    def toResult(sourceName: String, sourceType: String, startTime: Instant, deleted: Int): IngestionResult =
      IngestionResult(
        sourceName = sourceName,
        sourceType = sourceType,
        documentsAdded = added,
        documentsUpdated = updated,
        documentsDeleted = deleted,
        documentsUnchanged = unchanged,
        documentsFailed = failed,
        startTime = startTime,
        endTime = Instant.now()
      )
  }

  /**
   * Get current ingestion status.
   */
  def getStatus: IO[IngestionStatus] = status.get

  /**
   * List configured sources.
   */
  def listSources: IO[Seq[SourceConfig]] = IO.pure(config.sources)
}

object IngestionService {

  def apply(ragService: RAGService, config: IngestionConfig): IngestionService =
    new IngestionService(ragService, config)

  /**
   * The S3 loader for a source. Static credentials, when configured, are passed to the same
   * loader as the default credential chain would be, so the source's extensions and metadata
   * apply either way (`S3Loader.withCredentials` takes neither).
   */
  private[ingestion] def s3Loader(config: S3SourceConfig): DocumentLoader = {
    val credentials = (config.accessKeyId, config.secretAccessKey) match {
      case (Some(keyId), Some(secret)) =>
        Some(StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId.reveal, secret.reveal)))
      case _ => None
    }
    S3Loader(
      bucket = config.bucket,
      prefix = config.prefix,
      region = config.region,
      extensions = config.extensions,
      credentials = credentials,
      metadata = config.metadata + ("source" -> config.name)
    )
  }
}
