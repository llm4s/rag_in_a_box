package org.llm4s.ragbox.store

import cats.effect.IO
import io.circe.parser._
import io.circe.syntax._
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model.{ChunkInfo, ChunkListResponse, ChunkMetadataInfo, ChunkSizeDistribution, SizeBucket}

import java.sql.ResultSet
import javax.sql.DataSource

/**
 * PostgreSQL-backed chunk store.
 *
 * Stores chunk content and metadata for visibility into the chunking process.
 */
class PgChunkStore(dataSource: DataSource) extends ChunkStore {

  /**
   * Replace a document's chunks atomically: the delete and the inserts run in one transaction,
   * so a failure part-way leaves the previous chunks in place. A transaction-scoped advisory
   * lock on the document id serialises concurrent stores of the same document: without it, the
   * second transaction's delete (whose snapshot cannot see the first one's new rows) would be
   * followed by inserts that violate `UNIQUE(document_id, chunk_index)`.
   */
  override def store(documentId: String, chunks: Seq[ChunkInfo]): IO[Unit] = ConnectionPool.withTransaction(dataSource) { connection =>
    val lockStmt = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('chunk_registry'), hashtext(?))")
    try {
      lockStmt.setString(1, documentId)
      lockStmt.execute()
    } finally {
      lockStmt.close()
    }

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
          | is_code_block, language, start_offset, end_offset, collection, chunk_id)
          |VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
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
          insertStmt.setString(11, chunk.metadata.get("collection").filter(_.nonEmpty).orNull)
          insertStmt.setString(12, chunk.id)
          insertStmt.addBatch()
        }
        insertStmt.executeBatch()
      } finally {
        insertStmt.close()
      }
    }
  }

  override def getChunks(documentId: String): IO[Seq[ChunkInfo]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      """SELECT id, chunk_id, document_id, chunk_index, content, content_length, metadata,
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

  override def getChunk(documentId: String, index: Int): IO[Option[ChunkInfo]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      """SELECT id, chunk_id, document_id, chunk_index, content, content_length, metadata,
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

  override def listChunks(page: Int, pageSize: Int): IO[ChunkListResponse] = ConnectionPool.withConnection(dataSource) { connection =>
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
      """SELECT id, chunk_id, document_id, chunk_index, content, content_length, metadata,
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

  /**
   * One query: min, max, mean, median and p90 (`percentile_cont`) and the histogram
   * (`width_bucket` over [[PgChunkStore.BucketBounds]]), the statistics repeated on each bucket's
   * row. Only non-empty buckets are returned.
   */
  override def getChunkSizeDistribution(): IO[ChunkSizeDistribution] = ConnectionPool.withConnection(dataSource) { connection =>
    val bounds = PgChunkStore.BucketBounds
    val sql =
      s"""WITH stats AS (
         |  SELECT COUNT(*) AS n, MIN(content_length) AS min_size, MAX(content_length) AS max_size,
         |         AVG(content_length) AS avg_size,
         |         percentile_cont(0.5) WITHIN GROUP (ORDER BY content_length) AS median,
         |         percentile_cont(0.9) WITHIN GROUP (ORDER BY content_length) AS p90
         |  FROM chunk_registry
         |), buckets AS (
         |  SELECT width_bucket(content_length, ARRAY[${bounds.mkString(",")}]) AS bucket, COUNT(*) AS count
         |  FROM chunk_registry GROUP BY 1
         |)
         |SELECT s.*, b.bucket, b.count FROM stats s LEFT JOIN buckets b ON true ORDER BY b.bucket""".stripMargin
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      var distribution = ChunkSizeDistribution(0, 0, 0.0, 0.0, 0.0, Seq.empty)
      val buckets = scala.collection.mutable.ArrayBuffer.empty[SizeBucket]
      while (rs.next()) {
        if (rs.getLong("n") > 0) {
          distribution = ChunkSizeDistribution(
            min = rs.getInt("min_size"),
            max = rs.getInt("max_size"),
            avg = rs.getDouble("avg_size"),
            median = rs.getDouble("median"),
            p90 = rs.getDouble("p90"),
            buckets = Seq.empty
          )
          // width_bucket gives 0 below the first bound, i for [bounds(i-1), bounds(i)), and bounds.size above the last
          val bucket = rs.getInt("bucket")
          val start = if (bucket == 0) 0 else bounds(bucket - 1)
          val end = if (bucket < bounds.size) bounds(bucket) else Int.MaxValue
          buckets += SizeBucket(start, end, rs.getInt("count"))
        }
      }
      distribution.copy(buckets = buckets.toSeq)
    } finally {
      stmt.close()
    }
  }

  /** One `GROUP BY` for every collection. */
  override def statsByCollection(): IO[Map[String, CollectionChunkStats]] = ConnectionPool.withConnection(dataSource) { connection =>
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(
        "SELECT COALESCE(collection, 'default'), COUNT(*), AVG(content_length) FROM chunk_registry GROUP BY 1"
      )
      val stats = Map.newBuilder[String, CollectionChunkStats]
      while (rs.next()) {
        stats += rs.getString(1) -> CollectionChunkStats(rs.getInt(2), rs.getDouble(3))
      }
      stats.result()
    } finally {
      stmt.close()
    }
  }

  override def count(): IO[Int] = ConnectionPool.withConnection(dataSource) { connection =>
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery("SELECT COUNT(*) FROM chunk_registry")
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  override def deleteByDocument(documentId: String): IO[Unit] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "DELETE FROM chunk_registry WHERE document_id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, documentId)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  override def clear(): IO[Unit] = ConnectionPool.withConnection(dataSource) { connection =>
    val stmt = connection.createStatement()
    try {
      stmt.execute("DELETE FROM chunk_registry")
    } finally {
      stmt.close()
    }
  }

  override def close(): IO[Unit] = IO.unit

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
      id = Option(rs.getString("chunk_id")).getOrElse(rs.getInt("id").toString),
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
   * Upper bounds of the chunk size histogram's buckets: [0, 200), [200, 400), ..., [1000, 1500),
   * [1500, Int.MaxValue).
   */
  val BucketBounds: IndexedSeq[Int] = IndexedSeq(200, 400, 600, 800, 1000, 1500)

  /**
   * A PostgreSQL chunk store. The schema is managed by Flyway ([[org.llm4s.ragbox.db.Migrations]]); this does no I/O.
   */
  def apply(dataSource: DataSource): IO[PgChunkStore] = IO.pure(new PgChunkStore(dataSource))
}
