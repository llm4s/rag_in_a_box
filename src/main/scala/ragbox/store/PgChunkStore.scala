package ragbox.store

import cats.effect.IO
import cats.syntax.all._
import io.circe.parser._
import io.circe.syntax._
import ragbox.config.DatabaseConfig
import ragbox.model.{ChunkInfo, ChunkListResponse, ChunkMetadataInfo, ChunkSizeDistribution, SizeBucket}

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.util.Properties
import scala.compiletime.uninitialized

/**
 * PostgreSQL-backed chunk store.
 *
 * Stores chunk content and metadata for visibility into the chunking process.
 */
class PgChunkStore(dbConfig: DatabaseConfig) extends ChunkStore {

  private var connection: Connection = uninitialized

  override def initialize(): IO[Unit] = IO {
    Class.forName("org.postgresql.Driver")

    val props = new Properties()
    props.setProperty("user", dbConfig.effectiveUser)
    props.setProperty("password", dbConfig.effectivePassword)

    connection = DriverManager.getConnection(dbConfig.connectionString, props)

    val stmt = connection.createStatement()
    try {
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS chunk_registry (
          |    id SERIAL PRIMARY KEY,
          |    document_id TEXT NOT NULL,
          |    chunk_index INTEGER NOT NULL,
          |    content TEXT NOT NULL,
          |    content_length INTEGER NOT NULL,
          |    metadata JSONB DEFAULT '{}',
          |    headings TEXT[] DEFAULT '{}',
          |    is_code_block BOOLEAN DEFAULT FALSE,
          |    language TEXT,
          |    start_offset INTEGER,
          |    end_offset INTEGER,
          |    collection TEXT,
          |    created_at TIMESTAMPTZ DEFAULT NOW(),
          |    UNIQUE(document_id, chunk_index)
          |)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_chunk_registry_document
          |    ON chunk_registry(document_id)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_chunk_registry_collection
          |    ON chunk_registry(collection)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_chunk_registry_length
          |    ON chunk_registry(content_length)""".stripMargin
      )
    } finally {
      stmt.close()
    }
  }

  override def store(documentId: String, chunks: Seq[ChunkInfo]): IO[Unit] = IO {
    // First delete existing chunks for this document
    val deleteSql = "DELETE FROM chunk_registry WHERE document_id = ?"
    val deleteStmt = connection.prepareStatement(deleteSql)
    try {
      deleteStmt.setString(1, documentId)
      deleteStmt.executeUpdate()
    } finally {
      deleteStmt.close()
    }

    // Insert new chunks
    if (chunks.nonEmpty) {
      val insertSql =
        """INSERT INTO chunk_registry
          |(document_id, chunk_index, content, content_length, metadata, headings,
          | is_code_block, language, start_offset, end_offset, collection)
          |VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""".stripMargin
      val insertStmt = connection.prepareStatement(insertSql)
      try {
        chunks.foreach { chunk =>
          insertStmt.setString(1, chunk.documentId)
          insertStmt.setInt(2, chunk.index)
          insertStmt.setString(3, chunk.content)
          insertStmt.setInt(4, chunk.contentLength)
          insertStmt.setString(5, chunk.metadata.asJson.noSpaces)
          insertStmt.setArray(6, connection.createArrayOf("TEXT", chunk.chunkMetadata.headings.toArray))
          insertStmt.setBoolean(7, chunk.chunkMetadata.isCodeBlock)
          insertStmt.setString(8, chunk.chunkMetadata.language.orNull)
          insertStmt.setObject(9, chunk.chunkMetadata.startOffset.map(Integer.valueOf).orNull)
          insertStmt.setObject(10, chunk.chunkMetadata.endOffset.map(Integer.valueOf).orNull)
          insertStmt.setString(11, chunk.metadata.get("collection").orNull)
          insertStmt.addBatch()
        }
        insertStmt.executeBatch()
      } finally {
        insertStmt.close()
      }
    }
  }

  override def getChunks(documentId: String): IO[Seq[ChunkInfo]] = IO {
    val sql =
      """SELECT id, document_id, chunk_index, content, content_length, metadata,
        |       headings, is_code_block, language, start_offset, end_offset
        |FROM chunk_registry
        |WHERE document_id = ?
        |ORDER BY chunk_index""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, documentId)
      val rs = stmt.executeQuery()
      val chunks = scala.collection.mutable.ArrayBuffer.empty[ChunkInfo]
      while (rs.next()) {
        chunks += rowToChunkInfo(rs)
      }
      chunks.toSeq
    } finally {
      stmt.close()
    }
  }

  override def getChunk(documentId: String, index: Int): IO[Option[ChunkInfo]] = IO {
    val sql =
      """SELECT id, document_id, chunk_index, content, content_length, metadata,
        |       headings, is_code_block, language, start_offset, end_offset
        |FROM chunk_registry
        |WHERE document_id = ? AND chunk_index = ?""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, documentId)
      stmt.setInt(2, index)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToChunkInfo(rs)) else None
    } finally {
      stmt.close()
    }
  }

  override def listChunks(page: Int, pageSize: Int): IO[ChunkListResponse] = IO {
    val offset = (page - 1) * pageSize

    // Get total count
    val countStmt = connection.createStatement()
    val totalCount = try {
      val rs = countStmt.executeQuery("SELECT COUNT(*) FROM chunk_registry")
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      countStmt.close()
    }

    // Get page of chunks
    val sql =
      """SELECT id, document_id, chunk_index, content, content_length, metadata,
        |       headings, is_code_block, language, start_offset, end_offset
        |FROM chunk_registry
        |ORDER BY document_id, chunk_index
        |LIMIT ? OFFSET ?""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setInt(1, pageSize)
      stmt.setInt(2, offset)
      val rs = stmt.executeQuery()
      val chunks = scala.collection.mutable.ArrayBuffer.empty[ChunkInfo]
      while (rs.next()) {
        chunks += rowToChunkInfo(rs)
      }
      ChunkListResponse(
        chunks = chunks.toSeq,
        total = totalCount,
        page = page,
        pageSize = pageSize,
        hasMore = offset + chunks.size < totalCount
      )
    } finally {
      stmt.close()
    }
  }

  override def getChunkSizeDistribution(): IO[ChunkSizeDistribution] = IO {
    val stmt = connection.createStatement()
    try {
      // Get min, max, avg
      val statsRs = stmt.executeQuery(
        """SELECT
          |  MIN(content_length) as min_size,
          |  MAX(content_length) as max_size,
          |  AVG(content_length) as avg_size
          |FROM chunk_registry""".stripMargin
      )

      val (minSize, maxSize, avgSize) = if (statsRs.next()) {
        (statsRs.getInt("min_size"), statsRs.getInt("max_size"), statsRs.getDouble("avg_size"))
      } else {
        (0, 0, 0.0)
      }

      // Get median and p90
      val countRs = stmt.executeQuery("SELECT COUNT(*) FROM chunk_registry")
      val count = if (countRs.next()) countRs.getInt(1) else 0

      val (median, p90) = if (count > 0) {
        val medianRs = stmt.executeQuery(
          s"""SELECT content_length FROM chunk_registry
             |ORDER BY content_length
             |LIMIT 1 OFFSET ${count / 2}""".stripMargin
        )
        val medianVal = if (medianRs.next()) medianRs.getDouble(1) else 0.0

        val p90Rs = stmt.executeQuery(
          s"""SELECT content_length FROM chunk_registry
             |ORDER BY content_length
             |LIMIT 1 OFFSET ${(count * 0.9).toInt}""".stripMargin
        )
        val p90Val = if (p90Rs.next()) p90Rs.getDouble(1) else 0.0
        (medianVal, p90Val)
      } else {
        (0.0, 0.0)
      }

      // Get histogram buckets (0-200, 200-400, 400-600, 600-800, 800-1000, 1000+)
      val bucketsRs = stmt.executeQuery(
        """SELECT
          |  CASE
          |    WHEN content_length < 200 THEN 0
          |    WHEN content_length < 400 THEN 200
          |    WHEN content_length < 600 THEN 400
          |    WHEN content_length < 800 THEN 600
          |    WHEN content_length < 1000 THEN 800
          |    WHEN content_length < 1500 THEN 1000
          |    ELSE 1500
          |  END as bucket_start,
          |  COUNT(*) as count
          |FROM chunk_registry
          |GROUP BY bucket_start
          |ORDER BY bucket_start""".stripMargin
      )
      val buckets = scala.collection.mutable.ArrayBuffer.empty[SizeBucket]
      while (bucketsRs.next()) {
        val start = bucketsRs.getInt("bucket_start")
        val end = if (start == 1500) Int.MaxValue else start + 200
        buckets += SizeBucket(start, end, bucketsRs.getInt("count"))
      }

      ChunkSizeDistribution(
        min = minSize,
        max = maxSize,
        avg = avgSize,
        median = median,
        p90 = p90,
        buckets = buckets.toSeq
      )
    } finally {
      stmt.close()
    }
  }

  override def count(): IO[Int] = IO {
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery("SELECT COUNT(*) FROM chunk_registry")
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  override def countByDocument(documentId: String): IO[Int] = IO {
    val sql = "SELECT COUNT(*) FROM chunk_registry WHERE document_id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, documentId)
      val rs = stmt.executeQuery()
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  override def countByCollection(collection: String): IO[Int] = IO {
    val sql = "SELECT COUNT(*) FROM chunk_registry WHERE collection = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, collection)
      val rs = stmt.executeQuery()
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  override def avgChunkSize(): IO[Double] = IO {
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery("SELECT AVG(content_length) FROM chunk_registry")
      if (rs.next()) rs.getDouble(1) else 0.0
    } finally {
      stmt.close()
    }
  }

  override def avgChunkSizeByCollection(collection: String): IO[Double] = IO {
    val sql = "SELECT AVG(content_length) FROM chunk_registry WHERE collection = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, collection)
      val rs = stmt.executeQuery()
      if (rs.next()) rs.getDouble(1) else 0.0
    } finally {
      stmt.close()
    }
  }

  override def deleteByDocument(documentId: String): IO[Unit] = IO {
    val sql = "DELETE FROM chunk_registry WHERE document_id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, documentId)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  override def deleteByCollection(collection: String): IO[Unit] = IO {
    val sql = "DELETE FROM chunk_registry WHERE collection = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, collection)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  override def clear(): IO[Unit] = IO {
    val stmt = connection.createStatement()
    try {
      stmt.execute("DELETE FROM chunk_registry")
    } finally {
      stmt.close()
    }
  }

  override def close(): IO[Unit] = IO {
    if (connection != null && !connection.isClosed) {
      connection.close()
    }
  }

  private def rowToChunkInfo(rs: ResultSet): ChunkInfo = {
    val metadataJson = rs.getString("metadata")
    val metadata: Map[String, String] = parse(metadataJson)
      .flatMap(_.as[Map[String, String]])
      .getOrElse(Map.empty)

    val headingsArray = rs.getArray("headings")
    val headings: Seq[String] = if (headingsArray != null) {
      headingsArray.getArray.asInstanceOf[Array[String]].toSeq
    } else {
      Seq.empty
    }

    val startOffset = Option(rs.getObject("start_offset")).map(_.asInstanceOf[Integer].intValue())
    val endOffset = Option(rs.getObject("end_offset")).map(_.asInstanceOf[Integer].intValue())

    ChunkInfo(
      id = rs.getInt("id").toString,
      documentId = rs.getString("document_id"),
      index = rs.getInt("chunk_index"),
      content = rs.getString("content"),
      contentLength = rs.getInt("content_length"),
      metadata = metadata,
      chunkMetadata = ChunkMetadataInfo(
        headings = headings,
        isCodeBlock = rs.getBoolean("is_code_block"),
        language = Option(rs.getString("language")),
        startOffset = startOffset,
        endOffset = endOffset
      )
    )
  }
}

object PgChunkStore {

  /**
   * Create and initialize a PostgreSQL chunk store.
   */
  def apply(dbConfig: DatabaseConfig): IO[PgChunkStore] = {
    val store = new PgChunkStore(dbConfig)
    store.initialize().as(store)
  }
}
