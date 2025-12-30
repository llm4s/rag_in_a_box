package ragbox.registry

import cats.effect.IO
import ragbox.model._

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
