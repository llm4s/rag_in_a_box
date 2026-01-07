package org.llm4s.ragbox.db

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.llm4s.ragbox.config.DatabaseConfig

import java.sql.Connection

/**
 * HikariCP-based database connection pool.
 *
 * Provides efficient connection pooling for all database operations.
 * Connections are borrowed from the pool and automatically returned.
 */
object ConnectionPool {

  /**
   * Configuration for the connection pool.
   */
  final case class PoolConfig(
    maximumPoolSize: Int = 10,
    minimumIdle: Int = 2,
    connectionTimeoutMs: Long = 30000,
    idleTimeoutMs: Long = 600000,
    maxLifetimeMs: Long = 1800000,
    leakDetectionThresholdMs: Long = 60000
  )

  object PoolConfig {
    val default: PoolConfig = PoolConfig()
  }

  /**
   * Create a connection pool as a cats-effect Resource.
   * The pool is automatically closed when the resource is released.
   */
  def create(
    dbConfig: DatabaseConfig,
    poolConfig: PoolConfig = PoolConfig.default
  ): Resource[IO, HikariDataSource] = {
    Resource.make(IO {
      val config = new HikariConfig()

      // Connection settings
      config.setJdbcUrl(dbConfig.connectionString)
      config.setUsername(dbConfig.effectiveUser)
      config.setPassword(dbConfig.effectivePassword)
      config.setDriverClassName("org.postgresql.Driver")

      // Pool settings
      config.setMaximumPoolSize(poolConfig.maximumPoolSize)
      config.setMinimumIdle(poolConfig.minimumIdle)
      config.setConnectionTimeout(poolConfig.connectionTimeoutMs)
      config.setIdleTimeout(poolConfig.idleTimeoutMs)
      config.setMaxLifetime(poolConfig.maxLifetimeMs)
      config.setLeakDetectionThreshold(poolConfig.leakDetectionThresholdMs)

      // Connection test query for PostgreSQL
      config.setConnectionTestQuery("SELECT 1")

      // Pool name for monitoring
      config.setPoolName("ragbox-pool")

      new HikariDataSource(config)
    })(ds => IO(ds.close()))
  }

  /**
   * Borrow a connection from the pool for a single operation.
   * The connection is automatically returned to the pool after use.
   */
  def withConnection[A](pool: HikariDataSource)(f: Connection => A): IO[A] = {
    Resource.make(IO(pool.getConnection))(conn => IO(conn.close()))
      .use(conn => IO(f(conn)))
  }

  /**
   * Borrow a connection with transaction support.
   * Commits on success, rolls back on failure.
   */
  def withTransaction[A](pool: HikariDataSource)(f: Connection => A): IO[A] = {
    Resource.make(IO {
      val conn = pool.getConnection
      conn.setAutoCommit(false)
      conn
    })(conn => IO {
      conn.setAutoCommit(true)
      conn.close()
    }).use { conn =>
      IO(f(conn)).attempt.flatMap {
        case Right(result) =>
          IO(conn.commit()).as(result)
        case Left(error) =>
          IO(conn.rollback()) *> IO.raiseError(error)
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
