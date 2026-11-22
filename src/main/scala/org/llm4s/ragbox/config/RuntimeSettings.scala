package org.llm4s.ragbox.config

import org.llm4s.ragbox.model.RuntimeConfig

/**
 * Where the query, chat and ingest paths read the runtime-modifiable settings from.
 *
 * [[RuntimeConfigManager]] is the live implementation: what an admin changes through
 * `PUT /api/v1/config/runtime` is what the next query, chat message or ingest uses. Reads are
 * synchronous and lock-free, so they can be made on every request.
 */
trait RuntimeSettings {

  /** The settings in effect now. */
  def current: RuntimeConfig
}

object RuntimeSettings {

  /** The runtime settings an [[AppConfig]] starts with, before any runtime change. */
  def initial(config: AppConfig): RuntimeConfig =
    RuntimeConfig(
      topK = config.rag.search.topK,
      fusionStrategy = config.rag.search.fusionStrategy,
      rrfK = config.rag.search.rrfK,
      systemPrompt = config.rag.systemPrompt,
      llmTemperature = config.llm.temperature,
      chunkingStrategy = config.rag.chunking.strategy,
      chunkSize = config.rag.chunking.size,
      chunkOverlap = config.rag.chunking.overlap
    )

  /** Settings that never change: the [[AppConfig]]'s own. For tests and tools without a database. */
  def fixed(config: AppConfig): RuntimeSettings = {
    val settings = initial(config)
    new RuntimeSettings { def current: RuntimeConfig = settings }
  }

  /**
   * The largest chunk the chunker may produce for a target size, when nothing sets it: llm4s
   * `RAGConfig.withChunking(strategy, size, overlap)` uses 1.5 x the target size.
   */
  def defaultMaxSize(targetSize: Int): Int = (targetSize * 1.5).toInt
}
