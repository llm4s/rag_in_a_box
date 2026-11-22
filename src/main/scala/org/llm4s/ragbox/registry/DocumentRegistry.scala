package org.llm4s.ragbox.registry

import cats.effect.IO
import cats.syntax.all._
import java.time.Instant

/**
 * Entry representing an indexed document in the registry.
 *
 * @param documentId Unique document identifier
 * @param contentHash SHA-256 hash of document content for change detection
 * @param chunkCount Number of chunks created from the document
 * @param metadata Document metadata
 * @param collection Optional collection name for grouping documents
 * @param indexedAt When the document was first indexed
 * @param updatedAt When the document was last updated
 * @param readableBy Principal ids (llm4s `PrincipalId.value`) the document's vectors were
 *                   restricted to when ingested in permission mode; empty means "inherit from
 *                   the collection". Part of the upsert change check, so an access-list-only
 *                   change is re-applied.
 * @param sourceId The configured ingestion source that owns the document, if any. A source's
 *                 sync removes only documents it owns, so sources never delete each other's
 *                 documents (or ones uploaded through the API).
 */
final case class DocumentEntry(
  documentId: String,
  contentHash: String,
  chunkCount: Int,
  metadata: Map[String, String],
  collection: Option[String],
  indexedAt: Instant,
  updatedAt: Instant,
  readableBy: Set[Int] = Set.empty,
  sourceId: Option[String] = None
)

/**
 * A page of document entries and how many match in all.
 */
final case class DocumentEntryPage(entries: Seq[DocumentEntry], total: Int)

/**
 * Sync status information.
 *
 * @param lastSyncTime When the last sync was completed
 */
final case class SyncInfo(
  lastSyncTime: Option[Instant]
)

/**
 * Trait for document registry operations.
 *
 * The document registry tracks which documents have been ingested,
 * their content hashes for change detection, and sync status.
 */
object DocumentRegistry {

  /** `collection` is one of `allowed` or below one (`a/b` is below `a`). */
  def inCollections(collection: String, allowed: Set[String]): Boolean =
    allowed.exists(a => collection == a || collection.startsWith(a + "/"))
}

trait DocumentRegistry {

  /**
   * Get a document entry by ID.
   */
  def get(documentId: String): IO[Option[DocumentEntry]]

  /**
   * Put or update a document entry.
   */
  def put(entry: DocumentEntry): IO[Unit]

  /**
   * Remove a document entry.
   */
  def remove(documentId: String): IO[Unit]

  /**
   * Check if a document exists in the registry.
   */
  def contains(documentId: String): IO[Boolean]

  /**
   * List all document IDs.
   */
  def listIds(): IO[Seq[String]]

  /**
   * List all document entries (full details).
   */
  def listEntries(): IO[Seq[DocumentEntry]]

  /**
   * List document entries modified since a given time.
   */
  def listEntriesSince(since: Instant): IO[Seq[DocumentEntry]]

  /**
   * A page of entries for the sync API: those updated after `since` (ordered by update time)
   * or all (ordered by indexing time), restricted to `collections` and their sub-collections
   * when given ("default" is the documents with no collection), and to the documents sync source
   * `source` owns when given.
   *
   * The default filters [[listEntries]] in memory; database registries override it with a
   * paged query.
   */
  def listEntriesPage(
    since: Option[Instant],
    collections: Option[Set[String]],
    offset: Int,
    limit: Int,
    source: Option[String] = None
  ): IO[DocumentEntryPage] =
    since.fold(listEntries())(listEntriesSince).map { entries =>
      val visible = collections.fold(entries)(allowed =>
        entries.filter(e => DocumentRegistry.inCollections(e.collection.getOrElse("default"), allowed))
      ).filter(e => source.forall(s => e.sourceId.contains(s)))
      DocumentEntryPage(visible.slice(offset, offset + limit), visible.size)
    }

  /**
   * When the earliest current document was indexed and the latest one updated (None when there
   * are none). The default reads [[listEntries]]; database registries override it.
   */
  def ingestionTimes(): IO[(Option[Instant], Option[Instant])] =
    listEntries().map { entries =>
      (entries.map(_.indexedAt).minOption, entries.map(_.updatedAt).maxOption)
    }

  /**
   * Get multiple document entries by ID (batch lookup).
   */
  def getMultiple(documentIds: Seq[String]): IO[Seq[DocumentEntry]]

  /**
   * List document IDs in a specific collection.
   */
  def listIdsByCollection(collection: String): IO[Seq[String]]

  /**
   * List the IDs of documents owned by an ingestion source.
   */
  def listIdsBySource(sourceId: String): IO[Seq[String]]

  /**
   * List all unique collection names.
   */
  def listCollections(): IO[Seq[String]]

  /**
   * Get total document count.
   */
  def count(): IO[Int]

  /**
   * Get document count in a specific collection.
   */
  def countByCollection(collection: String): IO[Int]

  /**
   * Document count per collection, keyed like [[listCollections]] ("default" for documents with
   * no collection). Implementations backed by a database should override this with a single
   * query; the default issues one count per collection.
   */
  def countsByCollection(): IO[Map[String, Int]] =
    listCollections().flatMap { collections =>
      collections.toList.traverse(c => countByCollection(c).map(c -> _)).map(_.toMap)
    }

  /**
   * Clear all document entries.
   */
  def clear(): IO[Unit]

  /**
   * Get documents not in the provided keep list.
   */
  def findOrphans(keepIds: Set[String]): IO[Seq[String]]

  /**
   * Get sync status.
   */
  def getSyncInfo(): IO[SyncInfo]

  /**
   * Mark sync as completed.
   */
  def markSyncComplete(): IO[Unit]

  /**
   * Close any resources.
   */
  def close(): IO[Unit]
}
