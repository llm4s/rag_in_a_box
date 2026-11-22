package org.llm4s.ragbox.store

import cats.effect.IO
import org.llm4s.ragbox.model.{ChunkInfo, ChunkListResponse, ChunkSizeDistribution}

/** A collection's chunk count and average chunk size (characters). */
final case class CollectionChunkStats(chunkCount: Int, avgChunkSize: Double)

/**
 * Trait for storing and retrieving chunk information.
 *
 * This provides visibility into how documents have been chunked,
 * allowing users to understand and tune their RAG configuration.
 */
trait ChunkStore {

  /**
   * Store chunks for a document.
   * Replaces any existing chunks for the document.
   */
  def store(documentId: String, chunks: Seq[ChunkInfo]): IO[Unit]

  /**
   * Get all chunks for a document.
   */
  def getChunks(documentId: String): IO[Seq[ChunkInfo]]

  /**
   * Get a specific chunk by document ID and index.
   */
  def getChunk(documentId: String, index: Int): IO[Option[ChunkInfo]]

  /**
   * List all chunks with pagination.
   */
  def listChunks(page: Int, pageSize: Int): IO[ChunkListResponse]

  /**
   * Get chunk size distribution statistics.
   */
  def getChunkSizeDistribution(): IO[ChunkSizeDistribution]

  /**
   * Get total chunk count.
   */
  def count(): IO[Int]
  /**
   * Chunk count and average chunk size per collection, keyed like the document registry's
   * collections ("default" for chunks with no collection).
   */
  def statsByCollection(): IO[Map[String, CollectionChunkStats]]

  /**
   * Delete all chunks for a document.
   */
  def deleteByDocument(documentId: String): IO[Unit]
  /**
   * Clear all chunks.
   */
  def clear(): IO[Unit]

  /**
   * Close any resources.
   */
  def close(): IO[Unit]
}
