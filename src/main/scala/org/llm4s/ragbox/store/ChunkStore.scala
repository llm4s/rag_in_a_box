package org.llm4s.ragbox.store

import cats.effect.IO
import org.llm4s.ragbox.model.{ChunkInfo, ChunkListResponse, ChunkSizeDistribution}

/**
 * Trait for storing and retrieving chunk information.
 *
 * This provides visibility into how documents have been chunked,
 * allowing users to understand and tune their RAG configuration.
 */
trait ChunkStore {

  /**
   * Initialize the store (create tables if needed).
   */
  def initialize(): IO[Unit]

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
   * Get chunk count for a specific document.
   */
  def countByDocument(documentId: String): IO[Int]

  /**
   * Get chunk count for a specific collection.
   */
  def countByCollection(collection: String): IO[Int]

  /**
   * Get average chunk size.
   */
  def avgChunkSize(): IO[Double]

  /**
   * Get average chunk size for a collection.
   */
  def avgChunkSizeByCollection(collection: String): IO[Double]

  /**
   * Delete all chunks for a document.
   */
  def deleteByDocument(documentId: String): IO[Unit]

  /**
   * Delete all chunks for a collection.
   */
  def deleteByCollection(collection: String): IO[Unit]

  /**
   * Clear all chunks.
   */
  def clear(): IO[Unit]

  /**
   * Close any resources.
   */
  def close(): IO[Unit]
}
