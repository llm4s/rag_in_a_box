package org.llm4s.ragbox.service

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.ragbox.config.AppConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.registry.DocumentRegistry

import scala.concurrent.duration._

/**
 * Health, readiness and what the server is configured with: the configuration views, including
 * what the configured settings do not affect, and the available providers and models.
 */
final class SystemInfoService(config: AppConfig, pipelines: RagPipelines, documentRegistry: DocumentRegistry) {

  private val startTime: Long = System.currentTimeMillis()

  /** Current configuration. */
  def configInfo: IO[ConfigResponse] = pipelines.ready.map { connected =>
    val current = pipelines.current
    ConfigResponse(
      embedding = EmbeddingConfigInfo(
        provider = config.embedding.provider,
        model = config.embedding.model,
        dimensions = config.embedding.dimensions
      ),
      llm = LLMConfigInfo(model = config.llm.model, temperature = current.llmTemperature),
      rag = RAGConfigInfo(
        chunkingStrategy = current.chunkingStrategy,
        chunkSize = current.chunkSize,
        chunkOverlap = current.chunkOverlap,
        topK = current.topK,
        fusionStrategy = current.fusionStrategy,
        rrfK = current.rrfK,
        searchMode = "hybrid",
        fusionApplies = true,
        reranking = config.rag.reranking.normalizedStrategy.getOrElse(config.rag.reranking.strategy),
        queryTransform = config.rag.queryTransform.normalizedStrategy.getOrElse(config.rag.queryTransform.strategy),
        limitations = pipelineLimitations
      ),
      database = DatabaseConfigInfo(
        host = config.database.effectiveHost,
        port = config.database.effectivePort,
        database = config.database.effectiveDatabase,
        tableName = config.database.tableName,
        connected = connected
      )
    )
  }

  /** Detailed configuration with changeability annotations. */
  def detailedConfig: IO[DetailedConfigResponse] = pipelines.ready.map { connected =>
    val current = pipelines.current
    DetailedConfigResponse(
      embedding = VisibilityEmbeddingConfig(
        provider = config.embedding.provider,
        model = config.embedding.model,
        dimensions = config.embedding.dimensions,
        changeability = ChangeabilityInfo.cold
      ),
      llm = VisibilityLLMConfig(model = config.llm.model, temperature = current.llmTemperature, changeability = ChangeabilityInfo.hot),
      rag = DetailedRAGConfigInfo(
        chunkingStrategy = ConfigSetting("chunkingStrategy", current.chunkingStrategy, ChangeabilityInfo.warm),
        chunkSize = ConfigSetting("chunkSize", current.chunkSize.toString, ChangeabilityInfo.warm),
        chunkOverlap = ConfigSetting("chunkOverlap", current.chunkOverlap.toString, ChangeabilityInfo.warm),
        topK = ConfigSetting("topK", current.topK.toString, ChangeabilityInfo.hot),
        fusionStrategy = ConfigSetting("fusionStrategy", current.fusionStrategy, ChangeabilityInfo.hot),
        rrfK = ConfigSetting("rrfK", current.rrfK.toString, ChangeabilityInfo.hot),
        systemPrompt = Some(current.systemPrompt),
        searchMode = "hybrid",
        limitations = pipelineLimitations
      ),
      database = VisibilityDatabaseConfig(
        host = config.database.effectiveHost,
        port = config.database.effectivePort,
        database = config.database.effectiveDatabase,
        tableName = config.database.tableName,
        connected = connected
      ),
      runtimeConfigurable = RuntimeConfigurableInfo(
        hotSettings = Seq("topK", "fusionStrategy", "rrfK", "systemPrompt", "llmModel", "llmTemperature"),
        warmSettings = Seq("chunkingStrategy", "chunkSize", "chunkOverlap"),
        coldSettings = Seq("embeddingProvider", "embeddingModel", "databaseHost", "databasePort")
      )
    )
  }

  /**
   * Configured settings that do not take effect, and why. There are none: fusion, reranking and
   * query transforms apply in permission mode too, and the temperature to every answer.
   */
  private def pipelineLimitations: Seq[String] = Seq.empty

  // Live model lists by provider id, with when they were fetched
  private val modelListCache = new java.util.concurrent.ConcurrentHashMap[String, (Long, Seq[String])]()

  /**
   * Available providers: every LLM and embedding provider registered with llm4s's
   * `ProviderRegistry`. A provider counts as configured when it needs no API key or ragbox has
   * one for it. Models come from the provider's live model lister when it has one and is
   * configured (cached for [[RAGService.ModelListTtlMillis]]), otherwise from llm4s's bundled model
   * metadata and the embedding descriptors' model tables.
   */
  def providers: IO[ProvidersResponse] =
    for {
      llmProviders <- pipelines.providerRegistry.ids.toList.parTraverse(llmProviderInfo)
      embeddingProviders = pipelines.providerRegistry.embeddingIds.map(embeddingProviderInfo)
    } yield ProvidersResponse(
      embeddingProviders = embeddingProviders,
      llmProviders = llmProviders,
      chunkingStrategies = org.llm4s.chunking.ChunkerFactory.Strategy.all.map(_.name),
      fusionStrategies = RAGService.FusionStrategies
    )

  private def bundledModels(provider: String, mode: org.llm4s.model.ModelMode): Seq[String] =
    pipelines.modelRegistry.toOption
      .flatMap(_.listByProvider(org.llm4s.types.ProviderModelTypes.ProviderId(provider)).toOption)
      .getOrElse(Nil)
      .filter(_.mode == mode)
      .map(_.modelId.asString)
      .sorted

  private def embeddingProviderInfo(id: String): ProviderInfo = {
    val descriptor = pipelines.providerRegistry.findEmbedding(org.llm4s.types.ProviderModelTypes.ProviderId(id))
    val fromDescriptor = descriptor.map(_.modelDimensions.keys.toSeq.map(_.asString).sorted).getOrElse(Nil)
    val models = if (fromDescriptor.nonEmpty) fromDescriptor else bundledModels(id, org.llm4s.model.ModelMode.Embedding)
    val needsKey = descriptor.exists(_.configSpec.requiresApiKey)
    ProviderInfo(id, models, configured = !needsKey || config.apiKeys.getKey(id).isDefined)
  }

  private def llmProviderInfo(id: String): IO[ProviderInfo] = {
    val providerId = org.llm4s.types.ProviderModelTypes.ProviderId(id)
    val descriptor = pipelines.providerRegistry.find(providerId)
    val apiKey = config.apiKeys.getKey(id)
    val configured = descriptor.exists(d => !d.configSpec.requiresApiKey || apiKey.isDefined)
    val bundled = bundledModels(id, org.llm4s.model.ModelMode.Chat)
    val listed: IO[Option[Seq[String]]] = descriptor.flatMap(_.modelLister).filter(_ => configured) match {
      case None => IO.pure(None)
      case Some(lister) =>
        Option(modelListCache.get(id)).filter { case (at, _) =>
          System.currentTimeMillis() - at < RAGService.ModelListTtlMillis
        } match {
          case Some((_, models)) => IO.pure(Some(models))
          case None =>
            val isActive = config.llm.providerId == id
            val section = org.llm4s.config.NamedProviderConfig(
              provider = providerId,
              model = org.llm4s.types.ProviderModelTypes.ModelName(if (isActive) config.llm.modelName else ""),
              baseUrl = (if (isActive) config.llm.baseUrl else None)
                .map(org.llm4s.types.ProviderModelTypes.BaseUrl(_))
                .orElse(descriptor.flatMap(_.configSpec.defaultBaseUrl)),
              apiKey = apiKey.map(k => org.llm4s.types.ProviderModelTypes.ApiKey(k.reveal))
            )
            IO.interruptible {
              val http = org.llm4s.http.Llm4sHttpClient.create(org.llm4s.http.HttpClientOptions(connectTimeout = Some(3.seconds)))
              lister.listModels(section, http).map(_.map(_.name.asString).sorted.toSeq)
            }.timeout(5.seconds).attempt.map {
              case Right(Right(models)) if models.nonEmpty =>
                modelListCache.put(id, (System.currentTimeMillis(), models))
                Some(models)
              case Right(Left(error)) =>
                SystemInfoService.logger.debug(s"Listing models of '$id' failed: ${error.message}")
                None
              case Left(e) =>
                SystemInfoService.logger.debug(s"Listing models of '$id' failed: ${e.getMessage}")
                None
              case _ => None
            }
        }
    }
    listed.map(live => ProviderInfo(id, live.getOrElse(bundled), configured))
  }

  /** Basic health check with system information. */
  def healthCheck: IO[HealthResponse] = IO {
    val runtime = Runtime.getRuntime
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val freePercent = if (maxMemory > 0) ((maxMemory - usedMemory) * 100 / maxMemory).toInt else 0
    HealthResponse(
      status = "healthy",
      version = org.llm4s.ragbox.BuildInfo.version,
      uptime = System.currentTimeMillis() - startTime,
      system = Some(SystemInfo(
        memoryUsedMb = usedMemory,
        memoryMaxMb = maxMemory,
        memoryFreePercent = freePercent,
        cpuCount = runtime.availableProcessors(),
        javaVersion = System.getProperty("java.version", "unknown")
      ))
    )
  }

  /**
   * Readiness: the database answers a real query and the pipeline can be built (an earlier failed
   * build is retried, so readiness recovers on its own). The failure reasons are logged; the
   * response only says which check failed, as it is public.
   */
  def readinessCheck: IO[ReadinessResponse] = {
    def check(name: String, failure: String)(io: IO[?]): IO[CheckStatus] =
      io.attempt.flatMap {
        case Right(_) => IO.pure(CheckStatus("ok"))
        case Left(e) =>
          IO(SystemInfoService.logger.warn(s"Readiness check '$name' failed: ${e.getMessage}")).as(CheckStatus("error", Some(failure)))
      }

    val apiKeyCheck =
      if (config.apiKeys.hasAnyKey) CheckStatus("ok") else CheckStatus("warning", Some("No API keys configured"))

    for {
      db <- check("database", "Database unreachable")(documentRegistry.count())
      rag <- check("rag_instance", "RAG pipeline not available")(pipelines.rag)
    } yield ReadinessResponse(
      ready = rag.status == "ok" && db.status == "ok",
      checks = Map("database" -> db, "rag_instance" -> rag, "api_keys" -> apiKeyCheck)
    )
  }
}

object SystemInfoService {
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[SystemInfoService])
}
