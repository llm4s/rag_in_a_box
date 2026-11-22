package org.llm4s.ragbox.registry

import cats.effect.IO
import org.llm4s.ragbox.model._

/**
 * Trait for collection-level chunking configuration operations.
 *
 * Manages per-collection chunking settings with optional file-type overrides.
 * Provides configuration resolution that merges collection settings with defaults.
 */
trait CollectionConfigRegistry {

  /**
   * Get the chunking configuration for a specific collection.
   *
   * @param collection Collection name
   * @return The collection config if one exists
   */
  def get(collection: String): IO[Option[CollectionChunkingConfig]]

  /**
   * Save or update a collection's chunking configuration.
   *
   * @param config The collection configuration to save
   */
  def put(config: CollectionChunkingConfig): IO[Unit]

  /**
   * Delete a collection's custom configuration (reverts to defaults).
   *
   * @param collection Collection name
   * @return true if config was deleted, false if it didn't exist
   */
  def delete(collection: String): IO[Boolean]

  /**
   * List all collections with custom configurations.
   *
   * @return Sequence of collection names with custom configs
   */
  def listConfigured(): IO[Seq[String]]

  /**
   * Get all collection configurations.
   *
   * @return All stored collection configs
   */
  def getAll(): IO[Seq[CollectionChunkingConfig]]

  /**
   * Get the effective configuration for a file in a collection.
   *
   * Resolution order:
   * 1. File extension override (if filename provided and extension matches)
   * 2. Collection-level config
   * 3. Default settings
   *
   * @param collection Collection name
   * @param filename Optional filename to check for file-type override
   * @param defaultStrategy Default strategy from runtime config
   * @param defaultTargetSize Default target size from runtime config
   * @param defaultMaxSize Default max size from runtime config
   * @param defaultOverlap Default overlap from runtime config
   * @return Effective configuration with source annotation
   */
  def getEffective(
    collection: String,
    filename: Option[String],
    defaultStrategy: String,
    defaultTargetSize: Int,
    defaultMaxSize: Int,
    defaultOverlap: Int
  ): IO[EffectiveCollectionConfig]

  /**
   * Initialize the registry (create tables if needed).
   */
  def initialize(): IO[Unit]

  /**
   * Close any resources.
   */
  def close(): IO[Unit]
}

object CollectionConfigRegistry {

  /**
   * Resolve the effective configuration for a file in a collection from the collection's stored
   * config (if any). Pure, so callers that already hold every config (such as the list endpoint)
   * resolve without a query per collection.
   *
   * Resolution order: file extension override, then collection-level config, then defaults.
   */
  def resolveEffective(
    maybeConfig: Option[CollectionChunkingConfig],
    filename: Option[String],
    defaultStrategy: String,
    defaultTargetSize: Int,
    defaultMaxSize: Int,
    defaultOverlap: Int
  ): EffectiveCollectionConfig =
    maybeConfig match {
      case None =>
        EffectiveCollectionConfig(
          strategy = defaultStrategy,
          targetSize = defaultTargetSize,
          maxSize = defaultMaxSize,
          overlap = defaultOverlap,
          source = "default"
        )

      case Some(config) =>
        val fileExtension = filename.flatMap(extractExtension)
        fileExtension.flatMap(ext => config.fileTypeStrategies.get(ext)) match {
          case Some(strategy) =>
            EffectiveCollectionConfig(
              strategy = strategy,
              targetSize = config.targetSize.getOrElse(defaultTargetSize),
              maxSize = config.maxSize.getOrElse(defaultMaxSize),
              overlap = config.overlap.getOrElse(defaultOverlap),
              source = "file-type",
              appliedFileTypeOverride = fileExtension
            )
          case None =>
            EffectiveCollectionConfig(
              strategy = config.strategy.getOrElse(defaultStrategy),
              targetSize = config.targetSize.getOrElse(defaultTargetSize),
              maxSize = config.maxSize.getOrElse(defaultMaxSize),
              overlap = config.overlap.getOrElse(defaultOverlap),
              source = "collection"
            )
        }
    }

  /** File extension of `filename` (lower-cased, with leading dot). */
  private def extractExtension(filename: String): Option[String] = {
    val lastDot = filename.lastIndexOf('.')
    if (lastDot > 0 && lastDot < filename.length - 1) Some(filename.substring(lastDot).toLowerCase)
    else None
  }
}
