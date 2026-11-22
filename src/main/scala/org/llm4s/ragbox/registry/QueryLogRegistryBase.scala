package org.llm4s.ragbox.registry

import cats.effect.IO
import org.llm4s.ragbox.model.{CollectionMetrics, QueryAnalyticsSummary, QueryConfigSnapshot, QueryLogEntry}
import java.time.Instant

/**
 * Trait defining the query log registry interface.
 *
 * Implementations include PostgreSQL-backed and in-memory versions.
 */
trait QueryLogRegistryBase {

  /**
   * Log a query execution.
   *
   * @param queryText The query text
   * @param collectionPattern Collection pattern used
   * @param userId Optional user ID
   * @param embeddingLatencyMs Embedding generation latency
   * @param searchLatencyMs Search latency
   * @param llmLatencyMs LLM response latency
   * @param totalLatencyMs Total query latency
   * @param chunksRetrieved Number of chunks retrieved
   * @param chunksUsed Number of chunks used in response
   * @param answerTokens Number of tokens in answer
   * @param experimentId Optional experiment ID for A/B testing
   * @param configSnapshot Optional snapshot of config used for this query
   * @param promptTokens LLM prompt tokens, over every completion the query made
   * @param embeddingTokens Tokens embedded for the query
   * @param costUsd Estimated cost of the query, when llm4s knows the models' pricing
   * @param variant The experiment group (`baseline` or `variant`) the query was routed to
   * @param failed Whether the query failed (its latency is then the time to failure)
   * @param errorMessage Why it failed
   * @param id The id to log the query under (a new UUID when absent). Routes choose it up front
   *           so they can return it (`queryId`) before or without waiting for the log.
   * @return The query log ID
   */
  def logQuery(
    queryText: String,
    collectionPattern: Option[String],
    userId: Option[String],
    embeddingLatencyMs: Option[Int],
    searchLatencyMs: Option[Int],
    llmLatencyMs: Option[Int],
    totalLatencyMs: Int,
    chunksRetrieved: Int,
    chunksUsed: Int,
    answerTokens: Option[Int],
    experimentId: Option[String] = None,
    configSnapshot: Option[QueryConfigSnapshot] = None,
    promptTokens: Option[Int] = None,
    embeddingTokens: Option[Int] = None,
    costUsd: Option[Double] = None,
    variant: Option[String] = None,
    failed: Boolean = false,
    errorMessage: Option[String] = None,
    id: Option[String] = None
  ): IO[String]

  /**
   * Add feedback to a query.
   */
  def addFeedback(
    queryId: String,
    rating: Int,
    relevantChunks: Option[Seq[String]],
    comment: Option[String]
  ): IO[Boolean]

  /**
   * Get a query by ID.
   */
  def get(queryId: String): IO[Option[QueryLogEntry]]

  /**
   * List queries with pagination.
   */
  def list(
    from: Option[Instant],
    to: Option[Instant],
    collection: Option[String],
    page: Int = 1,
    pageSize: Int = 50
  ): IO[(Seq[QueryLogEntry], Int)]

  /**
   * Get analytics summary.
   */
  def getSummary(from: Instant, to: Instant): IO[QueryAnalyticsSummary]

  /**
   * Per-collection metrics of the queries that succeeded between `from` and `to`, for the
   * optimization suggestions: the `limit` collection patterns queried most, busiest first.
   *
   * By default computed from [[list]] in memory; the PostgreSQL registry does it in SQL.
   */
  def getCollectionMetrics(from: Instant, to: Instant, limit: Int = 10): IO[Seq[CollectionMetrics]] = {
    def all(page: Int, acc: Vector[QueryLogEntry]): IO[Vector[QueryLogEntry]] =
      list(Some(from), Some(to), None, page, 500).flatMap { case (entries, total) =>
        val next = acc ++ entries
        if (entries.isEmpty || next.size >= total) IO.pure(next) else all(page + 1, next)
      }
    all(1, Vector.empty).map(CollectionMetrics.fromEntries(_, limit))
  }

  /**
   * Close any resources.
   */
  def close(): IO[Unit]
}
