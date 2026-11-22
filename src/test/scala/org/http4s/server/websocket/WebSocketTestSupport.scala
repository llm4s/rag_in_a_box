package org.http4s.server.websocket

import fs2.{Pipe, Stream}
import org.http4s.Response
import org.http4s.websocket.{WebSocketFrame, WebSocketSeparatePipe}

/**
 * Test access to the WebSocket a route built with a [[WebSocketBuilder2]], without a network
 * connection. The attribute key the server reads it from, and the WebSocket's representation,
 * are `private[http4s]`, hence the package.
 */
object WebSocketTestSupport {

  /** The server side of a connection: the frames it sends, the client frame sink, and the close hook. */
  final case class ServerSide[F[_]](send: Stream[F, WebSocketFrame], receive: Pipe[F, WebSocketFrame, Unit], onClose: F[Unit])

  /** The connection `response` opens, if it is a WebSocket handshake built by `wsb`. */
  def serverSide[F[_]](wsb: WebSocketBuilder2[F], response: Response[F]): Option[ServerSide[F]] =
    response.attributes.lookup(wsb.webSocketKey).map(_.webSocket).collect {
      case WebSocketSeparatePipe(send, receive, onClose) => ServerSide(send, receive, onClose)
    }
}
