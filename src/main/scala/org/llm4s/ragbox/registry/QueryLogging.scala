package org.llm4s.ragbox.registry

import cats.effect.IO
import org.llm4s.ragbox.model.{QueryConfigSnapshot, QueryResponse}

/** Logs queries: answered ones with the metrics llm4s traced while answering, and failed ones. */
object QueryLogging {

  /** @return the query log id */
  def logAnswer(
    registry: QueryLogRegistryBase,
    queryText: String,
    collectionPattern: Option[String],
    userId: Option[String],
    totalLatencyMs: Int,
    response: QueryResponse,
    experimentId: Option[String] = None,
    configSnapshot: Option[QueryConfigSnapshot] = None,
    variant: Option[String] = None
  ): IO[String] = {
    val metrics = response.metrics
    registry.logQuery(
      queryText = queryText,
      collectionPattern = collectionPattern,
      userId = userId,
      embeddingLatencyMs = metrics.flatMap(_.embeddingLatencyMs),
      searchLatencyMs = metrics.flatMap(_.searchLatencyMs),
      llmLatencyMs = metrics.flatMap(_.llmLatencyMs),
      totalLatencyMs = totalLatencyMs,
      chunksRetrieved = metrics.fold(response.contexts.size)(_.chunksRetrieved),
      chunksUsed = metrics.fold(response.contexts.size)(_.chunksUsed),
      answerTokens = response.usage.map(_.completionTokens),
      experimentId = experimentId,
      configSnapshot = configSnapshot,
      promptTokens = metrics.flatMap(_.promptTokens),
      embeddingTokens = metrics.flatMap(_.embeddingTokens),
      costUsd = metrics.flatMap(_.costUsd),
      variant = variant
    )
  }

  /**
   * Log a query that failed, so experiment results count it in the error rate.
   *
   * @return the query log id
   */
  def logFailure(
    registry: QueryLogRegistryBase,
    queryText: String,
    collectionPattern: Option[String],
    userId: Option[String],
    totalLatencyMs: Int,
    error: Throwable,
    experimentId: Option[String] = None,
    configSnapshot: Option[QueryConfigSnapshot] = None,
    variant: Option[String] = None
  ): IO[String] =
    registry.logQuery(
      queryText = queryText,
      collectionPattern = collectionPattern,
      userId = userId,
      embeddingLatencyMs = None,
      searchLatencyMs = None,
      llmLatencyMs = None,
      totalLatencyMs = totalLatencyMs,
      chunksRetrieved = 0,
      chunksUsed = 0,
      answerTokens = None,
      experimentId = experimentId,
      configSnapshot = configSnapshot,
      variant = variant,
      failed = true,
      errorMessage = Some(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    )
}
