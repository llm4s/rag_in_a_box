package org.llm4s.ragbox.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.ragbox.testkit.{InMemoryChunkStore, InMemoryDocumentRegistry, TestFixtures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

class RAGServiceLifecycleSpec extends AnyFlatSpec with Matchers {

  private class FailingCloseRegistry extends InMemoryDocumentRegistry {
    override def close(): IO[Unit] = IO.raiseError(new IllegalStateException("registry close failed"))
  }

  private class TrackingChunkStore extends InMemoryChunkStore {
    val closed = new AtomicBoolean(false)
    override def close(): IO[Unit] = IO(closed.set(true))
  }

  "RAGService.close" should "close every resource even when one of them fails to close" in {
    val chunks = new TrackingChunkStore
    val service = new RAGService(TestFixtures.testAppConfig, new FailingCloseRegistry, chunks)

    val result = service.close().attempt.unsafeRunSync()

    result.left.map(_.getMessage) shouldBe Left("registry close failed")
    chunks.closed.get() shouldBe true
  }

  it should "stop the RAG instance from being built afterwards" in {
    val service = new RAGService(TestFixtures.testAppConfig, new InMemoryDocumentRegistry, new InMemoryChunkStore)
    service.close().unsafeRunSync()

    service.rag.attempt.unsafeRunSync().left.map(_.getMessage) shouldBe Left("RAG service is closed")
  }
}
