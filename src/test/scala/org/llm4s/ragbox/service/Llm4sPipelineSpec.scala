package org.llm4s.ragbox.service

import org.llm4s.chunking.ChunkerFactory
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.RerankingStrategy
import org.llm4s.ragbox.config.{AppConfig, ApiKeysConfig, EmbeddingConfig}
import org.llm4s.ragbox.testkit.{FakeEmbeddings, FakeLLM, TestFixtures}
import org.llm4s.security.Secret
import org.llm4s.trace.NoOpTracing
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** How ragbox resolves providers and assembles the llm4s pipeline config (issue #21). No network. */
class Llm4sPipelineSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = ModelRegistryService.default().fold(e => fail(e.message), identity)

  private val base: AppConfig = TestFixtures.testAppConfig

  private def withLlm(model: String, baseUrl: Option[String] = None, keys: ApiKeysConfig = base.apiKeys): AppConfig =
    base.copy(llm = base.llm.copy(model = model, baseUrl = baseUrl), apiKeys = keys)

  private val noKeys = ApiKeysConfig()

  "llmClient" should "resolve every provider through the default llm4s registry, Ollama included" in {
    val registry = ProviderRegistry.default
    registry.ids should contain allOf ("openai", "anthropic", "ollama")

    Llm4sPipeline.llmClient(withLlm("ollama/llama3.1", Some("http://ollama.internal:11434"), noKeys), registry)
      .map(_._2.endpointUrl) shouldBe Right(Some("http://ollama.internal:11434"))
    Llm4sPipeline.llmClient(withLlm("anthropic/claude-sonnet-4-5"), registry).map(_._2.model) shouldBe
      Right("claude-sonnet-4-5")
    Llm4sPipeline.llmClient(withLlm("gpt-4o-mini"), registry).map(_._2.providerId.asString) shouldBe Right("openai")
  }

  it should "report a missing API key and an unknown provider instead of swallowing them" in {
    val missingKey = Llm4sPipeline.llmClient(withLlm("openai/gpt-4o", keys = noKeys), ProviderRegistry.default)
    missingKey.left.map(_.message).left.getOrElse("") should include("needs an API key")

    val unknown = Llm4sPipeline.llmClient(withLlm("nosuchprovider/model"), ProviderRegistry.default)
    unknown.isLeft shouldBe true
  }

  it should "pass the configured base URL to a provider registered by the application" in {
    val llm = FakeLLM()
    val registry = ProviderRegistry.of(llm.descriptor)
    Llm4sPipeline.llmClient(withLlm("fake/m", Some("http://gateway:8080")), registry).isRight shouldBe true
    llm.lastBaseUrl shouldBe Some("http://gateway:8080")
  }

  "ragConfig" should "pass embedding dimensions and keep keyword search in PostgreSQL in both modes" in {
    val config = base.copy(embedding = EmbeddingConfig("voyage", "voyage-3", Some(512)))
    val rag = Llm4sPipeline.ragConfig(config, searchIndex = None, llm = None, tracing = new NoOpTracing).fold(e => fail(e.message), identity)

    rag.embeddingProvider.asString shouldBe "voyage"
    rag.embeddingModel shouldBe Some("voyage-3")
    rag.embeddingDimensions shouldBe Some(512)
    rag.pgKeywordTableName shouldBe Some(Llm4sPipeline.KeywordTableName)
    rag.pgVectorTableName shouldBe Some(base.database.tableName)
    rag.rerankingStrategy shouldBe RerankingStrategy.None
    rag.queryTransformers shouldBe empty
    rag.tracer shouldBe defined
  }

  it should "configure reranking and query rewriting, off by default" in {
    val llm = FakeLLM()
    val client = Llm4sPipeline.llmClient(withLlm("fake/m"), ProviderRegistry.of(llm.descriptor)).fold(e => fail(e.message), _._1)
    val config = base.copy(rag = base.rag.copy(
      reranking = base.rag.reranking.copy(strategy = "llm", candidates = 12),
      queryTransform = base.rag.queryTransform.copy(strategy = "llm-rewrite")
    ))
    val rag = Llm4sPipeline.ragConfig(config, None, Some(client), new NoOpTracing).fold(e => fail(e.message), identity)
    rag.rerankingStrategy shouldBe RerankingStrategy.LLM
    rag.rerankTopK shouldBe 12
    rag.queryTransformers.map(_.name) shouldBe Seq("llm-query-rewriter")

    val cohere = base.copy(
      rag = base.rag.copy(reranking = base.rag.reranking.copy(strategy = "cohere")),
      apiKeys = base.apiKeys.withKey("cohere", Secret("co-key"))
    )
    Llm4sPipeline.ragConfig(cohere, None, None, new NoOpTracing).map(_.rerankingStrategy) shouldBe
      Right(RerankingStrategy.Cohere("rerank-english-v3.0"))
    Llm4sPipeline.rerankerConfig(cohere).map(_.map(_.model)) shouldBe Right(Some("rerank-english-v3.0"))
  }

  it should "refuse LLM-based features when no LLM is available" in {
    val config = base.copy(rag = base.rag.copy(queryTransform = base.rag.queryTransform.copy(strategy = "llm-rewrite")))
    Llm4sPipeline.ragConfig(config, None, None, new NoOpTracing).left.map(_.message).left.getOrElse("") should
      include("needs the answer LLM")
  }

  "chunker" should "build the real semantic chunker from the embedding provider" in {
    val config = base.copy(
      embedding = EmbeddingConfig("openai", FakeEmbeddings.Model, Some(FakeEmbeddings.Dimensions)),
      rag = base.rag.copy(chunking = base.rag.chunking.copy(strategy = "semantic"))
    )
    val chunker = Llm4sPipeline.chunker(config, FakeEmbeddings().registry).fold(e => fail(e.message), identity)
    chunker.getClass.getSimpleName shouldBe "SemanticChunker"

    Llm4sPipeline.chunker(base, FakeEmbeddings().registry).map(_.getClass) shouldBe
      Right(ChunkerFactory.create(ChunkerFactory.Strategy.Simple).getClass)
  }

  "AppConfig.validate" should "reject unknown reranking and query-transform strategies, and Cohere without a key" in {
    val bad = base.copy(rag = base.rag.copy(
      reranking = base.rag.reranking.copy(strategy = "magic"),
      queryTransform = base.rag.queryTransform.copy(strategy = "hyde")
    ))
    bad.validate().exists(_.contains("Unknown reranking strategy")) shouldBe true
    bad.validate().exists(_.contains("Unknown query transform")) shouldBe true

    val cohere = base.copy(rag = base.rag.copy(reranking = base.rag.reranking.copy(strategy = "cohere")))
    cohere.validate().exists(_.contains("COHERE_API_KEY")) shouldBe true
  }
}
