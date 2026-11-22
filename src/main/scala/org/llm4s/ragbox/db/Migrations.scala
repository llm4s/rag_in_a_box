package org.llm4s.ragbox.db

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import javax.sql.DataSource
import scala.concurrent.duration._
import scala.util.Using
import scala.jdk.CollectionConverters._

/**
 * ragbox's database schema, managed by Flyway.
 *
 * The migrations are the SQL scripts in `src/main/resources/db/migration`
 * (`V<n>__<description>.sql`); Flyway records the ones it has applied in `flyway_schema_history`.
 * Components never create or alter tables themselves: the schema must be migrated before they
 * are used (at startup in `Main`, unless `database.migrate-on-start` is off, or with
 * `--migrate-only`).
 *
 * '''Databases created before Flyway.''' Those have ragbox's tables, possibly at any of the
 * historic states the old startup DDL produced, but no history table. Flyway baselines such a
 * database at version 0 ([[BaselineVersion]]), so V1 still runs on it; V1 is written to be
 * idempotent (`CREATE ... IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, guarded data fixes) and
 * brings any historic state, or an empty database, to the same schema.
 *
 * '''Several replicas.''' [[migrate]] holds a session-level PostgreSQL advisory lock
 * ([[LockKey]]) for the whole Flyway run, so replicas starting together migrate one at a time:
 * the first applies the migrations, the others wait, then find nothing pending.
 *
 * Flyway's own advisory lock is not enough (issue #52). It covers applying migrations, but not
 * Flyway's first step, "no history table yet: create an empty one, or baseline the schema if it is
 * not empty". Two instances starting on an empty database could both find no history table; one
 * created it, and the other then found the schema not empty (it now held that table), chose to
 * baseline, and failed with "Unable to baseline ... already exists, and is empty".
 *
 * The lock is taken on the connection Flyway then uses as its main connection (see
 * [[LockedFirst]]), so migrating needs no more connections than Flyway alone (two).
 *
 * Tables owned by llm4s (the vector/keyword table, `llm4s_collections`, `llm4s_principals`) are
 * not managed here; llm4s creates them when the search index or vector store starts, after the
 * migrations have run.
 */
object Migrations {

  /** Where the migration scripts live on the classpath. */
  val Location: String = "classpath:db/migration"

  /**
   * The version an existing pre-Flyway database is baselined at: 0, below V1, so that V1 brings
   * whatever historic schema it has up to date.
   */
  val BaselineVersion: String = "0"

  /**
   * The key of the advisory lock [[migrate]] holds: a fixed value ("ragmigra" in ASCII), distinct
   * from the key Flyway derives for its own lock.
   */
  val LockKey: Long = 0x7261676d69677261L

  /** How often a migration waiting for another instance's tries the lock again. */
  val LockRetryInterval: FiniteDuration = 250.millis

  /** What a migration run did. */
  final case class Result(applied: Seq[String], schemaVersion: Option[String])

  /** The Flyway instance for `dataSource`. */
  def flyway(dataSource: DataSource): Flyway =
    Flyway
      .configure()
      .dataSource(dataSource)
      .locations(Location)
      .baselineOnMigrate(true)
      .baselineVersion(BaselineVersion)
      .baselineDescription("Pre-Flyway schema (adopted; V1 upgrades it in place)")
      .load()

  /**
   * Apply all pending migrations to `dataSource`. A no-op when the schema is up to date. Safe to
   * call from several instances at once: they run one at a time (see the class comment).
   */
  def migrate(dataSource: DataSource): IO[Result] =
    locked(dataSource).use(conn => IO.blocking(runFlyway(new LockedFirst(dataSource, conn))))

  /**
   * A connection from `dataSource` holding [[LockKey]] while the resource is in use, waiting for
   * the lock while another instance migrates. Waiting polls `pg_try_advisory_lock` rather than
   * blocking in `pg_advisory_lock`, so a waiting migration can be cancelled.
   *
   * The lock is session-level, so it is released explicitly: a pooled connection's session
   * outlives `close()`. If the unlock fails, the connection is evicted from a Hikari pool so its
   * session, and the lock, end with it.
   */
  private def locked(dataSource: DataSource): Resource[IO, Connection] =
    Resource.make(IO.blocking(dataSource.getConnection()))(conn => IO.blocking(conn.close()))
      .flatMap { conn =>
        def acquire: IO[Unit] =
          IO.blocking(advisory(conn, "pg_try_advisory_lock")).flatMap { locked =>
            if (locked) IO.unit else IO.sleep(LockRetryInterval) *> acquire
          }
        Resource.makeFull[IO, Unit](poll => poll(acquire)) { _ =>
          IO.blocking(advisory(conn, "pg_advisory_unlock")).attempt.flatMap {
            case Right(true) => IO.unit
            case _ => IO.blocking(dataSource match {
              case pool: HikariDataSource => pool.evictConnection(conn)
              case _ => ()
            })
          }
        }.map(_ => conn)
      }

  /** Call the advisory lock function `fn` with [[LockKey]] on `conn`; its boolean result. */
  private def advisory(conn: Connection, fn: String): Boolean =
    Using.resource(conn.prepareStatement(s"SELECT $fn(?)")) { stmt =>
      stmt.setLong(1, LockKey)
      Using.resource(stmt.executeQuery()) { rs =>
        rs.next()
        rs.getBoolean(1)
      }
    }

  /**
   * `underlying`, except that the first connection asked for is `first` (the one holding the
   * lock), which Flyway's `close()` leaves open: [[locked]] releases it. Flyway keeps the first
   * connection it opens as its main connection and opens one more to migrate with, so Flyway runs
   * on the lock's connection plus one other, as many as it would use on its own.
   */
  private[db] final class LockedFirst(underlying: DataSource, first: Connection) extends DataSource {
    private val handedOut = new AtomicBoolean(false)

    override def getConnection(): Connection =
      if (handedOut.compareAndSet(false, true)) unclosable(first) else underlying.getConnection()
    override def getConnection(username: String, password: String): Connection =
      underlying.getConnection(username, password)
    override def getLogWriter: PrintWriter = underlying.getLogWriter
    override def setLogWriter(out: PrintWriter): Unit = underlying.setLogWriter(out)
    override def setLoginTimeout(seconds: Int): Unit = underlying.setLoginTimeout(seconds)
    override def getLoginTimeout: Int = underlying.getLoginTimeout
    override def getParentLogger: Logger = underlying.getParentLogger
    override def unwrap[T](iface: Class[T]): T = underlying.unwrap(iface)
    override def isWrapperFor(iface: Class[?]): Boolean = underlying.isWrapperFor(iface)
  }

  /** `conn`, with `close()` a no-op. */
  private[db] def unclosable(conn: Connection): Connection = {
    val handler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        if (method.getName == "close" && method.getParameterCount == 0) null
        else
          try method.invoke(conn, (if (args == null) Array.empty[AnyRef] else args)*)
          catch { case e: InvocationTargetException => throw e.getCause }
    }
    Proxy.newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]), handler).asInstanceOf[Connection]
  }

  private def runFlyway(dataSource: DataSource): Result = {
    val result = flyway(dataSource).migrate()
    val applied = Option(result.migrations).map(_.asScala.toSeq).getOrElse(Seq.empty)
      .map(m => s"V${m.version} ${m.description}")
    Result(
      applied = applied,
      schemaVersion = Option(result.targetSchemaVersion).orElse(Option(result.initialSchemaVersion))
    )
  }
}
