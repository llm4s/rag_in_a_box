package org.llm4s.ragbox.routes

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.syntax._
import org.http4s.{MediaType, Response, Status}
import org.http4s.headers.`Content-Type`
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.service.AnswerEvent

/**
 * A streamed answer as Server-Sent Events, for `/query/stream` and the chat message stream.
 *
 * The events, in order: `start`; a `context` per chunk the answer is generated from, before the
 * model is called; a `chunk` for each piece of the answer as the provider sends it; then `answer`
 * (the whole text), `usage` when the provider reported it, and `complete`. A failure - at any
 * point after `start` - is an `error` event, the last one.
 */
object AnswerSse {

  /** Format a message as a Server-Sent Event. */
  def format(event: String, data: Json): String =
    s"event: $event\ndata: ${data.noSpaces}\n\n"

  /**
   * The `text/event-stream` response for `events`.
   *
   * @param start       the `start` event's data
   * @param queryId     the query-log id, repeated in `complete`
   * @param onCompleted run with the answer and its latency in milliseconds, before the `answer`
   *                    event: logging the query, storing a chat reply
   * @param onFailed    run with the failure and its latency, before the `error` event
   */
  def response(
    start: Json,
    queryId: String,
    events: Stream[IO, AnswerEvent]
  )(onCompleted: (QueryResponse, Int) => IO[Unit], onFailed: (Throwable, Int) => IO[Unit]): Response[IO] = {
    val body: Stream[IO, String] = Stream.eval(IO.monotonic).flatMap { started =>
      val elapsedMs = IO.monotonic.map(now => (now - started).toMillis.toInt)
      Stream.emit(format("start", start)) ++ events.attempt.flatMap {
        case Right(AnswerEvent.Contexts(contexts)) =>
          Stream.emits(contexts.zipWithIndex.map { case (context, index) =>
            format("context", QueryContextEvent(context, index).asJson)
          })
        case Right(AnswerEvent.Delta(text)) =>
          Stream.emit(format("chunk", QueryChunkEvent(text).asJson))
        case Right(AnswerEvent.Completed(answer)) =>
          Stream.exec(elapsedMs.flatMap(onCompleted(answer, _))) ++
            Stream.emit(format("answer", QueryAnswerEvent(answer.answer).asJson)) ++
            Stream.emits(answer.usage.toSeq.map(usage => format("usage", QueryUsageEvent(usage).asJson))) ++
            Stream.emit(format("complete", QueryCompleteEvent(queryId, answer.contexts.size).asJson))
        case Left(e) =>
          Stream.exec(elapsedMs.flatMap(onFailed(e, _))) ++
            Stream.eval(ErrorHandling.clientMessage(e, "Query failed")).map { message =>
              format("error", QueryErrorEvent("query_failed", message).asJson)
            }
      }
    }
    // Raw UTF-8: an entity encoder would pick the circe one and JSON-quote every event
    Response[IO](Status.Ok)
      .withBodyStream(body.through(fs2.text.utf8.encode))
      .withContentType(`Content-Type`(MediaType.`text/event-stream`))
  }
}
