package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import org.http4s.websocket.WebSocketFrame
import org.llm4s.ragbox.ingestion.{IngestionEvent, IngestionResult}
import org.llm4s.ragbox.routes.WebSocketRoutes.BroadcastHub
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

class WebSocketRoutesSpec extends AnyFlatSpec with Matchers {

  "BroadcastHub" should "deliver to subscribers and forget them on unsubscribe" in {
    (for {
      hub <- BroadcastHub.create
      q <- Queue.bounded[IO, WebSocketFrame](4)
      token <- hub.subscribe(q)
      _ <- hub.broadcast(WebSocketRoutes.statsUpdate(1, 2, 3))
      received <- q.tryTake
      _ <- hub.unsubscribe(token)
      count <- hub.subscriberCount
    } yield {
      received.collect { case WebSocketFrame.Text(text, _) => text }.get should include("stats_update")
      count shouldBe 0
    }).unsafeRunSync()
  }

  it should "not block on a slow client with a full buffer" in {
    (for {
      hub <- BroadcastHub.create
      q <- Queue.bounded[IO, WebSocketFrame](1)
      _ <- hub.subscribe(q)
      _ <- (1 to 10).toList.traverse_(i => hub.broadcast(WebSocketRoutes.statsUpdate(i, 0, 0))).timeout(5.seconds)
      size <- q.size
    } yield size shouldBe 1).unsafeRunSync()
  }

  "ingestionProgress" should "encode ingestion events" in {
    val result = IngestionResult("docs", "directory", 1, 2, 0, 3, 0, Instant.EPOCH, Instant.EPOCH)
    val msg = WebSocketRoutes.ingestionProgress(IngestionEvent.finished(result))
    msg.`type` shouldBe "ingestion_progress"
    msg.payload.hcursor.get[String]("status") shouldBe Right("completed")
    msg.payload.hcursor.get[Double]("progress") shouldBe Right(1.0)
  }

  private implicit class TraverseOps[A](xs: List[A]) {
    def traverse_(f: A => IO[Unit]): IO[Unit] = xs.foldLeft(IO.unit)((acc, a) => acc *> f(a))
  }
}
