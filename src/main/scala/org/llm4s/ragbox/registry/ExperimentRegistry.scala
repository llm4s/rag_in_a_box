package org.llm4s.ragbox.registry

import cats.effect.IO
import io.circe.syntax._
import io.circe.parser._
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.model.Codecs._

import java.sql.{Connection, ResultSet, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Raised when an experiment cannot start because another one is already running. Routes map it
 * to 409 Conflict.
 *
 * @param running the experiment that is running, when known
 */
final case class ExperimentAlreadyRunningError(running: Option[Experiment])
    extends RuntimeException(
      running match {
        case Some(other) => s"Another experiment '${other.name}' (${other.id}) is already running. Stop it first."
        case None => "Another experiment is already running. Stop it first."
      }
    )

/**
 * PostgreSQL-backed experiment registry.
 *
 * Stores and manages A/B test experiments for RAG configuration testing.
 * Borrows a pooled connection per operation (see [[ConnectionPool]]), so it is safe for
 * concurrent use across HTTP requests.
 *
 * At most one experiment runs at a time. The database enforces it with a partial unique index
 * over running experiments, so two concurrent starts cannot both succeed.
 *
 * Ids are `exp-<uuid>`. Older installations created 8-hex-digit ids (`exp-1a2b3c4d`); those
 * are still found by every lookup, which compares ids as plain text.
 */
class ExperimentRegistry(dataSource: DataSource) {
  import ExperimentRegistry._

  /**
   * Initialize the registry and ensure tables and indexes exist.
   *
   * The single-running-experiment index is created only if no two experiments are running
   * already (possible in data written before it existed); otherwise the oldest-started running
   * experiments beyond the most recent one are marked completed first.
   */
  def initialize(): IO[Unit] = ConnectionPool.withTransaction(dataSource) { conn =>
    val stmt = conn.createStatement()
    try {
      stmt.execute("SELECT pg_advisory_xact_lock(hashtext('ragbox.experiments.schema'))")
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
      // Pre-existing data may have several running experiments; keep the latest one running
      stmt.execute(
        """UPDATE experiments SET status = 'completed', ended_at = NOW()
          |WHERE status = 'running'
          |  AND id <> (SELECT id FROM experiments WHERE status = 'running'
          |             ORDER BY started_at DESC NULLS LAST, id LIMIT 1)""".stripMargin
      )
      stmt.execute(
        s"""CREATE UNIQUE INDEX IF NOT EXISTS $SingleRunningIndex
           |    ON experiments ((status)) WHERE status = 'running'""".stripMargin
      )
    } finally {
      stmt.close()
    }
  }

  /**
   * Create a new experiment.
   */
  def create(request: CreateExperimentRequest): IO[Experiment] = ConnectionPool.withConnection(dataSource) { conn =>
    val id = s"exp-${UUID.randomUUID()}"
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
  }

  /**
   * Get an experiment by ID.
   */
  def get(id: String): IO[Option[Experiment]] = ConnectionPool.withConnection(dataSource) { conn =>
    val stmt = conn.prepareStatement(s"SELECT $Columns FROM experiments WHERE id = ?")
    try {
      stmt.setString(1, id)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToExperiment(rs)) else None
    } finally {
      stmt.close()
    }
  }

  /**
   * List experiments, newest first, optionally filtered by status.
   *
   * @param limit  maximum number of experiments to return
   * @param offset number of experiments to skip
   */
  def list(
    status: Option[String] = None,
    limit: Int = DefaultListLimit,
    offset: Int = 0
  ): IO[Seq[Experiment]] = ConnectionPool.withConnection(dataSource) { conn =>
    val whereClause = status.map(_ => "WHERE status = ?").getOrElse("")
    val sql =
      s"""SELECT $Columns
         |FROM experiments $whereClause
         |ORDER BY created_at DESC, id
         |LIMIT ? OFFSET ?""".stripMargin
    val stmt = conn.prepareStatement(sql)
    try {
      var idx = 1
      status.foreach { s =>
        stmt.setString(idx, s)
        idx += 1
      }
      stmt.setInt(idx, math.max(0, limit))
      stmt.setInt(idx + 1, math.max(0, offset))
      val rs = stmt.executeQuery()
      val experiments = Vector.newBuilder[Experiment]
      while (rs.next()) {
        experiments += rowToExperiment(rs)
      }
      experiments.result()
    } finally {
      stmt.close()
    }
  }

  /**
   * Count experiments, optionally filtered by status (the `total` for [[list]] pages).
   */
  def count(status: Option[String] = None): IO[Int] = ConnectionPool.withConnection(dataSource) { conn =>
    val whereClause = status.map(_ => "WHERE status = ?").getOrElse("")
    val stmt = conn.prepareStatement(s"SELECT COUNT(*) FROM experiments $whereClause")
    try {
      status.foreach(stmt.setString(1, _))
      val rs = stmt.executeQuery()
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Get the currently running experiment (if any).
   */
  def getRunning(): IO[Option[Experiment]] = ConnectionPool.withConnection(dataSource)(selectRunning)

  /**
   * Start a draft experiment. Returns the started experiment, or None if there is no draft
   * experiment with this id.
   *
   * Fails with [[ExperimentAlreadyRunningError]] if another experiment is running: the update
   * violates the single-running-experiment index, which also settles two concurrent starts.
   */
  def start(id: String): IO[Option[Experiment]] =
    transition(
      id,
      """UPDATE experiments SET status = 'running', started_at = ?
        |WHERE id = ? AND status = 'draft'""".stripMargin,
      withTimestamp = true
    ).handleErrorWith {
      case e if ConnectionPool.isUniqueViolation(e) =>
        getRunning().attempt.flatMap(running =>
          IO.raiseError(ExperimentAlreadyRunningError(running.toOption.flatten))
        )
      case e => IO.raiseError(e)
    }

  /**
   * Stop a running experiment.
   */
  def stop(id: String): IO[Option[Experiment]] =
    transition(
      id,
      """UPDATE experiments SET status = 'completed', ended_at = ?
        |WHERE id = ? AND status = 'running'""".stripMargin,
      withTimestamp = true
    )

  /**
   * Archive an experiment.
   */
  def archive(id: String): IO[Option[Experiment]] =
    transition(
      id,
      """UPDATE experiments SET status = 'archived'
        |WHERE id = ? AND status IN ('draft', 'completed')""".stripMargin,
      withTimestamp = false
    )

  /**
   * Delete an experiment.
   */
  def delete(id: String): IO[Boolean] = ConnectionPool.withConnection(dataSource) { conn =>
    val sql = "DELETE FROM experiments WHERE id = ? AND status = 'draft'"
    val stmt = conn.prepareStatement(sql)
    try {
      stmt.setString(1, id)
      stmt.executeUpdate() > 0
    } finally {
      stmt.close()
    }
  }

  /**
   * Close the registry. The connection pool is owned externally, so there
   * is nothing to release here.
   */
  def close(): IO[Unit] = IO.unit

  /**
   * Run a status-changing `UPDATE` (parameters: optional timestamp, then id) and return the
   * updated row through `RETURNING` - one statement on one connection, so it never needs a
   * second pooled connection while holding the first.
   */
  private def transition(id: String, update: String, withTimestamp: Boolean): IO[Option[Experiment]] =
    ConnectionPool.withConnection(dataSource) { conn =>
      val stmt = conn.prepareStatement(s"$update RETURNING $Columns")
      try {
        var idx = 1
        if (withTimestamp) {
          stmt.setTimestamp(idx, Timestamp.from(Instant.now()))
          idx += 1
        }
        stmt.setString(idx, id)
        val rs = stmt.executeQuery()
        if (rs.next()) Some(rowToExperiment(rs)) else None
      } finally {
        stmt.close()
      }
    }

  private def selectRunning(conn: Connection): Option[Experiment] = {
    val stmt = conn.prepareStatement(
      s"SELECT $Columns FROM experiments WHERE status = 'running' ORDER BY started_at DESC LIMIT 1"
    )
    try {
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rowToExperiment(rs)) else None
    } finally {
      stmt.close()
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

  /** Default page size for experiment listings. */
  val DefaultListLimit: Int = 100
  val MaxListLimit: Int = 1000

  private val SingleRunningIndex = "uq_experiments_single_running"

  private val Columns =
    """id, name, description, status, baseline_config, variant_config,
      |traffic_split, collection, created_at, started_at, ended_at""".stripMargin

  /**
   * Create and initialize an experiment registry backed by a connection pool.
   */
  def apply(dataSource: DataSource): IO[ExperimentRegistry] = {
    val registry = new ExperimentRegistry(dataSource)
    registry.initialize().as(registry)
  }
}
