package org.llm4s.ragbox.service

import cats.effect.IO
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._
import fs2.Stream
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{CompletionOptions, Message, StreamedChunk}
import org.llm4s.rag.{RAG, RAGAnswerResult, RAGSearchResult}
import org.llm4s.rag.permissions.{CollectionPattern, UserAuthorization}
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.service.Llm4sPipeline.ChatTurn
import org.llm4s.ragbox.service.RagPipelines.blockingResult
import org.llm4s.types.Result

/**
 * Search, answers and chat replies through the llm4s pipelines, with each answer's metrics
 * (per-stage latency, token usage, cost) from what llm4s traced while answering.
 *
 * Every answer is llm4s's: `RAG.queryWithAnswer` (or `queryWithPermissionsAndAnswer` in permission
 * mode) with the request's completion options - the temperature - and, for chat, the session's
 * earlier messages as `history`. A streamed answer is the `...Streaming` form of the same call:
 * llm4s hands over the contexts before the model is called, then each piece of the answer as the
 * provider sends it.
 */
final class QueryService(pipelines: RagPipelines) {
  import QueryService._

  /** Search for relevant chunks (no LLM answer generation). */
  def search(query: String, topK: Option[Int]): IO[SearchResponse] =
    pipelines.withCurrent { r =>
      blockingResult(r.query(query, Some(resolveTopK(topK)))).map(toSearchResponse)
    }

  /** [[search]], filtered to what `auth` may read in the collections `collectionPattern` matches. */
  def searchWithPermissions(query: String, auth: UserAuthorization, collectionPattern: String, topK: Option[Int]): IO[SearchResponse] =
    parsePattern(collectionPattern).flatMap { pattern =>
      pipelines.withCurrent { r =>
        blockingResult(r.queryWithPermissions(auth, pattern, query, Some(resolveTopK(topK)))).map(toSearchResponse)
      }
    }

  /**
   * Answer `question` on the pipeline `overrides` select over the runtime settings (an experiment
   * group's or a request's; built on first use, then reused).
   *
   * @param permissions the caller's authorization and collection pattern, in permission mode
   * @param history     a chat session's earlier messages, oldest first; the caller bounds how many
   */
  def answer(
    question: String,
    permissions: Option[(UserAuthorization, String)],
    history: Seq[ChatTurn],
    topK: Option[Int],
    overrides: RAGService.SettingsOverride
  ): IO[QueryResponse] =
    prepare(question, permissions, history, topK, overrides).flatMap(ask => run(ask, None))

  /**
   * [[answer]], streamed: the contexts the answer is generated from, then each piece of the answer
   * as the provider sends it, then the whole response. A failure ends the stream with the error,
   * after the events already emitted.
   *
   * llm4s calls back on the thread that runs the query, so the events cross to the stream through
   * a queue. The query runs interruptibly: when the consumer stops (a client that disconnected),
   * the model call is abandoned rather than generated to the end.
   */
  def answerStream(
    question: String,
    permissions: Option[(UserAuthorization, String)],
    history: Seq[ChatTurn],
    topK: Option[Int],
    overrides: RAGService.SettingsOverride
  ): Stream[IO, AnswerEvent] =
    for {
      ask <- Stream.eval(prepare(question, permissions, history, topK, overrides))
      dispatcher <- Stream.resource(Dispatcher.sequential[IO])
      queue <- Stream.eval(Queue.unbounded[IO, Option[Either[Throwable, AnswerEvent]]])
      emit = (event: AnswerEvent) => dispatcher.unsafeRunSync(queue.offer(Some(Right(event))))
      sink = Sink(
        onContexts = contexts => emit(AnswerEvent.Contexts(contexts.map(toContextItem))),
        onChunk = chunk => chunk.contentDelta.filter(_.nonEmpty).foreach(text => emit(AnswerEvent.Delta(text)))
      )
      produce = run(ask, Some(sink)).attempt
        .flatMap(result => queue.offer(Some(result.map(AnswerEvent.Completed(_)))) *> queue.offer(None))
      event <- Stream.fromQueueNoneTerminated(queue).rethrow.concurrently(Stream.eval(produce))
    } yield event

  /** What one answer needs, resolved before the pipeline is leased. */
  private final case class Ask(
    question: String,
    permissions: Option[(UserAuthorization, CollectionPattern)],
    history: Seq[Message],
    topK: Int,
    overrides: RAGService.SettingsOverride,
    options: CompletionOptions,
    systemPrompt: String
  )

  private def prepare(
    question: String,
    permissions: Option[(UserAuthorization, String)],
    history: Seq[ChatTurn],
    topK: Option[Int],
    overrides: RAGService.SettingsOverride
  ): IO[Ask] =
    for {
      pattern <- permissions.traverse { case (auth, p) => parsePattern(p).map(auth -> _) }
      settings <- IO.fromEither(pipelines.settingsFor(overrides))
      current = pipelines.current
    } yield Ask(
      question = question,
      permissions = pattern,
      history = Llm4sPipeline.history(history),
      topK = topK.getOrElse(current.topK),
      overrides = overrides,
      options = CompletionOptions().withTemperature(overrides.llmTemperature.getOrElse(current.llmTemperature)),
      systemPrompt = settings.systemPrompt
    )

  /** Where a streamed answer's contexts and pieces go (called on the thread running the query). */
  private final case class Sink(onContexts: Seq[RAGSearchResult] => Unit, onChunk: StreamedChunk => Unit)

  /** An answer and how many chunks retrieval returned for it. */
  private final case class Answered(result: RAGAnswerResult, retrieved: Int)

  /**
   * Run `ask` on its pipeline, on the blocking pool (interruptibly for a stream, see
   * [[answerStream]]), collecting what llm4s traced and timing it.
   */
  private def run(ask: Ask, sink: Option[Sink]): IO[QueryResponse] =
    pipelines.requireLlm.flatMap { client =>
      pipelines.withOverrides(ask.overrides) { r =>
        def call() = {
          val started = System.nanoTime()
          val (result, collected) = pipelines.metricsTracing.collect(
            ask.overrides.similarityThreshold match {
              case None => llm4sAnswer(r, ask, sink)
              case Some(threshold) => answerAboveThreshold(r, client, ask, sink, threshold)
            }
          )
          (result, collected, System.nanoTime() - started)
        }
        (if (sink.isDefined) IO.interruptible(call()) else IO.blocking(call())).flatMap { case (result, collected, nanos) =>
          IO.fromEither(result.left.map(e => new RuntimeException(e.message))).map(toQueryResponse(_, collected, nanos))
        }
      }
    }

  /** The answer as llm4s generates it: one call that retrieves, then answers from every chunk. */
  private def llm4sAnswer(r: RAG, ask: Ask, sink: Option[Sink]): Result[Answered] = {
    val k = Some(ask.topK)
    val result = (ask.permissions, sink) match {
      case (None, None) => r.queryWithAnswer(ask.question, k, ask.options, ask.history)
      case (None, Some(s)) => r.queryWithAnswerStreaming(ask.question, k, ask.options, ask.history)(s.onContexts, s.onChunk)
      case (Some((auth, pattern)), None) =>
        r.queryWithPermissionsAndAnswer(auth, pattern, ask.question, k, ask.options, ask.history)
      case (Some((auth, pattern)), Some(s)) =>
        r.queryWithPermissionsAndAnswerStreaming(auth, pattern, ask.question, k, ask.options, ask.history)(s.onContexts, s.onChunk)
    }
    result.map(answer => Answered(answer, answer.metrics.fold(answer.contexts.size)(_.candidatesRetrieved)))
  }

  /**
   * An answer from the retrieved chunks whose similarity reaches `threshold` only.
   *
   * llm4s's `queryWithAnswer` retrieves and answers in one call, with no hook between the two, so
   * with a threshold ragbox composes the two llm4s steps itself: retrieve, keep the chunks
   * [[QueryService.aboveThreshold]], then the answer LLM with the prompt llm4s builds (see
   * [[Llm4sPipeline.answerConversation]]) and the same completion options. Chunks below the
   * threshold are neither sent to the LLM nor returned.
   */
  private def answerAboveThreshold(r: RAG, client: LLMClient, ask: Ask, sink: Option[Sink], threshold: Double): Result[Answered] =
    for {
      retrieved <- ask.permissions match {
        case Some((auth, pattern)) => r.queryWithPermissions(auth, pattern, ask.question, Some(ask.topK))
        case None => r.query(ask.question, Some(ask.topK))
      }
      contexts = aboveThreshold(retrieved, threshold)
      conversation = Llm4sPipeline.answerConversation(ask.systemPrompt, ask.history, contexts, ask.question)
      completion <- sink match {
        case None => client.complete(conversation, ask.options)
        case Some(s) =>
          s.onContexts(contexts)
          client.streamComplete(conversation, ask.options)(s.onChunk)
      }
    } yield Answered(
      RAGAnswerResult(answer = completion.content, question = ask.question, contexts = contexts, usage = completion.usage),
      retrieved.size
    )

  /** topK for a call: the caller's, or the runtime setting. */
  private def resolveTopK(topK: Option[Int]): Int = topK.getOrElse(pipelines.current.topK)

  private def toSearchResponse(results: Seq[RAGSearchResult]): SearchResponse =
    SearchResponse(results = results.map(toContextItem), count = results.size)

  private def toQueryResponse(answered: Answered, collected: QueryMetricsTracing.Collected, nanos: Long): QueryResponse = {
    val result = answered.result
    QueryResponse(
      answer = result.answer,
      contexts = result.contexts.map(toContextItem),
      usage = result.usage.map(u => UsageInfo(
        promptTokens = u.promptTokens,
        completionTokens = u.completionTokens,
        totalTokens = u.totalTokens
      )),
      metrics = Some(queryMetrics(collected, nanos, answered.retrieved, result.contexts.size))
    )
  }

  /**
   * A query's metrics from its trace events.
   *
   * The stage latencies are the ones llm4s measured (`RAGOperationCompleted.stageDurations`):
   * `embedding`, the query's embedding; search, everything else of retrieval (`rewrite`, `search`
   * and `rerank`); and `answer`, or the answer completion's own duration when ragbox made that
   * call itself (an answer with a similarity threshold). Tokens and cost are totals over every
   * model call of the query: the answer's and, in retrieval, query rewriting's and LLM
   * reranking's.
   */
  private def queryMetrics(collected: QueryMetricsTracing.Collected, nanos: Long, retrieved: Int, used: Int): QueryMetrics = {
    def millis(d: scala.concurrent.duration.FiniteDuration): Int = d.toMillis.toInt
    val stages = collected.stageDurations
    val completions = collected.completions
    val searchStages = Seq("rewrite", "search", "rerank").flatMap(stages.get)
    val llmCost = completions.flatMap { c =>
      for {
        usage <- c.usage
        registry <- pipelines.modelRegistry.toOption
        meta <- registry.lookup(c.model).toOption
        cost <- meta.pricing.estimateCost(usage.promptTokens, usage.completionTokens)
      } yield cost
    }
    val costs = llmCost ++ collected.embeddingCostUsd.toSeq
    QueryMetrics(
      embeddingLatencyMs = stages.get("embedding").map(millis),
      searchLatencyMs = Option.when(searchStages.nonEmpty)(millis(searchStages.reduce(_ + _))),
      llmLatencyMs = stages.get("answer").orElse(completions.lastOption.flatMap(_.duration)).map(millis),
      chunksRetrieved = retrieved,
      chunksUsed = used,
      embeddingTokens = collected.embeddingTokens,
      promptTokens = Option.when(completions.exists(_.usage.isDefined))(completions.flatMap(_.usage).map(_.promptTokens).sum),
      completionTokens = Option.when(completions.exists(_.usage.isDefined))(completions.flatMap(_.usage).map(_.completionTokens).sum),
      costUsd = Option.when(costs.nonEmpty)(costs.sum)
    )
  }
}

object QueryService {

  /**
   * The results whose vector similarity is at least `threshold`.
   *
   * The threshold is compared with the vector (cosine) similarity llm4s reports for each result,
   * 0 to 1 - not with `score`, whose scale depends on the fusion strategy (with RRF it is a rank
   * score of about 1/(k + rank)). A result found by keyword search only has no similarity and is
   * kept: the threshold filters what vector search matched, not lexical matches.
   */
  def aboveThreshold(results: Seq[RAGSearchResult], threshold: Double): Seq[RAGSearchResult] =
    results.filter(_.vectorScore.forall(_ >= threshold))

  def parsePattern(pattern: String): IO[CollectionPattern] =
    IO.fromEither(
      CollectionPattern.parse(pattern).left.map(e => InvalidRequestError(s"Invalid collection pattern '$pattern': ${e.message}"))
    )

  def toContextItem(result: RAGSearchResult): ContextItem =
    ContextItem(
      content = result.content,
      score = result.score,
      metadata = result.metadata,
      documentId = result.metadata.get("docId"),
      chunkIndex = result.metadata.get("chunkIndex").flatMap(s => scala.util.Try(s.toInt).toOption)
    )
}

/** One event of a streamed answer, in order: [[AnswerEvent.Contexts]] once, [[AnswerEvent.Delta]]s, [[AnswerEvent.Completed]]. */
sealed trait AnswerEvent

object AnswerEvent {

  /** The contexts the answer is generated from, before the model is called. */
  final case class Contexts(contexts: Seq[ContextItem]) extends AnswerEvent

  /** The next piece of the answer's text. */
  final case class Delta(text: String) extends AnswerEvent

  /** The whole answer, with its contexts, usage and metrics; the last event. */
  final case class Completed(response: QueryResponse) extends AnswerEvent
}
