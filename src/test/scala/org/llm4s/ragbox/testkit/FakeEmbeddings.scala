package org.llm4s.ragbox.testkit

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.{EmbeddingRequest, EmbeddingResponse}
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
 * Use [[FakeEmbeddings.registry]] as the `ProviderRegistry` of a `RAGService`. It registers the
 * provider as `openai`, the id ragbox's configuration accepts, so no other code changes.
 */
object FakeEmbeddings {

  val Model: String = "fake-embedding"
  val Dimensions: Int = 64

  /** Number of texts embedded so far, across every provider built here. */
  private val embeddedTexts = new AtomicInteger(0)

  /** Texts embedded since the JVM started; compare before and after to count embedding work. */
  def embeddedCount: Int = embeddedTexts.get()

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

  final class Provider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      embeddedTexts.addAndGet(request.input.size)
      Right(EmbeddingResponse(embeddings = request.input.map(vectorFor), dim = Some(Dimensions)))
    }
  }

  object Descriptor extends EmbeddingProviderDescriptor {
    override val id: ProviderId = ProviderId("openai")
    override val modelDimensions: Map[String, Int] = Map(Model -> Dimensions)

    override def buildConfig(
      section: EmbeddingProviderSection,
      modelOverride: Option[String]
    ): Result[EmbeddingProviderConfig] =
      Right(EmbeddingProviderConfig("", modelOverride.orElse(section.model).getOrElse(Model), Secret("")))

    override def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = Right(new Provider)
  }

  /** A provider registry whose only embedding provider is the fake one, registered as `openai`. */
  val registry: ProviderRegistry = ProviderRegistry.ofEmbeddings(Descriptor)
}
