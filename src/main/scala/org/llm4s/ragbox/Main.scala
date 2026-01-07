package org.llm4s.ragbox

import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import cats.syntax.all._
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.middleware.{CORS, Logger => HttpLogger}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.Logger
import cats.effect.std.Supervisor
import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.llm4s.ragbox.config.{AppConfig, RuntimeConfigManager}
import org.llm4s.ragbox.ingestion.{IngestionScheduler, IngestionService}
import org.llm4s.ragbox.service.ChunkingService
import org.llm4s.ragbox.middleware.{AuthMiddleware, RateLimitMiddleware, RequestSizeMiddleware}
import org.llm4s.ragbox.registry.{PgCollectionConfigRegistry, QueryLogRegistry}
import org.llm4s.ragbox.routes._
import org.llm4s.ragbox.service.RAGService
import org.llm4s.ragbox.auth.{AuthService, UserRegistry, AccessTokenRegistry}

import scala.concurrent.duration._

/**
 * RAG in a Box - Main entry point.
 *
 * Starts the HTTP server with all RAG endpoints.
 */
object Main extends IOApp {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger: Logger[IO] = loggerFactory.getLogger

  override def run(args: List[String]): IO[ExitCode] = {
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
      _ <- logger.info(s"Database: ${config.database.host}:${config.database.port}/${config.database.database}")
      _ <- logger.info("Document registry: PostgreSQL (persistent)")
      _ <- logger.info(s"API authentication: ${if (config.security.isEnabled) "enabled" else "disabled"}")
      _ <- logger.info(s"Metrics endpoint: ${if (config.metrics.enabled) "enabled (/metrics)" else "disabled"}")
      _ <- logger.info(s"Rate limiting: ${if (config.production.rateLimit.enabled) s"enabled (${config.production.rateLimit.maxRequests}/${config.production.rateLimit.windowSeconds}s)" else "disabled"}")
      _ <- logger.info(s"Request size limit: ${if (config.production.requestSize.enabled) s"enabled (${config.production.requestSize.maxBodySizeMb}MB)" else "disabled"}")
      _ <- logger.info(s"CORS: ${if (config.production.cors.isAllowAll) "allow all origins" else s"restricted to ${config.production.cors.allowedOrigins.mkString(", ")}"}")
      _ <- logger.info("Permission-based RAG: enabled (LLM4S v0.2.6+)")
      exitCode <- server(config).use(_ => IO.never).as(ExitCode.Success)
    } yield exitCode
  }

  private def server(config: AppConfig): Resource[IO, Unit] = {
    for {
      // Create supervisor for background tasks
      supervisor <- Supervisor[IO]

      // Try to create SearchIndex for permission-based RAG
      // Falls back to legacy mode if SearchIndex creation fails
      searchIndexOpt <- Resource.eval(createSearchIndex(config))

      // Create RAG service with PostgreSQL-backed document registry
      // Use permission-aware mode if SearchIndex is available
      ragService <- searchIndexOpt match {
        case Some(si) =>
          Resource.make(RAGService.createWithPermissions(config, si))(_.close())
        case None =>
          Resource.make(RAGService.create(config))(_.close())
      }

      // Create Runtime Config Manager
      runtimeConfigManager <- Resource.make(RuntimeConfigManager(config))(_.close())

      // Create Collection Config Registry
      collectionConfigRegistry <- Resource.make(PgCollectionConfigRegistry(config.database))(_.close())

      // Create Query Log Registry for analytics
      queryLogRegistry <- Resource.make(QueryLogRegistry(config.database))(_.close())

      // Create Auth services
      userRegistry <- Resource.make(UserRegistry(config.database))(_.close())
      tokenRegistry <- Resource.make(AccessTokenRegistry(config.database))(_.close())
      authService <- Resource.eval(AuthService(userRegistry, config.security.auth))

      // Create Ingestion service and scheduler
      ingestionService = IngestionService(ragService.rag, config.ingestion)
      scheduler = IngestionScheduler(ingestionService, config.ingestion)

      // Create Chunking service
      chunkingService = ChunkingService(config)

      // Base routes
      baseRoutes = Seq(
        "/" -> HealthRoutes.routes(ragService),
        "/" -> DocumentRoutes.routes(ragService),
        "/" -> QueryRoutes.routes(ragService, queryLogRegistry, config.security.isAdminHeaderAllowed),
        "/" -> ConfigRoutes.routes(ragService),
        "/" -> IngestionRoutes.routes(ingestionService),
        "/" -> VisibilityRoutes.routes(ragService),
        "/" -> ChunkingRoutes.routes(chunkingService),
        "/" -> RuntimeConfigRoutes.routes(runtimeConfigManager),
        "/" -> CollectionConfigRoutes.routes(collectionConfigRegistry, ragService.getDocumentRegistry, runtimeConfigManager),
        "/" -> AnalyticsRoutes.routes(queryLogRegistry),
        "/" -> AuthRoutes.routes(authService, userRegistry, config.security.auth.jwtExpiration),
        "/" -> TokenRoutes.routes(tokenRegistry, authService),
        "/" -> StaticRoutes.routes
      )

      // Permission routes (only when SearchIndex is available)
      permissionRoutes = (ragService.principals, ragService.collections) match {
        case (Some(ps), Some(cs)) =>
          Seq(
            "/" -> PrincipalRoutes.routes(ps),
            "/" -> CollectionPermissionRoutes.routes(cs, ps)
          )
        case _ => Seq.empty
      }

      metricsRoute = if (config.metrics.enabled) Seq("/" -> MetricsRoutes.routes(ragService)) else Seq.empty
      allRoutes = Router((baseRoutes ++ permissionRoutes ++ metricsRoute)*)

      // Create rate limit state
      rateLimitState <- Resource.eval(RateLimitMiddleware.createState)

      // Configure rate limiting
      rateLimitConfig = RateLimitMiddleware.Config(
        maxRequests = config.production.rateLimit.maxRequests,
        window = config.production.rateLimit.windowSeconds.seconds,
        enabled = config.production.rateLimit.enabled
      )

      // Configure request size limits
      requestSizeConfig = RequestSizeMiddleware.Config(
        maxBodySize = config.production.requestSize.maxBodySizeBytes,
        enabled = config.production.requestSize.enabled
      )

      // Apply production middlewares (rate limiting, request size)
      productionRoutes = RateLimitMiddleware(rateLimitConfig, rateLimitState)(
        RequestSizeMiddleware(requestSizeConfig)(allRoutes)
      )

      // Apply authentication middleware
      authedRoutes = AuthMiddleware(config.security)(productionRoutes).orNotFound

      // Configure CORS policy based on settings
      corsPolicy = if (config.production.cors.isAllowAll) {
        CORS.policy.withAllowOriginAll
      } else {
        // Create CORS policy with specific allowed origins
        val allowedOrigins = config.production.cors.allowedOrigins.map(_.toLowerCase).toSet
        CORS.policy.withAllowOriginHostCi { origin =>
          val originStr = origin.toString.toLowerCase
          allowedOrigins.exists(allowed =>
            originStr == allowed || originStr.endsWith(s"://$allowed")
          )
        }
      }

      // Add CORS and logging middleware
      httpApp = HttpLogger.httpApp(
        logHeaders = false,
        logBody = false
      )(corsPolicy(authedRoutes))

      // Parse host and port
      host = Host.fromString(config.server.host).getOrElse(host"0.0.0.0")
      port = Port.fromInt(config.server.port).getOrElse(port"8080")

      // Graceful shutdown configuration
      shutdownTimeout = config.production.shutdown.timeoutSeconds.seconds
      drainConnectionsTime = config.production.shutdown.drainConnectionsSeconds.seconds

      // Start server with graceful shutdown
      _ <- EmberServerBuilder.default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .withShutdownTimeout(shutdownTimeout)
        .build
        .evalTap(_ => logger.info(s"Server started on http://${config.server.host}:${config.server.port}"))
        .evalTap(_ => logEndpoints())
        .evalTap(_ => runOnStartupIngestion(config, ingestionService))
        .evalTap(_ => startScheduler(scheduler, config, supervisor))
        .evalTap(_ => startRateLimitCleanup(rateLimitState, rateLimitConfig, supervisor))
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
   * Create SearchIndex for permission-based RAG.
   * Returns None if creation fails (falls back to legacy mode).
   */
  private def createSearchIndex(config: AppConfig): IO[Option[SearchIndex]] = {
    val jdbcUrl = s"jdbc:postgresql://${config.database.host}:${config.database.port}/${config.database.database}"
    IO {
      PgSearchIndex.fromJdbcUrl(
        jdbcUrl = jdbcUrl,
        user = config.database.user,
        password = config.database.password,
        vectorTableName = config.database.tableName
      ).toOption.flatMap { si =>
        // Initialize schema (adds permission columns if missing)
        si.initializeSchema() match {
          case Right(_) =>
            Some(si)
          case Left(err) =>
            None
        }
      }
    }.flatTap {
      case Some(_) => logger.info("SearchIndex initialized with permission schema")
      case None => IO.unit
    }.handleErrorWith { e =>
      logger.warn(s"Failed to create SearchIndex: ${e.getMessage}") *>
      logger.warn("Falling back to legacy RAG mode (no permissions)") *>
      IO.pure(None)
    }
  }

  /**
   * Start the ingestion scheduler if configured.
   */
  private def startScheduler(scheduler: IngestionScheduler, config: AppConfig, supervisor: Supervisor[IO]): IO[Unit] = {
    if (config.ingestion.enabled && config.ingestion.schedule.isDefined) {
      logger.info(s"Starting ingestion scheduler (schedule: ${config.ingestion.schedule.get})...") *>
        scheduler.start(supervisor)
    } else {
      IO.unit
    }
  }

  /**
   * Start periodic cleanup of rate limiter state to prevent memory leaks.
   * Runs every 5 minutes, cleaning up entries older than 2x the rate limit window.
   */
  private def startRateLimitCleanup(
    state: Ref[IO, RateLimitMiddleware.RateLimitState],
    config: RateLimitMiddleware.Config,
    supervisor: Supervisor[IO]
  ): IO[Unit] = {
    if (!config.enabled) {
      IO.unit
    } else {
      val cleanupInterval = 5.minutes
      val maxAge = config.window * 2  // Keep entries for 2x the window duration

      val cleanupLoop: IO[Unit] = (
        IO.sleep(cleanupInterval) *>
        RateLimitMiddleware.cleanup(state, maxAge) *>
        logger.debug("Rate limiter cleanup completed")
      ).foreverM

      supervisor.supervise(cleanupLoop).void *>
        logger.info(s"Rate limiter cleanup started (interval: $cleanupInterval, maxAge: $maxAge)")
    }
  }

  /**
   * Run ingestion on startup if configured.
   */
  private def runOnStartupIngestion(config: AppConfig, ingestionService: IngestionService): IO[Unit] = {
    if (config.ingestion.enabled && config.ingestion.runOnStartup) {
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
