package org.llm4s.ragbox.ingestion

import cats.effect.{IO, Resource}
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.db.AdvisoryLock
import org.postgresql.ds.PGSimpleDataSource

import javax.sql.DataSource

/**
 * Cluster-wide coordination of ingestion runs between ragbox instances sharing one database
 * (issue #63): a source is synced by at most one instance at a time.
 *
 * The lock is per source: one advisory lock per source id ([[IngestionLock.key]]). A run takes
 * the locks of every source it will sync before it starts, all or nothing, so
 *
 *  - two instances running all sources at the same moment (a schedule, or startup) never both
 *    run: the second finds the first's locks and skips the whole run;
 *  - runs of different single sources proceed in parallel on different instances;
 *  - a run of every source is refused while any one of them runs elsewhere, so a source is never
 *    run twice in one round.
 */
trait IngestionLock {

  /**
   * Hold the locks of `sourceIds` while the resource is in use.
   *
   * @return `Right(())` when every lock was taken, or `Left(sourceId)` naming a source another
   *         run holds (then nothing is held)
   */
  def tryLock(sourceIds: Seq[String]): Resource[IO, Either[String, Unit]]
}

object IngestionLock {

  /**
   * No cluster-wide coordination: for a single instance (and tests). The per-process guard in
   * [[IngestionService]] still applies.
   */
  val none: IngestionLock = _ => Resource.pure(Right(()))

  /**
   * Locks held as PostgreSQL session-level advisory locks on a connection from `connections`.
   *
   * Each run holds one connection for its whole duration, so `connections` should not be the
   * application's pool: a long run would hold a pooled connection idle the whole time, and runs
   * could starve a small pool. [[forDatabase]] gives each run a dedicated connection instead.
   * Because the lock lives in the connection's session, an instance that crashes or loses its
   * connection loses its locks with it.
   */
  def postgres(connections: DataSource): IngestionLock = new IngestionLock {
    def tryLock(sourceIds: Seq[String]): Resource[IO, Either[String, Unit]] =
      if (sourceIds.isEmpty) Resource.pure(Right(()))
      else AdvisoryLock.tryAll(connections, sourceIds.map(key)).map(
        _.left.map(refused => sourceIds.find(key(_) == refused).getOrElse(refused.toString)).map(_ => ())
      )
  }

  /** [[postgres]] locks, each run on its own connection to `db`, opened outside the pool. */
  def forDatabase(db: DatabaseConfig): IngestionLock = postgres(dedicatedConnections(db))

  /**
   * An unpooled data source over `db`: each connection is opened when a run starts and closed
   * (ending its session, so releasing its locks) when the run ends. TCP keep-alive lets a dead
   * peer's session, and its locks, be noticed and ended.
   */
  def dedicatedConnections(db: DatabaseConfig): DataSource = {
    val ds = new PGSimpleDataSource()
    ds.setURL(db.connectionString)
    ds.setUser(db.effectiveUser)
    ds.setPassword(db.effectivePassword.reveal)
    ds.setApplicationName("ragbox-ingestion-lock")
    ds.setTcpKeepAlive(true)
    ds.setConnectTimeout(ConnectTimeoutSeconds)
    ds
  }

  /** How long opening a lock connection may take. */
  val ConnectTimeoutSeconds: Int = 10

  /** The upper 32 bits of every ingestion lock key: "ragi" in ASCII (migrations use "ragm..."). */
  private val Namespace: Long = 0x72616769L << 32

  /**
   * The advisory lock key of a source: [[Namespace]] plus the source id's `String.hashCode`,
   * which the Java language defines, so every instance and version derives the same key. Two ids
   * that collide only make their runs exclude each other.
   */
  def key(sourceId: String): Long = Namespace | (sourceId.hashCode.toLong & 0xffffffffL)
}
