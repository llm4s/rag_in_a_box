package org.llm4s.ragbox.testkit

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.{EmbeddingRequest, EmbeddingResponse, EmbeddingUsage}
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{EmbeddingProviderDescriptor, EmbeddingProviderSection, ProviderRegistry}
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicInteger

/**
 * A deterministic, in-process embedding provider for tests: no network, no API key.
 *
 * Text is embedded with the hashing trick - each lower-cased word adds 1 to component
 * `hash(word) mod dimensions`, and the vector is L2-normalised - so cosine similarity tracks how
 * many words two texts share. A query that repeats a document's distinctive words ranks that
 * document first, which lets retrieval tests assert *which* document came back.
 *
 * Use [[registry]] as the `ProviderRegistry` of a `RAGService`. It registers the provider as
 * `openai`, the id ragbox's configuration accepts, so no other code changes. Each instance
 * counts the texts it embedded, so a test can check that nothing was re-embedded.
 *
 * @param failFirstBuilds how many times building the provider fails before it succeeds, to
 *                        simulate an embedding provider that is unavailable at startup
 */
final class FakeEmbeddings(failFirstBuilds: Int = 0) {
  import FakeEmbeddings._

  private val embedded = new AtomicInteger(0)
  private val remainingBuildFailures = new AtomicInteger(failFirstBuilds)

  /** Number of texts this instance has embedded. */
  def embeddedCount: Int = embedded.get()

  private final class Provider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      embedded.addAndGet(request.input.size)
      // Reports token usage as a real provider does, so llm4s traces the call (with its duration)
      val tokens = request.input.map(_.split("\\s+").count(_.nonEmpty)).sum
      Right(EmbeddingResponse(
        embeddings = request.input.map(vectorFor),
        dim = Some(Dimensions),
        usage = Some(EmbeddingUsage(promptTokens = tokens, totalTokens = tokens))
      ))
    }
  }

  private object Descriptor extends EmbeddingProviderDescriptor {
    override val id: ProviderId = ProviderId("openai")
    override val modelDimensions: Map[String, Int] = Map(Model -> Dimensions)

    override def buildConfig(
      section: EmbeddingProviderSection,
      modelOverride: Option[String]
    ): Result[EmbeddingProviderConfig] =
      Right(EmbeddingProviderConfig("", modelOverride.orElse(section.model).getOrElse(Model), Secret("")))

    override def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
      if (remainingBuildFailures.getAndDecrement() > 0) Left(ConfigurationError("fake embedding provider unavailable"))
      else Right(new Provider)
  }

  /** A provider registry whose only embedding provider is this one, registered as `openai`. */
  val registry: ProviderRegistry = ProviderRegistry.ofEmbeddings(Descriptor)
}

object FakeEmbeddings {

  val Model: String = "fake-embedding"
  val Dimensions: Int = 64

  def apply(failFirstBuilds: Int = 0): FakeEmbeddings = new FakeEmbeddings(failFirstBuilds)

  def vectorFor(text: String): Seq[Double] = {
    val counts = Array.fill(Dimensions)(0.0)
    text.toLowerCase.split("[^a-z0-9]+").filter(_.nonEmpty).foreach { word =>
      counts(Math.floorMod(word.hashCode, Dimensions)) += 1.0
    }
    val norm = math.sqrt(counts.map(c => c * c).sum)
    // An empty text still needs a non-zero vector for cosine distance
    if (norm == 0.0) counts.indices.map(i => if (i == 0) 1.0 else 0.0)
    else counts.toSeq.map(_ / norm)
  }
}
