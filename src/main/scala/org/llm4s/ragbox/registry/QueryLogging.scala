package org.llm4s.ragbox.registry

import cats.effect.IO
import org.llm4s.ragbox.model.{QueryConfigSnapshot, QueryResponse}

/** Logs an answered query with the metrics llm4s traced while answering it. */
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
    configSnapshot: Option[QueryConfigSnapshot] = None
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
      costUsd = metrics.flatMap(_.costUsd)
    )
  }
}
