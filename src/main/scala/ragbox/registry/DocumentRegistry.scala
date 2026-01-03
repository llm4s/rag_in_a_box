package ragbox.registry

import cats.effect.IO
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
 */
final case class DocumentEntry(
  documentId: String,
  contentHash: String,
  chunkCount: Int,
  metadata: Map[String, String],
  collection: Option[String],
  indexedAt: Instant,
  updatedAt: Instant
)

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
   * Get multiple document entries by ID (batch lookup).
   */
  def getMultiple(documentIds: Seq[String]): IO[Seq[DocumentEntry]]

  /**
   * List document IDs in a specific collection.
   */
  def listIdsByCollection(collection: String): IO[Seq[String]]

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
   * Initialize the registry (create tables if needed).
   */
  def initialize(): IO[Unit]

  /**
   * Close any resources.
   */
  def close(): IO[Unit]
}
