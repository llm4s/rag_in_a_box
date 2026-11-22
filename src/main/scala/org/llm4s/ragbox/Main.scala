package org.llm4s.ragbox

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.Logger
import cats.effect.std.Supervisor
import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.llm4s.ragbox.config.{AppConfig, RuntimeConfigManager}
import org.llm4s.ragbox.registry.PgCollectionConfigRegistry
import org.llm4s.ragbox.db.{ConnectionPool, Migrations}
import org.llm4s.ragbox.ingestion.IngestionService
import org.llm4s.ragbox.service.RAGService

import javax.sql.DataSource
import scala.concurrent.duration._

/**
 * RAG in a Box - Main entry point.
 *
 * Starts the HTTP server with all RAG endpoints.
 *
 * With `--migrate-only`, applies the database migrations and exits instead (for a deploy job
 * that migrates before the servers roll out with `database.migrate-on-start = false`).
 */
object Main extends IOApp {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger: Logger[IO] = loggerFactory.getLogger

  /** Command-line flag: apply the database migrations, then exit. */
  val MigrateOnlyFlag: String = "--migrate-only"

  override def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil => serve
      case List(MigrateOnlyFlag) => migrateOnly
      case other =>
        logger.error(s"Unknown arguments: ${other.mkString(" ")} (supported: $MigrateOnlyFlag)").as(ExitCode(2))
    }

  /** Apply the database migrations and exit. */
  private def migrateOnly: IO[ExitCode] =
    for {
      config <- AppConfig.load
      _ <- config.database.urlError.traverse_(e => IO.raiseError(new IllegalStateException(s"Invalid DATABASE_URL: $e")))
      _ <- logger.info(s"Migrating ${config.database.effectiveHost}:${config.database.effectivePort}/${config.database.effectiveDatabase}")
      _ <- ConnectionPool.create(config.database, Some(ConnectionPool.PoolConfig(maximumPoolSize = 2, minimumIdle = 0)))
        .use(migrate)
    } yield ExitCode.Success

  /** Apply pending migrations to `pool`, logging what was done. */
  private def migrate(pool: DataSource): IO[Unit] =
    Migrations.migrate(pool).flatMap { result =>
      val version = result.schemaVersion.getOrElse("none")
      if (result.applied.isEmpty) logger.info(s"Database schema is up to date (version $version)")
      else logger.info(s"Applied ${result.applied.size} migration(s): ${result.applied.mkString(", ")}; schema version $version")
    }

  private def serve: IO[ExitCode] = {
    for {
      _ <- logger.info(banner)
      _ <- logger.info("Starting RAG in a Box...")
      config <- AppConfig.load
      // Validate configuration at startup
      _ <- config.validate() match {
        case Nil => IO.unit
        case errors =>
          errors.traverse_(e => logger.error(s"Configuration error: $e")) *>
          IO.raiseError(new IllegalStateException(s"Configuration validation failed: ${errors.mkString(", ")}"))
      }
      _ <- logger.info(s"Server will listen on ${config.server.host}:${config.server.port}")
      _ <- logger.info(s"Embedding provider: ${config.embedding.provider}/${config.embedding.model}")
      _ <- logger.info(s"LLM model: ${config.llm.model}")
      _ <- logger.info(s"Database: ${config.database.effectiveHost}:${config.database.effectivePort}/${config.database.effectiveDatabase}${if (config.database.parsedUrl.isDefined) " (from DATABASE_URL)" else ""}")
      _ <- logger.info("Document registry: PostgreSQL (persistent)")
      _ <- logger.info(s"Authentication: mode=${config.security.auth.mode.toString.toLowerCase}, API key ${if (config.security.apiKey.exists(_.nonEmpty)) "set" else "not set"}${if (!config.security.isEnabled) " (open: every request is an anonymous administrator)" else ""}")
      _ <- logger.info(s"Metrics endpoint: ${if (!config.metrics.enabled) "disabled" else if (config.metrics.requireAuth) "enabled (/metrics, administrators only)" else "enabled (/metrics, public)"}")
      _ <- logger.info(s"Rate limiting: ${if (config.production.rateLimit.enabled) s"enabled (${config.production.rateLimit.maxRequests}/${config.production.rateLimit.windowSeconds}s)" else "disabled"}; login throttling: ${if (config.production.rateLimit.loginMaxAttempts > 0) s"${config.production.rateLimit.loginMaxAttempts} failures/${config.production.rateLimit.loginWindowSeconds}s per IP and per username" else "disabled"}; trusted proxies: ${if (config.production.rateLimit.trustedProxies.isEmpty) "none" else config.production.rateLimit.trustedProxies.mkString(", ")}")
      _ <- logger.info(s"Request size limit: ${if (config.production.requestSize.enabled) s"enabled (${config.production.requestSize.maxBodySizeMb}MB)" else "disabled"}")
      _ <- logger.info(s"CORS: ${config.production.cors.describe}")
      exitCode <- server(config).use(_ => IO.never).as(ExitCode.Success)
    } yield exitCode
  }

  private def server(config: AppConfig): Resource[IO, Unit] = {
    for {
      // Create supervisor for background tasks
      supervisor <- Supervisor[IO]

      // One connection pool for the whole application, created first and passed to every
      // database component (registries, chunk store, permission search index). Released last.
      dbPool <- ConnectionPool.create(config.database)

      // Bring ragbox's schema up to date before anything uses it. Replicas starting together
      // are serialised by Flyway's lock. llm4s's own tables are created afterwards, by the
      // search index and vector store.
      _ <- Resource.eval(
        if (config.database.migrateOnStart) migrate(dbPool)
        else logger.info("Database migrations on start disabled (database.migrate-on-start = false)")
      )

      // Try to create SearchIndex for permission-based RAG
      // Falls back to legacy mode if SearchIndex creation fails
      searchIndexOpt <- createSearchIndex(config, dbPool)
      _ <- Resource.eval(searchIndexOpt match {
        case Some(_) => logger.info("Permission-based RAG: enabled")
        case None => logger.warn("Permission-based RAG: DISABLED (legacy mode, queries ignore permissions)")
      })

      // Runtime settings (restored from the database) and per-collection chunking configs, which
      // the RAG service reads on every query and ingest
      runtimeConfigManager <- Resource.make(RuntimeConfigManager(config, dbPool, permissionMode = searchIndexOpt.isDefined))(_.close())
      collectionConfigRegistry <- Resource.make(PgCollectionConfigRegistry(dbPool))(_.close())

      // Create RAG service with PostgreSQL-backed document registry
      // Use permission-aware mode if SearchIndex is available
      ragService <- searchIndexOpt match {
        case Some(si) =>
          Resource.make(RAGService.createWithPermissions(
            config, dbPool, si, runtimeSettings = Some(runtimeConfigManager), collectionConfigs = Some(collectionConfigRegistry)
          ))(_.close())
        case None =>
          Resource.make(RAGService.create(
            config, dbPool, runtimeSettings = Some(runtimeConfigManager), collectionConfigs = Some(collectionConfigRegistry)
          ))(_.close())
      }

      // Registries, services, routes and the middleware stack (shared with the full-stack tests)
      app <- RagboxApp.resource(
        config, dbPool, ragService,
        runtimeConfigManager = Some(runtimeConfigManager),
        collectionConfigRegistry = Some(collectionConfigRegistry)
      )

      // Parse host and port
      host = Host.fromString(config.server.host).getOrElse(host"0.0.0.0")
      port = Port.fromInt(config.server.port).getOrElse(port"8080")

      // Graceful shutdown configuration
      shutdownTimeout = config.production.shutdown.timeoutSeconds.seconds
      drainConnectionsTime = config.production.shutdown.drainConnectionsSeconds.seconds

      // Start server with graceful shutdown (WebSocket-capable app: /ws)
      _ <- EmberServerBuilder.default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpWebSocketApp(app.httpApp)
        .withShutdownTimeout(shutdownTimeout)
        .build
        .evalTap(_ => logger.info(s"Server started on http://${config.server.host}:${config.server.port}"))
        .evalTap(_ => logEndpoints())
        .evalTap(_ => runOnStartupIngestion(config, app.ingestionService))
        .evalTap(_ => app.startBackgroundTasks(supervisor))
        .evalTap(_ => logger.info("Server ready. Press Ctrl+C to stop"))
        .onFinalize(
          logger.info("Initiating graceful shutdown...") *>
          logger.info(s"Draining connections for ${drainConnectionsTime.toSeconds}s...") *>
          IO.sleep(drainConnectionsTime) *>
          logger.info("Connection drain complete. Shutting down server...") *>
          logger.info("Shutdown complete.")
        )
    } yield ()
  }

  /**
   * Log all available endpoints at startup.
   */
  private def logEndpoints(): IO[Unit] = {
    val endpoints = Seq(
      "Document Endpoints:",
      "  POST   /api/v1/documents      - Upload document",
      "  PUT    /api/v1/documents/{id} - Upsert document (idempotent)",
      "  GET    /api/v1/documents      - List documents",
      "  DELETE /api/v1/documents/{id} - Delete document",
      "  GET    /api/v1/collections    - List collections",
      "",
      "Sync Endpoints:",
      "  GET  /api/v1/sync/status      - Get sync status",
      "  GET  /api/v1/sync/documents   - List synced document IDs",
      "  POST /api/v1/sync             - Mark sync complete (prune)",
      "",
      "Query Endpoints:",
      "  POST /api/v1/query            - Query with answer",
      "  POST /api/v1/search           - Search only",
      "",
      "Ingestion Endpoints:",
      "  POST /api/v1/ingest/directory - Ingest from directory",
      "  POST /api/v1/ingest/url       - Ingest from URLs",
      "  POST /api/v1/ingest/run       - Run all configured sources",
      "",
      "Config & Health:",
      "  GET  /api/v1/config           - Get configuration",
      "  GET  /health                  - Health check",
      "  GET  /health/ready            - Readiness check",
      "",
      "Admin UI:",
      "  GET  /admin                   - Admin Dashboard"
    )
    logger.debug(endpoints.mkString("\n"))
  }

  /**
   * Create SearchIndex for permission-based RAG over the application's connection pool.
   *
   * The index shares `pool` (`PgSearchIndex.fromDataSource`), so it opens no pool of its own; it
   * is still closed through the Resource, which leaves the shared pool to its owner. On failure
   * the error is logged and, unless `permissions.required` is set, None is returned (legacy
   * mode, no permissions). With `permissions.required = true` a failure aborts startup.
   */
  private def createSearchIndex(config: AppConfig, pool: DataSource): Resource[IO, Option[SearchIndex]] = {
    val attempt: IO[Either[String, SearchIndex]] = IO.blocking {
      PgSearchIndex.fromDataSource(pool, config.database.tableName)
        .left.map(err => s"Failed to create SearchIndex: ${err.message}")
        .flatMap { si =>
          // Initialize schema (adds permission columns if missing)
          si.initializeSchema() match {
            case Right(_) => Right(si)
            case Left(err) =>
              si.close()
              Left(s"Failed to initialize permission schema: ${err.message}")
          }
        }
    }.handleError(e => Left(s"Failed to create SearchIndex: ${e.getMessage}"))

    val acquire: IO[Option[SearchIndex]] = attempt.flatMap {
      case Right(si) =>
        logger.info("SearchIndex initialized with permission schema").as(Some(si))
      case Left(error) if config.permissions.required =>
        logger.error(error) *>
          IO.raiseError(new IllegalStateException(
            s"$error. Refusing to start without permission-based RAG because PERMISSIONS_REQUIRED=true"
          ))
      case Left(error) =>
        logger.warn(error) *>
          logger.warn(
            "Falling back to legacy RAG mode (no permissions). " +
              "Set PERMISSIONS_REQUIRED=true to fail startup instead."
          ).as(None)
    }

    Resource.make(acquire)(_.traverse_(si => IO.blocking(si.close())))
  }

  /**
   * Run ingestion on startup if configured.
   */
  private def runOnStartupIngestion(config: AppConfig, ingestionService: IngestionService): IO[Unit] = {
    if (config.ingestion.runOnStartup && config.ingestion.activeSources.isEmpty) {
      logger.warn("INGEST_ON_STARTUP / run-on-startup ignored: no ingestion sources are configured and enabled")
    } else if (config.ingestion.runOnStartup) {
      logger.info("Running startup ingestion...") *>
        ingestionService.runAll().flatMap { results =>
          results.traverse_ { result =>
            val status = if (result.isSuccess) "OK" else s"ERROR: ${result.error.getOrElse("unknown")}"
            val msg = s"[${result.sourceType}] ${result.sourceName}: " +
              s"added=${result.documentsAdded}, updated=${result.documentsUpdated}, " +
              s"unchanged=${result.documentsUnchanged} ($status)"
            if (result.isSuccess) logger.info(msg) else logger.error(msg)
          }
        }
    } else {
      IO.unit
    }
  }

  private val banner: String =
    """
      |  ____      _    ____   _         ____
      | |  _ \    / \  / ___| (_)_ __   | __ )  _____  __
      | | |_) |  / _ \| |  _  | | '_ \  |  _ \ / _ \ \/ /
      | |  _ <  / ___ \ |_| | | | | | | | |_) | (_) >  <
      | |_| \_\/_/   \_\____| |_|_| |_| |____/ \___/_/\_\
      |
      | Powered by llm4s
      |""".stripMargin
}
