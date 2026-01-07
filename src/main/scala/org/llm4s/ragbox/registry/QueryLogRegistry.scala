package org.llm4s.ragbox.registry

import cats.effect.IO
import com.zaxxer.hikari.HikariDataSource
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model.{CollectionQueryStats, QueryAnalyticsSummary, QueryLogEntry}

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.time.Instant
import java.util.{Properties, UUID}
import javax.sql.DataSource
import scala.compiletime.uninitialized

/**
 * PostgreSQL-backed query log registry.
 *
 * Tracks all queries for analytics and RAGA evaluation.
 * Uses connection pooling for efficient database access.
 */
class QueryLogRegistry(dataSource: DataSource) extends QueryLogRegistryBase {

  // For backwards compatibility - will be removed after all registries updated
  private var legacyConnection: Connection = uninitialized
  private var useLegacyConnection: Boolean = false

  /**
   * Secondary constructor for backwards compatibility with DatabaseConfig.
   * @deprecated Use the DataSource constructor with connection pool instead.
   */
  def this(dbConfig: DatabaseConfig) = {
    this(null.asInstanceOf[DataSource])
    useLegacyConnection = true
  }

  private def getConnection(): Connection = {
    if (useLegacyConnection) legacyConnection
    else dataSource.getConnection()
  }

  private def releaseConnection(conn: Connection): Unit = {
    if (!useLegacyConnection) conn.close()
  }

  // Store DatabaseConfig for legacy initialization
  private var dbConfigForLegacy: DatabaseConfig = uninitialized

  /**
   * Initialize the registry and ensure tables exist.
   */
  def initialize(): IO[Unit] = IO {
    val conn = getConnection()
    try {
      val stmt = conn.createStatement()
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
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Initialize legacy connection for backwards compatibility.
   * Called when using DatabaseConfig constructor.
   */
  private[registry] def initializeLegacy(dbConfig: DatabaseConfig): Unit = {
    Class.forName("org.postgresql.Driver")
    val props = new Properties()
    props.setProperty("user", dbConfig.effectiveUser)
    props.setProperty("password", dbConfig.effectivePassword)
    legacyConnection = DriverManager.getConnection(dbConfig.connectionString, props)
    dbConfigForLegacy = dbConfig
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
    val conn = getConnection()
    try {
      val id = UUID.randomUUID().toString
      val sql =
        """INSERT INTO query_logs
          |    (id, query_text, collection_pattern, user_id,
          |     embedding_latency_ms, search_latency_ms, llm_latency_ms, total_latency_ms,
          |     chunks_retrieved, chunks_used, answer_tokens)
          |VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setQueryTimeout(30)
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
    } finally {
      releaseConnection(conn)
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
    val conn = getConnection()
    try {
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
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Get a query by ID.
   */
  def get(queryId: String): IO[Option[QueryLogEntry]] = IO {
    val conn = getConnection()
    try {
      val sql =
        """SELECT id, query_text, collection_pattern, user_id,
          |       embedding_latency_ms, search_latency_ms, llm_latency_ms, total_latency_ms,
          |       chunks_retrieved, chunks_used, answer_tokens, user_rating, created_at
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
    } finally {
      releaseConnection(conn)
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
    val conn = getConnection()
    try {
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
             |       chunks_retrieved, chunks_used, answer_tokens, user_rating, created_at
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
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Get analytics summary.
   */
  def getSummary(from: Instant, to: Instant): IO[QueryAnalyticsSummary] = IO {
    val conn = getConnection()
    try {
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
    } finally {
      releaseConnection(conn)
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
        |WHERE created_at >= ? AND created_at <= ?
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
   * Close the registry.
   * For pooled connections, this is a no-op since pool manages connections.
   * For legacy mode, closes the held connection.
   */
  def close(): IO[Unit] = IO {
    if (useLegacyConnection && legacyConnection != null && !legacyConnection.isClosed) {
      legacyConnection.close()
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
   * Create and initialize a query log registry with connection pool.
   * This is the recommended way to create the registry for production use.
   */
  def apply(dataSource: DataSource): IO[QueryLogRegistry] = {
    val registry = new QueryLogRegistry(dataSource)
    registry.initialize().as(registry)
  }

  /**
   * Create and initialize a query log registry with legacy single connection.
   * @deprecated Use the DataSource constructor with connection pool instead.
   */
  def withConfig(dbConfig: DatabaseConfig): IO[QueryLogRegistry] = {
    val registry = new QueryLogRegistry(dbConfig)
    registry.initializeLegacy(dbConfig)
    registry.initialize().as(registry)
  }

  /**
   * Create and initialize a query log registry.
   * @deprecated Use apply(DataSource) with connection pool instead.
   */
  def apply(dbConfig: DatabaseConfig): IO[QueryLogRegistry] = withConfig(dbConfig)
}
