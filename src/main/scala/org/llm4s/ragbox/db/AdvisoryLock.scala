package org.llm4s.ragbox.db

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.HikariDataSource

import java.sql.Connection
import javax.sql.DataSource
import scala.concurrent.duration.FiniteDuration
import scala.util.Using

/**
 * Session-level PostgreSQL advisory locks (`pg_try_advisory_lock` / `pg_advisory_unlock`), held
 * on one connection for as long as a resource is in use.
 *
 * Every lock here is session-level: it lasts until it is unlocked or the session ends, so a
 * process that dies (or loses its connection) loses its locks with it. A pooled connection's
 * session outlives `close()`, so the locks are released explicitly; if the unlock fails, the
 * connection is evicted from a Hikari pool so its session, and its locks, end with it.
 *
 * Keys are single `bigint`s. Each user picks keys in its own range (see
 * [[Migrations.LockKey]] and `IngestionLock.key`), so locks of different users never collide.
 */
object AdvisoryLock {

  /**
   * A connection from `dataSource` holding `key` while the resource is in use, waiting for the
   * lock while another session holds it. Waiting polls `pg_try_advisory_lock` every
   * `retryInterval` rather than blocking in `pg_advisory_lock`, so a waiter can be cancelled.
   */
  def waitFor(dataSource: DataSource, key: Long, retryInterval: FiniteDuration): Resource[IO, Connection] =
    connection(dataSource).flatMap { conn =>
      def acquire: IO[Unit] =
        IO.blocking(call(conn, "pg_try_advisory_lock", key)).flatMap { locked =>
          if (locked) IO.unit else IO.sleep(retryInterval) *> acquire
        }
      Resource.makeFull[IO, Unit](poll => poll(acquire))(_ => release(dataSource, conn, Seq(key))).map(_ => conn)
    }

  /**
   * Try, without waiting, to take every one of `keys` on one connection from `dataSource`.
   *
   * @return while in use: `Right(connection)` holding all of them, or `Left(key)` naming the first
   *         key another session holds (any taken before it are released at once, so a failed
   *         attempt holds nothing). All are released when the resource is.
   */
  def tryAll(dataSource: DataSource, keys: Seq[Long]): Resource[IO, Either[Long, Connection]] =
    connection(dataSource).flatMap { conn =>
      // Take the keys in order, stopping at the first one held elsewhere
      val attempt = IO.blocking {
        keys.distinct.foldLeft((Vector.empty[Long], Option.empty[Long])) {
          case ((taken, None), key) =>
            if (call(conn, "pg_try_advisory_lock", key)) (taken :+ key, None) else (taken, Some(key))
          case (done, _) => done
        }
      }
      val acquire: IO[Either[Long, Seq[Long]]] = attempt.flatMap {
        case (taken, Some(refused)) => release(dataSource, conn, taken).as(Left(refused))
        case (taken, None) => IO.pure(Right(taken))
      }
      Resource.make(acquire)(_.fold(_ => IO.unit, release(dataSource, conn, _))).map(_.map(_ => conn))
    }

  private def connection(dataSource: DataSource): Resource[IO, Connection] =
    Resource.make(IO.blocking(dataSource.getConnection()))(conn => IO.blocking(conn.close()))

  /**
   * Unlock `keys` on `conn`; if that fails (or a key was not held), evict the connection from a
   * Hikari pool so its session ends.
   */
  private def release(dataSource: DataSource, conn: Connection, keys: Seq[Long]): IO[Unit] =
    if (keys.isEmpty) IO.unit
    else
      IO.blocking(keys.forall(call(conn, "pg_advisory_unlock", _))).attempt.flatMap {
        case Right(true) => IO.unit
        case _ => IO.blocking(dataSource match {
          case pool: HikariDataSource => pool.evictConnection(conn)
          case _ => ()
        })
      }

  /** Call the advisory lock function `fn` with `key` on `conn`; its boolean result. */
  private def call(conn: Connection, fn: String, key: Long): Boolean =
    Using.resource(conn.prepareStatement(s"SELECT $fn(?)")) { stmt =>
      stmt.setLong(1, key)
      Using.resource(stmt.executeQuery()) { rs =>
        rs.next()
        rs.getBoolean(1)
      }
    }
}
