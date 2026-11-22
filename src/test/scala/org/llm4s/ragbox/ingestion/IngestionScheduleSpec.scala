package org.llm4s.ragbox.ingestion

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, ZoneId, ZoneOffset}
import scala.concurrent.duration._

class IngestionScheduleSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val utc = ZoneOffset.UTC
  private def at(iso: String): Instant = Instant.parse(iso)
  private def parse(s: String, zone: ZoneId = utc): IngestionSchedule =
    IngestionSchedule.parse(s, zone).fold(e => fail(e), identity)

  "IngestionSchedule.parse" should "read a daily cron as midnight, not 24h from startup" in {
    parse("0 0 * * *").nextRun(at("2026-03-10T15:42:00Z")) shouldBe Some(at("2026-03-11T00:00:00Z"))
    parse("daily").nextRun(at("2026-03-10T15:42:00Z")) shouldBe Some(at("2026-03-11T00:00:00Z"))
  }

  it should "use UNIX day-of-week numbering (Sunday = 0)" in {
    // 2026-03-10 is a Tuesday
    parse("30 2 * * 0").nextRun(at("2026-03-10T00:00:00Z")) shouldBe Some(at("2026-03-15T02:30:00Z"))
    parse("weekly").nextRun(at("2026-03-10T00:00:00Z")) shouldBe Some(at("2026-03-15T00:00:00Z"))
    parse("0 9 * * 1-5").nextRun(at("2026-03-13T10:00:00Z")) shouldBe Some(at("2026-03-16T09:00:00Z")) // Fri -> Mon
  }

  it should "support steps, lists and ranges" in {
    parse("*/15 * * * *").nextRun(at("2026-03-10T10:07:00Z")) shouldBe Some(at("2026-03-10T10:15:00Z"))
    parse("0 6,18 * * *").nextRun(at("2026-03-10T07:00:00Z")) shouldBe Some(at("2026-03-10T18:00:00Z"))
    parse("hourly").nextRun(at("2026-03-10T10:00:00Z")) shouldBe Some(at("2026-03-10T11:00:00Z"))
  }

  it should "evaluate cron in the configured time zone" in {
    parse("0 2 * * *", ZoneId.of("Europe/Berlin")).nextRun(at("2026-07-01T12:00:00Z")) shouldBe
      Some(at("2026-07-02T00:00:00Z")) // 02:00 CEST
  }

  it should "read durations as intervals" in {
    parse("30m") shouldBe IngestionSchedule.Every(30.minutes)
    parse("6h").nextRun(at("2026-03-10T10:00:00Z")) shouldBe Some(at("2026-03-10T16:00:00Z"))
    parse("1d") shouldBe IngestionSchedule.Every(1.day)
  }

  it should "reject invalid schedules instead of ignoring them" in {
    Seq("", "every day", "0 0 * *", "0 0 * * * *", "61 * * * *", "0 25 * * *", "0m", "0 0 31 2 *").foreach { s =>
      withClue(s"'$s': ") {
        IngestionSchedule.parse(s, utc).left.toOption.get should include("Invalid ingestion schedule")
      }
    }
    succeed
  }

  "IngestionScheduler" should "run on the cron schedule, against the clock" in {
    // TestControl's clock starts at the epoch (1970-01-01T00:00Z, a Thursday)
    val program = for {
      runs <- Ref.of[IO, Vector[Instant]](Vector.empty)
      next <- Ref.of[IO, Option[Instant]](None)
      scheduler = new IngestionScheduler(
        IO.realTimeInstant.flatMap(now => runs.update(_ :+ now)).as(Seq.empty),
        n => next.set(n),
        parse("30 2 * * *")
      )
      fiber <- scheduler.run.start
      _ <- IO.sleep(3.days)
      ran <- runs.get
      upcoming <- next.get
      _ <- fiber.cancel
    } yield (ran, upcoming)

    TestControl.executeEmbed(program).asserting { case (ran, upcoming) =>
      ran shouldBe Vector(at("1970-01-01T02:30:00Z"), at("1970-01-02T02:30:00Z"), at("1970-01-03T02:30:00Z"))
      upcoming shouldBe Some(at("1970-01-04T02:30:00Z"))
    }
  }

  it should "keep its schedule when a run fails or overruns" in {
    val program = for {
      runs <- Ref.of[IO, Vector[Instant]](Vector.empty)
      scheduler = new IngestionScheduler(
        IO.realTimeInstant.flatMap { now =>
          runs.update(_ :+ now) *>
            (if (now == at("1970-01-01T01:00:00Z")) IO.raiseError(new RuntimeException("boom"))
             else if (now == at("1970-01-01T02:00:00Z")) IO.sleep(90.minutes).as(Seq.empty) // overruns 03:00
             else IO.pure(Seq.empty))
        },
        _ => IO.unit,
        parse("0 * * * *")
      )
      fiber <- scheduler.run.start
      _ <- IO.sleep(5.hours + 1.minute)
      ran <- runs.get
      _ <- fiber.cancel
    } yield ran

    TestControl.executeEmbed(program).asserting { ran =>
      // 03:00 is skipped (the 02:00 run was still going); no catch-up burst
      ran shouldBe Vector("01", "02", "04", "05").map(h => at(s"1970-01-01T$h:00:00Z"))
    }
  }

  it should "skip a scheduled run while another run is in progress" in {
    val program = for {
      attempts <- Ref.of[IO, Int](0)
      scheduler = new IngestionScheduler(
        attempts.update(_ + 1) *> IO.raiseError(IngestionAlreadyRunning()),
        _ => IO.unit,
        parse("10m")
      )
      fiber <- scheduler.run.start
      _ <- IO.sleep(35.minutes)
      n <- attempts.get
      _ <- fiber.cancel
    } yield n

    TestControl.executeEmbed(program).asserting(_ shouldBe 3)
  }

  it should "run a cron schedule in the configured INGEST_SCHEDULE_TZ" in {
    // 02:30 in New York (UTC-5 in January) is 07:30 UTC
    val config = IngestionConfig(schedule = Some("30 2 * * *"), scheduleTimeZone = Some("America/New_York"))
    val schedule = config.parsedSchedule.get.fold(e => fail(e), identity)
    val program = for {
      runs <- Ref.of[IO, Vector[Instant]](Vector.empty)
      scheduler = new IngestionScheduler(IO.realTimeInstant.flatMap(now => runs.update(_ :+ now)).as(Seq.empty), _ => IO.unit, schedule)
      fiber <- scheduler.run.start
      _ <- IO.sleep(2.days)
      ran <- runs.get
      _ <- fiber.cancel
    } yield ran

    TestControl.executeEmbed(program).asserting { ran =>
      ran shouldBe Vector(at("1970-01-01T07:30:00Z"), at("1970-01-02T07:30:00Z"))
      schedule.describe should include("America/New_York")
    }
  }
}
