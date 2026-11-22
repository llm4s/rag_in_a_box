package org.llm4s.ragbox.registry

import cats.effect.IO
import io.circe.parser._
import io.circe.syntax._
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model._

import java.sql.{ResultSet, Timestamp}
import java.time.Instant
import javax.sql.DataSource

/**
 * PostgreSQL-backed collection configuration registry.
 *
 * Persists per-collection chunking configurations to PostgreSQL for
 * durability across application restarts.
 */
class PgCollectionConfigRegistry(dataSource: DataSource) extends CollectionConfigRegistry {

  override def get(collection: String): IO[Option[CollectionChunkingConfig]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      """SELECT collection, strategy, target_size, max_size, overlap, file_type_strategies, updated_at
        |FROM collection_configs WHERE collection = ?""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, collection)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToConfig(rs)) else None
    } finally {
      stmt.close()
    }
  }

  override def put(config: CollectionChunkingConfig): IO[Unit] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      """INSERT INTO collection_configs (collection, strategy, target_size, max_size, overlap, file_type_strategies, updated_at)
        |VALUES (?, ?, ?, ?, ?, ?::jsonb, NOW())
        |ON CONFLICT (collection) DO UPDATE SET
        |    strategy = EXCLUDED.strategy,
        |    target_size = EXCLUDED.target_size,
        |    max_size = EXCLUDED.max_size,
        |    overlap = EXCLUDED.overlap,
        |    file_type_strategies = EXCLUDED.file_type_strategies,
        |    updated_at = NOW()""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, config.collection)
      stmt.setString(2, config.strategy.orNull)
      if (config.targetSize.isDefined) stmt.setInt(3, config.targetSize.get)
      else stmt.setNull(3, java.sql.Types.INTEGER)
      if (config.maxSize.isDefined) stmt.setInt(4, config.maxSize.get)
      else stmt.setNull(4, java.sql.Types.INTEGER)
      if (config.overlap.isDefined) stmt.setInt(5, config.overlap.get)
      else stmt.setNull(5, java.sql.Types.INTEGER)
      stmt.setString(6, config.fileTypeStrategies.asJson.noSpaces)
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  override def delete(collection: String): IO[Boolean] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "DELETE FROM collection_configs WHERE collection = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, collection)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  override def listConfigured(): IO[Seq[String]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql = "SELECT collection FROM collection_configs ORDER BY collection"
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

  override def getAll(): IO[Seq[CollectionChunkingConfig]] = ConnectionPool.withConnection(dataSource) { connection =>
    val sql =
      """SELECT collection, strategy, target_size, max_size, overlap, file_type_strategies, updated_at
        |FROM collection_configs ORDER BY collection""".stripMargin
    val stmt = connection.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      val configs = scala.collection.mutable.ArrayBuffer.empty[CollectionChunkingConfig]
      while (rs.next()) {
        configs += rowToConfig(rs)
      }
      configs.toSeq
    } finally {
      stmt.close()
    }
  }

  override def getEffective(
    collection: String,
    filename: Option[String],
    defaultStrategy: String,
    defaultTargetSize: Int,
    defaultMaxSize: Int,
    defaultOverlap: Int
  ): IO[EffectiveCollectionConfig] = {
    get(collection).map { maybeConfig =>
      CollectionConfigRegistry.resolveEffective(
        maybeConfig, filename, defaultStrategy, defaultTargetSize, defaultMaxSize, defaultOverlap
      )
    }
  }

  override def close(): IO[Unit] = IO.unit

  private def rowToConfig(rs: ResultSet): CollectionChunkingConfig = {
    val fileTypeJson = Option(rs.getString("file_type_strategies")).getOrElse("{}")
    val fileTypeStrategies: Map[String, String] = parse(fileTypeJson)
      .flatMap(_.as[Map[String, String]])
      .getOrElse(Map.empty)

    CollectionChunkingConfig(
      collection = rs.getString("collection"),
      strategy = Option(rs.getString("strategy")),
      targetSize = Option(rs.getInt("target_size")).filter(_ => !rs.wasNull()),
      maxSize = Option(rs.getInt("max_size")).filter(_ => !rs.wasNull()),
      overlap = Option(rs.getInt("overlap")).filter(_ => !rs.wasNull()),
      fileTypeStrategies = fileTypeStrategies,
      updatedAt = Option(rs.getTimestamp("updated_at")).map(_.toInstant)
    )
  }
}

object PgCollectionConfigRegistry {

  /**
   * A PostgreSQL collection config registry. The schema is managed by Flyway ([[org.llm4s.ragbox.db.Migrations]]); this does no I/O.
   */
  def apply(dataSource: DataSource): IO[PgCollectionConfigRegistry] = IO.pure(new PgCollectionConfigRegistry(dataSource))
}
