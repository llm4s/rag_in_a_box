package org.llm4s.ragbox.model

import io.circe.Codec

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
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * Metadata about a configuration item (`GET /api/v1/config/runtime/meta`).
 *
 * @param `type` `hot`, `warm` or `cold`
 */
final case class ConfigMetaItem(
  key: String,
  value: String,
  `type`: String,
  description: String,
  requiresRestart: Boolean
) derives Codec.AsObject

/**
 * Result of updating a single setting.
 *
 * @param effectiveImmediately whether the next request the setting applies to uses the new value
 *                             (no restart, no rebuild to wait for)
 * @param note                 where the setting does not apply, when there is such a case (for
 *                             example `llmTemperature` on `/query` answers)
 */
final case class SettingUpdateResult(
  field: String,
  oldValue: String,
  newValue: String,
  changeability: String,  // "hot" or "warm"
  effectiveImmediately: Boolean,
  note: Option[String] = None
) derives Codec.AsObject

/**
 * Response from updating runtime configuration.
 */
final case class RuntimeConfigUpdateResponse(
  success: Boolean,
  changes: Seq[SettingUpdateResult],
  current: RuntimeConfig,
  message: String
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * Response from validating configuration changes.
 */
final case class RuntimeConfigValidateResponse(
  valid: Boolean,
  validations: Seq[SettingValidation],
  warnings: Seq[String]
) derives Codec.AsObject

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
) derives Codec.AsObject

/**
 * Response containing configuration change history.
 */
final case class ConfigHistoryResponse(
  changes: Seq[ConfigChange],
  total: Int,
  page: Int,
  pageSize: Int,
  hasMore: Boolean
) derives Codec.AsObject

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
) derives Codec.AsObject
