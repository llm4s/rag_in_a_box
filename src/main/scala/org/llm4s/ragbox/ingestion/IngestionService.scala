package org.llm4s.ragbox.ingestion

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.llm4s.rag.RAG
import org.llm4s.rag.loader.{CrawlerConfig, DirectoryLoader, UrlLoader, WebCrawlerLoader, DocumentLoader, LoadStats, SyncStats}
import org.llm4s.ragbox.model._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider, DefaultCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ListObjectsV2Request, S3Object}
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.sql.{Connection, DriverManager, ResultSet}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._
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
   * Lists objects in an S3 bucket (with optional prefix filtering) and ingests
   * text content from files matching the configured patterns.
   *
   * Supports:
   * - Explicit credentials (access key + secret)
   * - Role assumption for cross-account access
   * - Default credential provider chain (IAM roles, environment, config files)
   */
  private def runS3Ingestion(
    config: S3SourceConfig,
    startTime: Instant
  ): IO[IngestionResult] = {
    IO.blocking {
      val s3Client = buildS3Client(config)

      try {
        val ragInstance = rag.unsafeRunSync()
        var added = 0
        var failed = 0
        var continuationToken: Option[String] = None
        var hasMore = true

        while (hasMore) {
          val requestBuilder = ListObjectsV2Request.builder()
            .bucket(config.bucket)
            .maxKeys(config.maxKeys)

          if (config.prefix.nonEmpty) {
            requestBuilder.prefix(config.prefix)
          }

          continuationToken.foreach(token => requestBuilder.continuationToken(token))

          val response = s3Client.listObjectsV2(requestBuilder.build())
          val objects = response.contents().asScala

          for (obj <- objects) {
            val key = obj.key()

            // Check if file matches patterns
            if (matchesPatterns(key, config.extensions)) {
              try {
                val content = downloadObjectAsText(s3Client, config.bucket, key)

                if (content.nonEmpty) {
                  val metadata = config.metadata +
                    ("source" -> config.name) +
                    ("source_type" -> "s3") +
                    ("s3_bucket" -> config.bucket) +
                    ("s3_key" -> key) +
                    ("s3_size" -> obj.size().toString) +
                    ("s3_last_modified" -> obj.lastModified().toString)

                  ragInstance.ingestText(content, s"s3://${config.bucket}/$key", metadata) match {
                    case Right(_) => added += 1
                    case Left(_) => failed += 1
                  }
                }
              } catch {
                case _: Exception => failed += 1
              }
            }
          }

          if (response.isTruncated) {
            continuationToken = Some(response.nextContinuationToken())
          } else {
            hasMore = false
          }
        }

        IngestionResult(
          sourceName = config.name,
          sourceType = "s3",
          documentsAdded = added,
          documentsUpdated = 0,
          documentsDeleted = 0,
          documentsUnchanged = 0,
          documentsFailed = failed,
          startTime = startTime,
          endTime = Instant.now()
        )
      } finally {
        s3Client.close()
      }
    }.handleError { e =>
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

  /**
   * Build an S3 client with appropriate credentials.
   */
  private def buildS3Client(config: S3SourceConfig): S3Client = {
    val region = Region.of(config.region)

    val baseCredentialsProvider = (config.accessKeyId, config.secretAccessKey) match {
      case (Some(accessKey), Some(secretKey)) =>
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(accessKey, secretKey)
        )
      case _ =>
        DefaultCredentialsProvider.create()
    }

    // If role ARN is specified, use role assumption
    val credentialsProvider = config.roleArn match {
      case Some(roleArn) =>
        val stsClient = StsClient.builder()
          .region(region)
          .credentialsProvider(baseCredentialsProvider)
          .build()

        StsAssumeRoleCredentialsProvider.builder()
          .stsClient(stsClient)
          .refreshRequest(
            AssumeRoleRequest.builder()
              .roleArn(roleArn)
              .roleSessionName(s"ragbox-s3-ingestion-${System.currentTimeMillis()}")
              .build()
          )
          .build()

      case None =>
        baseCredentialsProvider
    }

    S3Client.builder()
      .region(region)
      .credentialsProvider(credentialsProvider)
      .build()
  }

  /**
   * Check if a key matches the configured file patterns.
   */
  private def matchesPatterns(key: String, extensions: Set[String]): Boolean = {
    val fileName = key.split("/").lastOption.getOrElse(key)
    val extension = fileName.split("\\.").lastOption.getOrElse("")
    extensions.contains(extension.toLowerCase)
  }

  /**
   * Download an S3 object and return its content as a string.
   */
  private def downloadObjectAsText(s3Client: S3Client, bucket: String, key: String): String = {
    val request = GetObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .build()

    val response = s3Client.getObject(request)

    try {
      val reader = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8))
      val content = new StringBuilder()
      var line: String = null

      while ({line = reader.readLine(); line != null}) {
        content.append(line).append("\n")
      }

      content.toString().trim
    } finally {
      response.close()
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
