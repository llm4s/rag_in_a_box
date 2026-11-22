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
 * logged and the schedule carries on; a run that would overlap a manual one is skipped.
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

  private def runOnce: IO[Unit] =
    IO(logger.info("Starting scheduled ingestion...")) *>
      runAll.attempt.flatMap {
        case Right(results) =>
          val summary = results.map { r =>
            s"${r.sourceName}: added=${r.documentsAdded}, updated=${r.documentsUpdated}, " +
              s"deleted=${r.documentsDeleted}, unchanged=${r.documentsUnchanged}" +
              r.error.fold("")(e => s", error=$e")
          }.mkString("; ")
          IO(logger.info(s"Scheduled ingestion complete: $summary"))
        case Left(_: IngestionAlreadyRunning) =>
          IO(logger.info("Skipping scheduled ingestion: a run is already in progress"))
        case Left(e) =>
          IO(logger.error(s"Scheduled ingestion failed: ${e.getMessage}", e))
      }
}

object IngestionScheduler {

  /** Run `ingestionService`'s sources on `schedule`. */
  def apply(ingestionService: IngestionService, schedule: IngestionSchedule): IngestionScheduler =
    new IngestionScheduler(ingestionService.runAll(), ingestionService.recordNextRun, schedule)
}
