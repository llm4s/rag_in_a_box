package org.llm4s.ragbox.testkit

import cats.effect.IO
import org.llm4s.ragbox.model.{CollectionQueryStats, QueryAnalyticsSummary, QueryLogEntry}
import org.llm4s.ragbox.registry.QueryLogRegistryBase
import java.time.Instant
import java.util.UUID
import scala.collection.mutable

/**
 * In-memory implementation of QueryLogRegistry for fast testing.
 *
 * Provides the same interface as the PostgreSQL-backed QueryLogRegistry
 * but stores everything in memory for fast, isolated tests.
 */
class InMemoryQueryLogRegistry extends QueryLogRegistryBase {

  private val logs: mutable.Map[String, QueryLogEntry] = mutable.Map.empty
  private val feedback: mutable.Map[String, (Int, Option[Seq[String]], Option[String])] = mutable.Map.empty

  /**
   * Initialize the registry (no-op for in-memory).
   */
  override def initialize(): IO[Unit] = IO.unit

  /**
   * Log a query execution.
   */
  override def logQuery(
    queryText: String,
    collectionPattern: Option[String],
    userId: Option[String],
    embeddingLatencyMs: Option[Int],
    searchLatencyMs: Option[Int],
    llmLatencyMs: Option[Int],
    totalLatencyMs: Int,
    chunksRetrieved: Int,
    chunksUsed: Int,
    answerTokens: Option[Int]
  ): IO[String] = IO {
    val id = UUID.randomUUID().toString
    val entry = QueryLogEntry(
      id = id,
      queryText = queryText,
      collectionPattern = collectionPattern,
      userId = userId,
      embeddingLatencyMs = embeddingLatencyMs,
      searchLatencyMs = searchLatencyMs,
      llmLatencyMs = llmLatencyMs,
      totalLatencyMs = totalLatencyMs,
      chunksRetrieved = chunksRetrieved,
      chunksUsed = chunksUsed,
      answerTokens = answerTokens,
      userRating = None,
      createdAt = Instant.now()
    )
    logs.update(id, entry)
    id
  }

  /**
   * Add feedback to a query.
   */
  override def addFeedback(
    queryId: String,
    rating: Int,
    relevantChunks: Option[Seq[String]],
    comment: Option[String]
  ): IO[Boolean] = IO {
    logs.get(queryId) match {
      case Some(entry) =>
        logs.update(queryId, entry.copy(userRating = Some(rating)))
        feedback.update(queryId, (rating, relevantChunks, comment))
        true
      case None =>
        false
    }
  }

  /**
   * Get a query by ID.
   */
  override def get(queryId: String): IO[Option[QueryLogEntry]] =
    IO.pure(logs.get(queryId))

  /**
   * List queries with pagination.
   */
  override def list(
    from: Option[Instant],
    to: Option[Instant],
    collection: Option[String],
    page: Int = 1,
    pageSize: Int = 50
  ): IO[(Seq[QueryLogEntry], Int)] = IO {
    var filtered = logs.values.toSeq

    from.foreach { f =>
      filtered = filtered.filter(e => !e.createdAt.isBefore(f))
    }

    to.foreach { t =>
      filtered = filtered.filter(e => !e.createdAt.isAfter(t))
    }

    collection.foreach { c =>
      filtered = filtered.filter(_.collectionPattern.contains(c))
    }

    val total = filtered.size
    val sorted = filtered.sortBy(_.createdAt)(Ordering[Instant].reverse)
    val offset = (page - 1) * pageSize
    val paginated = sorted.slice(offset, offset + pageSize)

    (paginated, total)
  }

  /**
   * Get analytics summary.
   */
  override def getSummary(from: Instant, to: Instant): IO[QueryAnalyticsSummary] = IO {
    val filtered = logs.values.toSeq.filter { e =>
      !e.createdAt.isBefore(from) && !e.createdAt.isAfter(to)
    }

    if (filtered.isEmpty) {
      QueryAnalyticsSummary(
        totalQueries = 0,
        averageLatencyMs = 0.0,
        p50LatencyMs = 0,
        p95LatencyMs = 0,
        p99LatencyMs = 0,
        averageChunksRetrieved = 0.0,
        averageChunksUsed = 0.0,
        averageRating = None,
        ratedQueriesCount = 0,
        queriesWithFeedback = 0,
        topCollections = Seq.empty,
        periodStart = from,
        periodEnd = to
      )
    } else {
      val latencies = filtered.map(_.totalLatencyMs).sorted
      val ratings = filtered.flatMap(_.userRating)

      def percentile(data: Seq[Int], p: Double): Int = {
        if (data.isEmpty) 0
        else {
          val idx = ((p / 100.0) * data.length).toInt.min(data.length - 1)
          data(idx)
        }
      }

      // Calculate top collections
      val collectionCounts = filtered
        .groupBy(_.collectionPattern.getOrElse("*"))
        .map { case (coll, entries) =>
          CollectionQueryStats(
            collection = coll,
            queryCount = entries.size,
            averageLatencyMs = entries.map(_.totalLatencyMs).sum.toDouble / entries.size,
            averageRating = {
              val r = entries.flatMap(_.userRating)
              if (r.isEmpty) None else Some(r.sum.toDouble / r.size)
            }
          )
        }
        .toSeq
        .sortBy(-_.queryCount)
        .take(10)

      QueryAnalyticsSummary(
        totalQueries = filtered.size,
        averageLatencyMs = latencies.sum.toDouble / latencies.size,
        p50LatencyMs = percentile(latencies, 50),
        p95LatencyMs = percentile(latencies, 95),
        p99LatencyMs = percentile(latencies, 99),
        averageChunksRetrieved = filtered.map(_.chunksRetrieved).sum.toDouble / filtered.size,
        averageChunksUsed = filtered.map(_.chunksUsed).sum.toDouble / filtered.size,
        averageRating = if (ratings.isEmpty) None else Some(ratings.sum.toDouble / ratings.size),
        ratedQueriesCount = ratings.size,
        queriesWithFeedback = ratings.size,
        topCollections = collectionCounts,
        periodStart = from,
        periodEnd = to
      )
    }
  }

  /**
   * Close the registry (no-op for in-memory).
   */
  override def close(): IO[Unit] = IO.unit

  // ============================================================
  // Test Helper Methods
  // ============================================================

  /**
   * Clear all logs for test isolation.
   */
  def reset(): Unit = {
    logs.clear()
    feedback.clear()
  }

  /**
   * Get the current log count for assertions.
   */
  def logCount: Int = logs.size

  /**
   * Seed the registry with test query logs.
   */
  def seed(entries: Seq[QueryLogEntry]): Unit = {
    entries.foreach(e => logs.update(e.id, e))
  }

  /**
   * Check if a specific query log exists.
   */
  def hasLog(queryId: String): Boolean = logs.contains(queryId)

  /**
   * Get feedback for a query.
   */
  def getFeedback(queryId: String): Option[(Int, Option[Seq[String]], Option[String])] =
    feedback.get(queryId)
}

object InMemoryQueryLogRegistry {
  /**
   * Create a new in-memory query log registry.
   */
  def apply(): InMemoryQueryLogRegistry = new InMemoryQueryLogRegistry()

  /**
   * Create a registry pre-seeded with test query logs.
   */
  def withLogs(entries: Seq[QueryLogEntry]): InMemoryQueryLogRegistry = {
    val registry = new InMemoryQueryLogRegistry()
    registry.seed(entries)
    registry
  }
}
