package org.llm4s.ragbox.registry

import cats.effect.IO
import io.circe.syntax._
import io.circe.parser._
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model.{CollectionMetrics, CollectionQueryStats, QueryAnalyticsSummary, QueryConfigSnapshot, QueryLogEntry}

import java.sql.{Connection, ResultSet, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * PostgreSQL-backed query log registry.
 *
 * Tracks all queries for analytics and RAGA evaluation.
 * Uses connection pooling for efficient database access.
 */
class QueryLogRegistry(dataSource: DataSource) extends QueryLogRegistryBase {

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
  ): IO[String] = ConnectionPool.withConnection(dataSource) { conn =>
    val logId = id.getOrElse(UUID.randomUUID().toString)
    val sql =
      """INSERT INTO query_logs
        |    (id, query_text, collection_pattern, user_id,
        |     embedding_latency_ms, search_latency_ms, llm_latency_ms, total_latency_ms,
        |     chunks_retrieved, chunks_used, answer_tokens, experiment_id, config_snapshot,
        |     prompt_tokens, embedding_tokens, cost_usd, variant, failed, error_message)
        |VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setQueryTimeout(30)
      stmt.setString(1, logId)
      stmt.setString(2, queryText)
      stmt.setString(3, collectionPattern.orNull)
      stmt.setString(4, userId.orNull)
      embeddingLatencyMs.foreach(stmt.setInt(5, _))
      if (embeddingLatencyMs.isEmpty) stmt.setNull(5, java.sql.Types.INTEGER)
      searchLatencyMs.foreach(stmt.setInt(6, _))
      if (searchLatencyMs.isEmpty) stmt.setNull(6, java.sql.Types.INTEGER)
      llmLatencyMs.foreach(stmt.setInt(7, _))
      if (llmLatencyMs.isEmpty) stmt.setNull(7, java.sql.Types.INTEGER)
      stmt.setInt(8, totalLatencyMs)
      stmt.setInt(9, chunksRetrieved)
      stmt.setInt(10, chunksUsed)
      answerTokens.foreach(stmt.setInt(11, _))
      if (answerTokens.isEmpty) stmt.setNull(11, java.sql.Types.INTEGER)
      stmt.setString(12, experimentId.orNull)
      configSnapshot match {
        case Some(snapshot) => stmt.setString(13, snapshot.asJson.noSpaces)
        case None => stmt.setNull(13, java.sql.Types.VARCHAR)
      }
      promptTokens.fold(stmt.setNull(14, java.sql.Types.INTEGER))(stmt.setInt(14, _))
      embeddingTokens.fold(stmt.setNull(15, java.sql.Types.INTEGER))(stmt.setInt(15, _))
      costUsd.fold(stmt.setNull(16, java.sql.Types.DOUBLE))(stmt.setDouble(16, _))
      stmt.setString(17, variant.orNull)
      stmt.setBoolean(18, failed)
      stmt.setString(19, errorMessage.map(_.take(2000)).orNull)
      stmt.executeUpdate()
      logId
    } finally {
      stmt.close()
    }
  }

  /**
   * Add feedback to a query.
   */
  def addFeedback(
    queryId: String,
    rating: Int,
    relevantChunks: Option[Seq[String]],
    comment: Option[String]
  ): IO[Boolean] = ConnectionPool.withConnection(dataSource) { conn =>
    val sql =
      """UPDATE query_logs SET
        |    user_rating = ?,
        |    relevant_chunks = ?,
        |    feedback_comment = ?
        |WHERE id = ?::uuid""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setQueryTimeout(30)
      stmt.setInt(1, rating)
      relevantChunks match {
        case Some(chunks) =>
          val array = conn.createArrayOf("text", chunks.toArray)
          stmt.setArray(2, array)
        case None =>
          stmt.setNull(2, java.sql.Types.ARRAY)
      }
      stmt.setString(3, comment.orNull)
      stmt.setString(4, queryId)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Get a query by ID.
   */
  def get(queryId: String): IO[Option[QueryLogEntry]] = ConnectionPool.withConnection(dataSource) { conn =>
    val sql =
      """SELECT id, query_text, collection_pattern, user_id,
        |       embedding_latency_ms, search_latency_ms, llm_latency_ms, total_latency_ms,
        |       chunks_retrieved, chunks_used, answer_tokens, user_rating,
        |       experiment_id, config_snapshot, created_at,
        |       prompt_tokens, embedding_tokens, cost_usd, variant, failed, error_message
        |FROM query_logs WHERE id = ?::uuid""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setQueryTimeout(30)
      stmt.setString(1, queryId)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToEntry(rs)) else None
    } finally {
      stmt.close()
    }
  }

  /**
   * List queries with pagination.
   */
  def list(
    from: Option[Instant],
    to: Option[Instant],
    collection: Option[String],
    page: Int = 1,
    pageSize: Int = 50
  ): IO[(Seq[QueryLogEntry], Int)] = ConnectionPool.withConnection(dataSource) { conn =>
    val conditions = scala.collection.mutable.ArrayBuffer.empty[String]
    from.foreach(_ => conditions += "created_at >= ?")
    to.foreach(_ => conditions += "created_at <= ?")
    collection.foreach(_ => conditions += "collection_pattern = ?")

    val whereClause = if (conditions.nonEmpty) "WHERE " + conditions.mkString(" AND ") else ""

    // Get total count
    val countSql = s"SELECT COUNT(*) FROM query_logs $whereClause"
    val countStmt = conn.prepareStatement(countSql)
    try {
      countStmt.setQueryTimeout(30)
      var paramIdx = 1
      from.foreach { ts =>
        countStmt.setTimestamp(paramIdx, Timestamp.from(ts))
        paramIdx += 1
      }
      to.foreach { ts =>
        countStmt.setTimestamp(paramIdx, Timestamp.from(ts))
        paramIdx += 1
      }
      collection.foreach { col =>
        countStmt.setString(paramIdx, col)
        paramIdx += 1
      }

      val countRs = countStmt.executeQuery()
      val total = if (countRs.next()) countRs.getInt(1) else 0

      // Get paginated results
      val offset = (page - 1) * pageSize
      val sql =
        s"""SELECT id, query_text, collection_pattern, user_id,
           |       embedding_latency_ms, search_latency_ms, llm_latency_ms, total_latency_ms,
           |       chunks_retrieved, chunks_used, answer_tokens, user_rating,
           |       experiment_id, config_snapshot, created_at,
        |       prompt_tokens, embedding_tokens, cost_usd, variant, failed, error_message
           |FROM query_logs $whereClause
           |ORDER BY created_at DESC
           |LIMIT ? OFFSET ?""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setQueryTimeout(30)
        var idx = 1
        from.foreach { ts =>
          stmt.setTimestamp(idx, Timestamp.from(ts))
          idx += 1
        }
        to.foreach { ts =>
          stmt.setTimestamp(idx, Timestamp.from(ts))
          idx += 1
        }
        collection.foreach { col =>
          stmt.setString(idx, col)
          idx += 1
        }
        stmt.setInt(idx, pageSize)
        stmt.setInt(idx + 1, offset)

        val rs = stmt.executeQuery()
        val entries = scala.collection.mutable.ArrayBuffer.empty[QueryLogEntry]
        while (rs.next()) {
          entries += rowToEntry(rs)
        }
        (entries.toSeq, total)
      } finally {
        stmt.close()
      }
    } finally {
      countStmt.close()
    }
  }

  /**
   * Get analytics summary, over the queries that succeeded (failed queries are logged for
   * experiment error rates, and have no meaningful latency or chunk counts).
   */
  def getSummary(from: Instant, to: Instant): IO[QueryAnalyticsSummary] = ConnectionPool.withConnection(dataSource) { conn =>
    val sql =
      """SELECT
        |    COUNT(*) as total_queries,
        |    AVG(total_latency_ms) as avg_latency,
        |    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY total_latency_ms) as p50,
        |    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY total_latency_ms) as p95,
        |    PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY total_latency_ms) as p99,
        |    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY total_latency_ms - llm_latency_ms)
        |        FILTER (WHERE llm_latency_ms IS NOT NULL) as p95_retrieval,
        |    SUM(cost_usd) as total_cost,
        |    AVG(chunks_retrieved) as avg_chunks_retrieved,
        |    AVG(chunks_used) as avg_chunks_used,
        |    AVG(user_rating) as avg_rating,
        |    COUNT(user_rating) as rated_count
        |FROM query_logs
        |WHERE created_at >= ? AND created_at <= ? AND NOT failed""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setQueryTimeout(30)
      stmt.setTimestamp(1, Timestamp.from(from))
      stmt.setTimestamp(2, Timestamp.from(to))
      val rs = stmt.executeQuery()

      if (rs.next()) {
        val totalQueries = rs.getInt("total_queries")
        val avgLatency = Option(rs.getDouble("avg_latency")).filterNot(_ => rs.wasNull()).getOrElse(0.0)
        val p50 = Option(rs.getInt("p50")).filterNot(_ => rs.wasNull()).getOrElse(0)
        val p95 = Option(rs.getInt("p95")).filterNot(_ => rs.wasNull()).getOrElse(0)
        val p99 = Option(rs.getInt("p99")).filterNot(_ => rs.wasNull()).getOrElse(0)
        val p95Retrieval = Option(rs.getInt("p95_retrieval")).filterNot(_ => rs.wasNull())
        val totalCost = Option(rs.getDouble("total_cost")).filterNot(_ => rs.wasNull())
        val avgChunksRetrieved = Option(rs.getDouble("avg_chunks_retrieved")).filterNot(_ => rs.wasNull()).getOrElse(0.0)
        val avgChunksUsed = Option(rs.getDouble("avg_chunks_used")).filterNot(_ => rs.wasNull()).getOrElse(0.0)
        val avgRating = Option(rs.getDouble("avg_rating")).filterNot(_ => rs.wasNull())
        val ratedCount = rs.getInt("rated_count")

        // Get top collections (uses same connection)
        val topCollections = getTopCollections(conn, from, to)

        QueryAnalyticsSummary(
          totalQueries = totalQueries,
          averageLatencyMs = avgLatency,
          p50LatencyMs = p50,
          p95LatencyMs = p95,
          p99LatencyMs = p99,
          averageChunksRetrieved = avgChunksRetrieved,
          averageChunksUsed = avgChunksUsed,
          averageRating = avgRating,
          ratedQueriesCount = ratedCount,
          queriesWithFeedback = ratedCount,
          topCollections = topCollections,
          periodStart = from,
          periodEnd = to,
          p95RetrievalLatencyMs = p95Retrieval,
          totalCostUsd = totalCost
        )
      } else {
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
      }
    } finally {
      stmt.close()
    }
  }

  private def getTopCollections(conn: Connection, from: Instant, to: Instant): Seq[CollectionQueryStats] = {
    val sql =
      """SELECT
        |    COALESCE(collection_pattern, '*') as collection,
        |    COUNT(*) as query_count,
        |    AVG(total_latency_ms) as avg_latency,
        |    AVG(user_rating) as avg_rating
        |FROM query_logs
        |WHERE created_at >= ? AND created_at <= ? AND NOT failed
        |GROUP BY collection_pattern
        |ORDER BY query_count DESC
        |LIMIT 10""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setQueryTimeout(30)
      stmt.setTimestamp(1, Timestamp.from(from))
      stmt.setTimestamp(2, Timestamp.from(to))
      val rs = stmt.executeQuery()
      val stats = scala.collection.mutable.ArrayBuffer.empty[CollectionQueryStats]
      while (rs.next()) {
        stats += CollectionQueryStats(
          collection = rs.getString("collection"),
          queryCount = rs.getInt("query_count"),
          averageLatencyMs = rs.getDouble("avg_latency"),
          averageRating = Option(rs.getDouble("avg_rating")).filterNot(_ => rs.wasNull())
        )
      }
      stats.toSeq
    } finally {
      stmt.close()
    }
  }

  /**
   * Per-collection metrics, over the queries that succeeded: the real p95 latencies
   * (`PERCENTILE_CONT`), the number of queries that retrieved nothing, and the collection's own
   * chunk and rating averages. A query without a collection counts as `*`.
   */
  override def getCollectionMetrics(from: Instant, to: Instant, limit: Int = 10): IO[Seq[CollectionMetrics]] =
    ConnectionPool.withConnection(dataSource) { conn =>
      val sql =
        """SELECT
          |    COALESCE(collection_pattern, '*') as collection,
          |    COUNT(*) as query_count,
          |    AVG(total_latency_ms) as avg_latency,
          |    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY total_latency_ms) as p95,
          |    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY total_latency_ms - llm_latency_ms)
          |        FILTER (WHERE llm_latency_ms IS NOT NULL) as p95_retrieval,
          |    AVG(chunks_retrieved) as avg_chunks_retrieved,
          |    AVG(chunks_used) as avg_chunks_used,
          |    AVG(user_rating) as avg_rating,
          |    COUNT(user_rating) as rated_count,
          |    COUNT(*) FILTER (WHERE chunks_retrieved = 0) as zero_results
          |FROM query_logs
          |WHERE created_at >= ? AND created_at <= ? AND NOT failed
          |GROUP BY COALESCE(collection_pattern, '*')
          |ORDER BY query_count DESC, collection
          |LIMIT ?""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setQueryTimeout(30)
        stmt.setTimestamp(1, Timestamp.from(from))
        stmt.setTimestamp(2, Timestamp.from(to))
        stmt.setInt(3, limit)
        val rs = stmt.executeQuery()
        def optDouble(column: String): Option[Double] = Option(rs.getDouble(column)).filterNot(_ => rs.wasNull())
        val metrics = scala.collection.mutable.ArrayBuffer.empty[CollectionMetrics]
        while (rs.next()) {
          metrics += CollectionMetrics(
            collection = rs.getString("collection"),
            queryCount = rs.getInt("query_count"),
            avgLatencyMs = optDouble("avg_latency").getOrElse(0.0),
            avgChunksRetrieved = optDouble("avg_chunks_retrieved").getOrElse(0.0),
            avgChunksUsed = optDouble("avg_chunks_used").getOrElse(0.0),
            avgRating = optDouble("avg_rating"),
            ratedQueryCount = rs.getInt("rated_count"),
            zeroResultQueries = rs.getInt("zero_results"),
            p95LatencyMs = optDouble("p95").map(math.round(_).toInt).getOrElse(0),
            p95RetrievalLatencyMs = optDouble("p95_retrieval").map(math.round(_).toInt)
          )
        }
        metrics.toSeq
      } finally {
        stmt.close()
      }
    }

  /**
   * Close the registry: a no-op, since the connection pool is owned by the caller.
   */
  def close(): IO[Unit] = IO.unit

  private def rowToEntry(rs: ResultSet): QueryLogEntry = {
    val configSnapshotJson = Option(rs.getString("config_snapshot"))
    val configSnapshot = configSnapshotJson.flatMap { json =>
      decode[QueryConfigSnapshot](json).toOption
    }

    QueryLogEntry(
      id = rs.getString("id"),
      queryText = rs.getString("query_text"),
      collectionPattern = Option(rs.getString("collection_pattern")),
      userId = Option(rs.getString("user_id")),
      embeddingLatencyMs = Option(rs.getInt("embedding_latency_ms")).filterNot(_ => rs.wasNull()),
      searchLatencyMs = Option(rs.getInt("search_latency_ms")).filterNot(_ => rs.wasNull()),
      llmLatencyMs = Option(rs.getInt("llm_latency_ms")).filterNot(_ => rs.wasNull()),
      totalLatencyMs = rs.getInt("total_latency_ms"),
      chunksRetrieved = rs.getInt("chunks_retrieved"),
      chunksUsed = rs.getInt("chunks_used"),
      answerTokens = Option(rs.getInt("answer_tokens")).filterNot(_ => rs.wasNull()),
      userRating = Option(rs.getInt("user_rating")).filterNot(_ => rs.wasNull()),
      experimentId = Option(rs.getString("experiment_id")),
      configSnapshot = configSnapshot,
      createdAt = rs.getTimestamp("created_at").toInstant,
      promptTokens = Option(rs.getInt("prompt_tokens")).filterNot(_ => rs.wasNull()),
      embeddingTokens = Option(rs.getInt("embedding_tokens")).filterNot(_ => rs.wasNull()),
      costUsd = Option(rs.getDouble("cost_usd")).filterNot(_ => rs.wasNull()),
      variant = Option(rs.getString("variant")),
      failed = rs.getBoolean("failed"),
      errorMessage = Option(rs.getString("error_message"))
    )
  }
}

object QueryLogRegistry {

  /**
   * A query log registry over the connection pool. The schema is managed by Flyway ([[org.llm4s.ragbox.db.Migrations]]); this does no I/O.
   */
  def apply(dataSource: DataSource): IO[QueryLogRegistry] = IO.pure(new QueryLogRegistry(dataSource))
}
