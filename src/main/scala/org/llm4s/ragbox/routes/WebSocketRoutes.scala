package org.llm4s.ragbox.routes

import cats.effect.{IO, Ref, Unique}
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.{Pipe, Stream}
import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.llm4s.ragbox.auth.Principal
import org.llm4s.ragbox.ingestion.IngestionEvent

import scala.concurrent.duration._

/**
 * WebSocket endpoint (`GET /ws`) for real-time updates: ingestion progress and stats.
 *
 * The endpoint is behind the authenticator like every other API route. Browsers cannot set
 * headers on a WebSocket handshake, so besides the OIDC session cookie the authenticator also
 * accepts `?access_token=<token>` on this path only.
 *
 * Messages are server-to-client JSON `{"type", "payload", "timestamp"}`; client messages are
 * ignored.
 */
object WebSocketRoutes {

  /** Frames buffered per client; when a slow client's buffer is full, new messages are dropped. */
  val ClientBufferSize: Int = 256

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

  /**
   * Fan-out of server messages to the connected clients.
   *
   * Each client has a bounded queue; [[broadcast]] never blocks on a slow client (it drops the
   * message for that client instead). A client is removed when its connection closes.
   */
  final class BroadcastHub private (subscribers: Ref[IO, Map[Unique.Token, Queue[IO, WebSocketFrame]]]) {

    def broadcast(message: WSMessage): IO[Unit] = {
      val frame = WebSocketFrame.Text(message.asJson.noSpaces)
      subscribers.get.flatMap(_.values.toList.traverse_(_.tryOffer(frame).void))
    }

    /** Register a client queue; returns the key to [[unsubscribe]] it with. */
    def subscribe(queue: Queue[IO, WebSocketFrame]): IO[Unique.Token] =
      IO.unique.flatTap(token => subscribers.update(_ + (token -> queue)))

    def unsubscribe(token: Unique.Token): IO[Unit] =
      subscribers.update(_ - token)

    def subscriberCount: IO[Int] = subscribers.get.map(_.size)
  }

  object BroadcastHub {
    def create: IO[BroadcastHub] =
      Ref.of[IO, Map[Unique.Token, Queue[IO, WebSocketFrame]]](Map.empty).map(new BroadcastHub(_))
  }

  def routes(wsb: WebSocketBuilder2[IO], hub: BroadcastHub): AuthedRoutes[Principal, IO] = AuthedRoutes.of[Principal, IO] {
    case GET -> Root / "ws" as _ =>
      for {
        clientQueue <- Queue.bounded[IO, WebSocketFrame](ClientBufferSize)
        token <- hub.subscribe(clientQueue)

        // Heartbeat + broadcast messages; leaving the hub however the stream ends
        sendStream = Stream
          .awakeEvery[IO](30.seconds)
          .map(_ => WebSocketFrame.Ping())
          .mergeHaltBoth(Stream.fromQueueUnterminated(clientQueue))
          .onFinalize(hub.unsubscribe(token))

        // Client messages are not used
        receiveStream: Pipe[IO, WebSocketFrame, Unit] = _.drain

        response <- wsb
          .withOnClose(hub.unsubscribe(token))
          .build(sendStream, receiveStream)
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

  def ingestionProgress(event: IngestionEvent): WSMessage =
    ingestionProgress(event.source, event.status, event.progress, event.message)
}
