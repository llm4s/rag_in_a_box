package org.llm4s.ragbox.db

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.llm4s.ragbox.config.DatabaseConfig

import java.sql.{Connection, SQLException}
import javax.sql.DataSource

/**
 * HikariCP-based database connection pool, and the helpers every component uses to run JDBC.
 *
 * The application creates one pool at startup ([[create]]) and passes it, as a `DataSource`, to
 * every database component. Nothing holds a connection between operations: each operation
 * borrows one, uses it and returns it, so a dropped socket or a Postgres restart costs at most
 * the operations in flight, and Hikari replaces broken connections on the next borrow.
 *
 * All JDBC is blocking, so [[withConnection]] and [[withTransaction]] run the borrow, the work
 * and the release on the cats-effect blocking pool (`IO.blocking`), never on the compute pool.
 * The work function must not run other `IO`s (no `unsafeRunSync`): it holds a pooled connection,
 * and waiting for a second one from inside it can deadlock a small pool.
 */
object ConnectionPool {

  /**
   * Configuration for the connection pool.
   */
  final case class PoolConfig(
    maximumPoolSize: Int = DatabaseConfig.DefaultPoolSize,
    minimumIdle: Int = 2,
    connectionTimeoutMs: Long = 30000,
    idleTimeoutMs: Long = 600000,
    maxLifetimeMs: Long = 1800000,
    leakDetectionThresholdMs: Long = 60000
  )

  object PoolConfig {
    val default: PoolConfig = PoolConfig()

    /** Pool settings for `db`: its configured pool size, other settings at their defaults. */
    def forDatabase(db: DatabaseConfig): PoolConfig =
      PoolConfig(maximumPoolSize = db.poolSize, minimumIdle = math.min(2, db.poolSize))
  }

  /** SQLSTATE PostgreSQL reports for a unique constraint violation. */
  val UniqueViolation: String = "23505"

  /** Whether `e` is (or wraps) a unique constraint violation. */
  def isUniqueViolation(e: Throwable): Boolean = e match {
    case sql: SQLException if sql.getSQLState == UniqueViolation => true
    case _ if e.getCause != null && (e.getCause ne e) => isUniqueViolation(e.getCause)
    case _ => false
  }

  /**
   * Create a connection pool as a cats-effect Resource.
   * The pool is automatically closed when the resource is released.
   */
  def create(
    dbConfig: DatabaseConfig,
    poolConfig: Option[PoolConfig] = None
  ): Resource[IO, HikariDataSource] = {
    val settings = poolConfig.getOrElse(PoolConfig.forDatabase(dbConfig))
    Resource.make(IO.blocking {
      val config = new HikariConfig()

      // Connection settings
      config.setJdbcUrl(dbConfig.connectionString)
      config.setUsername(dbConfig.effectiveUser)
      config.setPassword(dbConfig.effectivePassword.reveal)
      config.setDriverClassName("org.postgresql.Driver")

      // Pool settings
      config.setMaximumPoolSize(settings.maximumPoolSize)
      config.setMinimumIdle(settings.minimumIdle)
      config.setConnectionTimeout(settings.connectionTimeoutMs)
      config.setIdleTimeout(settings.idleTimeoutMs)
      config.setMaxLifetime(settings.maxLifetimeMs)
      config.setLeakDetectionThreshold(settings.leakDetectionThresholdMs)

      // Pool name for monitoring
      config.setPoolName("ragbox-pool")

      new HikariDataSource(config)
    })(ds => IO.blocking(ds.close()))
  }

  /**
   * Borrow a connection from the pool for a single operation (auto-commit).
   * The connection is returned to the pool after use, also when `f` throws.
   */
  def withConnection[A](pool: DataSource)(f: Connection => A): IO[A] =
    Resource.make(IO.blocking(pool.getConnection))(conn => IO.blocking(conn.close()))
      .use(conn => IO.blocking(f(conn)))

  /**
   * Borrow a connection and run `f` in a transaction: commit on success, roll back on failure.
   *
   * The connection is always returned to the pool (bracket semantics, so also on cancellation).
   * If `f` or the commit fails - or the fiber is cancelled - the transaction is rolled back
   * before release; a failing rollback is swallowed so the original error is the one re-raised.
   * Auto-commit is restored before release (after the rollback, so restoring it never commits
   * partial work); if that fails Hikari evicts the connection rather than reusing it.
   */
  def withTransaction[A](pool: DataSource)(f: Connection => A): IO[A] = {
    def release(conn: Connection): IO[Unit] =
      IO.blocking(if (!conn.getAutoCommit) conn.rollback()).attempt *>
        IO.blocking(conn.setAutoCommit(true)).attempt *>
        IO.blocking(conn.close()).attempt.void

    Resource.make(IO.blocking(pool.getConnection))(release).use { conn =>
      IO.blocking {
        conn.setAutoCommit(false)
        val result = f(conn)
        conn.commit()
        result
      }
    }
  }

  /**
   * Check pool health - returns true if pool can provide connections.
   */
  def isHealthy(pool: HikariDataSource): IO[Boolean] = {
    IO {
      !pool.isClosed && pool.isRunning
    }.handleError(_ => false)
  }

  /**
   * Get pool statistics for monitoring.
   */
  def getStats(pool: HikariDataSource): IO[PoolStats] = IO {
    val metrics = pool.getHikariPoolMXBean
    PoolStats(
      activeConnections = metrics.getActiveConnections,
      idleConnections = metrics.getIdleConnections,
      totalConnections = metrics.getTotalConnections,
      threadsAwaitingConnection = metrics.getThreadsAwaitingConnection
    )
  }

  /**
   * Pool statistics for monitoring.
   */
  final case class PoolStats(
    activeConnections: Int,
    idleConnections: Int,
    totalConnections: Int,
    threadsAwaitingConnection: Int
  )
}
