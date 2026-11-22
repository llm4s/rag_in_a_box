package org.llm4s.ragbox.routes

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.parser.parse
import org.http4s.{Header, Method, Request, Status}
import org.http4s.implicits._
import org.http4s.server.websocket.{WebSocketBuilder2, WebSocketTestSupport}
import org.http4s.websocket.WebSocketFrame
import org.llm4s.ragbox.config.CorsConfig
import org.llm4s.ragbox.ingestion.{IngestionEvent, IngestionResult}
import org.llm4s.ragbox.routes.WebSocketRoutes.BroadcastHub
import org.llm4s.ragbox.testkit.TestAuth.asAdmin
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIStringSyntax

import java.time.Instant
import scala.concurrent.duration._

/**
 * The `/ws` endpoint and its broadcast hub. The handshake's authentication is covered by the
 * stack specs (`AuthStackSpec`), and the `Origin` rules by `SecurityHardeningStackSpec`.
 */
class WebSocketRoutesSpec extends AnyFlatSpec with Matchers {

  /** A client connection, as the server side of `/ws` sees it: what it sends, and how it ends. */
  private type Client = WebSocketTestSupport.ServerSide[IO]

  /** Open `/ws` on `hub` the way the server would, without a network connection. */
  private def connect(hub: BroadcastHub, origin: Option[String] = None): IO[Either[Status, Client]] =
    for {
      wsb <- WebSocketBuilder2[IO]
      routes = WebSocketRoutes.routes(wsb, hub, CorsConfig(allowedOrigins = Seq.empty, allowAllOrigins = false)).asAdmin
      req = Request[IO](Method.GET, uri"/ws").putHeaders(Header.Raw(ci"Host", "ragbox.example"))
      response <- routes.orNotFound.run(origin.fold(req)(o => req.putHeaders(Header.Raw(ci"Origin", o))))
    } yield WebSocketTestSupport.serverSide(wsb, response).toRight(response.status)

  private def text(frame: WebSocketFrame): String = frame match {
    case WebSocketFrame.Text(t, _) => t
    case other => fail(s"not a text frame: $other")
  }

  "GET /ws" should "subscribe the client to broadcasts, and send them as JSON text frames" in {
    (for {
      hub <- BroadcastHub.create
      client <- connect(hub).map(_.toOption.get)
      subscribed <- hub.subscriberCount
      _ <- hub.broadcast(WebSocketRoutes.statsUpdate(3, 30, 1))
      _ <- hub.broadcast(WebSocketRoutes.documentEvent("document_deleted", "doc-1"))
      frames <- client.send.take(2).compile.toList.timeout(5.seconds)
    } yield {
      subscribed shouldBe 1
      val json = frames.map(f => parse(text(f)).toOption.get)
      json.map(_.hcursor.get[String]("type")) shouldBe Seq(Right("stats_update"), Right("document_deleted"))
      json.head.hcursor.downField("payload").get[Int]("chunkCount") shouldBe Right(30)
      json(1).hcursor.downField("payload").get[String]("documentId") shouldBe Right("doc-1")
      json.head.hcursor.get[String]("timestamp").isRight shouldBe true
    }).unsafeRunSync()
  }

  it should "deliver each broadcast to every connected client" in {
    (for {
      hub <- BroadcastHub.create
      a <- connect(hub).map(_.toOption.get)
      b <- connect(hub).map(_.toOption.get)
      _ <- hub.broadcast(WebSocketRoutes.ingestionProgress("docs", "running", 0.5))
      fromA <- a.send.head.compile.lastOrError.timeout(5.seconds)
      fromB <- b.send.head.compile.lastOrError.timeout(5.seconds)
    } yield {
      text(fromA) shouldBe text(fromB)
      text(fromA) should include("ingestion_progress")
    }).unsafeRunSync()
  }

  it should "unsubscribe a client when its send stream ends, or when the connection closes" in {
    (for {
      hub <- BroadcastHub.create
      ended <- connect(hub).map(_.toOption.get)
      closed <- connect(hub).map(_.toOption.get)
      both <- hub.subscriberCount
      _ <- hub.broadcast(WebSocketRoutes.statsUpdate(1, 1, 1))
      _ <- ended.send.take(1).compile.drain.timeout(5.seconds)
      afterEnd <- hub.subscriberCount
      _ <- closed.onClose
      afterClose <- hub.subscriberCount
      // closing an already finished connection is harmless
      _ <- ended.onClose
      _ <- hub.broadcast(WebSocketRoutes.statsUpdate(2, 2, 2))
    } yield (both, afterEnd, afterClose) shouldBe (2, 1, 0)).unsafeRunSync()
  }

  it should "ignore what the client sends" in {
    (for {
      hub <- BroadcastHub.create
      client <- connect(hub).map(_.toOption.get)
      _ <- Stream(WebSocketFrame.Text("""{"type":"subscribe"}"""), WebSocketFrame.Text("garbage"))
        .covary[IO].through(client.receive).compile.drain.timeout(5.seconds)
      _ <- hub.broadcast(WebSocketRoutes.statsUpdate(1, 1, 1))
      frame <- client.send.head.compile.lastOrError.timeout(5.seconds)
    } yield text(frame) should include("stats_update")).unsafeRunSync()
  }

  it should "buffer up to ClientBufferSize messages for a slow client and drop the rest" in {
    val total = WebSocketRoutes.ClientBufferSize + 50
    (for {
      hub <- BroadcastHub.create
      slow <- connect(hub).map(_.toOption.get)
      _ <- (1 to total).toList.traverse_(i => hub.broadcast(WebSocketRoutes.statsUpdate(i, 0, 0))).timeout(5.seconds)
      buffered <- slow.send.take(WebSocketRoutes.ClientBufferSize.toLong).compile.toList.timeout(5.seconds)
    } yield {
      val counts = buffered.map(f => parse(text(f)).toOption.get.hcursor.downField("payload").get[Int]("documentCount").toOption.get)
      // the oldest messages are kept, in order; the ones that arrived while the buffer was full are lost
      counts shouldBe (1 to WebSocketRoutes.ClientBufferSize).toList
    }).unsafeRunSync()
    // ...and once the client has caught up, it gets new messages again
    (for {
      hub <- BroadcastHub.create
      client <- connect(hub).map(_.toOption.get)
      _ <- (1 to total).toList.traverse_(i => hub.broadcast(WebSocketRoutes.statsUpdate(i, 0, 0)))
      // one connection: a new message is broadcast once the buffered ones have been sent
      frames <- client.send.zipWithIndex
        .evalTap { case (_, i) => if (i == WebSocketRoutes.ClientBufferSize - 1L) hub.broadcast(WebSocketRoutes.statsUpdate(-1, 0, 0)) else IO.unit }
        .map(_._1)
        .take(WebSocketRoutes.ClientBufferSize + 1L)
        .compile.toList.timeout(5.seconds)
    } yield text(frames.last) should include("\"documentCount\":-1")).unsafeRunSync()
  }

  it should "refuse a cross-site handshake without subscribing it" in {
    (for {
      hub <- BroadcastHub.create
      refused <- connect(hub, origin = Some("https://evil.example"))
      sameSite <- connect(hub, origin = Some("https://ragbox.example"))
      count <- hub.subscriberCount
    } yield {
      refused shouldBe Left(Status.Forbidden)
      sameSite.isRight shouldBe true
      count shouldBe 1
    }).unsafeRunSync()
  }

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
