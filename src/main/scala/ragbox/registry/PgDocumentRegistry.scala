package ragbox.registry

import cats.effect.IO
import cats.syntax.all._
import io.circe.parser._
import io.circe.syntax._
import ragbox.config.DatabaseConfig

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.time.Instant
import java.util.Properties
import scala.compiletime.uninitialized

/**
 * PostgreSQL-backed document registry.
 *
 * Persists document tracking information to PostgreSQL for durability
 * across application restarts.
 */
class PgDocumentRegistry(dbConfig: DatabaseConfig) extends DocumentRegistry {

  private var connection: Connection = uninitialized

  /**
   * Initialize the registry and ensure tables exist.
   */
  override def initialize(): IO[Unit] = IO {
    Class.forName("org.postgresql.Driver")

    val props = new Properties()
    props.setProperty("user", dbConfig.effectiveUser)
    props.setProperty("password", dbConfig.effectivePassword)

    connection = DriverManager.getConnection(dbConfig.connectionString, props)

    // Ensure tables exist
    val stmt = connection.createStatement()
    try {
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS document_registry (
          |    document_id TEXT PRIMARY KEY,
          |    content_hash TEXT NOT NULL,
          |    chunk_count INTEGER NOT NULL DEFAULT 0,
          |    metadata JSONB DEFAULT '{}',
          |    collection TEXT,
          |    indexed_at TIMESTAMPTZ DEFAULT NOW(),
          |    updated_at TIMESTAMPTZ DEFAULT NOW()
          |)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_document_registry_indexed_at
          |    ON document_registry(indexed_at)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_document_registry_collection
          |    ON document_registry(collection)""".stripMargin
      )
      // Add collection column if not exists (for existing installations)
      stmt.execute(
        """DO $$ BEGIN
          |    ALTER TABLE document_registry ADD COLUMN collection TEXT;
          |EXCEPTION
          |    WHEN duplicate_column THEN NULL;
          |END $$""".stripMargin
      )
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS sync_status (
          |    id INTEGER PRIMARY KEY DEFAULT 1,
          |    last_sync_time TIMESTAMPTZ,
          |    CONSTRAINT single_row CHECK (id = 1)
          |)""".stripMargin
      )
      stmt.execute(
        """INSERT INTO sync_status (id, last_sync_time)
          |VALUES (1, NULL)
          |ON CONFLICT (id) DO NOTHING""".stripMargin
      )
    } finally {
      stmt.close()
    }
  }

  override def get(documentId: String): IO[Option[DocumentEntry]] = IO {
    val sql =
      """SELECT document_id, content_hash, chunk_count, metadata, collection, indexed_at, updated_at
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

  override def put(entry: DocumentEntry): IO[Unit] = IO {
    val sql =
      """INSERT INTO document_registry (document_id, content_hash, chunk_count, metadata, collection, indexed_at, updated_at)
        |VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
        |ON CONFLICT (document_id) DO UPDATE SET
        |    content_hash = EXCLUDED.content_hash,
        |    chunk_count = EXCLUDED.chunk_count,
        |    metadata = EXCLUDED.metadata,
        |    collection = EXCLUDED.collection,
        |    updated_at = EXCLUDED.updated_at""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, entry.documentId)
      stmt.setString(2, entry.contentHash)
      stmt.setInt(3, entry.chunkCount)
      stmt.setString(4, entry.metadata.asJson.noSpaces)
      stmt.setString(5, entry.collection.orNull)
      stmt.setTimestamp(6, Timestamp.from(entry.indexedAt))
      stmt.setTimestamp(7, Timestamp.from(entry.updatedAt))
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  override def remove(documentId: String): IO[Unit] = IO {
    val sql = "DELETE FROM document_registry WHERE document_id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, documentId)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  override def contains(documentId: String): IO[Boolean] = IO {
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

  override def listIds(): IO[Seq[String]] = IO {
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

  override def listIdsByCollection(collection: String): IO[Seq[String]] = IO {
    val sql = "SELECT document_id FROM document_registry WHERE collection = ? ORDER BY indexed_at"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, collection)
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

  override def listCollections(): IO[Seq[String]] = IO {
    val sql = "SELECT DISTINCT collection FROM document_registry WHERE collection IS NOT NULL ORDER BY collection"
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      val collections = scala.collection.mutable.ArrayBuffer.empty[String]
      while (rs.next()) {
        collections += rs.getString("collection")
      }
      collections.toSeq
    } finally {
      stmt.close()
    }
  }

  override def count(): IO[Int] = IO {
    val sql = "SELECT COUNT(*) FROM document_registry"
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  override def countByCollection(collection: String): IO[Int] = IO {
    val sql = "SELECT COUNT(*) FROM document_registry WHERE collection = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, collection)
      val rs = stmt.executeQuery()
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  override def clear(): IO[Unit] = IO {
    val stmt = connection.createStatement()
    try {
      stmt.execute("DELETE FROM document_registry")
    } finally {
      stmt.close()
    }
  }

  override def findOrphans(keepIds: Set[String]): IO[Seq[String]] =
    listIds().map(_.filterNot(keepIds.contains))

  override def getSyncInfo(): IO[SyncInfo] = IO {
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

  override def markSyncComplete(): IO[Unit] = IO {
    val sql = "UPDATE sync_status SET last_sync_time = NOW() WHERE id = 1"
    val stmt = connection.createStatement()
    try {
      stmt.executeUpdate(sql)
    } finally {
      stmt.close()
    }
  }

  override def close(): IO[Unit] = IO {
    if (connection != null && !connection.isClosed) {
      connection.close()
    }
  }

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
      updatedAt = rs.getTimestamp("updated_at").toInstant
    )
  }
}

object PgDocumentRegistry {

  /**
   * Create and initialize a PostgreSQL document registry.
   */
  def apply(dbConfig: DatabaseConfig): IO[PgDocumentRegistry] = {
    val registry = new PgDocumentRegistry(dbConfig)
    registry.initialize().as(registry)
  }
}
