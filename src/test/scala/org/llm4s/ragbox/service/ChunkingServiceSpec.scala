package org.llm4s.ragbox.service

import cats.effect.unsafe.implicits.global
import org.llm4s.ragbox.model.{ChunkingCompareRequest, ChunkingPreviewRequest}
import org.llm4s.ragbox.testkit.TestFixtures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChunkingServiceSpec extends AnyFlatSpec with Matchers {

  private val service = ChunkingService(TestFixtures.testAppConfig)

  private val prose = "The quick brown fox jumps over the lazy dog. " * 40

  "preview" should "not label the sentence fallback as semantic output" in {
    val response = service.preview(ChunkingPreviewRequest(content = prose, strategy = Some("semantic"))).unsafeRunSync()

    response.strategy shouldBe "sentence"
    response.config.strategy shouldBe "sentence"
    response.config.source shouldBe "semantic-fallback"
    response.chunks should not be empty
    response.warnings.exists(_.message.contains("not previewed")) shouldBe true
  }

  "compare" should "report semantic as not previewed instead of showing sentence chunks" in {
    val response = service.compare(ChunkingCompareRequest(content = prose, strategies = Seq("sentence", "semantic"))).unsafeRunSync()

    val semantic = response.results.find(_.strategy == "semantic").getOrElse(fail("no semantic result"))
    semantic.chunks shouldBe empty
    semantic.warnings.exists(_.message.contains("not previewed")) shouldBe true
    response.recommendation.map(_.strategy) shouldBe Some("sentence")
  }

  it should "recommend markdown for multi-line markdown, which String.matches never detected" in {
    val markdown = "# Guide\n\nSome introduction text.\n\n## Install\n\nRun the installer and follow the steps.\n"
    val response = service.compare(ChunkingCompareRequest(content = markdown, strategies = Seq("simple", "sentence", "markdown"))).unsafeRunSync()

    response.recommendation.map(_.strategy) shouldBe Some("markdown")
    response.recommendation.map(_.reason) shouldBe Some("Content appears to be markdown")
  }
}
