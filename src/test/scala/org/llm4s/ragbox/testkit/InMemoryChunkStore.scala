package org.llm4s.ragbox.testkit

import cats.effect.IO
import org.llm4s.ragbox.model.{ChunkInfo, ChunkListResponse, ChunkSizeDistribution, SizeBucket}
import org.llm4s.ragbox.store.ChunkStore
import scala.collection.mutable

/**
 * In-memory implementation of ChunkStore for fast testing.
 */
class InMemoryChunkStore extends ChunkStore {

  private val chunks: mutable.Map[String, Seq[ChunkInfo]] = mutable.Map.empty

  override def initialize(): IO[Unit] = IO.unit

  override def store(documentId: String, newChunks: Seq[ChunkInfo]): IO[Unit] =
    IO(chunks.update(documentId, newChunks))

  override def getChunks(documentId: String): IO[Seq[ChunkInfo]] =
    IO.pure(chunks.getOrElse(documentId, Seq.empty))

  override def getChunk(documentId: String, index: Int): IO[Option[ChunkInfo]] =
    IO.pure(chunks.get(documentId).flatMap(_.find(_.index == index)))

  override def listChunks(page: Int, pageSize: Int): IO[ChunkListResponse] = IO {
    val allChunks = chunks.values.flatten.toSeq
    val offset = (page - 1) * pageSize
    val pageChunks = allChunks.slice(offset, offset + pageSize)
    val hasMore = offset + pageSize < allChunks.size
    ChunkListResponse(
      chunks = pageChunks,
      total = allChunks.size,
      page = page,
      pageSize = pageSize,
      hasMore = hasMore
    )
  }

  override def getChunkSizeDistribution(): IO[ChunkSizeDistribution] = IO {
    val allChunks = chunks.values.flatten.toSeq
    if (allChunks.isEmpty) {
      ChunkSizeDistribution(
        min = 0,
        max = 0,
        avg = 0.0,
        median = 0.0,
        p90 = 0.0,
        buckets = Seq.empty
      )
    } else {
      val sizes = allChunks.map(_.contentLength).sorted
      val min = sizes.head
      val max = sizes.last
      val avg = sizes.sum.toDouble / sizes.size
      val median = sizes(sizes.size / 2).toDouble
      val p90 = sizes((sizes.size * 0.9).toInt.min(sizes.size - 1)).toDouble

      // Simple bucket calculation
      val bucketRanges = Seq((0, 100), (100, 250), (250, 500), (500, 1000), (1000, Int.MaxValue))
      val buckets = bucketRanges.map { case (lo, hi) =>
        val count = sizes.count(s => s >= lo && s < hi)
        SizeBucket(rangeStart = lo, rangeEnd = hi, count = count)
      }

      ChunkSizeDistribution(
        min = min,
        max = max,
        avg = avg,
        median = median,
        p90 = p90,
        buckets = buckets
      )
    }
  }

  override def count(): IO[Int] =
    IO.pure(chunks.values.flatten.size)

  override def countByDocument(documentId: String): IO[Int] =
    IO.pure(chunks.getOrElse(documentId, Seq.empty).size)

  override def countByCollection(collection: String): IO[Int] = IO {
    chunks.values.flatten.count(_.metadata.get("collection").contains(collection))
  }

  override def avgChunkSize(): IO[Double] = IO {
    val allChunks = chunks.values.flatten.toSeq
    if (allChunks.isEmpty) 0.0
    else allChunks.map(_.contentLength).sum.toDouble / allChunks.size
  }

  override def avgChunkSizeByCollection(collection: String): IO[Double] = IO {
    val collChunks = chunks.values.flatten.filter(_.metadata.get("collection").contains(collection)).toSeq
    if (collChunks.isEmpty) 0.0
    else collChunks.map(_.contentLength).sum.toDouble / collChunks.size
  }

  override def deleteByDocument(documentId: String): IO[Unit] =
    IO(chunks.remove(documentId)).void

  override def deleteByCollection(collection: String): IO[Unit] = IO {
    chunks.keys.foreach { docId =>
      val remaining = chunks.getOrElse(docId, Seq.empty).filterNot(_.metadata.get("collection").contains(collection))
      if (remaining.isEmpty) chunks.remove(docId)
      else chunks.update(docId, remaining)
    }
  }

  override def clear(): IO[Unit] =
    IO(chunks.clear())

  override def close(): IO[Unit] = IO.unit

  // ============================================================
  // Test Helper Methods
  // ============================================================

  /**
   * Clear all chunks for test isolation.
   */
  def reset(): Unit = chunks.clear()

  /**
   * Get the total chunk count for assertions.
   */
  def chunkCount: Int = chunks.values.flatten.size

  /**
   * Seed the store with test chunks.
   */
  def seed(documentId: String, chunkList: Seq[ChunkInfo]): Unit =
    chunks.update(documentId, chunkList)
}

object InMemoryChunkStore {
  /**
   * Create a new in-memory chunk store.
   */
  def apply(): InMemoryChunkStore = new InMemoryChunkStore()
}
