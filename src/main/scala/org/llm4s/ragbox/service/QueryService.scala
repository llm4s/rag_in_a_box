package org.llm4s.ragbox.service

import cats.effect.IO
import cats.syntax.all._
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.rag.{RAGAnswerResult, RAGSearchResult}
import org.llm4s.rag.permissions.{CollectionPattern, UserAuthorization}
import org.llm4s.ragbox.model._
import org.llm4s.ragbox.service.Llm4sPipeline.ChatTurn
import org.llm4s.ragbox.service.RagPipelines.blockingResult
import org.llm4s.types.Result

/**
 * Search, answers and chat replies through the llm4s pipelines, with each answer's metrics
 * (per-stage latency, token usage, cost) from what llm4s traced while answering.
 *
 * Permission-filtered search is vector-only in llm4s: fusion, keyword search, reranking and query
 * transforms do not apply there (llm4s/llm4s#2716).
 */
final class QueryService(pipelines: RagPipelines) {

  /** Search for relevant chunks (no LLM answer generation). */
  def search(query: String, topK: Option[Int]): IO[SearchResponse] =
    pipelines.withCurrent { r =>
      blockingResult(r.query(query, Some(resolveTopK(topK)))).map(toSearchResponse)
    }

  /** [[search]], filtered to what `auth` may read in the collections `collectionPattern` matches. */
  def searchWithPermissions(query: String, auth: UserAuthorization, collectionPattern: String, topK: Option[Int]): IO[SearchResponse] =
    QueryService.parsePattern(collectionPattern).flatMap { pattern =>
      pipelines.withCurrent { r =>
        blockingResult(r.queryWithPermissions(auth, pattern, query, Some(resolveTopK(topK)))).map(toSearchResponse)
      }
    }

  /**
   * Answer through llm4s `RAG.queryWithAnswer`, on the pipeline `overrides` select over the
   * runtime settings (an experiment group's or a request's; built on first use, then reused).
   */
  def queryWithAnswer(question: String, topK: Option[Int], overrides: RAGService.SettingsOverride): IO[QueryResponse] =
    pipelines.requireLlm *>
      pipelines.withOverrides(overrides)(r => measured(r.queryWithAnswer(question, Some(resolveTopK(topK)))))
        .map(toQueryResponse.tupled)

  /** [[queryWithAnswer]] with permission filtering. */
  def queryWithPermissionsAndAnswer(
    question: String,
    auth: UserAuthorization,
    collectionPattern: String,
    topK: Option[Int],
    overrides: RAGService.SettingsOverride
  ): IO[QueryResponse] =
    (pipelines.requireLlm, QueryService.parsePattern(collectionPattern)).flatMapN { (_, pattern) =>
      pipelines.withOverrides(overrides)(r =>
        measured(r.queryWithPermissionsAndAnswer(auth, pattern, question, Some(resolveTopK(topK))))
      ).map(toQueryResponse.tupled)
    }

  /**
   * Answer a chat message, with the session's earlier messages as conversation history.
   *
   * llm4s's `queryWithAnswer` takes a single question (llm4s/llm4s#2733), so ragbox composes the
   * two llm4s steps itself: retrieval through the pipeline (`RAG.query`, or
   * `RAG.queryWithPermissions` when `permissions` is given), then the answer LLM with the history,
   * the retrieved context, and the runtime system prompt and temperature (see
   * [[Llm4sPipeline.chatConversation]]).
   *
   * @param history     earlier messages, oldest first; the caller bounds how many
   * @param permissions the user's authorization and collection pattern, in permission mode
   */
  def chatAnswer(
    question: String,
    history: Seq[ChatTurn],
    permissions: Option[(UserAuthorization, String)],
    topK: Option[Int]
  ): IO[QueryResponse] =
    for {
      client <- pipelines.requireLlm
      pattern <- permissions.traverse { case (auth, p) => QueryService.parsePattern(p).map(auth -> _) }
      current = pipelines.current
      k = Some(topK.getOrElse(current.topK))
      answered <- pipelines.withCurrent { r =>
        measured {
          for {
            contexts <- pattern match {
              case Some((auth, p)) => r.queryWithPermissions(auth, p, question, k)
              case None => r.query(question, k)
            }
            conversation = Llm4sPipeline.chatConversation(current.systemPrompt, history, contexts, question)
            completion <- client.complete(conversation, CompletionOptions(temperature = current.llmTemperature))
          } yield RAGAnswerResult(answer = completion.content, question = question, contexts = contexts, usage = completion.usage)
        }
      }
    } yield toQueryResponse.tupled(answered)

  /** topK for a call: the caller's, or the runtime setting. */
  private def resolveTopK(topK: Option[Int]): Int = topK.getOrElse(pipelines.current.topK)

  /** Run a synchronous llm4s call on the blocking pool, collecting what it traced and timing it. */
  private def measured[A](call: => Result[A]): IO[(A, QueryMetricsTracing.Collected, Long)] =
    IO.blocking {
      val started = System.nanoTime()
      val (result, collected) = pipelines.metricsTracing.collect(call)
      (result, collected, System.nanoTime() - started)
    }.flatMap { case (result, collected, nanos) =>
      IO.fromEither(result.left.map(e => new RuntimeException(e.message))).map(a => (a, collected, nanos))
    }

  private def toSearchResponse(results: Seq[RAGSearchResult]): SearchResponse =
    SearchResponse(results = results.map(QueryService.toContextItem), count = results.size)

  private val toQueryResponse: (RAGAnswerResult, QueryMetricsTracing.Collected, Long) => QueryResponse =
    (result, collected, nanos) =>
      QueryResponse(
        answer = result.answer,
        contexts = result.contexts.map(QueryService.toContextItem),
        usage = result.usage.map(u => UsageInfo(
          promptTokens = u.promptTokens,
          completionTokens = u.completionTokens,
          totalTokens = u.totalTokens
        )),
        metrics = Some(queryMetrics(collected, nanos, result.contexts.size))
      )

  /**
   * A query's metrics from its trace events. The answer is the last completion; earlier ones
   * (query rewriting, LLM reranking) are part of retrieval. Search latency is the rest of the
   * query once embedding and answer generation are taken out, so it is known only when the
   * embedding provider reported its duration (llm4s traces it with the call's token usage).
   */
  private def queryMetrics(collected: QueryMetricsTracing.Collected, nanos: Long, contexts: Int): QueryMetrics = {
    def millis(d: scala.concurrent.duration.FiniteDuration): Int = d.toMillis.toInt
    val totalMs = (nanos / 1000000L).toInt
    val completions = collected.completions
    val llmMs = completions.lastOption.flatMap(_.duration).map(millis)
    val embeddingMs = collected.embeddingDuration.map(millis)
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
      embeddingLatencyMs = embeddingMs,
      searchLatencyMs = embeddingMs.map(e => math.max(0, totalMs - e - llmMs.getOrElse(0))),
      llmLatencyMs = llmMs,
      // llm4s sends every retrieved chunk to the LLM, so the two are equal
      chunksRetrieved = contexts,
      chunksUsed = contexts,
      embeddingTokens = collected.embeddingTokens,
      promptTokens = Option.when(completions.exists(_.usage.isDefined))(completions.flatMap(_.usage).map(_.promptTokens).sum),
      completionTokens = Option.when(completions.exists(_.usage.isDefined))(completions.flatMap(_.usage).map(_.completionTokens).sum),
      costUsd = Option.when(costs.nonEmpty)(costs.sum)
    )
  }
}

object QueryService {

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
