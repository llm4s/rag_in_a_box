package ragbox.registry

import cats.effect.IO
import io.circe.parser._
import io.circe.syntax._
import ragbox.config.DatabaseConfig
import ragbox.model._

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.time.Instant
import java.util.Properties
import scala.compiletime.uninitialized

/**
 * PostgreSQL-backed collection configuration registry.
 *
 * Persists per-collection chunking configurations to PostgreSQL for
 * durability across application restarts.
 */
class PgCollectionConfigRegistry(dbConfig: DatabaseConfig) extends CollectionConfigRegistry {

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

    // Ensure table exists
    val stmt = connection.createStatement()
    try {
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS collection_configs (
          |    collection TEXT PRIMARY KEY,
          |    strategy TEXT,
          |    target_size INTEGER,
          |    max_size INTEGER,
          |    overlap INTEGER,
          |    file_type_strategies JSONB DEFAULT '{}',
          |    updated_at TIMESTAMPTZ DEFAULT NOW()
          |)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_collection_configs_updated
          |    ON collection_configs(updated_at DESC)""".stripMargin
      )
    } finally {
      stmt.close()
    }
  }

  override def get(collection: String): IO[Option[CollectionChunkingConfig]] = IO {
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

  override def put(config: CollectionChunkingConfig): IO[Unit] = IO {
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

  override def delete(collection: String): IO[Boolean] = IO {
    val sql = "DELETE FROM collection_configs WHERE collection = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, collection)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  override def listConfigured(): IO[Seq[String]] = IO {
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

  override def getAll(): IO[Seq[CollectionChunkingConfig]] = IO {
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
      maybeConfig match {
        case None =>
          // No custom config, use defaults
          EffectiveCollectionConfig(
            strategy = defaultStrategy,
            targetSize = defaultTargetSize,
            maxSize = defaultMaxSize,
            overlap = defaultOverlap,
            source = "default"
          )

        case Some(config) =>
          // Check for file-type override
          val fileExtension = filename.flatMap(extractExtension)
          val fileTypeStrategy = fileExtension.flatMap(ext => config.fileTypeStrategies.get(ext))

          fileTypeStrategy match {
            case Some(strategy) =>
              // File-type override found
              EffectiveCollectionConfig(
                strategy = strategy,
                targetSize = config.targetSize.getOrElse(defaultTargetSize),
                maxSize = config.maxSize.getOrElse(defaultMaxSize),
                overlap = config.overlap.getOrElse(defaultOverlap),
                source = "file-type",
                appliedFileTypeOverride = fileExtension
              )

            case None =>
              // Use collection config
              EffectiveCollectionConfig(
                strategy = config.strategy.getOrElse(defaultStrategy),
                targetSize = config.targetSize.getOrElse(defaultTargetSize),
                maxSize = config.maxSize.getOrElse(defaultMaxSize),
                overlap = config.overlap.getOrElse(defaultOverlap),
                source = "collection"
              )
          }
      }
    }
  }

  override def close(): IO[Unit] = IO {
    if (connection != null && !connection.isClosed) {
      connection.close()
    }
  }

  /**
   * Extract file extension from filename (with leading dot).
   */
  private def extractExtension(filename: String): Option[String] = {
    val lastDot = filename.lastIndexOf('.')
    if (lastDot > 0 && lastDot < filename.length - 1) {
      Some(filename.substring(lastDot).toLowerCase)
    } else {
      None
    }
  }

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
   * Create and initialize a PostgreSQL collection config registry.
   */
  def apply(dbConfig: DatabaseConfig): IO[PgCollectionConfigRegistry] = {
    val registry = new PgCollectionConfigRegistry(dbConfig)
    registry.initialize().as(registry)
  }
}
