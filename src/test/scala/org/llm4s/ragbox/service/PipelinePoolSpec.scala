package org.llm4s.ragbox.service

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class PipelinePoolSpec extends AnyFlatSpec with Matchers {

  private def run[A](io: IO[A]): A = io.timeout(10.seconds).unsafeRunSync()

  /** A pool of strings `"<key>#<n>"`, recording what it built and closed. */
  private final class Probe(maxEntries: Int = 4, failBuilds: Int = 0, cooldown: FiniteDuration = 1.hour) {
    val built: Ref[IO, List[String]] = Ref.unsafe(Nil)
    val closed: Ref[IO, List[String]] = Ref.unsafe(Nil)
    private val failures: Ref[IO, Int] = Ref.unsafe(failBuilds)
    val pool: PipelinePool[String, String] = run(PipelinePool[String, String](
      build = key =>
        failures.modify(n => (n - 1, n > 0)).flatMap { fail =>
          if (fail) IO.raiseError(new RuntimeException(s"cannot build $key"))
          else built.modify(b => (s"$key#${b.size}" :: b, s"$key#${b.size}"))
        },
      dispose = value => closed.update(value :: _),
      maxEntries = maxEntries,
      retryCooldown = cooldown,
      closedMessage = "pool closed"
    ))
  }

  "A pipeline pool" should "build one instance per key and reuse it" in {
    val p = new Probe
    val (a1, a2, b) = run((p.pool.use("a")(IO.pure), p.pool.use("a")(IO.pure), p.pool.use("b")(IO.pure)).tupled)
    a1 shouldBe a2
    b should not be a1
    run(p.built.get) should have size 2
  }

  it should "build a key once when many requests ask for it at the same time" in {
    val p = new Probe
    val results = run(List.fill(20)(p.pool.use("a")(v => IO.sleep(5.millis).as(v))).parSequence)
    results.distinct should have size 1
    run(p.built.get) should have size 1
  }

  it should "close a retired instance only once the request using it has finished" in {
    val p = new Probe
    val (closedDuringUse, closedAfter, rebuilt) = run(for {
      started <- Deferred[IO, Unit]
      finish <- Deferred[IO, Unit]
      inFlight <- p.pool.use("a")(v => started.complete(()) *> finish.get.as(v)).start
      _ <- started.get
      _ <- p.pool.retire("a")
      during <- p.closed.get
      _ <- finish.complete(())
      _ <- inFlight.joinWithNever
      after <- p.closed.get
      next <- p.pool.use("a")(IO.pure)
    } yield (during, after, next))
    closedDuringUse shouldBe empty
    closedAfter shouldBe List("a#0")
    rebuilt shouldBe "a#1"
  }

  it should "close the least recently used idle instance beyond its size" in {
    val p = new Probe(maxEntries = 2)
    run(p.pool.use("a")(IO.pure) *> p.pool.use("b")(IO.pure) *> p.pool.use("a")(IO.pure) *> p.pool.use("c")(IO.pure))
    run(p.closed.get) shouldBe List("b#1")
    run(p.pool.keys) shouldBe Set("a", "c")
  }

  it should "not keep a failed build, and retry it after the cooldown" in {
    val p = new Probe(failBuilds = 1, cooldown = 50.millis)
    run(p.pool.use("a")(IO.pure).attempt).left.map(_.getMessage) shouldBe Left("cannot build a")
    // Within the cooldown the last error is returned without building again
    run(p.pool.use("a")(IO.pure).attempt).left.map(_.getMessage) shouldBe Left("cannot build a")
    run(IO.sleep(60.millis) *> p.pool.use("a")(IO.pure)) shouldBe "a#0"
  }

  it should "close everything on close and refuse later leases" in {
    val p = new Probe
    run(p.pool.use("a")(IO.pure) *> p.pool.use("b")(IO.pure) *> p.pool.close)
    run(p.closed.get).toSet shouldBe Set("a#0", "b#1")
    run(p.pool.use("a")(IO.pure).attempt).left.map(_.getMessage) shouldBe Left("pool closed")
  }
}
