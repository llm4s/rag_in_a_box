package org.llm4s.ragbox.ingestion

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import org.llm4s.error.NetworkError
import org.llm4s.rag.loader.{CrawlerConfig, UrlLoader, WebCrawlerLoader, DocumentLoader, LoadResult}
import org.llm4s.rag.loader.s3.S3Loader
import org.llm4s.ragbox.service.RAGService
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}

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
 * Progress notification for one source run, published to [[IngestionService]]'s listener
 * (the server broadcasts them to WebSocket clients as `ingestion_progress`).
 *
 * @param status   `running`, `completed` or `failed`
 * @param progress 0.0 when a run starts, 1.0 when it ends
 */
final case class IngestionEvent(
  source: String,
  status: String,
  progress: Double,
  message: Option[String] = None
)

object IngestionEvent {
  def started(source: String): IngestionEvent = IngestionEvent(source, "running", 0.0)

  def finished(result: IngestionResult): IngestionEvent = result.error match {
    case Some(error) => IngestionEvent(result.sourceName, "failed", 1.0, Some(error))
    case None =>
      IngestionEvent(
        result.sourceName,
        "completed",
        1.0,
        Some(
          s"added=${result.documentsAdded}, updated=${result.documentsUpdated}, " +
            s"deleted=${result.documentsDeleted}, unchanged=${result.documentsUnchanged}, failed=${result.documentsFailed}"
        )
      )
  }
}

/**
 * Raised when an ingestion run is requested while another is still in progress.
 * The ingestion routes map it to 409 Conflict.
 */
final case class IngestionAlreadyRunning() extends RuntimeException(IngestionAlreadyRunning.Message)

object IngestionAlreadyRunning {
  val Message: String = "An ingestion run is already in progress"
}

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
 *
 * '''Ingestion policy.''' Directories are read only under the allowed roots
 * ([[IngestionConfig.allowedRoots]]), and the walk never follows a symlink out of the directory.
 * URLs must be http(s), on an allowed host, and resolve to public addresses ([[UrlPolicy]]).
 * The one-shot methods fail with [[IngestionRequestRejected]] when a request breaks the policy.
 */
class IngestionService(
  ragService: RAGService,
  config: IngestionConfig,
  listener: IngestionEvent => IO[Unit] = _ => IO.unit
) {

  private val logger = LoggerFactory.getLogger(getClass)

  private val pathPolicy = new PathPolicy(config.allowedRoots)
  private val urlPolicy = new UrlPolicy(config.policy.url)

  private val status: Ref[IO, IngestionStatus] = Ref.unsafe(
    IngestionStatus(
      running = false,
      lastRun = None,
      lastResults = Seq.empty,
      nextScheduledRun = None
    )
  )

  /** Record when the schedule ([[IngestionScheduler]]) will next run, for the status endpoint. */
  def recordNextRun(next: Option[Instant]): IO[Unit] =
    status.update(_.copy(nextScheduledRun = next))

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
            status.update(_.copy(lastRun = Some(now), lastResults = results))
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
          directoryLoader(dir).flatMap(syncSource(dir.name, dir.name, "directory", _, removeUnlisted = true, startTime))
        case url: UrlSourceConfig =>
          urlSourceLoader(url).flatMap(syncSource(url.name, url.name, "url", _, removeUnlisted = false, startTime))
        case db: DatabaseSourceConfig =>
          runDatabaseIngestion(db, startTime)
        case web: WebCrawlerSourceConfig =>
          webLoader(web).flatMap(syncSource(web.name, web.name, "web", _, removeUnlisted = true, startTime))
        case s3: S3SourceConfig =>
          syncSource(s3.name, s3.name, "s3", IngestionService.s3Loader(s3), removeUnlisted = true, startTime)
      }
      reporting(source.name)(run.handleError(failedResult(source.name, sourceTypeOf(source), startTime)))
    }

  /** Publish start/finish events around a source run. A failing listener never fails ingestion. */
  private def reporting(sourceName: String)(run: IO[IngestionResult]): IO[IngestionResult] = {
    def publish(event: IngestionEvent): IO[Unit] =
      listener(event).handleError(e => logger.warn(s"Ingestion listener failed: ${e.getMessage}"))
    publish(IngestionEvent.started(sourceName)) *>
      run.guaranteeCase {
        case cats.effect.Outcome.Succeeded(result) => result.flatMap(r => publish(IngestionEvent.finished(r)))
        case cats.effect.Outcome.Errored(e) => publish(IngestionEvent(sourceName, "failed", 1.0, Option(e.getMessage)))
        case cats.effect.Outcome.Canceled() => publish(IngestionEvent(sourceName, "failed", 1.0, Some("cancelled")))
      }
  }

  private def sourceTypeOf(source: SourceConfig): String = source match {
    case _: DirectorySourceConfig => "directory"
    case _: UrlSourceConfig => "url"
    case _: DatabaseSourceConfig => "database"
    case _: WebCrawlerSourceConfig => "web"
    case _: S3SourceConfig => "s3"
  }

  /**
   * A loader for the directory at `path`, if the policy allows it: under an allowed root, with
   * valid patterns. Fails with [[IngestionRequestRejected]] otherwise.
   */
  private def confinedDirectoryLoader(
    path: String,
    patterns: Set[String],
    recursive: Boolean,
    metadata: Map[String, String],
    maxDepth: Int
  ): IO[DocumentLoader] =
    IO.blocking {
      for {
        extensions <- PathPolicy.checkPatterns(patterns)
        allowed <- pathPolicy.checkDirectory(path)
      } yield ConfinedDirectoryLoader(allowed.path, allowed.real, extensions, recursive, metadata, maxDepth)
    }.flatMap(_.fold(reason => IO.raiseError(IngestionRequestRejected(reason)), loader => IO.pure(loader)))

  private def directoryLoader(config: DirectorySourceConfig): IO[DocumentLoader] =
    confinedDirectoryLoader(
      config.path,
      config.patterns,
      config.recursive,
      config.metadata + ("source" -> config.name),
      config.maxDepth
    )

  /** Check each URL against the policy: the allowed ones, and a failure for each refused one. */
  private def partitionUrls(urls: Seq[String]): IO[(Seq[String], Seq[(String, String)])] =
    IO.blocking {
      urls.partitionMap(url => urlPolicy.check(url).left.map(url -> _).map(_ => url)).swap
    }

  private def refusals(refused: Seq[(String, String)], documentIds: Boolean): DocumentLoader =
    FixedResultsLoader(refused.map { case (url, reason) =>
      logger.warn(s"Ingestion policy refused URL '$url': $reason")
      val error = NetworkError(s"Refused by ingestion policy: $reason", None, "ingestion-policy")
      // Naming the document keeps its previously ingested version
      if (documentIds) LoadResult.failure(url, error, documentId = url) else LoadResult.failure(url, error)
    })

  /** The configured URLs the policy allows; each refused one is reported as a failure. */
  private def urlSourceLoader(config: UrlSourceConfig): IO[DocumentLoader] =
    partitionUrls(config.urls).map { case (allowed, refused) =>
      UrlLoader(urls = allowed, metadata = config.metadata + ("source" -> config.name)) ++
        refusals(refused, documentIds = true)
    }

  /**
   * Crawls websites from the seed URLs the policy allows; sync is incremental by URL and content
   * hash. llm4s checks the address of every page and redirect it fetches; pages on hosts outside
   * the allowed hosts list are skipped (and so removed if previously ingested).
   */
  private def webLoader(config: WebCrawlerSourceConfig): IO[DocumentLoader] =
    partitionUrls(config.seedUrls).flatMap { case (allowed, refused) =>
      if (allowed.isEmpty)
        IO.raiseError(IngestionRequestRejected(
          refused.headOption.fold("No seed URLs configured")(r => s"Seed URL refused by ingestion policy: ${r._2}")
        ))
      else
        IO.pure(FilteredLoader(crawler(config, allowed), hostFilter) ++ refusals(refused, documentIds = false))
    }

  private def hostFilter(success: LoadResult.Success): LoadResult = {
    val url = success.document.metadata.getOrElse("url", success.document.id)
    if (urlPolicy.urlHostAllowed(url)) success
    else LoadResult.skipped(url, "Host is not in the allowed hosts list")
  }

  private def crawler(config: WebCrawlerSourceConfig, seedUrls: Seq[String]): DocumentLoader =
    WebCrawlerLoader(
      seedUrls = seedUrls,
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
      reporting(path) {
        confinedDirectoryLoader(path, patterns, recursive, metadata + ("source" -> "api"), maxDepth = 10).flatMap {
          loader => syncSource(s"api:directory:$path", path, "directory", loader, removeUnlisted = true, startTime)
        }
      }
    }

  /**
   * Ingest URLs (one-shot). Unchanged pages are not re-embedded; nothing is removed.
   */
  def ingestUrls(
    urls: Seq[String],
    metadata: Map[String, String] = Map.empty
  ): IO[IngestionResult] =
    IO.realTimeInstant.flatMap { startTime =>
      for {
        _ <- IO.raiseWhen(urls.isEmpty)(IngestionRequestRejected("At least one URL is required"))
        checked <- partitionUrls(urls)
        _ <- checked._2.headOption.traverse_ { case (url, reason) =>
          IO.raiseError(IngestionRequestRejected(s"URL '${url.take(UrlPolicy.MaxUrlLength)}' refused: $reason"))
        }
        loader = UrlLoader(urls = urls, metadata = metadata + ("source" -> "api"))
        result <- reporting("url-ingest")(syncSource("api:url", "url-ingest", "url", loader, removeUnlisted = false, startTime))
      } yield result
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

  def apply(
    ragService: RAGService,
    config: IngestionConfig,
    listener: IngestionEvent => IO[Unit] = _ => IO.unit
  ): IngestionService =
    new IngestionService(ragService, config, listener)

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
