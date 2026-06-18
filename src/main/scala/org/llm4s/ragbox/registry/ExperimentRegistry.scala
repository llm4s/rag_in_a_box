package org.llm4s.ragbox.registry

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax._
import io.circe.parser._
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import java.time.Instant
import java.util.{Properties, UUID}
import scala.compiletime.uninitialized

/**
 * PostgreSQL-backed experiment registry.
 *
 * Stores and manages A/B test experiments for RAG configuration testing.
 */
class ExperimentRegistry(dbConfig: DatabaseConfig) {

  private var connection: Connection = uninitialized

  private def getConnection(): Connection = connection
  private def releaseConnection(conn: Connection): Unit = {
    // Connection is held for the lifetime of the registry
  }

  /**
   * Initialize the registry and ensure tables exist.
   */
  def initialize(): IO[Unit] = IO {
    Class.forName("org.postgresql.Driver")

    val props = new Properties()
    props.setProperty("user", dbConfig.effectiveUser)
    props.setProperty("password", dbConfig.effectivePassword)

    connection = DriverManager.getConnection(dbConfig.connectionString, props)

    val conn = getConnection()
    try {
      val stmt = conn.createStatement()
      try {
        stmt.execute(
          """CREATE TABLE IF NOT EXISTS experiments (
            |    id VARCHAR(64) PRIMARY KEY,
            |    name VARCHAR(255) NOT NULL,
            |    description TEXT,
            |    status VARCHAR(32) NOT NULL DEFAULT 'draft',
            |    baseline_config JSONB NOT NULL,
            |    variant_config JSONB NOT NULL,
            |    traffic_split DOUBLE PRECISION NOT NULL DEFAULT 0.5,
            |    collection VARCHAR(255),
            |    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            |    started_at TIMESTAMPTZ,
            |    ended_at TIMESTAMPTZ
            |)""".stripMargin
        )
        stmt.execute(
          """CREATE INDEX IF NOT EXISTS idx_experiments_status
            |    ON experiments(status)""".stripMargin
        )
        stmt.execute(
          """CREATE INDEX IF NOT EXISTS idx_experiments_created_at
            |    ON experiments(created_at)""".stripMargin
        )
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Create a new experiment.
   */
  def create(request: CreateExperimentRequest): IO[Experiment] = IO {
    val conn = getConnection()
    try {
      val id = s"exp-${UUID.randomUUID().toString.take(8)}"
      val now = Instant.now()

      val sql =
        """INSERT INTO experiments
          |    (id, name, description, status, baseline_config, variant_config, traffic_split, collection, created_at)
          |VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, id)
        stmt.setString(2, request.name)
        stmt.setString(3, request.description.orNull)
        stmt.setString(4, ExperimentStatus.Draft)
        stmt.setString(5, request.baselineConfig.asJson.noSpaces)
        stmt.setString(6, request.variantConfig.asJson.noSpaces)
        stmt.setDouble(7, request.trafficSplit.getOrElse(0.5))
        stmt.setString(8, request.collection.orNull)
        stmt.setTimestamp(9, Timestamp.from(now))
        stmt.executeUpdate()

        Experiment(
          id = id,
          name = request.name,
          description = request.description,
          status = ExperimentStatus.Draft,
          baselineConfig = request.baselineConfig,
          variantConfig = request.variantConfig,
          trafficSplit = request.trafficSplit.getOrElse(0.5),
          collection = request.collection,
          createdAt = now
        )
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Get an experiment by ID.
   */
  def get(id: String): IO[Option[Experiment]] = IO {
    val conn = getConnection()
    try {
      val sql =
        """SELECT id, name, description, status, baseline_config, variant_config,
          |       traffic_split, collection, created_at, started_at, ended_at
          |FROM experiments WHERE id = ?""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, id)
        val rs = stmt.executeQuery()
        if (rs.next()) Some(rowToExperiment(rs)) else None
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * List all experiments.
   */
  def list(status: Option[String] = None): IO[Seq[Experiment]] = IO {
    val conn = getConnection()
    try {
      val whereClause = status.map(_ => "WHERE status = ?").getOrElse("")
      val sql =
        s"""SELECT id, name, description, status, baseline_config, variant_config,
           |       traffic_split, collection, created_at, started_at, ended_at
           |FROM experiments $whereClause
           |ORDER BY created_at DESC""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        status.foreach(s => stmt.setString(1, s))
        val rs = stmt.executeQuery()
        val experiments = scala.collection.mutable.ArrayBuffer.empty[Experiment]
        while (rs.next()) {
          experiments += rowToExperiment(rs)
        }
        experiments.toSeq
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Get the currently running experiment (if any).
   */
  def getRunning(): IO[Option[Experiment]] = IO {
    val conn = getConnection()
    try {
      val sql =
        """SELECT id, name, description, status, baseline_config, variant_config,
          |       traffic_split, collection, created_at, started_at, ended_at
          |FROM experiments WHERE status = 'running'
          |ORDER BY started_at DESC LIMIT 1""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        val rs = stmt.executeQuery()
        if (rs.next()) Some(rowToExperiment(rs)) else None
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Start an experiment.
   */
  def start(id: String): IO[Option[Experiment]] = IO {
    val conn = getConnection()
    try {
      val now = Instant.now()
      val sql =
        """UPDATE experiments SET status = 'running', started_at = ?
          |WHERE id = ? AND status = 'draft'""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setTimestamp(1, Timestamp.from(now))
        stmt.setString(2, id)
        val updated = stmt.executeUpdate()
        if (updated > 0) {
          // Fetch the updated experiment
          get(id).unsafeRunSync()
        } else {
          None
        }
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Stop an experiment.
   */
  def stop(id: String): IO[Option[Experiment]] = IO {
    val conn = getConnection()
    try {
      val now = Instant.now()
      val sql =
        """UPDATE experiments SET status = 'completed', ended_at = ?
          |WHERE id = ? AND status = 'running'""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setTimestamp(1, Timestamp.from(now))
        stmt.setString(2, id)
        val updated = stmt.executeUpdate()
        if (updated > 0) {
          // Fetch the updated experiment
          get(id).unsafeRunSync()
        } else {
          None
        }
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Archive an experiment.
   */
  def archive(id: String): IO[Option[Experiment]] = IO {
    val conn = getConnection()
    try {
      val sql =
        """UPDATE experiments SET status = 'archived'
          |WHERE id = ? AND status IN ('draft', 'completed')""".stripMargin
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, id)
        val updated = stmt.executeUpdate()
        if (updated > 0) {
          get(id).unsafeRunSync()
        } else {
          None
        }
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Delete an experiment.
   */
  def delete(id: String): IO[Boolean] = IO {
    val conn = getConnection()
    try {
      val sql = "DELETE FROM experiments WHERE id = ? AND status = 'draft'"
      val stmt = conn.prepareStatement(sql)
      try {
        stmt.setString(1, id)
        stmt.executeUpdate() > 0
      } finally {
        stmt.close()
      }
    } finally {
      releaseConnection(conn)
    }
  }

  /**
   * Close the registry.
   */
  def close(): IO[Unit] = IO {
    if (connection != null && !connection.isClosed) {
      connection.close()
    }
  }

  private def rowToExperiment(rs: ResultSet): Experiment = {
    val baselineJson = rs.getString("baseline_config")
    val variantJson = rs.getString("variant_config")

    val baselineConfig = decode[ExperimentConfigSnapshot](baselineJson)
      .getOrElse(ExperimentConfigSnapshot())
    val variantConfig = decode[ExperimentConfigSnapshot](variantJson)
      .getOrElse(ExperimentConfigSnapshot())

    Experiment(
      id = rs.getString("id"),
      name = rs.getString("name"),
      description = Option(rs.getString("description")),
      status = rs.getString("status"),
      baselineConfig = baselineConfig,
      variantConfig = variantConfig,
      trafficSplit = rs.getDouble("traffic_split"),
      collection = Option(rs.getString("collection")),
      createdAt = rs.getTimestamp("created_at").toInstant,
      startedAt = Option(rs.getTimestamp("started_at")).map(_.toInstant),
      endedAt = Option(rs.getTimestamp("ended_at")).map(_.toInstant)
    )
  }
}

object ExperimentRegistry {

  /**
   * Create and initialize an experiment registry.
   */
  def apply(dbConfig: DatabaseConfig): IO[ExperimentRegistry] = {
    val registry = new ExperimentRegistry(dbConfig)
    registry.initialize().as(registry)
  }
}
