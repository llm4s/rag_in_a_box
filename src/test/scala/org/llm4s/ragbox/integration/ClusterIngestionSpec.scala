package org.llm4s.ragbox.integration

import org.llm4s.ragbox.testkit.TestAuth.*

import cats.effect.{Deferred, IO, Resource}
import cats.effect.std.Supervisor
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.llm4s.ragbox.config.DatabaseConfig
import org.llm4s.ragbox.ingestion._
import org.llm4s.ragbox.model.ErrorResponse
import org.llm4s.ragbox.routes.IngestionRoutes
import org.llm4s.ragbox.testkit.PgVectorFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * Ingestion coordinated between instances sharing one database (issue #63). Each "instance" is
 * its own RAGService (its own connection pool) and IngestionService, locking through
 * [[IngestionLock.forDatabase]] as the server does.
 */
class ClusterIngestionSpec extends AnyFlatSpec with Matchers with PgVectorFixture {

  private val base: Path = Files.createTempDirectory("ragbox-cluster")

  private def sourceDir(name: String, text: String): Path = {
    val dir = Files.createDirectories(base.resolve(s"$name-${System.nanoTime()}"))
    Files.writeString(dir.resolve(s"$name.txt"), text)
    dir
  }

  private val alphaDir = sourceDir("alpha", "Alpha document about glaciers and icebergs in polar oceans.")
  private val betaDir = sourceDir("beta", "Beta document about volcanoes and magma chambers.")

  private val config = IngestionConfig(
    enabled = true,
    sources = Seq(
      DirectorySourceConfig(name = "alpha", path = alphaDir.toString, patterns = Set("*.txt")),
      DirectorySourceConfig(name = "beta", path = betaDir.toString, patterns = Set("*.txt"))
    ),
    policy = IngestionPolicyConfig(allowedRoots = Some(Seq(base.toString)))
  )

  /**
   * Holds runs as they start: the listener of an instance built with [[Gate.listener]] waits, once
   * a source run has started (so with its locks held), until [[release]].
   */
  private final class Gate(val started: Deferred[IO, Unit], releaseSignal: Deferred[IO, Unit]) {
    val events = new ConcurrentLinkedQueue[IngestionEvent]()
    def listener(event: IngestionEvent): IO[Unit] =
      IO(events.add(event)) *>
        (if (event.status == "running") started.complete(()) *> releaseSignal.get else IO.unit)
    def release: IO[Unit] = releaseSignal.complete(()).void
  }

  private def gate: IO[Gate] = (Deferred[IO, Unit], Deferred[IO, Unit]).mapN(new Gate(_, _))

  /** An instance over `db`: its own pool and RAGService, the cluster-wide lock, `listener`. */
  private def instance(
    db: DatabaseConfig,
    listener: IngestionEvent => IO[Unit] = _ => IO.unit,
    ingestion: IngestionConfig = config
  ): Resource[IO, IngestionService] =
    plainService(db).map(rag => IngestionService(rag, ingestion, listener, IngestionLock.forDatabase(db)))

  private def recording(events: ConcurrentLinkedQueue[IngestionEvent])(e: IngestionEvent): IO[Unit] =
    IO(events.add(e)).void

  private def conflict[A](attempt: Either[Throwable, A]): String = attempt match {
    case Left(e: IngestionAlreadyRunning) => e.getMessage
    case other => fail(s"expected IngestionAlreadyRunning, got $other")
  }

  /** How many lock connections are open to `db`'s database. */
  private def lockConnections(db: DatabaseConfig): IO[Int] =
    connect(db).use { conn =>
      IO.blocking(Using.resource(conn.createStatement()) { st =>
        val rs = st.executeQuery(
          "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'ragbox-ingestion-lock' AND datname = current_database()"
        )
        rs.next()
        rs.getInt(1)
      })
    }

  private def post(service: IngestionService, path: String, body: Option[Json] = None): IO[(Status, Json)] = {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(path))
    IngestionRoutes.routes(service).asAdmin.orNotFound
      .run(body.fold(req)(req.withEntity(_)))
      .flatMap(r => r.as[Json].map(r.status -> _))
  }

  private def added(results: Seq[IngestionResult]): Seq[(String, Int, Option[String])] =
    results.map(r => (r.sourceName, r.documentsAdded, r.error))

  "Two instances" should "never both run all sources when they start together" in withContainers { pg =>
    val db = freshDatabase(pg)
    val test = gate.flatMap { g =>
      (instance(db, g.listener), instance(db, g.listener)).tupled.use { case (a, b) =>
        for {
          fa <- a.runAll().attempt.start
          fb <- b.runAll().attempt.start
          _ <- g.started.get.timeout(30.seconds)
          // The run that lost the race fails at once; the other is held at the gate
          loser <- IO.race(fa.joinWithNever, fb.joinWithNever).timeout(30.seconds)
          _ <- g.release
          outcomes <- (fa.joinWithNever, fb.joinWithNever).tupled
        } yield (loser.merge, outcomes)
      }
    }
    val (loser, (ra, rb)) = test.unsafeRunSync()
    conflict(loser) should include("another run")
    List(ra, rb).count(_.isLeft) shouldBe 1
    List(ra, rb).collect { case Right(r) => added(r) } shouldBe List(Seq(("alpha", 1, None), ("beta", 1, None)))
    lockConnections(db).unsafeRunSync() shouldBe 0
  }

  they should "refuse a run, with 409 from the routes, while the other holds the sources, then run" in withContainers { pg =>
    val db = freshDatabase(pg)
    val test = gate.flatMap { g =>
      (instance(db, g.listener), instance(db)).tupled.use { case (a, b) =>
        for {
          run <- a.runAll().start
          _ <- g.started.get.timeout(30.seconds)
          refused <- b.runAll().attempt
          runAll <- post(b, "/api/v1/ingest/run")
          runOne <- post(b, "/api/v1/ingest/run/alpha")
          bStatus <- b.getStatus
          _ <- g.release
          results <- run.joinWithNever
          after <- b.runAll()
        } yield (refused, runAll, runOne, bStatus, results, after)
      }
    }
    val (refused, (runAllStatus, runAllBody), (runOneStatus, runOneBody), bStatus, results, after) = test.unsafeRunSync()
    conflict(refused) shouldBe IngestionAlreadyRunning.elsewhere("alpha").getMessage
    runAllStatus shouldBe Status.Conflict
    runAllBody shouldBe ErrorResponse("conflict", IngestionAlreadyRunning.elsewhere("alpha").getMessage).asJson
    runOneStatus shouldBe Status.Conflict
    runOneBody.hcursor.get[String]("error") shouldBe Right("conflict")
    // The refused instance's own flag is clear: nothing runs there
    bStatus.running shouldBe false
    added(results) shouldBe Seq(("alpha", 1, None), ("beta", 1, None))
    after.map(r => (r.documentsAdded, r.documentsUnchanged)) shouldBe Seq((0, 1), (0, 1))
  }

  they should "run different sources in parallel, but not the same source, nor all of them" in withContainers { pg =>
    val db = freshDatabase(pg)
    val bEvents = new ConcurrentLinkedQueue[IngestionEvent]()
    val test = gate.flatMap { g =>
      (instance(db, g.listener), instance(db, recording(bEvents))).tupled.use { case (a, b) =>
        for {
          alpha <- a.runSource("alpha").start
          _ <- g.started.get.timeout(30.seconds)
          beta <- b.runSource("beta")
          sameSource <- b.runSource("alpha").attempt
          everything <- b.runAll().attempt
          _ <- g.release
          alphaResult <- alpha.joinWithNever
        } yield (beta, sameSource, everything, alphaResult)
      }
    }
    val (beta, sameSource, everything, alphaResult) = test.unsafeRunSync()
    beta.map(r => (r.documentsAdded, r.error)) shouldBe Some((1, None))
    conflict(sameSource) should include("'alpha'")
    conflict(everything) should include("'alpha'")
    alphaResult.map(r => (r.documentsAdded, r.error)) shouldBe Some((1, None))
    // The refused run of all sources ran none of them (not even beta, which was free)
    bEvents.asScala.toList.map(e => (e.source, e.status)) shouldBe List("beta" -> "running", "beta" -> "completed")
  }

  they should "release the locks when a run is cancelled" in withContainers { pg =>
    val db = freshDatabase(pg)
    val test = gate.flatMap { g =>
      (instance(db, g.listener), instance(db)).tupled.use { case (a, b) =>
        for {
          run <- a.runAll().start
          _ <- g.started.get.timeout(30.seconds)
          held <- lockConnections(db)
          _ <- run.cancel
          released <- lockConnections(db)
          results <- b.runAll()
        } yield (held, released, results)
      }
    }
    val (held, released, results) = test.unsafeRunSync()
    held shouldBe 1
    released shouldBe 0
    added(results) shouldBe Seq(("alpha", 1, None), ("beta", 1, None))
  }

  they should "release the locks when a source fails" in withContainers { pg =>
    val db = freshDatabase(pg)
    val missing = config.copy(sources = Seq(DirectorySourceConfig(name = "alpha", path = base.resolve("missing").toString)))
    val test = (instance(db, ingestion = missing), instance(db)).tupled.use { case (a, b) =>
      (a.runAll(), b.runSource("alpha")).tupled
    }
    val (failed, alpha) = test.unsafeRunSync()
    failed.map(_.error.isDefined) shouldBe Seq(true)
    alpha.map(r => (r.documentsAdded, r.error)) shouldBe Some((1, None))
    lockConnections(db).unsafeRunSync() shouldBe 0
  }

  they should "find the locks of an instance that died released with its connection" in withContainers { pg =>
    val db = freshDatabase(pg)
    val test = instance(db).use { b =>
      for {
        // A "crashed" instance: a session holding alpha's lock, then gone
        whileHeld <- connect(db).use { conn =>
          IO.blocking(Using.resource(conn.createStatement())(_.execute(s"SELECT pg_advisory_lock(${IngestionLock.key("alpha")})"))) *>
            b.runSource("alpha").attempt
        }
        afterwards <- b.runSource("alpha")
      } yield (whileHeld, afterwards)
    }
    val (whileHeld, afterwards) = test.unsafeRunSync()
    conflict(whileHeld) should include("'alpha'")
    afterwards.map(_.documentsAdded) shouldBe Some(1)
  }

  they should "not run the same one-shot directory ingest at once, but other directories and sources" in withContainers { pg =>
    val db = freshDatabase(pg)
    val body = Json.obj("path" -> alphaDir.toString.asJson, "patterns" -> Json.arr("*.txt".asJson))
    val test = gate.flatMap { g =>
      (instance(db, g.listener), instance(db)).tupled.use { case (a, b) =>
        for {
          run <- a.ingestDirectory(alphaDir.toString, Set("*.txt")).start
          _ <- g.started.get.timeout(30.seconds)
          same <- b.ingestDirectory(alphaDir.toString, Set("*.txt")).attempt
          sameRoute <- post(b, "/api/v1/ingest/directory", Some(body))
          other <- b.ingestDirectory(betaDir.toString, Set("*.txt"))
          sources <- b.runAll()
          _ <- g.release
          result <- run.joinWithNever
        } yield (same, sameRoute._1, other, sources, result)
      }
    }
    val (same, sameStatus, other, sources, result) = test.unsafeRunSync()
    conflict(same) should include(s"api:directory:$alphaDir")
    sameStatus shouldBe Status.Conflict
    other.error shouldBe None
    sources.map(_.error) shouldBe Seq(None, None)
    result.error shouldBe None
  }

  "The schedule" should "skip its runs while another instance runs the sources, then run" in withContainers { pg =>
    val db = freshDatabase(pg)
    val bEvents = new ConcurrentLinkedQueue[IngestionEvent]()
    def completed = bEvents.asScala.count(_.status == "completed")
    val test = gate.flatMap { g =>
      (instance(db, g.listener), instance(db, recording(bEvents)), Supervisor[IO]).tupled.use { case (a, b, supervisor) =>
        for {
          run <- a.runAll().start
          _ <- g.started.get.timeout(30.seconds)
          // Startup ingestion on the second instance is skipped, not failed
          _ <- IngestionScheduler.runLogged("startup", b.runAll())
          _ <- IngestionScheduler(b, IngestionSchedule.Every(100.millis)).start(supervisor)
          _ <- IO.sleep(700.millis)
          whileHeld <- IO(bEvents.size)
          scheduled <- b.getStatus.map(_.nextScheduledRun)
          _ <- g.release
          _ <- run.joinWithNever
          _ <- (IO.sleep(20.millis) *> IO(completed)).iterateUntil(_ >= 2).timeout(30.seconds)
          ran <- IO(completed)
        } yield (whileHeld, scheduled, ran)
      }
    }
    val (whileHeld, scheduled, ran) = test.unsafeRunSync()
    whileHeld shouldBe 0
    scheduled should not be empty
    ran should be >= 2
  }
}
