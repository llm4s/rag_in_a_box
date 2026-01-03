package ragbox.registry

import cats.effect.IO
import ragbox.config.DatabaseConfig
import ragbox.model.{CollectionQueryStats, QueryAnalyticsSummary, QueryLogEntry}

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.time.Instant
import java.util.{Properties, UUID}
import scala.compiletime.uninitialized

/**
 * PostgreSQL-backed query log registry.
 *
 * Tracks all queries for analytics and RAGA evaluation.
 */
class QueryLogRegistry(dbConfig: DatabaseConfig) {

  private var connection: Connection = uninitialized

  /**
   * Initialize the registry and ensure tables exist.
   */
  def initialize(): IO[Unit] = IO {
    Class.forName("org.postgresql.Driver")

    val props = new Properties()
    props.setProperty("user", dbConfig.effectiveUser)
    props.setProperty("password", dbConfig.effectivePassword)

    connection = DriverManager.getConnection(dbConfig.connectionString, props)

    val stmt = connection.createStatement()
    try {
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS query_logs (
          |    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          |    query_text TEXT NOT NULL,
          |    collection_pattern TEXT,
          |    user_id TEXT,
          |
          |    embedding_latency_ms INTEGER,
          |    search_latency_ms INTEGER,
          |    llm_latency_ms INTEGER,
          |    total_latency_ms INTEGER NOT NULL,
          |
          |    chunks_retrieved INTEGER NOT NULL DEFAULT 0,
          |    chunks_used INTEGER NOT NULL DEFAULT 0,
          |    answer_tokens INTEGER,
          |
          |    user_rating INTEGER,
          |    relevant_chunks TEXT[],
          |    feedback_comment TEXT,
          |
          |    created_at TIMESTAMPTZ DEFAULT NOW()
          |)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_query_logs_created_at
          |    ON query_logs(created_at)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_query_logs_collection
          |    ON query_logs(collection_pattern)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_query_logs_user
          |    ON query_logs(user_id)""".stripMargin
      )
    } finally {
      stmt.close()
    }
  }

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
  ): IO[String] = IO {
    val id = UUID.randomUUID().toString
    val sql =
      """INSERT INTO query_logs
        |    (id, query_text, collection_pattern, user_id,
        |     embedding_latency_ms, search_latency_ms, llm_latency_ms, total_latency_ms,
        |     chunks_retrieved, chunks_used, answer_tokens)
        |VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, id)
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
      stmt.executeUpdate()
      id
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
  ): IO[Boolean] = IO {
    val sql =
      """UPDATE query_logs SET
        |    user_rating = ?,
        |    relevant_chunks = ?,
        |    feedback_comment = ?
        |WHERE id = ?::uuid""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setInt(1, rating)
      relevantChunks match {
        case Some(chunks) =>
          val array = connection.createArrayOf("text", chunks.toArray)
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
  def get(queryId: String): IO[Option[QueryLogEntry]] = IO {
    val sql =
      """SELECT id, query_text, collection_pattern, user_id,
        |       embedding_latency_ms, search_latency_ms, llm_latency_ms, total_latency_ms,
        |       chunks_retrieved, chunks_used, answer_tokens, user_rating, created_at
        |FROM query_logs WHERE id = ?::uuid""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
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
  ): IO[(Seq[QueryLogEntry], Int)] = IO {
    val conditions = scala.collection.mutable.ArrayBuffer.empty[String]
    from.foreach(_ => conditions += "created_at >= ?")
    to.foreach(_ => conditions += "created_at <= ?")
    collection.foreach(_ => conditions += "collection_pattern = ?")

    val whereClause = if (conditions.nonEmpty) "WHERE " + conditions.mkString(" AND ") else ""

    // Get total count
    val countSql = s"SELECT COUNT(*) FROM query_logs $whereClause"
    val countStmt = connection.prepareStatement(countSql)
    try {
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
           |       chunks_retrieved, chunks_used, answer_tokens, user_rating, created_at
           |FROM query_logs $whereClause
           |ORDER BY created_at DESC
           |LIMIT ? OFFSET ?""".stripMargin
      val stmt = connection.prepareStatement(sql)
      try {
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
   * Get analytics summary.
   */
  def getSummary(from: Instant, to: Instant): IO[QueryAnalyticsSummary] = IO {
    val sql =
      """SELECT
        |    COUNT(*) as total_queries,
        |    AVG(total_latency_ms) as avg_latency,
        |    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY total_latency_ms) as p50,
        |    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY total_latency_ms) as p95,
        |    PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY total_latency_ms) as p99,
        |    AVG(chunks_retrieved) as avg_chunks_retrieved,
        |    AVG(chunks_used) as avg_chunks_used,
        |    AVG(user_rating) as avg_rating,
        |    COUNT(user_rating) as rated_count
        |FROM query_logs
        |WHERE created_at >= ? AND created_at <= ?""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setTimestamp(1, Timestamp.from(from))
      stmt.setTimestamp(2, Timestamp.from(to))
      val rs = stmt.executeQuery()

      if (rs.next()) {
        val totalQueries = rs.getInt("total_queries")
        val avgLatency = Option(rs.getDouble("avg_latency")).filterNot(_ => rs.wasNull()).getOrElse(0.0)
        val p50 = Option(rs.getInt("p50")).filterNot(_ => rs.wasNull()).getOrElse(0)
        val p95 = Option(rs.getInt("p95")).filterNot(_ => rs.wasNull()).getOrElse(0)
        val p99 = Option(rs.getInt("p99")).filterNot(_ => rs.wasNull()).getOrElse(0)
        val avgChunksRetrieved = Option(rs.getDouble("avg_chunks_retrieved")).filterNot(_ => rs.wasNull()).getOrElse(0.0)
        val avgChunksUsed = Option(rs.getDouble("avg_chunks_used")).filterNot(_ => rs.wasNull()).getOrElse(0.0)
        val avgRating = Option(rs.getDouble("avg_rating")).filterNot(_ => rs.wasNull())
        val ratedCount = rs.getInt("rated_count")

        // Get top collections
        val topCollections = getTopCollections(from, to)

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
          periodEnd = to
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

  private def getTopCollections(from: Instant, to: Instant): Seq[CollectionQueryStats] = {
    val sql =
      """SELECT
        |    COALESCE(collection_pattern, '*') as collection,
        |    COUNT(*) as query_count,
        |    AVG(total_latency_ms) as avg_latency,
        |    AVG(user_rating) as avg_rating
        |FROM query_logs
        |WHERE created_at >= ? AND created_at <= ?
        |GROUP BY collection_pattern
        |ORDER BY query_count DESC
        |LIMIT 10""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
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
   * Close the connection.
   */
  def close(): IO[Unit] = IO {
    if (connection != null && !connection.isClosed) {
      connection.close()
    }
  }

  private def rowToEntry(rs: ResultSet): QueryLogEntry = {
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
      createdAt = rs.getTimestamp("created_at").toInstant
    )
  }
}

object QueryLogRegistry {

  /**
   * Create and initialize a query log registry.
   */
  def apply(dbConfig: DatabaseConfig): IO[QueryLogRegistry] = {
    val registry = new QueryLogRegistry(dbConfig)
    registry.initialize().as(registry)
  }
}
