package org.llm4s.ragbox.service

import cats.effect.{IO, Ref, Resource, SyncIO}
import cats.effect.std.Mutex
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

/**
 * Pipelines built on demand and kept by the settings they were built with.
 *
 * An llm4s `RAG` bakes its settings (fusion, system prompt, reranker, query transform) in when it
 * is built, so a different setting needs a different instance. The pool keeps one per key - the
 * key is the settings themselves, a case class, so its equality and hash are the "config hash" -
 * builds a missing one on first use and reuses it after.
 *
 *  - '''Leases''': every use holds a lease ([[lease]] / [[use]]) for as long as it runs, so an
 *    instance is never closed under a request that is still using it.
 *  - '''Retiring''': [[retire]] (when the runtime settings move on) and eviction (when more than
 *    `maxEntries` are kept; the least recently used goes) take an instance out of the pool at once,
 *    so no new request gets it, but close it only when its last lease is released.
 *  - '''Builds''' are serialised, so two requests never build the same instance twice. A failed
 *    build is not kept: the key is retried on use, at most once per `retryCooldown` (calls within
 *    the cooldown fail fast with the last error), so a provider that is down at startup does not
 *    leave the service broken until restart.
 *  - '''Close''': [[close]] closes every idle instance, closes busy ones when they are released,
 *    and fails every later lease with `closedMessage`.
 */
final class PipelinePool[K, A] private (
  state: Ref[IO, PipelinePool.State[K, A]],
  buildLock: Mutex[IO],
  build: K => IO[A],
  dispose: A => IO[Unit],
  maxEntries: Int,
  retryCooldown: FiniteDuration,
  closedMessage: String
) {
  import PipelinePool._

  /** Use the instance for `key`, building it if needed; it stays open until `f` completes. */
  def use[B](key: K)(f: A => IO[B]): IO[B] = lease(key).use(f)

  /** A lease on the instance for `key`, building it if needed. */
  def lease(key: K): Resource[IO, A] =
    Resource.make(acquire(key))(leased => release(leased._1)).map(_._2)

  /** Whether the instance for `key` is built (without building it). */
  def isBuilt(key: K): IO[Boolean] = state.get.map(_.live.contains(key))

  /** Keys with a built instance. */
  def keys: IO[Set[K]] = state.get.map(_.live.keySet)

  /**
   * Take the instance for `key` out of the pool: closed now if idle, otherwise when its last
   * lease is released. The next use of `key` builds a new one.
   */
  def retire(key: K): IO[Unit] =
    state.modify { s =>
      s.live.get(key) match {
        case None => (s, None)
        case Some(e) if e.inFlight == 0 => (s.copy(live = s.live - key), Some(e.value))
        case Some(e) => (s.copy(live = s.live - key, retiring = s.retiring.updated(e.id, e)), None)
      }
    }.flatMap(_.traverse_(disposeQuietly))

  /** Close every instance (busy ones when released); later leases fail. */
  def close: IO[Unit] =
    state.modify { s =>
      val (busy, idle) = s.live.values.partition(_.inFlight > 0)
      (s.copy(live = Map.empty, retiring = s.retiring ++ busy.map(e => e.id -> e), closed = true), idle.map(_.value).toList)
    }.flatMap(_.traverse_(disposeQuietly))

  private def acquire(key: K): IO[(Long, A)] =
    takeLive(key).flatMap {
      case Some(leased) => IO.pure(leased)
      case None => buildLock.lock.surround(takeLive(key).flatMap(_.fold(buildAndTake(key))(IO.pure)))
    }

  /** Lease the live instance for `key`, if there is one; fail if the pool is closed. */
  private def takeLive(key: K): IO[Option[(Long, A)]] =
    state.modify { s =>
      if (s.closed) (s, Left(new IllegalStateException(closedMessage)))
      else
        s.live.get(key) match {
          case Some(e) =>
            val used = e.copy(inFlight = e.inFlight + 1, lastUsed = s.tick)
            (s.copy(live = s.live.updated(key, used), tick = s.tick + 1), Right(Some(e.id -> e.value)))
          case None => (s, Right(None))
        }
    }.flatMap(IO.fromEither)

  /** Build the instance for `key` (with the build lock held) and lease it. */
  private def buildAndTake(key: K): IO[(Long, A)] =
    for {
      now <- IO.realTime.map(_.toMillis)
      failure <- state.get.map(_.failures.get(key))
      leased <- failure match {
        case Some((at, message)) if now - at < retryCooldown.toMillis =>
          IO.raiseError(new RuntimeException(message))
        case _ =>
          // Uncancelable from build to insert, so a built instance is never lost unclosed
          IO.uncancelable { _ =>
            build(key).attempt.flatMap {
              case Left(e) =>
                val message = Option(e.getMessage).getOrElse(e.toString)
                state.update(s => s.copy(failures = s.failures.updated(key, now -> message))) *>
                  IO.raiseError(new RuntimeException(message))
              case Right(value) => insert(key, value)
            }
          }
      }
    } yield leased

  private def insert(key: K, value: A): IO[(Long, A)] =
    state.modify { s =>
      if (s.closed) (s, Left(value))
      else {
        val entry = Entry(s.nextId, value, inFlight = 1, lastUsed = s.tick)
        val added = s.copy(
          live = s.live.updated(key, entry),
          failures = s.failures - key,
          nextId = s.nextId + 1,
          tick = s.tick + 1
        )
        val (evicted, toClose) = evict(added, keep = key)
        (evicted, Right((entry.id -> value, toClose)))
      }
    }.flatMap {
      case Left(orphan) => disposeQuietly(orphan) *> IO.raiseError(new IllegalStateException(closedMessage))
      case Right((leased, toClose)) => toClose.traverse_(disposeQuietly).as(leased)
    }

  /** Retire least recently used entries (never `keep`) until at most `maxEntries` are live. */
  private def evict(s: State[K, A], keep: K): (State[K, A], List[A]) =
    if (s.live.size <= maxEntries) (s, Nil)
    else {
      val (oldestKey, oldest) = (s.live - keep).minBy(_._2.lastUsed)
      val retired =
        if (oldest.inFlight == 0) (s.copy(live = s.live - oldestKey), List(oldest.value))
        else (s.copy(live = s.live - oldestKey, retiring = s.retiring.updated(oldest.id, oldest)), Nil)
      val (next, more) = evict(retired._1, keep)
      (next, retired._2 ++ more)
    }

  private def release(id: Long): IO[Unit] =
    state.modify { s =>
      s.live.find(_._2.id == id) match {
        case Some((key, e)) => (s.copy(live = s.live.updated(key, e.copy(inFlight = e.inFlight - 1))), None)
        case None =>
          s.retiring.get(id) match {
            case Some(e) if e.inFlight <= 1 => (s.copy(retiring = s.retiring - id), Some(e.value))
            case Some(e) => (s.copy(retiring = s.retiring.updated(id, e.copy(inFlight = e.inFlight - 1))), None)
            case None => (s, None)
          }
      }
    }.flatMap(_.traverse_(disposeQuietly))

  private def disposeQuietly(value: A): IO[Unit] =
    dispose(value).handleErrorWith(e => IO(logger.warn(s"Closing a pipeline failed: ${e.getMessage}")))
}

object PipelinePool {

  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[PipelinePool[?, ?]])

  private final case class Entry[A](id: Long, value: A, inFlight: Int, lastUsed: Long)

  private final case class State[K, A](
    live: Map[K, Entry[A]] = Map.empty[K, Entry[A]],
    retiring: Map[Long, Entry[A]] = Map.empty[Long, Entry[A]],
    failures: Map[K, (Long, String)] = Map.empty[K, (Long, String)],
    nextId: Long = 0L,
    tick: Long = 0L,
    closed: Boolean = false
  )

  /**
   * A pool that builds with `build` and closes with `dispose`.
   *
   * @param maxEntries    how many instances are kept; the least recently used one beyond it is retired
   * @param retryCooldown minimum time between build attempts for a key after a failure
   * @param closedMessage the error leases fail with after [[PipelinePool.close]]
   */
  def apply[K, A](
    build: K => IO[A],
    dispose: A => IO[Unit],
    maxEntries: Int,
    retryCooldown: FiniteDuration,
    closedMessage: String
  ): IO[PipelinePool[K, A]] =
    (Ref.of[IO, State[K, A]](State[K, A]()), Mutex[IO]).mapN { (state, lock) =>
      new PipelinePool(state, lock, build, dispose, math.max(1, maxEntries), retryCooldown, closedMessage)
    }

  /**
   * [[apply]] for a caller that is not itself effectful (a class whose constructor creates its
   * pool). Creating the pool only allocates a `Ref` and a `Mutex`, which `SyncIO` does synchronously.
   */
  def unsafeCreate[K, A](
    build: K => IO[A],
    dispose: A => IO[Unit],
    maxEntries: Int,
    retryCooldown: FiniteDuration,
    closedMessage: String
  ): PipelinePool[K, A] =
    (Ref.in[SyncIO, IO, State[K, A]](State[K, A]()), Mutex.in[SyncIO, IO]).mapN { (state, lock) =>
      new PipelinePool(state, lock, build, dispose, math.max(1, maxEntries), retryCooldown, closedMessage)
    }.unsafeRunSync()
}
