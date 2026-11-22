package org.llm4s.ragbox.testkit

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.{LLMClient, LlmClientOptions}
import org.llm4s.llmconnect.config.{ContextWindowResolver, ProviderConfig}
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk, TokenUsage}
import org.llm4s.llmconnect.spi.{ProviderConfigSpec, ProviderDescriptor}
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.{ProviderId, asUrl}
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

  /** The base URL the last client was built with. */
  @volatile var lastBaseUrl: Option[String] = None

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
      Right(Completion(
        id = s"fake-${counter.incrementAndGet()}",
        created = Instant.now(),
        content = content,
        model = Model,
        message = AssistantMessage(content),
        usage = Some(TokenUsage(promptTokens = 100, completionTokens = 10, totalTokens = 110))
      ))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int = 128000
    override def getReserveCompletion(): Int = 4096
  }

  private final case class Config(model: String, endpointUrl: Option[String]) extends ProviderConfig {
    override def providerId: ProviderId = Id
    override def contextWindow: Int = 128000
    override def reserveCompletion: Int = 4096
    override def withModel(model: String): ProviderConfig = copy(model = model)
  }

  /** The provider, registered as `fake`. */
  val descriptor: ProviderDescriptor = new ProviderDescriptor {
    override def id: ProviderId = Id
    override def configSpec: ProviderConfigSpec = ProviderConfigSpec()

    override def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] = {
      lastBaseUrl = section.baseUrl.map(_.asUrl)
      Right(Config(section.model.toString, lastBaseUrl))
    }

    override def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
      ModelRegistryService
    ): Result[LLMClient] = Right(new Client)
  }
}

object FakeLLM {
  val Id: ProviderId = ProviderId("fake")
  val Model: String = "fake-model"
  val Answer: String = "A fake answer."

  def apply(): FakeLLM = new FakeLLM
}
