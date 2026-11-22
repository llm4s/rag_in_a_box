package org.llm4s.ragbox.registry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class DocumentRegistrySpec extends AnyFlatSpec with Matchers {

  "DocumentEntry" should "be created with all fields" in {
    val now = Instant.now()
    val entry = DocumentEntry(
      documentId = "doc-123",
      contentHash = "abc123hash",
      chunkCount = 5,
      metadata = Map("author" -> "test"),
      collection = Some("docs"),
      indexedAt = now,
      updatedAt = now
    )

    entry.documentId `shouldBe` "doc-123"
    entry.contentHash `shouldBe` "abc123hash"
    entry.chunkCount `shouldBe` 5
    entry.metadata `shouldBe` Map("author" -> "test")
    entry.collection `shouldBe` Some("docs")
  }

  it should "support None collection" in {
    val now = Instant.now()
    val entry = DocumentEntry(
      documentId = "doc-456",
      contentHash = "def456hash",
      chunkCount = 3,
      metadata = Map.empty,
      collection = None,
      indexedAt = now,
      updatedAt = now
    )

    entry.collection `shouldBe` None
  }

  "DocumentRegistry.listEntriesPage" should "filter by owning source in its default (in-memory) form" in {
    import cats.effect.unsafe.implicits.global
    val registry = new org.llm4s.ragbox.testkit.InMemoryDocumentRegistry
    val now = Instant.now()
    def entry(id: String, source: Option[String], collection: Option[String] = None) =
      DocumentEntry(id, s"hash-$id", 1, Map.empty, collection, now, now, sourceId = source)
    val page = (for {
      _ <- registry.put(entry("a1", Some("api")))
      _ <- registry.put(entry("a2", Some("api"), Some("docs")))
      _ <- registry.put(entry("c1", Some("confluence")))
      _ <- registry.put(entry("u1", None))
      api <- registry.listEntriesPage(None, None, 0, 10, Some("api"))
      apiInDocs <- registry.listEntriesPage(None, Some(Set("docs")), 0, 10, Some("api"))
      all <- registry.listEntriesPage(None, None, 0, 10)
    } yield (api, apiInDocs, all)).unsafeRunSync()
    page._1.entries.map(_.documentId).toSet `shouldBe` Set("a1", "a2")
    page._1.total `shouldBe` 2
    page._2.entries.map(_.documentId) `shouldBe` Seq("a2")
    page._3.total `shouldBe` 4
  }
}
