package org.llm4s.ragbox.service

import org.llm4s.llmconnect.model.{Completion, TokenUsage}
import org.llm4s.trace.{TraceEvent, Tracing}
import org.llm4s.types.Result

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration

/**
 * An llm4s [[Tracing]] backend that attributes trace events to the request that caused them, so
 * ragbox can record per-query token, cost and latency figures.
 *
 * It is handed to llm4s twice: to the RAG pipeline with `RAGConfig.withTracing` - which traces
 * every embedding call as a `TraceEvent.EmbeddingUsageRecorded` (with its duration) and a
 * `CostRecorded` - and around the answer LLM as `TracingLLMClient`, which traces every completion
 * as a `CompletionReceived` with its duration and token usage. Query rewriting and LLM reranking
 * go through that same client, so their completions are recorded too.
 *
 * llm4s RAG calls are synchronous and run on the calling thread, so the events of one call are
 * collected through a thread-local: [[collect]] opens a collection on the current (blocking)
 * thread, runs the call and returns what was traced during it. Events outside a collection are
 * dropped.
 *
 * llm4s's own `withTracing` covers only the embedding client and `RAGAnswerResult` carries only
 * the answer's usage, hence this; llm4s/llm4s#2734 proposes per-query metrics from llm4s itself.
 */
final class QueryMetricsTracing extends Tracing {
  import QueryMetricsTracing._

  private val current = new ThreadLocal[ArrayBuffer[TraceEvent]]

  /** Run `call` on this thread and return its result with the events it traced. */
  def collect[A](call: => A): (A, Collected) = {
    val previous = current.get()
    val events = ArrayBuffer.empty[TraceEvent]
    current.set(events)
    try {
      val result = call
      (result, Collected(events.toVector))
    } finally {
      if (previous == null) current.remove() else current.set(previous)
    }
  }

  override def traceEvent(event: TraceEvent): Result[Unit] = {
    Option(current.get()).foreach(_ += event)
    Right(())
  }

  override def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = Right(())
  override def traceError(error: Throwable, context: String): Result[Unit] = Right(())
  override def traceCompletion(completion: Completion, model: String): Result[Unit] = Right(())
  override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
}

object QueryMetricsTracing {

  /** One traced LLM completion. */
  final case class CompletionCall(model: String, duration: Option[FiniteDuration], usage: Option[TokenUsage])

  /** What one llm4s call traced. */
  final case class Collected(events: Vector[TraceEvent]) {

    /** Embedding calls: their total duration, when every call reported one. */
    def embeddingDuration: Option[FiniteDuration] = {
      val durations = embeddingEvents.map(_.duration)
      if (durations.isEmpty || durations.exists(_.isEmpty)) None else Some(durations.flatten.reduce(_ + _))
    }

    def embeddingTokens: Option[Int] =
      Option.when(embeddingEvents.nonEmpty)(embeddingEvents.map(_.usage.totalTokens).sum)

    /** Cost the embedding client reported, for models llm4s knows the pricing of. */
    def embeddingCostUsd: Option[Double] = {
      val costs = events.collect { case c: TraceEvent.CostRecorded => c.costUsd }
      Option.when(costs.nonEmpty)(costs.sum)
    }

    /** LLM completions, in the order they were made. */
    def completions: Vector[CompletionCall] =
      events.collect { case c: TraceEvent.CompletionReceived => CompletionCall(c.model, c.duration, c.usage) }

    private def embeddingEvents: Vector[TraceEvent.EmbeddingUsageRecorded] =
      events.collect { case e: TraceEvent.EmbeddingUsageRecorded => e }
  }
}
