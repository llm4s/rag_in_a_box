package org.llm4s.ragbox.service

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.chunking.{ChunkerFactory, DocumentChunker}
import org.llm4s.llmconnect.{EmbeddingClient, LLMClient}
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.RAG
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.rag.permissions.SearchIndex
import org.llm4s.trace.TracingLLMClient
import org.llm4s.types.Result
import org.llm4s.ragbox.config.{AppConfig, RuntimeSettings}
import org.llm4s.ragbox.model.RuntimeConfig
import org.llm4s.ragbox.service.Llm4sPipeline.PipelineSettings

import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

/**
 * The llm4s components every other service works through: the runtime settings, the answer LLM,
 * the embedder, the chunkers and the pool of RAG pipelines.
 *
 * '''Runtime settings.''' Every query, chat message and ingest reads the current
 * [[RuntimeSettings]] (topK, fusion, system prompt, temperature, chunking), so a change made
 * through the runtime config API applies to the next request without a restart. The settings an
 * llm4s [[RAG]] bakes in when it is built ([[PipelineSettings]]: fusion, system prompt,
 * reranking, query transform) select a pipeline from a [[PipelinePool]], keyed by those settings:
 * a change builds a new pipeline on the next query, and the previous one is retired - closed
 * once the requests still using it finish.
 *
 * A pipeline is built on first use and kept only once it has been built: a failure (database or
 * embedding provider unreachable at startup, say) is not cached, so a later call retries - at
 * most once per [[RAGService.RetryCooldownMillis]] - instead of the service staying broken until
 * restart.
 *
 * All llm4s calls are synchronous (embedding and LLM HTTP calls, JDBC), so they run on the
 * blocking pool through [[RagPipelines.blockingResult]], never on the compute pool.
 */
final class RagPipelines(
  config: AppConfig,
  val providerRegistry: ProviderRegistry,
  val searchIndex: Option[SearchIndex],
  runtimeSettings: Option[RuntimeSettings]
) {
  import RagPipelines.blockingResult

  private given ProviderRegistry = providerRegistry

  /** Permission mode: a [[SearchIndex]] is configured. */
  val hasPermissions: Boolean = searchIndex.isDefined

  // Model metadata bundled with llm4s: resolves context windows for LLM configs, prices tokens,
  // and is required (as a given) when building LLM clients and the RAG pipeline.
  lazy val modelRegistry: Result[ModelRegistryService] = ModelRegistryService.default()

  /**
   * Attributes llm4s trace events (embedding and completion usage, durations, cost) to the
   * request that caused them; see [[QueryMetricsTracing]].
   */
  val metricsTracing = new QueryMetricsTracing

  /**
   * The answer LLM, resolved through the provider registry (see [[Llm4sPipeline.llmClient]]) and
   * traced with [[metricsTracing]]. When it cannot be built the reason is logged and the service
   * runs without answer generation: search still works, answers fail with [[LlmUnavailableError]].
   */
  lazy val llmClient: Option[LLMClient] = {
    val built = modelRegistry.flatMap { registry =>
      given ModelRegistryService = registry
      Llm4sPipeline.llmClient(config, providerRegistry)
    }
    built match {
      case Right((client, providerConfig)) =>
        RagPipelines.logger.info(s"LLM '${config.llm.model}' resolved through the llm4s provider registry")
        Some(TracingLLMClient(client, metricsTracing, providerConfig))
      case Left(error) =>
        RagPipelines.logger.warn(s"LLM '${config.llm.model}' is not available, answer generation is disabled: ${error.formatted}")
        None
    }
  }

  /** The answer LLM, or [[LlmUnavailableError]]. */
  def requireLlm: IO[LLMClient] = IO.fromOption(llmClient)(LlmUnavailableError())

  /** The runtime settings requests read. */
  val settings: RuntimeSettings = runtimeSettings.getOrElse(RuntimeSettings.fixed(config))

  /** The runtime settings in effect now. */
  def current: RuntimeConfig = settings.current

  /** Build the llm4s pipeline for `pipelineSettings` (blocking: it connects to the stores). */
  private def buildRag(pipelineSettings: PipelineSettings): IO[RAG] =
    IO.blocking {
      val effective = pipelineSettings.applyTo(config)
      scala.util.Try(
        for {
          registry <- modelRegistry
          ragConfig <- Llm4sPipeline.ragConfig(effective, searchIndex, llmClient, metricsTracing)
          rag <- {
            given ModelRegistryService = registry
            ragConfig.build(
              Llm4sPipeline.embeddingProviderConfig(config, providerRegistry),
              () => Llm4sPipeline.rerankerConfig(effective)
            )
          }
        } yield rag
      ).toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString)).flatMap(_.left.map(_.message))
    }.flatMap(result => IO.fromEither(result.left.map(new RuntimeException(_))))

  /** Pipelines by the settings they were built with (see the class description). */
  private val pipelines: PipelinePool[PipelineSettings, RAG] = PipelinePool.unsafeCreate(
    build = buildRag,
    dispose = r => IO.blocking(r.close()),
    maxEntries = RAGService.MaxPipelines,
    retryCooldown = RAGService.RetryCooldownMillis.millis,
    closedMessage = "RAG service is closed"
  )

  /** The pipeline settings the current runtime settings select. */
  def currentPipelineSettings: PipelineSettings = {
    val now = settings.current
    PipelineSettings.from(config)
      .copy(fusionStrategy = now.fusionStrategy, rrfK = now.rrfK, systemPrompt = now.systemPrompt)
      .forMode(config, hasPermissions)
  }

  // The pipeline settings the runtime settings selected last, so the pipeline they replace is retired
  private val lastCurrentSettings = new AtomicReference[Option[PipelineSettings]](None)

  /**
   * Run `f` with the pipeline the current runtime settings select; it stays open until `f`
   * completes. When the runtime settings have changed since the last call, the pipeline of the
   * previous settings is retired: it takes no new requests and is closed when the ones still
   * using it finish.
   */
  def withCurrent[B](f: RAG => IO[B]): IO[B] = {
    val selected = currentPipelineSettings
    IO(lastCurrentSettings.getAndSet(Some(selected)))
      .flatMap(_.filter(_ != selected).traverse_(pipelines.retire)) *>
      pipelines.use(selected)(f)
  }

  /**
   * The pipeline settings `overrides` select over the current runtime settings.
   *
   * @return the settings, or an [[InvalidRequestError]] for a value llm4s has no strategy for
   */
  def settingsFor(overrides: RAGService.SettingsOverride): Either[InvalidRequestError, PipelineSettings] = {
    val base = currentPipelineSettings
    for {
      fusion <- overrides.fusionStrategy.fold(Right(base.fusionStrategy)) { f =>
        Either.cond(RAGService.FusionStrategies.contains(f), f, InvalidRequestError(
          s"Unknown fusion strategy '$f'. Use one of: ${RAGService.FusionStrategies.mkString(", ")}"
        ))
      }
      reranking <- overrides.reranking.fold(Right(base.reranking)) { r =>
        val rerankSettings = base.reranking.copy(strategy = r)
        rerankSettings.normalizedStrategy.map(_ => rerankSettings).left.map(InvalidRequestError(_))
      }
    } yield base
      .copy(
        fusionStrategy = fusion,
        rrfK = overrides.rrfK.getOrElse(base.rrfK),
        systemPrompt = overrides.systemPrompt.getOrElse(base.systemPrompt),
        reranking = reranking
      )
      .forMode(config, hasPermissions)
  }

  /** Run `f` with the pipeline `overrides` select (the current one when they change nothing). */
  def withOverrides[B](overrides: RAGService.SettingsOverride)(f: RAG => IO[B]): IO[B] =
    IO.fromEither(settingsFor(overrides)).flatMap { selected =>
      if (selected == currentPipelineSettings) withCurrent(f) else pipelines.use(selected)(f)
    }

  /**
   * The pipeline for the current runtime settings, building it if it has not been built yet.
   * For checks (readiness, warm-up): the instance is no longer leased once this returns.
   */
  def rag: IO[RAG] = withCurrent(IO.pure)

  /** Whether the pipeline for the current settings has been built (without trying to build it). */
  def ready: IO[Boolean] = pipelines.isBuilt(currentPipelineSettings)

  // The embedding client and model, once built (a failure is retried on next use)
  private val embedderRef = new AtomicReference[Option[(EmbeddingClient, EmbeddingModelConfig)]](None)

  /** The embedding client permission-mode ingest embeds with, traced like the pipeline's. */
  def embedder: IO[(EmbeddingClient, EmbeddingModelConfig)] =
    embedderRef.get() match {
      case Some(built) => IO.pure(built)
      case None =>
        blockingResult(modelRegistry.flatMap { registry =>
          given ModelRegistryService = registry
          Llm4sPipeline.embedder(config, providerRegistry)
        }).map { case (client, model) =>
          val traced = (client.withTracing(metricsTracing), model)
          embedderRef.compareAndSet(None, Some(traced))
          embedderRef.get().getOrElse(traced)
        }
    }

  // Chunkers by strategy, once built (the semantic one needs the embedding provider)
  private val chunkers = TrieMap.empty[ChunkerFactory.Strategy, DocumentChunker]

  def chunkerFor(strategyName: String): IO[DocumentChunker] =
    IO.fromEither(Llm4sPipeline.chunkerStrategy(strategyName).left.map(e => new RuntimeException(e.message))).flatMap { strategy =>
      chunkers.get(strategy) match {
        case Some(chunker) => IO.pure(chunker)
        case None =>
          blockingResult(modelRegistry.flatMap { registry =>
            given ModelRegistryService = registry
            Llm4sPipeline.chunker(strategy, config, providerRegistry)
          }).map(chunker => chunkers.putIfAbsent(strategy, chunker).getOrElse(chunker))
      }
    }

  /** Close the pipelines, each once the requests using it finish. */
  def close: IO[Unit] = pipelines.close
}

object RagPipelines {

  /** Run a synchronous llm4s call on the blocking pool and lift its `Result`. */
  def blockingResult[A](call: => Result[A]): IO[A] =
    IO.blocking(call).flatMap(result => IO.fromEither(result.left.map(e => new RuntimeException(e.message))))

  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[RAGService])
}
