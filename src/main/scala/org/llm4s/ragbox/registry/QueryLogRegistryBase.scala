package org.llm4s.ragbox.registry

import cats.effect.IO
import org.llm4s.ragbox.model.{QueryAnalyticsSummary, QueryLogEntry}
import java.time.Instant

/**
 * Trait defining the query log registry interface.
 *
 * Implementations include PostgreSQL-backed and in-memory versions.
 */
trait QueryLogRegistryBase {

  /**
   * Initialize the registry.
   */
  def initialize(): IO[Unit]

  /**
   * Log a query execution.
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
    answerTokens: Option[Int]
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
   * Close any resources.
   */
  def close(): IO[Unit]
}
