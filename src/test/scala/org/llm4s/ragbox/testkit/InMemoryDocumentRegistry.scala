package org.llm4s.ragbox.testkit

import cats.effect.IO
import org.llm4s.ragbox.registry.{DocumentEntry, DocumentRegistry, SyncInfo}
import java.time.Instant
import scala.collection.mutable

/**
 * In-memory implementation of DocumentRegistry for fast testing.
 *
 * Thread-safe for sequential IO operations.
 */
class InMemoryDocumentRegistry extends DocumentRegistry {

  private val documents: mutable.Map[String, DocumentEntry] = mutable.Map.empty
  private var lastSyncTime: Option[Instant] = None

  override def get(documentId: String): IO[Option[DocumentEntry]] =
    IO.pure(documents.get(documentId))

  override def put(entry: DocumentEntry): IO[Unit] =
    IO(documents.update(entry.documentId, entry))

  override def remove(documentId: String): IO[Unit] =
    IO(documents.remove(documentId)).void

  override def contains(documentId: String): IO[Boolean] =
    IO.pure(documents.contains(documentId))

  override def listIds(): IO[Seq[String]] =
    IO.pure(documents.keys.toSeq)

  override def listEntries(): IO[Seq[DocumentEntry]] =
    IO.pure(documents.values.toSeq)

  override def listEntriesSince(since: Instant): IO[Seq[DocumentEntry]] =
    IO.pure(documents.values.filter(_.updatedAt.isAfter(since)).toSeq)

  override def getMultiple(documentIds: Seq[String]): IO[Seq[DocumentEntry]] =
    IO.pure(documentIds.flatMap(documents.get))

  override def listIdsByCollection(collection: String): IO[Seq[String]] =
    IO.pure(documents.values.filter(_.collection.contains(collection)).map(_.documentId).toSeq)

  override def listCollections(): IO[Seq[String]] =
    IO.pure(documents.values.flatMap(_.collection).toSeq.distinct)

  override def count(): IO[Int] =
    IO.pure(documents.size)

  override def countByCollection(collection: String): IO[Int] =
    IO.pure(documents.values.count(_.collection.contains(collection)))

  override def clear(): IO[Unit] =
    IO(documents.clear())

  override def findOrphans(keepIds: Set[String]): IO[Seq[String]] =
    IO.pure(documents.keys.filterNot(keepIds.contains).toSeq)

  override def getSyncInfo(): IO[SyncInfo] =
    IO.pure(SyncInfo(lastSyncTime))

  override def markSyncComplete(): IO[Unit] =
    IO { lastSyncTime = Some(Instant.now()) }

  override def initialize(): IO[Unit] = IO.unit

  override def close(): IO[Unit] = IO.unit

  // ============================================================
  // Test Helper Methods
  // ============================================================

  /**
   * Clear all documents for test isolation.
   */
  def reset(): Unit = {
    documents.clear()
    lastSyncTime = None
  }

  /**
   * Get the internal document count for assertions.
   */
  def documentCount: Int = documents.size

  /**
   * Check if a specific document exists.
   */
  def hasDocument(documentId: String): Boolean = documents.contains(documentId)

  /**
   * Seed the registry with test documents.
   */
  def seed(entries: Seq[DocumentEntry]): Unit = {
    entries.foreach(e => documents.update(e.documentId, e))
  }
}

object InMemoryDocumentRegistry {
  /**
   * Create a new in-memory document registry.
   */
  def apply(): InMemoryDocumentRegistry = new InMemoryDocumentRegistry()

  /**
   * Create a registry pre-seeded with test documents.
   */
  def withDocuments(entries: Seq[DocumentEntry]): InMemoryDocumentRegistry = {
    val registry = new InMemoryDocumentRegistry()
    registry.seed(entries)
    registry
  }
}
