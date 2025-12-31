package ragbox

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.middleware.{CORS, Logger}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import cats.effect.std.Supervisor
import ragbox.config.{AppConfig, RuntimeConfigManager}
import ragbox.ingestion.{IngestionScheduler, IngestionService}
import ragbox.service.ChunkingService
import ragbox.middleware.AuthMiddleware
import ragbox.registry.PgCollectionConfigRegistry
import ragbox.routes._
import ragbox.service.RAGService

/**
 * RAG in a Box - Main entry point.
 *
 * Starts the HTTP server with all RAG endpoints.
 */
object Main extends IOApp {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      _ <- IO(println(banner))
      _ <- IO(println("Starting RAG in a Box..."))
      config <- AppConfig.load
      _ <- IO(println(s"Server will listen on ${config.server.host}:${config.server.port}"))
      _ <- IO(println(s"Embedding provider: ${config.embedding.provider}/${config.embedding.model}"))
      _ <- IO(println(s"LLM model: ${config.llm.model}"))
      _ <- IO(println(s"Database: ${config.database.host}:${config.database.port}/${config.database.database}"))
      _ <- IO(println("Document registry: PostgreSQL (persistent)"))
      _ <- IO(println(s"API authentication: ${if (config.security.isEnabled) "enabled" else "disabled"}"))
      _ <- IO(println(s"Metrics endpoint: ${if (config.metrics.enabled) "enabled (/metrics)" else "disabled"}"))
      exitCode <- server(config).use(_ => IO.never).as(ExitCode.Success)
    } yield exitCode
  }

  private def server(config: AppConfig): Resource[IO, Unit] = {
    for {
      // Create supervisor for background tasks
      supervisor <- Supervisor[IO]

      // Create RAG service with PostgreSQL-backed document registry
      ragService <- Resource.make(RAGService.create(config))(_.close())

      // Create Runtime Config Manager
      runtimeConfigManager <- Resource.make(RuntimeConfigManager(config))(_.close())

      // Create Collection Config Registry
      collectionConfigRegistry <- Resource.make(PgCollectionConfigRegistry(config.database))(_.close())

      // Create Ingestion service and scheduler
      ingestionService = IngestionService(ragService.rag, config.ingestion)
      scheduler = IngestionScheduler(ingestionService, config.ingestion)

      // Create Chunking service
      chunkingService = ChunkingService(config)

      // Combine all routes (conditionally include metrics if enabled)
      baseRoutes = Seq(
        "/" -> HealthRoutes.routes(ragService),
        "/" -> DocumentRoutes.routes(ragService),
        "/" -> QueryRoutes.routes(ragService),
        "/" -> ConfigRoutes.routes(ragService),
        "/" -> IngestionRoutes.routes(ingestionService),
        "/" -> VisibilityRoutes.routes(ragService),
        "/" -> ChunkingRoutes.routes(chunkingService),
        "/" -> RuntimeConfigRoutes.routes(runtimeConfigManager),
        "/" -> CollectionConfigRoutes.routes(collectionConfigRegistry, ragService.getDocumentRegistry, runtimeConfigManager),
        "/" -> StaticRoutes.routes
      )
      metricsRoute = if (config.metrics.enabled) Seq("/" -> MetricsRoutes.routes(ragService)) else Seq.empty
      allRoutes = Router((baseRoutes ++ metricsRoute)*)

      // Apply authentication middleware
      authedRoutes = AuthMiddleware(config.security)(allRoutes).orNotFound

      // Add CORS and logging middleware
      httpApp = Logger.httpApp(
        logHeaders = false,
        logBody = false
      )(CORS.policy.withAllowOriginAll(authedRoutes))

      // Parse host and port
      host = Host.fromString(config.server.host).getOrElse(host"0.0.0.0")
      port = Port.fromInt(config.server.port).getOrElse(port"8080")

      // Start server
      _ <- EmberServerBuilder.default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .evalTap(_ => IO(println(s"\n✓ Server started on http://${config.server.host}:${config.server.port}")))
        .evalTap(_ => IO(println("\nDocument Endpoints:")))
        .evalTap(_ => IO(println("  POST   /api/v1/documents      - Upload document")))
        .evalTap(_ => IO(println("  PUT    /api/v1/documents/{id} - Upsert document (idempotent)")))
        .evalTap(_ => IO(println("  GET    /api/v1/documents      - List documents")))
        .evalTap(_ => IO(println("  DELETE /api/v1/documents/{id} - Delete document")))
        .evalTap(_ => IO(println("  DELETE /api/v1/documents      - Clear all documents")))
        .evalTap(_ => IO(println("  GET    /api/v1/collections    - List collections")))
        .evalTap(_ => IO(println("\nSync Endpoints:")))
        .evalTap(_ => IO(println("  GET  /api/v1/sync/status      - Get sync status")))
        .evalTap(_ => IO(println("  GET  /api/v1/sync/documents   - List synced document IDs")))
        .evalTap(_ => IO(println("  POST /api/v1/sync             - Mark sync complete (prune)")))
        .evalTap(_ => IO(println("\nQuery Endpoints:")))
        .evalTap(_ => IO(println("  POST /api/v1/query            - Query with answer")))
        .evalTap(_ => IO(println("  POST /api/v1/search           - Search only")))
        .evalTap(_ => IO(println("\nIngestion Endpoints:")))
        .evalTap(_ => IO(println("  POST /api/v1/ingest/directory - Ingest from directory")))
        .evalTap(_ => IO(println("  POST /api/v1/ingest/url       - Ingest from URLs")))
        .evalTap(_ => IO(println("  POST /api/v1/ingest/run       - Run all configured sources")))
        .evalTap(_ => IO(println("  GET  /api/v1/ingest/status    - Get ingestion status")))
        .evalTap(_ => IO(println("  GET  /api/v1/ingest/sources   - List configured sources")))
        .evalTap(_ => IO(println("\nConfig & Health:")))
        .evalTap(_ => IO(println("  GET  /api/v1/config           - Get configuration")))
        .evalTap(_ => IO(println("  GET  /api/v1/stats            - Get statistics")))
        .evalTap(_ => IO(println("  GET  /health                  - Health check")))
        .evalTap(_ => IO(println("  GET  /health/ready            - Readiness check")))
        .evalTap(_ => IO(println("\nAdmin UI:")))
        .evalTap(_ => IO(println("  GET  /admin                   - Admin Dashboard")))
        .evalTap(_ => runOnStartupIngestion(config, ingestionService))
        .evalTap(_ => startScheduler(scheduler, config, supervisor))
        .evalTap(_ => IO(println("\nPress Ctrl+C to stop")))
    } yield ()
  }

  /**
   * Start the ingestion scheduler if configured.
   */
  private def startScheduler(scheduler: IngestionScheduler, config: AppConfig, supervisor: Supervisor[IO]): IO[Unit] = {
    if (config.ingestion.enabled && config.ingestion.schedule.isDefined) {
      IO(println(s"\nStarting ingestion scheduler (schedule: ${config.ingestion.schedule.get})...")) *>
        scheduler.start(supervisor)
    } else {
      IO.unit
    }
  }

  /**
   * Run ingestion on startup if configured.
   */
  private def runOnStartupIngestion(config: AppConfig, ingestionService: IngestionService): IO[Unit] = {
    if (config.ingestion.enabled && config.ingestion.runOnStartup) {
      IO(println("\nRunning startup ingestion...")) *>
        ingestionService.runAll().flatMap { results =>
          results.foreach { result =>
            val status = if (result.isSuccess) "OK" else s"ERROR: ${result.error.getOrElse("unknown")}"
            println(s"  [${result.sourceType}] ${result.sourceName}: " +
              s"added=${result.documentsAdded}, updated=${result.documentsUpdated}, " +
              s"unchanged=${result.documentsUnchanged} ($status)")
          }
          IO.unit
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
