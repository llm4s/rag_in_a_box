package org.llm4s.ragbox

import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import cats.syntax.all._
import com.zaxxer.hikari.HikariDataSource
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.server.middleware.CORS
import org.http4s.server.websocket.WebSocketBuilder2
import org.llm4s.ragbox.auth._
import org.llm4s.ragbox.config.{AppConfig, AuthMode, RuntimeConfigManager}
import org.llm4s.ragbox.ingestion.{IngestionEvent, IngestionScheduler, IngestionService}
import org.llm4s.ragbox.middleware.{Authenticator, RateLimitMiddleware, RequestLogger, RequestSizeMiddleware}
import org.llm4s.ragbox.registry.{ChatSessionRegistry, ExperimentRegistry, PgCollectionConfigRegistry, QueryLogRegistry}
import org.llm4s.ragbox.routes._
import org.llm4s.ragbox.routes.WebSocketRoutes.BroadcastHub
import org.llm4s.ragbox.service.{ChunkingService, ExperimentService, RAGService, SuggestionService}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._

/**
 * The application's components and its HTTP stack, separated from `Main` so tests can build
 * exactly the app the server runs (same routes, same middleware, same authenticator) over a
 * test database.
 *
 * {{{
 * RagboxApp.resource(config, pool, ragService).use { app =>
 *   val http = app.httpApp(wsb)  // what Main serves
 *   ...
 * }
 * }}}
 */
final class RagboxApp private (
  val config: AppConfig,
  val ragService: RAGService,
  val authService: AuthService,
  val userRegistry: UserRegistry,
  val tokenRegistry: AccessTokenRegistry,
  val ingestionService: IngestionService,
  val hub: BroadcastHub,
  val oidc: Option[RagboxApp.Oidc],
  routes: RagboxApp.Routes,
  limiters: RateLimitMiddleware.Limiters,
  logger: Logger[IO]
) {

  private val httpLogger: Logger[IO] = Slf4jLogger.getLoggerFromName[IO]("org.llm4s.ragbox.http")

  val authenticator: Authenticator =
    Authenticator(config.security, authService, userRegistry, tokenRegistry, oidc.map(_.service))

  /**
   * The complete HTTP app: public routes, then every other route behind the [[authenticator]].
   * Errors any route raises are turned into responses by [[ErrorHandling]].
   * Outside the authenticator, from the inside out: rate limits (so failed authentication is
   * limited too), the request-size limit, CORS, request logging (credentials redacted) and the
   * removal of untrusted identity headers.
   */
  def httpApp(wsb: WebSocketBuilder2[IO]): HttpApp[IO] = {
    // Errors raised by any route (or the authenticator) become ErrorResponses here
    val all: HttpRoutes[IO] = ErrorHandling(
      routes.public <+> authenticator(Authz.tokenAllowlist(routes.authed <+> WebSocketRoutes.routes(wsb, hub)))
    )

    val limited = RequestSizeMiddleware(RequestSizeMiddleware.Config(
      maxBodySize = config.production.requestSize.maxBodySizeBytes,
      enabled = config.production.requestSize.enabled
    ))(
      RateLimitMiddleware(RateLimitMiddleware.Config.from(config.production.rateLimit), limiters)(all)
    ).orNotFound

    // Same-origin only unless origins are configured: then CORS headers for those origins only
    val cors = config.production.cors
    val withCors =
      if (cors.isDenyAll) limited
      else if (cors.isAllowAll) CORS.policy.withAllowOriginAll(limited)
      else CORS.policy.withAllowOriginHostCi(origin => cors.allows(origin.toString))(limited)

    // Identity headers only count in trusted-proxy mode; otherwise nothing downstream sees them
    Authenticator.stripIdentityHeaders(config.security.auth.trustedProxyHeaders)(
      RequestLogger(httpLogger)(withCors)
    )
  }

  /**
   * Background work: the ingestion schedule and expired OAuth session/state cleanup. Runs
   * until `supervisor` is released. (The rate limiters bound and expire their own entries.)
   */
  def startBackgroundTasks(supervisor: Supervisor[IO]): IO[Unit] = {
    val scheduler = IngestionScheduler(ingestionService, config.ingestion)
    val startScheduler =
      if (config.ingestion.enabled && config.ingestion.schedule.isDefined)
        logger.info(s"Starting ingestion scheduler (schedule: ${config.ingestion.schedule.get})...") *>
          scheduler.start(supervisor)
      else IO.unit

    val sessionCleanup = oidc.traverse_ { o =>
      val interval = math.max(60, o.config.stateTtl).seconds
      val loop = (IO.sleep(interval) *>
        o.sessions.cleanupExpired()
          .flatMap(n => logger.debug(s"Removed $n expired OAuth sessions/states"))
          .handleErrorWith(e => logger.warn(s"OAuth session cleanup failed: ${e.getMessage}"))).foreverM
      supervisor.supervise(loop).void *> logger.info(s"OAuth session cleanup started (interval: $interval)")
    }

    startScheduler *> sessionCleanup
  }
}

object RagboxApp {

  /** OAuth/OIDC components, present in oauth mode. */
  final case class Oidc(config: OAuthConfig, service: OidcService[IO], sessions: SessionRegistry[IO])

  private final case class Routes(public: HttpRoutes[IO], authed: org.http4s.AuthedRoutes[Principal, IO])

  /**
   * Build the application's registries and services over `pool` and `ragService`.
   *
   * @param oidcHttpClient HTTP client for the OIDC token endpoint (oauth mode); defaults to an
   *                       Ember client
   * @param runtimeConfigManager the runtime settings `ragService` reads (created when not given)
   * @param collectionConfigRegistry the collection chunking configs `ragService` reads (created when not given)
   */
  def resource(
    config: AppConfig,
    pool: HikariDataSource,
    ragService: RAGService,
    oidcHttpClient: Resource[IO, Client[IO]] = EmberClientBuilder.default[IO].build,
    runtimeConfigManager: Option[RuntimeConfigManager] = None,
    collectionConfigRegistry: Option[PgCollectionConfigRegistry] = None
  ): Resource[IO, RagboxApp] =
    for {
      logger <- Resource.eval(Slf4jLogger.create[IO])
      // The ones ragService reads, when the caller built it with them (as Main does); otherwise new
      runtimeConfigManager <- runtimeConfigManager.fold(
        Resource.make(RuntimeConfigManager(config, pool, permissionMode = ragService.hasPermissions))(_.close())
      )(Resource.pure(_))
      collectionConfigRegistry <- collectionConfigRegistry.fold(
        Resource.make(PgCollectionConfigRegistry(pool))(_.close())
      )(Resource.pure(_))
      queryLogRegistry <- Resource.make(QueryLogRegistry(pool))(_.close())
      userRegistry <- Resource.make(UserRegistry(pool))(_.close())
      tokenRegistry <- Resource.make(AccessTokenRegistry(pool))(_.close())
      authService <- Resource.eval(AuthService(userRegistry, config.security.auth))
      hub <- Resource.eval(BroadcastHub.create)
      ingestionService = IngestionService(ragService, config.ingestion, broadcastIngestion(hub, ragService))
      chunkingService = ChunkingService(config, runtimeConfigManager)
      suggestionService = SuggestionService(queryLogRegistry, config, runtimeConfigManager)
      experimentRegistry <- Resource.eval(ExperimentRegistry(pool))
      experimentService = ExperimentService(experimentRegistry, pool)
      chatSessionRegistry <- Resource.eval(ChatSessionRegistry(pool))
      oidc <- oidcComponents(config, pool, ragService, oidcHttpClient)
      limiters <- Resource.eval(RateLimitMiddleware.createLimiters(RateLimitMiddleware.Config.from(config.production.rateLimit)))
    } yield {
      val public: HttpRoutes[IO] = Seq(
        Some(HealthRoutes.routes(ragService)),
        Some(AuthRoutes.publicRoutes(authService, config.security)),
        oidc.map(o => OAuthRoutes(o.service, o.config.session, o.config.stateTtl).routes),
        Some(StaticRoutes.routes),
        Option.when(config.metrics.enabled && !config.metrics.requireAuth)(MetricsRoutes.routes(ragService))
      ).flatten.reduce(_ <+> _)

      val permissionRoutes = (ragService.principals, ragService.collections) match {
        case (Some(ps), Some(cs)) => Seq(PrincipalRoutes.routes(ps), CollectionPermissionRoutes.routes(cs, ps))
        case _ => Seq.empty
      }

      // Order matters where paths overlap (e.g. /collections/...): same order as before
      val authed = (Seq(
        DocumentRoutes.routes(ragService),
        QueryRoutes.routes(ragService, queryLogRegistry, Some(config), Some(experimentService)),
        ConfigRoutes.routes(ragService),
        IngestionRoutes.routes(ingestionService),
        VisibilityRoutes.routes(ragService),
        ChunkingRoutes.routes(chunkingService),
        RuntimeConfigRoutes.routes(runtimeConfigManager),
        CollectionConfigRoutes.routes(collectionConfigRegistry, ragService.getDocumentRegistry, runtimeConfigManager),
        AnalyticsRoutes.routes(queryLogRegistry),
        SuggestionRoutes.routes(suggestionService),
        ExperimentRoutes.routes(experimentService),
        ChatRoutes.routes(chatSessionRegistry, ragService, queryLogRegistry, Some(config)),
        AuthRoutes.routes(authService, userRegistry),
        TokenRoutes.routes(tokenRegistry)
      ) ++ permissionRoutes ++
        Option.when(config.metrics.enabled && config.metrics.requireAuth)(MetricsRoutes.authedRoutes(ragService))
      ).reduce(_ <+> _)

      new RagboxApp(
        config, ragService, authService, userRegistry, tokenRegistry, ingestionService, hub, oidc,
        Routes(public, authed), limiters, logger
      )
    }

  /** Publish ingestion progress, and fresh stats when a run ends, to WebSocket clients. */
  private def broadcastIngestion(hub: BroadcastHub, ragService: RAGService)(event: IngestionEvent): IO[Unit] =
    hub.broadcast(WebSocketRoutes.ingestionProgress(event)) *>
      (if (event.status == "running") IO.unit
       else ragService.getStats.flatMap { stats =>
         hub.broadcast(WebSocketRoutes.statsUpdate(stats.documentCount, stats.chunkCount, stats.collections.size))
       })

  /** In oauth mode: session registry, token validator, claim mapper and OIDC service. */
  private def oidcComponents(
    config: AppConfig,
    pool: HikariDataSource,
    ragService: RAGService,
    httpClient: Resource[IO, Client[IO]]
  ): Resource[IO, Option[Oidc]] =
    (config.security.auth.mode, config.security.auth.oauth) match {
      case (AuthMode.OAuth, Some(oauth)) =>
        for {
          principals <- Resource.eval(IO.fromOption(ragService.principals)(OidcError.ConfigurationError(
            "AUTH_MODE=oauth needs permission-based RAG (the PostgreSQL SearchIndex), which is unavailable"
          )))
          client <- httpClient
          sessions <- Resource.eval(SessionRegistry.create(oauth.sessionStorage, Some(pool)))
          validator <- Resource.eval(OidcTokenValidatorImpl.create(oauth.provider, oauth.claimMapping))
          claimMapper <- Resource.eval(ClaimMapperImpl.create(principals, oauth.claimMapping))
          service <- Resource.eval(OidcServiceImpl.create(oauth, client, validator, sessions, claimMapper))
        } yield Some(Oidc(oauth, service, sessions))
      case _ => Resource.pure(None)
    }
}
