package org.llm4s.ragbox.testkit

import org.llm4s.config.NamedProviderConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk, TokenUsage}
import org.llm4s.llmconnect.spi.{ProviderBuildContext, ProviderConfigContext, ProviderConfigSpec, ProviderDescriptor}
import org.llm4s.types.ProviderModelTypes.{BaseUrl, ModelName, ProviderId}
import org.llm4s.types.Result

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * A scripted, in-process LLM provider for tests: no network, no API key.
 *
 * Register [[descriptor]] in a `ProviderRegistry` (e.g. `FakeEmbeddings().registry.withProvider(llm.descriptor)`)
 * and configure `llm.model = "fake/<model>"`: ragbox then resolves it through the registry like
 * any real provider. Every completion is recorded with its options, so a test can check what was
 * sent - the conversation history, the temperature, a query-rewrite or rerank prompt.
 *
 * A streamed completion arrives word by word ([[FakeLLM.pieces]]).
 *
 * Replies: a query-rewrite request (llm4s `LLMQueryRewriter`) gets `rewritten: <query>`; an LLM
 * rerank request gets a JSON array of scores, highest last, so reranking reverses the candidates;
 * anything else gets [[Answer]].
 */
final class FakeLLM {
  import FakeLLM._

  private val calls = new ConcurrentLinkedQueue[(Conversation, CompletionOptions)]()
  private val counter = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Completions requested so far, oldest first. */
  def requests: Seq[(Conversation, CompletionOptions)] = calls.asScala.toSeq

  private val streamed = new java.util.concurrent.atomic.AtomicInteger(0)

  /** How many of the completions were streamed (each as its words, one piece per word). */
  def streamedCount: Int = streamed.get()

  /** The base URL the last client was built with. */
  @volatile var lastBaseUrl: Option[String] = None

  /** While set, every completion fails with this message (a provider outage). */
  @volatile var failing: Option[String] = None

  private def reply(conversation: Conversation): String = {
    val system = conversation.messages.headOption.map(_.content).getOrElse("")
    val user = conversation.messages.lastOption.map(_.content).getOrElse("")
    if (system.contains("query rewriting")) s"rewritten: $user"
    else if (user.contains("Respond with ONLY a JSON array of scores")) {
      val documents = "(?m)^Document \\d+:".r.findAllIn(user).size
      (1 to documents).map(i => (i.toDouble / (documents + 1)).toString).mkString("[", ", ", "]")
    } else Answer
  }

  private final class Client extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.add(conversation -> options)
      val content = reply(conversation)
      failing.fold[Result[Completion]](Right(Completion(
        id = s"fake-${counter.incrementAndGet()}",
        created = Instant.now(),
        model = Model,
        message = AssistantMessage(content),
        usage = Some(TokenUsage(promptTokens = 100, completionTokens = 10, totalTokens = 110))
      )))(message => Left(org.llm4s.error.ProcessingError("fake-llm", message)))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions
    )(onChunk: StreamedChunk => Unit): Result[Completion] =
      complete(conversation, options).map { completion =>
        streamed.incrementAndGet()
        pieces(completion.content).foreach(piece => onChunk(StreamedChunk(id = completion.id, contentDelta = Some(piece))))
        completion
      }

    override def contextWindow: Int = 128000
    override def reserveCompletion: Int = 4096
  }

  private final case class Config(model: ModelName, endpointUrl: Option[BaseUrl]) extends ProviderConfig {
    override def providerId: ProviderId = Id
    override def contextWindow: Int = 128000
    override def reserveCompletion: Int = 4096
    override def withModel(model: ModelName): ProviderConfig = copy(model = model)
    override def apiKeyProvider: Option[org.llm4s.llmconnect.auth.ApiKeyProvider] = None
    override def network: org.llm4s.http.HttpNetworkSettings = org.llm4s.http.HttpNetworkSettings.default
    override def timeouts: org.llm4s.llmconnect.config.ProviderTimeouts = org.llm4s.llmconnect.config.ProviderTimeouts.default
    override def withApiKeyProvider(provider: org.llm4s.llmconnect.auth.ApiKeyProvider): Result[ProviderConfig] = Right(this)
    override def withNetwork(network: org.llm4s.http.HttpNetworkSettings): ProviderConfig = this
    override def withTimeouts(timeouts: org.llm4s.llmconnect.config.ProviderTimeouts): ProviderConfig = this
  }

  /** The provider, registered as `fake`. */
  val descriptor: ProviderDescriptor = new ProviderDescriptor {
    override def id: ProviderId = Id
    override def configSpec: ProviderConfigSpec = ProviderConfigSpec()

    override def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig] = {
      lastBaseUrl = section.baseUrl.map(_.asUrl)
      Right(Config(section.model, section.baseUrl))
    }

    override def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient] = Right(new Client)
  }
}

object FakeLLM {
  val Id: ProviderId = ProviderId("fake")
  val Model: ModelName = ModelName("fake-model")
  val Answer: String = "A fake answer."

  /** The pieces a streamed completion of `content` arrives in: its words, each with the space before it. */
  def pieces(content: String): Seq[String] = content.split("(?= )").toSeq

  def apply(): FakeLLM = new FakeLLM
}
