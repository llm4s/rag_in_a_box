package org.llm4s.ragbox.ingestion

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration._
import scala.jdk.OptionConverters._
import scala.util.Try

/**
 * When scheduled ingestion runs happen (`INGEST_SCHEDULE` / `ingestion.schedule`).
 *
 * Either a calendar schedule - a standard five-field UNIX cron expression (minute, hour,
 * day of month, month, day of week with Sunday = 0 or 7), or `hourly` / `daily` / `weekly` for
 * `0 * * * *` / `0 0 * * *` / `0 0 * * 0` - or a fixed interval between runs (`30m`, `6h`, `1d`).
 */
sealed trait IngestionSchedule {

  /** The first run strictly after `after`, or None if the schedule never fires again. */
  def nextRun(after: Instant): Option[Instant]

  /** Human-readable form for logs. */
  def describe: String
}

object IngestionSchedule {

  /**
   * A cron schedule, evaluated in `zone`.
   *
   * @param expression the five-field expression it was parsed from
   */
  final case class Cron(expression: String, zone: ZoneId) extends IngestionSchedule {
    private val executionTime = ExecutionTime.forCron(parser.parse(expression).validate())

    def nextRun(after: Instant): Option[Instant] =
      executionTime.nextExecution(ZonedDateTime.ofInstant(after, zone)).toScala.map(_.toInstant)

    def describe: String = s"cron '$expression' ($zone)"
  }

  /** A run every `interval`, the first one `interval` after the scheduler starts. */
  final case class Every(interval: FiniteDuration) extends IngestionSchedule {
    def nextRun(after: Instant): Option[Instant] = Some(after.plusMillis(interval.toMillis))
    def describe: String = s"every $interval"
  }

  private lazy val parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

  private val Aliases = Map("hourly" -> "0 * * * *", "daily" -> "0 0 * * *", "weekly" -> "0 0 * * 0")
  private val IntervalPattern = """(\d+)\s*([smhd])""".r

  /**
   * Parse a schedule, or say why it is invalid.
   *
   * @param zone the time zone cron expressions are evaluated in (the server's by default)
   */
  def parse(schedule: String, zone: ZoneId = ZoneId.systemDefault()): Either[String, IngestionSchedule] = {
    val trimmed = schedule.trim
    def invalid(reason: String) =
      Left(s"Invalid ingestion schedule '$schedule': $reason. Use a five-field cron expression " +
        "(e.g. '0 2 * * *'), hourly, daily, weekly, or an interval such as 30m, 6h or 1d")

    trimmed.toLowerCase match {
      case "" => invalid("it is empty")
      case alias if Aliases.contains(alias) => Right(Cron(Aliases(alias), zone))
      case IntervalPattern(n, unit) =>
        Try(n.toLong).toOption.filter(_ > 0) match {
          case None => invalid("the interval must be positive")
          case Some(count) =>
            val interval = unit match {
              case "s" => count.seconds
              case "m" => count.minutes
              case "h" => count.hours
              case _ => count.days
            }
            Right(Every(interval))
        }
      case _ =>
        Try(Cron(trimmed.split("\\s+").mkString(" "), zone)).toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString))
          .flatMap(cron => if (cron.nextRun(Instant.now()).isDefined) Right(cron) else Left("it never fires"))
          .fold(reason => invalid(reason), Right(_))
    }
  }
}
