package org.llm4s.ragbox.model

import java.time.Instant

/**
 * Models for Runtime Configuration API.
 *
 * Allows users to modify hot/warm settings without server restart.
 */

// ============================================================
// Runtime Configuration
// ============================================================

/**
 * Current runtime-modifiable settings.
 */
final case class RuntimeConfig(
  // Hot settings (immediate effect)
  topK: Int,
  fusionStrategy: String,
  rrfK: Int,
  systemPrompt: String,
  llmTemperature: Double,

  // Warm settings (affects new documents only)
  chunkingStrategy: String,
  chunkSize: Int,
  chunkOverlap: Int
)

/**
 * Request to update runtime configuration.
 */
final case class RuntimeConfigUpdateRequest(
  topK: Option[Int] = None,
  fusionStrategy: Option[String] = None,
  rrfK: Option[Int] = None,
  systemPrompt: Option[String] = None,
  llmTemperature: Option[Double] = None,
  chunkingStrategy: Option[String] = None,
  chunkSize: Option[Int] = None,
  chunkOverlap: Option[Int] = None
)

/**
 * Result of updating a single setting.
 */
final case class SettingUpdateResult(
  field: String,
  oldValue: String,
  newValue: String,
  changeability: String,  // "hot" or "warm"
  effectiveImmediately: Boolean
)

/**
 * Response from updating runtime configuration.
 */
final case class RuntimeConfigUpdateResponse(
  success: Boolean,
  changes: Seq[SettingUpdateResult],
  current: RuntimeConfig,
  message: String
)

/**
 * Request to validate proposed configuration changes.
 */
final case class RuntimeConfigValidateRequest(
  topK: Option[Int] = None,
  fusionStrategy: Option[String] = None,
  rrfK: Option[Int] = None,
  systemPrompt: Option[String] = None,
  llmTemperature: Option[Double] = None,
  chunkingStrategy: Option[String] = None,
  chunkSize: Option[Int] = None,
  chunkOverlap: Option[Int] = None
)

/**
 * Validation result for a single setting.
 */
final case class SettingValidation(
  field: String,
  valid: Boolean,
  currentValue: String,
  proposedValue: String,
  error: Option[String] = None,
  warning: Option[String] = None
)

/**
 * Response from validating configuration changes.
 */
final case class RuntimeConfigValidateResponse(
  valid: Boolean,
  validations: Seq[SettingValidation],
  warnings: Seq[String]
)

// ============================================================
// Configuration History
// ============================================================

/**
 * A single configuration change record.
 */
final case class ConfigChange(
  id: Long,
  field: String,
  oldValue: String,
  newValue: String,
  changedAt: Instant,
  changedBy: Option[String] = None
)

/**
 * Response containing configuration change history.
 */
final case class ConfigHistoryResponse(
  changes: Seq[ConfigChange],
  total: Int,
  page: Int,
  pageSize: Int,
  hasMore: Boolean
)

// ============================================================
// Runtime Config Response (GET)
// ============================================================

/**
 * Response containing current runtime configuration.
 */
final case class RuntimeConfigResponse(
  config: RuntimeConfig,
  lastModified: Option[Instant],
  hotSettings: Seq[String],
  warmSettings: Seq[String]
)
