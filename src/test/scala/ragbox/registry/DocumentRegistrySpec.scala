package ragbox.registry

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
}
