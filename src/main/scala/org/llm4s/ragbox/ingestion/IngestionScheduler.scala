package org.llm4s.ragbox.ingestion

import cats.effect.IO
import cats.effect.std.Supervisor
import org.slf4j.LoggerFactory

import java.time.{Duration => JDuration, Instant}
import scala.concurrent.duration._

/**
 * Runs every configured ingestion source on an [[IngestionSchedule]] (`INGEST_SCHEDULE` /
 * `ingestion.schedule`): a cron expression, evaluated against the wall clock, or a fixed
 * interval between runs.
 *
 * The next run is always computed from the clock when the previous one ends, so a run that
 * overruns its slot skips the missed slots instead of running back to back. A run that fails is
 * logged and the schedule carries on; a run that would overlap another run - a manual one, or one
 * on another instance sharing the database ([[IngestionLock]]) - is skipped.
 */
class IngestionScheduler(
  runAll: IO[Seq[IngestionResult]],
  recordNextRun: Option[Instant] => IO[Unit],
  schedule: IngestionSchedule
) {
  private val logger = LoggerFactory.getLogger(getClass)

  /** Start the schedule as a fiber of `supervisor` (it runs until the supervisor is released). */
  def start(supervisor: Supervisor[IO]): IO[Unit] =
    IO(logger.info(s"Ingestion schedule: ${schedule.describe}")) *> supervisor.supervise(run).void

  /** The schedule loop: wait for the next run, run every source, repeat. Never completes. */
  def run: IO[Unit] = {
    def loop(earliest: Instant): IO[Unit] =
      IO.realTimeInstant.flatMap { now =>
        // Never schedule before the previous run's slot, even if the clock stepped back
        schedule.nextRun(if (now.isBefore(earliest)) earliest else now) match {
          case None =>
            recordNextRun(None) *>
              IO(logger.info(s"Ingestion schedule ${schedule.describe} will not run again"))
          case Some(next) =>
            val wait = JDuration.between(now, next).toMillis.max(0L).millis
            recordNextRun(Some(next)) *>
              IO(logger.info(s"Next scheduled ingestion at $next")) *>
              IO.sleep(wait) *>
              runOnce *>
              loop(next)
        }
      }
    IO.realTimeInstant.flatMap(loop)
  }

  private def runOnce: IO[Unit] = IngestionScheduler.runLogged("scheduled", runAll)
}

object IngestionScheduler {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Run `runAll` for `trigger` (`scheduled`, `startup`), logging the outcome. Never fails: a run
   * refused because another is in progress, here or on another instance, is skipped with an info
   * log; any other failure is logged as an error.
   */
  def runLogged(trigger: String, runAll: IO[Seq[IngestionResult]]): IO[Unit] =
    IO(logger.info(s"Starting $trigger ingestion...")) *>
      runAll.attempt.flatMap {
        case Right(results) =>
          val summary = results.map { r =>
            s"${r.sourceName}: added=${r.documentsAdded}, updated=${r.documentsUpdated}, " +
              s"deleted=${r.documentsDeleted}, unchanged=${r.documentsUnchanged}" +
              r.error.fold("")(e => s", error=$e")
          }.mkString("; ")
          val message = s"${trigger.capitalize} ingestion complete: $summary"
          IO(if (results.forall(_.isSuccess)) logger.info(message) else logger.warn(message))
        case Left(e: IngestionAlreadyRunning) =>
          IO(logger.info(s"Skipping $trigger ingestion: ${e.getMessage}"))
        case Left(e) =>
          IO(logger.error(s"${trigger.capitalize} ingestion failed: ${e.getMessage}", e))
      }

  /** Run `ingestionService`'s sources on `schedule`. */
  def apply(ingestionService: IngestionService, schedule: IngestionSchedule): IngestionScheduler =
    new IngestionScheduler(ingestionService.runAll(), ingestionService.recordNextRun, schedule)
}
