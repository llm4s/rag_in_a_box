package ragbox.routes

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import fs2.{Pipe, Stream}
import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration._

/**
 * WebSocket routes for real-time updates.
 */
object WebSocketRoutes {

  // Message types for WebSocket communication
  case class WSMessage(
    `type`: String,
    payload: Json,
    timestamp: String = java.time.Instant.now().toString
  )

  object WSMessage {
    implicit val encoder: Encoder[WSMessage] = Encoder.forProduct3("type", "payload", "timestamp")(m =>
      (m.`type`, m.payload, m.timestamp)
    )
  }

  // Broadcast hub for all connected clients
  case class BroadcastHub(
    subscribers: Ref[IO, List[Queue[IO, WebSocketFrame]]]
  ) {
    def broadcast(message: WSMessage): IO[Unit] = {
      val frame = WebSocketFrame.Text(message.asJson.noSpaces)
      subscribers.get.flatMap { subs =>
        IO.parTraverseN(10)(subs) { queue =>
          queue.tryOffer(frame).void
        }.void
      }
    }

    def subscribe(queue: Queue[IO, WebSocketFrame]): IO[Unit] =
      subscribers.update(_ :+ queue)

    def unsubscribe(queue: Queue[IO, WebSocketFrame]): IO[Unit] =
      subscribers.update(_.filterNot(_ == queue))
  }

  object BroadcastHub {
    def create: IO[BroadcastHub] =
      Ref.of[IO, List[Queue[IO, WebSocketFrame]]](List.empty).map(BroadcastHub(_))
  }

  def routes(wsb: WebSocketBuilder2[IO], hub: BroadcastHub): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "ws" =>
      for {
        // Create a queue for this client
        clientQueue <- Queue.unbounded[IO, WebSocketFrame]
        _ <- hub.subscribe(clientQueue)

        // Send stream: heartbeat + broadcast messages
        sendStream = Stream.awakeEvery[IO](30.seconds)
          .map(_ => WebSocketFrame.Ping())
          .mergeHaltBoth(Stream.fromQueueUnterminated(clientQueue))

        // Receive stream: handle pings, log messages
        receiveStream: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
          case WebSocketFrame.Ping(data) =>
            clientQueue.offer(WebSocketFrame.Pong(data))
          case WebSocketFrame.Pong(_) =>
            IO.unit
          case WebSocketFrame.Text(text, _) =>
            // Could handle client messages here
            IO.unit
          case WebSocketFrame.Close(_) =>
            hub.unsubscribe(clientQueue)
          case _ =>
            IO.unit
        }

        response <- wsb.build(sendStream, receiveStream)
      } yield response
  }

  // Helper to create stats update message
  def statsUpdate(documentCount: Int, chunkCount: Int, collectionCount: Int): WSMessage =
    WSMessage(
      "stats_update",
      Json.obj(
        "documentCount" -> Json.fromInt(documentCount),
        "chunkCount" -> Json.fromInt(chunkCount),
        "collectionCount" -> Json.fromInt(collectionCount)
      )
    )

  // Helper to create document event message
  def documentEvent(eventType: String, documentId: String): WSMessage =
    WSMessage(
      eventType,
      Json.obj("documentId" -> Json.fromString(documentId))
    )

  // Helper to create ingestion progress message
  def ingestionProgress(source: String, status: String, progress: Double, message: Option[String] = None): WSMessage =
    WSMessage(
      "ingestion_progress",
      Json.obj(
        "source" -> Json.fromString(source),
        "status" -> Json.fromString(status),
        "progress" -> Json.fromDoubleOrNull(progress),
        "message" -> message.map(Json.fromString).getOrElse(Json.Null)
      )
    )
}
