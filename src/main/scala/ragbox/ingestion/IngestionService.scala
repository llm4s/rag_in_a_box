package ragbox.ingestion

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.llm4s.rag.RAG
import org.llm4s.rag.loader.{DirectoryLoader, UrlLoader, DocumentLoader, LoadStats, SyncStats}
import ragbox.model._

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
        nextScheduledRun = None // TODO: Calculate from cron schedule
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
