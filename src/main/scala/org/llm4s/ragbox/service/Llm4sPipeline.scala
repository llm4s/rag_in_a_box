package org.llm4s.ragbox.service

import org.llm4s.chunking.{ChunkerFactory, DocumentChunker}
import org.llm4s.config.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.{EmbeddingClient, LLMClient}
import org.llm4s.llmconnect.config.{EmbeddingModelConfig, EmbeddingProviderConfig, ProviderConfig}
import org.llm4s.llmconnect.model.{AssistantMessage, Conversation, Message, SystemMessage, UserMessage}
import org.llm4s.llmconnect.spi.{EmbeddingBuildContext, EmbeddingConfigContext, EmbeddingProviderSection, ProviderBuildContext, ProviderConfigContext, ProviderRegistry}
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{RAGConfig, RAGSearchResult}
import org.llm4s.rag.permissions.SearchIndex
import org.llm4s.rag.transform.LLMQueryRewriter
import org.llm4s.reranker.{CohereReranker, RerankProviderConfig}
import org.llm4s.trace.Tracing
import org.llm4s.types.ProviderModelTypes.{ApiKey, BaseUrl, ModelName}
import org.llm4s.types.Result
import org.llm4s.ragbox.config.{AppConfig, QueryTransformSettings, RerankingSettings}

/**
 * How ragbox assembles its llm4s pipeline from [[AppConfig]]: every provider is resolved through
 * llm4s's [[ProviderRegistry]], so any provider module on the classpath (and any provider a test
 * registers) can be named in configuration, with nothing in ragbox matching on provider names.
 */
object Llm4sPipeline {

  /** Keyword (BM25 / PostgreSQL full-text) base table; llm4s creates `documents_keyword`. */
  val KeywordTableName: String = "documents"

  /**
   * Build the answer LLM client: resolve the configured provider id through the registry, let its
   * descriptor turn ragbox's settings into the provider's config, then build the client.
   *
   * @return the client and the provider config it was built from, or the reason it could not be built
   */
  def llmClient(config: AppConfig, registry: ProviderRegistry)(using ModelRegistryService): Result[(LLMClient, ProviderConfig)] = {
    val id = registry.canonicalId(config.llm.providerId)
    val apiKey = config.apiKeys.getKey(id.asString)
    for {
      descriptor <- registry.resolve(id)
      _ <- Either.cond(
        !descriptor.configSpec.requiresApiKey || apiKey.isDefined,
        (),
        ConfigurationError(s"LLM provider '${id.asString}' needs an API key, and none is configured")
      )
      section = NamedProviderConfig(
        provider = id,
        model = ModelName(config.llm.modelName),
        baseUrl = config.llm.baseUrl.map(BaseUrl(_)),
        apiKey = apiKey.map(key => ApiKey(key.reveal))
      )
      providerConfig <- descriptor.buildConfig(section, ProviderConfigContext.of(id.asString))
      client <- descriptor.build(providerConfig, ProviderBuildContext.of())
    } yield (client, providerConfig)
  }

  /**
   * The embedding provider config llm4s asks for when it builds the pipeline (and that the
   * semantic chunker is built from). The provider's descriptor supplies its defaults; ragbox
   * supplies the API key, model and base URL from its own configuration.
   */
  def embeddingProviderConfig(config: AppConfig, registry: ProviderRegistry)(providerId: String): Result[EmbeddingProviderConfig] = {
    val id = registry.canonicalEmbeddingId(providerId)
    for {
      descriptor <- registry.resolveEmbedding(id)
      section = EmbeddingProviderSection(
        apiKey = config.apiKeys.getKey(id.asString),
        baseUrl = config.embedding.baseUrl.map(BaseUrl(_))
      )
      providerConfig <- descriptor.buildConfig(section, EmbeddingConfigContext(Some(ModelName(config.embedding.model))))
    } yield providerConfig
  }

  /** The Cohere reranker config, when Cohere reranking is configured. */
  def rerankerConfig(config: AppConfig): Result[Option[RerankProviderConfig]] =
    config.rag.reranking.normalizedStrategy match {
      case Right("cohere") =>
        config.apiKeys.getKey("cohere")
          .toRight(ConfigurationError("Cohere reranking needs COHERE_API_KEY"))
          .map(key => Some(RerankProviderConfig(CohereReranker.DEFAULT_BASE_URL, key, config.rag.reranking.model)))
      case _ => Right(None)
    }

  /**
   * The llm4s [[RAGConfig]] for ragbox's settings.
   *
   * Storage is PostgreSQL for both halves of hybrid search in both modes (`withPgHybrid`): vectors
   * in pgvector and BM25 keywords in PostgreSQL full-text search, so keyword search survives a
   * restart. In permission mode the [[SearchIndex]] is attached as well: llm4s then searches
   * through it (`queryWithPermissions`), with the same fusion, reranking and query transforms, and
   * its keyword half is PostgreSQL full-text search over the index's own table.
   *
   * The answer temperature is not here: every answer passes its completion options with the call.
   */
  def ragConfig(
    config: AppConfig,
    searchIndex: Option[SearchIndex],
    llm: Option[LLMClient],
    tracing: Tracing
  ): Result[RAGConfig] = {
    val embedding = config.embedding
    val withEmbeddings = embedding.dimensions match {
      case Some(dims) => RAGConfig.default.withEmbeddings(embedding.providerId, embedding.model, dims)
      case None => RAGConfig.default.withEmbeddings(embedding.providerId, embedding.model)
    }
    val base = withEmbeddings
      .withChunking(config.rag.chunking.toChunkerStrategy, config.rag.chunking.size, config.rag.chunking.overlap)
      .withTopK(config.rag.search.topK)
      .withFusion(config.rag.search.toFusionStrategy)
      .withSystemPrompt(config.rag.systemPrompt)
      .withTracing(tracing)

    val db = config.database
    val stored = searchIndex.fold(base)(base.withSearchIndex).withPgHybrid(
      connectionString = db.connectionString,
      user = db.effectiveUser,
      password = db.effectivePassword,
      vectorTableName = db.tableName,
      keywordTableName = searchIndex.flatMap(_.pgConfig).map(_.keywordTableName).getOrElse(KeywordTableName)
    )

    def needsLlm(feature: String): Result[LLMClient] =
      llm.toRight(ConfigurationError(s"$feature needs the answer LLM, which is not available (see the LLM warning above)"))

    for {
      reranking <- config.rag.reranking.normalizedStrategy.left.map(ConfigurationError(_))
      reranked <- reranking match {
        case "cohere" => Right(stored.withCohereReranking(config.rag.reranking.model).withRerankTopK(config.rag.reranking.candidates))
        case "llm" => needsLlm("LLM reranking").map(_ => stored.withLLMReranking.withRerankTopK(config.rag.reranking.candidates))
        case _ => Right(stored)
      }
      transform <- config.rag.queryTransform.normalizedStrategy.left.map(ConfigurationError(_))
      transformed <- transform match {
        case "llm-rewrite" => needsLlm("LLM query rewriting").map(client => reranked.withQueryTransformer(LLMQueryRewriter(client)))
        case _ => Right(reranked)
      }
    } yield llm.fold(transformed)(transformed.withLLM)
  }

  /**
   * The embedding client and model ragbox embeds with outside a pipeline: for the semantic
   * chunker, which embeds sentences to find its boundaries. Built the way llm4s's `RAG` builds
   * its own.
   */
  def embedder(config: AppConfig, registry: ProviderRegistry)(using ModelRegistryService): Result[(EmbeddingClient, EmbeddingModelConfig)] = {
    val id = registry.canonicalEmbeddingId(config.embedding.providerId)
    for {
      descriptor <- registry.resolveEmbedding(id)
      providerConfig <- embeddingProviderConfig(config, registry)(id.asString)
      provider <- descriptor.build(providerConfig, EmbeddingBuildContext())
    } yield {
      val dims = config.embedding.dimensions.orElse(descriptor.dimensionsOf(providerConfig.model)).getOrElse(1536)
      (EmbeddingClient(provider).withProviderId(id), EmbeddingModelConfig(providerConfig.model, dims))
    }
  }

  /**
   * A chunker for `strategy`, built the way llm4s's `RAG` builds its own: the semantic chunker
   * embeds sentences with the configured embedding provider, every other strategy is
   * `ChunkerFactory.create`. ragbox chunks each document once - with the strategy its collection
   * and the runtime settings resolve to - and hands the chunks to llm4s, so the chunk store holds
   * exactly what was indexed.
   */
  def chunker(strategy: ChunkerFactory.Strategy, config: AppConfig, registry: ProviderRegistry)(using ModelRegistryService): Result[DocumentChunker] =
    strategy match {
      case ChunkerFactory.Strategy.Semantic =>
        embedder(config, registry).map { case (client, model) => ChunkerFactory.semantic(client, model) }
      case other => Right(ChunkerFactory.create(other))
    }

  /** The chunker for the configuration file's chunking strategy. */
  def chunker(config: AppConfig, registry: ProviderRegistry)(using ModelRegistryService): Result[DocumentChunker] =
    chunker(config.rag.chunking.toChunkerStrategy, config, registry)

  /** The llm4s chunking strategy named `name` (`simple`, `sentence`, `markdown` or `semantic`). */
  def chunkerStrategy(name: String): Result[ChunkerFactory.Strategy] =
    ChunkerFactory.Strategy.fromString(name).toRight(ConfigurationError(s"Unknown chunking strategy: $name"))

  /**
   * The settings an llm4s `RAG` instance bakes in when it is built, so the key it is kept by (see
   * [[PipelinePool]]): equal settings share an instance, and changing any of them - at runtime,
   * or for an experiment variant - needs another. topK and the answer temperature are not part
   * of it: every call passes them.
   */
  final case class PipelineSettings(
    fusionStrategy: String,
    rrfK: Int,
    systemPrompt: String,
    reranking: RerankingSettings,
    queryTransform: QueryTransformSettings
  ) {

    /** `config` with these settings, for [[ragConfig]] and [[rerankerConfig]]. */
    def applyTo(config: AppConfig): AppConfig =
      config.copy(rag = config.rag.copy(
        search = config.rag.search.copy(fusionStrategy = fusionStrategy, rrfK = rrfK),
        systemPrompt = systemPrompt,
        reranking = reranking,
        queryTransform = queryTransform
      ))
  }

  object PipelineSettings {

    /** The configuration file's pipeline settings. */
    def from(config: AppConfig): PipelineSettings =
      PipelineSettings(
        fusionStrategy = config.rag.search.fusionStrategy,
        rrfK = config.rag.search.rrfK,
        systemPrompt = config.rag.systemPrompt,
        reranking = config.rag.reranking,
        queryTransform = config.rag.queryTransform
      )
  }

  /** One earlier message of a chat session. */
  final case class ChatTurn(role: String, content: String)

  /** A chat session's earlier messages as the `history` llm4s's answer calls take. */
  def history(turns: Seq[ChatTurn]): Seq[Message] =
    turns.map {
      case ChatTurn("assistant", content) => AssistantMessage(content)
      case ChatTurn(_, content) => UserMessage(content)
    }

  /**
   * The conversation llm4s's `RAG.queryWithAnswer` sends the LLM - the system prompt, the history,
   * then the numbered contexts and the question - for the one answer ragbox asks the LLM for
   * itself: an answer from the chunks above a similarity threshold (see `QueryService`).
   */
  def answerConversation(
    systemPrompt: String,
    history: Seq[Message],
    contexts: Seq[RAGSearchResult],
    question: String
  ): Conversation = {
    val contextText = contexts.zipWithIndex.map { case (ctx, i) => s"[${i + 1}] ${ctx.content}" }.mkString("\n\n")
    Conversation(
      (SystemMessage(systemPrompt) +: history) :+
        UserMessage(s"Context:\n$contextText\n\nQuestion: $question\n\nAnswer:")
    )
  }
}
