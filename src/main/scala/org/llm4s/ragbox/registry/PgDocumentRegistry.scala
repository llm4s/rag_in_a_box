package org.llm4s.ragbox.registry

import cats.effect.IO
import io.circe.parser._
import io.circe.syntax._
import org.llm4s.ragbox.db.ConnectionPool

import java.sql.{ResultSet, Timestamp}
import java.time.Instant
import javax.sql.DataSource

/**
 * PostgreSQL-backed document registry.
 *
 * Persists document tracking information to PostgreSQL for durability
 * across application restarts.
 */
class PgDocumentRegistry(dataSource: DataSource) extends DocumentRegistry {
  import PgDocumentRegistry.EntryColumns

  override def get(documentId: String): IO[Option[DocumentEntry]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      s"""SELECT $EntryColumns
        |FROM document_registry WHERE document_id = ?""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, documentId)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToEntry(rs)) else None
    } finally {
      stmt.close()
    }
  }

  override def put(entry: DocumentEntry): IO[Unit] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      """INSERT INTO document_registry (document_id, content_hash, chunk_count, metadata, collection, indexed_at, updated_at, readable_by, source_id)
        |VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
        |ON CONFLICT (document_id) DO UPDATE SET
        |    content_hash = EXCLUDED.content_hash,
        |    chunk_count = EXCLUDED.chunk_count,
        |    metadata = EXCLUDED.metadata,
        |    collection = EXCLUDED.collection,
        |    updated_at = EXCLUDED.updated_at,
        |    readable_by = EXCLUDED.readable_by,
        |    source_id = EXCLUDED.source_id""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, entry.documentId)
      stmt.setString(2, entry.contentHash)
      stmt.setInt(3, entry.chunkCount)
      stmt.setString(4, entry.metadata.asJson.noSpaces)
      stmt.setString(5, entry.collection.orNull)
      stmt.setTimestamp(6, Timestamp.from(entry.indexedAt))
      stmt.setTimestamp(7, Timestamp.from(entry.updatedAt))
      stmt.setArray(8, connection.createArrayOf("integer", entry.readableBy.toSeq.sorted.map(Int.box).toArray))
      stmt.setString(9, entry.sourceId.orNull)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  override def remove(documentId: String): IO[Unit] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "DELETE FROM document_registry WHERE document_id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, documentId)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  override def contains(documentId: String): IO[Boolean] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT 1 FROM document_registry WHERE document_id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, documentId)
      val rs = stmt.executeQuery()
      rs.next()
    } finally {
      stmt.close()
    }
  }

  override def listIds(): IO[Seq[String]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT document_id FROM document_registry ORDER BY indexed_at"
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      val ids = scala.collection.mutable.ArrayBuffer.empty[String]
      while (rs.next()) {
        ids += rs.getString("document_id")
      }
      ids.toSeq
    } finally {
      stmt.close()
    }
  }

  override def listEntries(): IO[Seq[DocumentEntry]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      s"""SELECT $EntryColumns
        |FROM document_registry ORDER BY indexed_at""".stripMargin
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      val entries = scala.collection.mutable.ArrayBuffer.empty[DocumentEntry]
      while (rs.next()) {
        entries += rowToEntry(rs)
      }
      entries.toSeq
    } finally {
      stmt.close()
    }
  }

  override def listEntriesSince(since: Instant): IO[Seq[DocumentEntry]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      s"""SELECT $EntryColumns
        |FROM document_registry WHERE updated_at > ? ORDER BY updated_at""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setTimestamp(1, Timestamp.from(since))
      val rs = stmt.executeQuery()
      val entries = scala.collection.mutable.ArrayBuffer.empty[DocumentEntry]
      while (rs.next()) {
        entries += rowToEntry(rs)
      }
      entries.toSeq
    } finally {
      stmt.close()
    }
  }

  /** One query for the page and its total (`COUNT(*) OVER ()`). */
  override def listEntriesPage(
    since: Option[Instant],
    collections: Option[Set[String]],
    offset: Int,
    limit: Int
  ): IO[DocumentEntryPage] = ConnectionPool.withConnection(dataSource) { connection =>
    // "default" stands for no collection; a collection includes its sub-collections
    val conditions = since.map(_ => "updated_at > ?").toList ++ collections.map(_ =>
      "EXISTS (SELECT 1 FROM unnest(?::text[]) AS a(c) " +
        "WHERE COALESCE(collection, 'default') = a.c OR left(COALESCE(collection, 'default'), length(a.c) + 1) = a.c || '/')"
    )
    val where = if (conditions.isEmpty) "" else conditions.mkString("WHERE ", " AND ", "")
    val order = if (since.isDefined) "updated_at, document_id" else "indexed_at, document_id"
    val sql =
      s"""SELECT $EntryColumns, COUNT(*) OVER () AS total
        |FROM document_registry $where ORDER BY $order OFFSET ? LIMIT ?""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      var i = 0
      def next(): Int = { i += 1; i }
      since.foreach(s => stmt.setTimestamp(next(), Timestamp.from(s)))
      collections.foreach(c => stmt.setArray(next(), connection.createArrayOf("text", c.toArray[AnyRef])))
      stmt.setInt(next(), offset)
      stmt.setInt(next(), limit)
      val rs = stmt.executeQuery()
      val entries = scala.collection.mutable.ArrayBuffer.empty[DocumentEntry]
      var total = 0
      while (rs.next()) {
        entries += rowToEntry(rs)
        total = rs.getInt("total")
      }
      if (entries.nonEmpty || offset == 0) DocumentEntryPage(entries.toSeq, total)
      else {
        // Past the last page: the window total is not available, so count separately
        val countSql = s"SELECT COUNT(*) FROM document_registry $where"
        val countStmt = connection.prepareStatement(countSql)
        try {
          var j = 0
          def nextCount(): Int = { j += 1; j }
          since.foreach(s => countStmt.setTimestamp(nextCount(), Timestamp.from(s)))
          collections.foreach(c => countStmt.setArray(nextCount(), connection.createArrayOf("text", c.toArray[AnyRef])))
          val countRs = countStmt.executeQuery()
          DocumentEntryPage(Seq.empty, if (countRs.next()) countRs.getInt(1) else 0)
        } finally {
          countStmt.close()
        }
      }
    } finally {
      stmt.close()
    }
  }

  override def ingestionTimes(): IO[(Option[Instant], Option[Instant])] = ConnectionPool.withConnection(dataSource) { connection =>
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery("SELECT MIN(indexed_at), MAX(updated_at) FROM document_registry")
      if (rs.next()) (Option(rs.getTimestamp(1)).map(_.toInstant), Option(rs.getTimestamp(2)).map(_.toInstant))
      else (None, None)
    } finally {
      stmt.close()
    }
  }

  override def getMultiple(documentIds: Seq[String]): IO[Seq[DocumentEntry]] = ConnectionPool.withConnection(dataSource) { connection =>
    if (documentIds.isEmpty) {
      Seq.empty
    } else {
      // Use ANY with array for efficient batch lookup
      val sql =
        s"""SELECT $EntryColumns
          |FROM document_registry WHERE document_id = ANY(?)""".stripMargin
      val stmt = connection.prepareStatement(sql)
      try {
        val array = connection.createArrayOf("text", documentIds.toArray)
        stmt.setArray(1, array)
        val rs = stmt.executeQuery()
        val entries = scala.collection.mutable.ArrayBuffer.empty[DocumentEntry]
        while (rs.next()) {
          entries += rowToEntry(rs)
        }
        entries.toSeq
      } finally {
        stmt.close()
      }
    }
  }

  override def listIdsByCollection(collection: String): IO[Seq[String]] = ConnectionPool.withConnection(dataSource) { connection =>
    // Handle "default" as the pseudo-collection for documents without explicit collection
    val (sql, useParam) = if (collection == "default") {
      ("SELECT document_id FROM document_registry WHERE collection IS NULL ORDER BY indexed_at", false)
    } else {
      ("SELECT document_id FROM document_registry WHERE collection = ? ORDER BY indexed_at", true)
    }
    val stmt = connection.prepareStatement(sql)
    try {
      if (useParam) stmt.setString(1, collection)
      val rs = stmt.executeQuery()
      val ids = scala.collection.mutable.ArrayBuffer.empty[String]
      while (rs.next()) {
        ids += rs.getString("document_id")
      }
      ids.toSeq
    } finally {
      stmt.close()
    }
  }

  override def listIdsBySource(sourceId: String): IO[Seq[String]] = ConnectionPool.withConnection(dataSource) { connection =>
    val stmt = connection.prepareStatement("SELECT document_id FROM document_registry WHERE source_id = ?")
    try {
      stmt.setString(1, sourceId)
      val rs = stmt.executeQuery()
      val ids = scala.collection.mutable.ArrayBuffer.empty[String]
      while (rs.next()) {
        ids += rs.getString("document_id")
      }
      ids.toSeq
    } finally {
      stmt.close()
    }
  }

  override def listCollections(): IO[Seq[String]] = ConnectionPool.withConnection(dataSource) { connection =>
    // First get all distinct non-null collections
    val sql = "SELECT DISTINCT collection FROM document_registry WHERE collection IS NOT NULL ORDER BY collection"
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      val collections = scala.collection.mutable.ArrayBuffer.empty[String]
      while (rs.next()) {
        collections += rs.getString("collection")
      }
      // Also check if there are documents with no collection (legacy/default)
      val defaultCheckSql = "SELECT EXISTS(SELECT 1 FROM document_registry WHERE collection IS NULL)"
      val defaultCheck = connection.createStatement()
      try {
        val defaultRs = defaultCheck.executeQuery(defaultCheckSql)
        val hasDefault = defaultRs.next() && defaultRs.getBoolean(1)
        if (hasDefault) {
          // Prepend "default" for documents without explicit collection
          ("default" +: collections.toSeq)
        } else {
          collections.toSeq
        }
      } finally {
        defaultCheck.close()
      }
    } finally {
      stmt.close()
    }
  }

  override def count(): IO[Int] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT COUNT(*) FROM document_registry"
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  override def countByCollection(collection: String): IO[Int] = ConnectionPool.withConnection(dataSource) { connection =>
    // Handle "default" as the pseudo-collection for documents without explicit collection
    val (sql, useParam) = if (collection == "default") {
      ("SELECT COUNT(*) FROM document_registry WHERE collection IS NULL", false)
    } else {
      ("SELECT COUNT(*) FROM document_registry WHERE collection = ?", true)
    }
    val stmt = connection.prepareStatement(sql)
    try {
      if (useParam) stmt.setString(1, collection)
      val rs = stmt.executeQuery()
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  override def clear(): IO[Unit] = ConnectionPool.withConnection(dataSource) { connection =>
    val stmt = connection.createStatement()
    try {
      stmt.execute("DELETE FROM document_registry")
    } finally {
      stmt.close()
    }
  }

  /** Computed in SQL, so the registry's ids are never all loaded into memory. */
  override def findOrphans(keepIds: Set[String]): IO[Seq[String]] = ConnectionPool.withConnection(dataSource) { connection =>
    val stmt = connection.prepareStatement(
      "SELECT document_id FROM document_registry WHERE NOT (document_id = ANY(?)) ORDER BY indexed_at"
    )
    try {
      stmt.setArray(1, connection.createArrayOf("text", keepIds.toArray[AnyRef]))
      val rs = stmt.executeQuery()
      val ids = scala.collection.mutable.ArrayBuffer.empty[String]
      while (rs.next()) {
        ids += rs.getString("document_id")
      }
      ids.toSeq
    } finally {
      stmt.close()
    }
  }

  /** One `GROUP BY` instead of a count query per collection. */
  override def countsByCollection(): IO[Map[String, Int]] = ConnectionPool.withConnection(dataSource) { connection =>
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(
        "SELECT COALESCE(collection, 'default') AS coll, COUNT(*) FROM document_registry GROUP BY 1"
      )
      val counts = Map.newBuilder[String, Int]
      while (rs.next()) {
        counts += rs.getString(1) -> rs.getInt(2)
      }
      counts.result()
    } finally {
      stmt.close()
    }
  }

  override def getSyncInfo(): IO[SyncInfo] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT last_sync_time FROM sync_status WHERE id = 1"
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      if (rs.next()) {
        val ts = rs.getTimestamp("last_sync_time")
        SyncInfo(Option(ts).map(_.toInstant))
      } else {
        SyncInfo(None)
      }
    } finally {
      stmt.close()
    }
  }

  override def markSyncComplete(): IO[Unit] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "UPDATE sync_status SET last_sync_time = NOW() WHERE id = 1"
    val stmt = connection.createStatement()
    try {
      stmt.executeUpdate(sql)
    } finally {
      stmt.close()
    }
  }

  override def close(): IO[Unit] = IO.unit

  private def rowToEntry(rs: ResultSet): DocumentEntry = {
    val metadataJson = rs.getString("metadata")
    val metadata: Map[String, String] = parse(metadataJson)
      .flatMap(_.as[Map[String, String]])
      .getOrElse(Map.empty)

    DocumentEntry(
      documentId = rs.getString("document_id"),
      contentHash = rs.getString("content_hash"),
      chunkCount = rs.getInt("chunk_count"),
      metadata = metadata,
      collection = Option(rs.getString("collection")),
      indexedAt = rs.getTimestamp("indexed_at").toInstant,
      updatedAt = rs.getTimestamp("updated_at").toInstant,
      readableBy = Option(rs.getArray("readable_by"))
        .map(_.getArray.asInstanceOf[Array[java.lang.Integer]].map(_.intValue).toSet)
        .getOrElse(Set.empty),
      sourceId = Option(rs.getString("source_id"))
    )
  }
}

object PgDocumentRegistry {

  /** Columns read into a [[DocumentEntry]] by `rowToEntry`. */
  private val EntryColumns =
    "document_id, content_hash, chunk_count, metadata, collection, indexed_at, updated_at, readable_by, source_id"

  /**
   * A PostgreSQL document registry. The schema is managed by Flyway ([[org.llm4s.ragbox.db.Migrations]]); this does no I/O.
   */
  def apply(dataSource: DataSource): IO[PgDocumentRegistry] = IO.pure(new PgDocumentRegistry(dataSource))
}
