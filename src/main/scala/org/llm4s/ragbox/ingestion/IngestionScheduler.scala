package org.llm4s.ragbox.ingestion

import cats.effect.{IO, Ref, Temporal}
import cats.effect.std.Supervisor
import cats.syntax.all._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import java.time.Instant

/**
 * Scheduler for periodic ingestion runs.
 *
 * Supports:
 * - Fixed interval scheduling (e.g., every 6 hours)
 * - Simple cron-like expressions (hourly, daily, etc.)
 */
class IngestionScheduler(
  ingestionService: IngestionService,
  config: IngestionConfig
) {
  private val logger = LoggerFactory.getLogger(getClass)

  private case class SchedulerState(
    running: Boolean = false,
    nextRun: Option[Instant] = None,
    lastRun: Option[Instant] = None,
    runCount: Int = 0
  )

  /**
   * Start the scheduler as a background fiber.
   * Returns an IO that completes when the scheduler is started.
   */
  def start(supervisor: Supervisor[IO]): IO[Unit] = {
    parseInterval(config.schedule) match {
      case Some(interval) if config.enabled =>
        supervisor.supervise(runLoop(interval)).void
      case _ =>
        IO.unit // No schedule configured or not enabled
    }
  }

  /**
   * Main scheduling loop.
   */
  private def runLoop(interval: FiniteDuration): IO[Unit] = {
    def loop: IO[Unit] = for {
      _ <- IO(logger.info(s"Next ingestion run in ${formatDuration(interval)}"))
      _ <- Temporal[IO].sleep(interval)
      _ <- IO(logger.info("Starting scheduled ingestion..."))
      results <- ingestionService.runAll().attempt
      _ <- results match {
        case Right(r) =>
          val summary = r.map { res =>
            s"${res.sourceName}: added=${res.documentsAdded}, updated=${res.documentsUpdated}, unchanged=${res.documentsUnchanged}"
          }.mkString(", ")
          IO(logger.info(s"Ingestion complete: $summary"))
        case Left(e) =>
          IO(logger.error(s"Ingestion failed: ${e.getMessage}", e))
      }
      _ <- loop
    } yield ()

    loop
  }

  /**
   * Parse schedule configuration into a duration.
   *
   * Supports:
   * - Simple intervals: "5m", "1h", "6h", "1d"
   * - Named schedules: "hourly", "daily", "weekly"
   * - Cron-like: "0 * * * *" (hourly), "0 0 * * *" (daily)
   */
  private def parseInterval(schedule: Option[String]): Option[FiniteDuration] = {
    schedule.flatMap { s =>
      val trimmed = s.trim.toLowerCase

      // Named schedules
      trimmed match {
        case "hourly" => Some(1.hour)
        case "daily" => Some(24.hours)
        case "weekly" => Some(7.days)
        case _ =>
          // Try parsing as duration (e.g., "5m", "1h", "6h", "1d")
          parseDuration(trimmed).orElse(parseCron(s))
      }
    }
  }

  /**
   * Parse simple duration strings like "5m", "1h", "6h", "1d".
   */
  private def parseDuration(s: String): Option[FiniteDuration] = {
    val pattern = """(\d+)\s*(s|m|h|d)""".r
    s match {
      case pattern(num, unit) =>
        val n = num.toInt
        unit match {
          case "s" => Some(n.seconds)
          case "m" => Some(n.minutes)
          case "h" => Some(n.hours)
          case "d" => Some(n.days)
          case _ => None
        }
      case _ => None
    }
  }

  /**
   * Parse simple cron expressions to intervals.
   * Only supports basic patterns for now.
   */
  private def parseCron(s: String): Option[FiniteDuration] = {
    val parts = s.split("\\s+")
    if (parts.length >= 5) {
      // Very basic cron parsing - just detect common patterns
      (parts(0), parts(1), parts(2), parts(3), parts(4)) match {
        case ("0", "*", "*", "*", "*") => Some(1.hour)      // Every hour
        case ("0", "0", "*", "*", "*") => Some(24.hours)    // Daily at midnight
        case ("0", "*/2", "*", "*", "*") => Some(2.hours)   // Every 2 hours
        case ("0", "*/4", "*", "*", "*") => Some(4.hours)   // Every 4 hours
        case ("0", "*/6", "*", "*", "*") => Some(6.hours)   // Every 6 hours
        case ("0", "*/12", "*", "*", "*") => Some(12.hours) // Every 12 hours
        case _ => None
      }
    } else {
      None
    }
  }

  /**
   * Format duration for display.
   */
  private def formatDuration(d: FiniteDuration): String = {
    if (d.toHours >= 24) s"${d.toDays} day(s)"
    else if (d.toMinutes >= 60) s"${d.toHours} hour(s)"
    else if (d.toSeconds >= 60) s"${d.toMinutes} minute(s)"
    else s"${d.toSeconds} second(s)"
  }
}

object IngestionScheduler {

  def apply(ingestionService: IngestionService, config: IngestionConfig): IngestionScheduler =
    new IngestionScheduler(ingestionService, config)
}
