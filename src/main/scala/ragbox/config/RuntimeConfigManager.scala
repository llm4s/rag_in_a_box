package ragbox.config

import cats.effect.{IO, Ref}
import ragbox.model._

import java.sql.{Connection, DriverManager, Timestamp}
import java.time.Instant
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import scala.compiletime.uninitialized

/**
 * Manages runtime-modifiable configuration.
 *
 * Provides thread-safe access to hot/warm settings that can be changed
 * without server restart. Changes are persisted to the database for
 * durability and audit trail.
 */
class RuntimeConfigManager(
  initialConfig: AppConfig,
  dbConfig: DatabaseConfig
) {

  // Mutable state for runtime settings
  private val currentConfig: AtomicReference[RuntimeConfig] = new AtomicReference(
    RuntimeConfig(
      topK = initialConfig.rag.search.topK,
      fusionStrategy = initialConfig.rag.search.fusionStrategy,
      rrfK = initialConfig.rag.search.rrfK,
      systemPrompt = initialConfig.rag.systemPrompt,
      llmTemperature = initialConfig.llm.temperature,
      chunkingStrategy = initialConfig.rag.chunking.strategy,
      chunkSize = initialConfig.rag.chunking.size,
      chunkOverlap = initialConfig.rag.chunking.overlap
    )
  )

  private var lastModified: Option[Instant] = None
  private var connection: Connection = uninitialized

  // Valid values for validation
  private val validFusionStrategies = Set("rrf", "weighted", "vector_only", "keyword_only")
  private val validChunkingStrategies = Set("simple", "sentence", "markdown", "semantic")

  /**
   * Initialize the database connection and create tables.
   */
  def initialize(): IO[Unit] = IO {
    Class.forName("org.postgresql.Driver")

    val props = new Properties()
    props.setProperty("user", dbConfig.effectiveUser)
    props.setProperty("password", dbConfig.effectivePassword)

    connection = DriverManager.getConnection(dbConfig.connectionString, props)

    val stmt = connection.createStatement()
    try {
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS config_history (
          |    id SERIAL PRIMARY KEY,
          |    field TEXT NOT NULL,
          |    old_value TEXT,
          |    new_value TEXT,
          |    changed_at TIMESTAMPTZ DEFAULT NOW(),
          |    changed_by TEXT
          |)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_config_history_field
          |    ON config_history(field)""".stripMargin
      )
      stmt.execute(
        """CREATE INDEX IF NOT EXISTS idx_config_history_changed_at
          |    ON config_history(changed_at DESC)""".stripMargin
      )
    } finally {
      stmt.close()
    }
  }

  /**
   * Get the current runtime configuration.
   */
  def get: IO[RuntimeConfigResponse] = IO {
    RuntimeConfigResponse(
      config = currentConfig.get(),
      lastModified = lastModified,
      hotSettings = Seq("topK", "fusionStrategy", "rrfK", "systemPrompt", "llmTemperature"),
      warmSettings = Seq("chunkingStrategy", "chunkSize", "chunkOverlap")
    )
  }

  /**
   * Get current topK setting.
   */
  def getTopK: Int = currentConfig.get().topK

  /**
   * Get current fusion strategy.
   */
  def getFusionStrategy: String = currentConfig.get().fusionStrategy

  /**
   * Get current RRF K setting.
   */
  def getRrfK: Int = currentConfig.get().rrfK

  /**
   * Get current system prompt.
   */
  def getSystemPrompt: String = currentConfig.get().systemPrompt

  /**
   * Get current LLM temperature.
   */
  def getLLMTemperature: Double = currentConfig.get().llmTemperature

  /**
   * Get current chunking strategy.
   */
  def getChunkingStrategy: String = currentConfig.get().chunkingStrategy

  /**
   * Get current chunk size.
   */
  def getChunkSize: Int = currentConfig.get().chunkSize

  /**
   * Get current chunk overlap.
   */
  def getChunkOverlap: Int = currentConfig.get().chunkOverlap

  /**
   * Update runtime configuration.
   */
  def update(request: RuntimeConfigUpdateRequest): IO[RuntimeConfigUpdateResponse] = IO {
    val current = currentConfig.get()
    val changes = scala.collection.mutable.ArrayBuffer.empty[SettingUpdateResult]
    val now = Instant.now()

    var updated = current

    // Update hot settings
    request.topK.foreach { v =>
      if (v != current.topK) {
        recordChange("topK", current.topK.toString, v.toString, now)
        changes += SettingUpdateResult("topK", current.topK.toString, v.toString, "hot", effectiveImmediately = true)
        updated = updated.copy(topK = v)
      }
    }

    request.fusionStrategy.foreach { v =>
      if (v != current.fusionStrategy) {
        recordChange("fusionStrategy", current.fusionStrategy, v, now)
        changes += SettingUpdateResult("fusionStrategy", current.fusionStrategy, v, "hot", effectiveImmediately = true)
        updated = updated.copy(fusionStrategy = v)
      }
    }

    request.rrfK.foreach { v =>
      if (v != current.rrfK) {
        recordChange("rrfK", current.rrfK.toString, v.toString, now)
        changes += SettingUpdateResult("rrfK", current.rrfK.toString, v.toString, "hot", effectiveImmediately = true)
        updated = updated.copy(rrfK = v)
      }
    }

    request.systemPrompt.foreach { v =>
      if (v != current.systemPrompt) {
        recordChange("systemPrompt", current.systemPrompt, v, now)
        changes += SettingUpdateResult("systemPrompt", current.systemPrompt.take(50) + "...", v.take(50) + "...", "hot", effectiveImmediately = true)
        updated = updated.copy(systemPrompt = v)
      }
    }

    request.llmTemperature.foreach { v =>
      if (v != current.llmTemperature) {
        recordChange("llmTemperature", current.llmTemperature.toString, v.toString, now)
        changes += SettingUpdateResult("llmTemperature", current.llmTemperature.toString, v.toString, "hot", effectiveImmediately = true)
        updated = updated.copy(llmTemperature = v)
      }
    }

    // Update warm settings
    request.chunkingStrategy.foreach { v =>
      if (v != current.chunkingStrategy) {
        recordChange("chunkingStrategy", current.chunkingStrategy, v, now)
        changes += SettingUpdateResult("chunkingStrategy", current.chunkingStrategy, v, "warm", effectiveImmediately = false)
        updated = updated.copy(chunkingStrategy = v)
      }
    }

    request.chunkSize.foreach { v =>
      if (v != current.chunkSize) {
        recordChange("chunkSize", current.chunkSize.toString, v.toString, now)
        changes += SettingUpdateResult("chunkSize", current.chunkSize.toString, v.toString, "warm", effectiveImmediately = false)
        updated = updated.copy(chunkSize = v)
      }
    }

    request.chunkOverlap.foreach { v =>
      if (v != current.chunkOverlap) {
        recordChange("chunkOverlap", current.chunkOverlap.toString, v.toString, now)
        changes += SettingUpdateResult("chunkOverlap", current.chunkOverlap.toString, v.toString, "warm", effectiveImmediately = false)
        updated = updated.copy(chunkOverlap = v)
      }
    }

    // Apply changes
    if (changes.nonEmpty) {
      currentConfig.set(updated)
      lastModified = Some(now)
    }

    val hotChanges = changes.count(_.changeability == "hot")
    val warmChanges = changes.count(_.changeability == "warm")
    val message = if (changes.isEmpty) {
      "No changes applied"
    } else {
      val parts = Seq(
        if (hotChanges > 0) Some(s"$hotChanges hot setting(s) updated (immediate effect)") else None,
        if (warmChanges > 0) Some(s"$warmChanges warm setting(s) updated (affects new documents)") else None
      ).flatten
      parts.mkString(", ")
    }

    RuntimeConfigUpdateResponse(
      success = true,
      changes = changes.toSeq,
      current = updated,
      message = message
    )
  }

  /**
   * Validate proposed configuration changes.
   */
  def validate(request: RuntimeConfigValidateRequest): IO[RuntimeConfigValidateResponse] = IO {
    val current = currentConfig.get()
    val validations = scala.collection.mutable.ArrayBuffer.empty[SettingValidation]
    val warnings = scala.collection.mutable.ArrayBuffer.empty[String]

    request.topK.foreach { v =>
      val (valid, error) = if (v < 1) (false, Some("topK must be at least 1"))
        else if (v > 100) (false, Some("topK must be at most 100"))
        else (true, None)
      validations += SettingValidation("topK", valid, current.topK.toString, v.toString, error)
    }

    request.fusionStrategy.foreach { v =>
      val (valid, error) = if (!validFusionStrategies.contains(v))
        (false, Some(s"Invalid fusion strategy. Valid values: ${validFusionStrategies.mkString(", ")}"))
      else (true, None)
      validations += SettingValidation("fusionStrategy", valid, current.fusionStrategy, v, error)
    }

    request.rrfK.foreach { v =>
      val (valid, error) = if (v < 1) (false, Some("rrfK must be at least 1"))
        else if (v > 1000) (false, Some("rrfK must be at most 1000"))
        else (true, None)
      validations += SettingValidation("rrfK", valid, current.rrfK.toString, v.toString, error)
    }

    request.llmTemperature.foreach { v =>
      val (valid, error, warning) = if (v < 0) (false, Some("temperature must be non-negative"), None)
        else if (v > 2) (false, Some("temperature must be at most 2.0"), None)
        else if (v > 1) (true, None, Some("High temperature (>1.0) may produce inconsistent results"))
        else (true, None, None)
      validations += SettingValidation("llmTemperature", valid, current.llmTemperature.toString, v.toString, error, warning)
      warning.foreach(warnings += _)
    }

    request.chunkingStrategy.foreach { v =>
      val (valid, error) = if (!validChunkingStrategies.contains(v))
        (false, Some(s"Invalid chunking strategy. Valid values: ${validChunkingStrategies.mkString(", ")}"))
      else (true, None)
      validations += SettingValidation("chunkingStrategy", valid, current.chunkingStrategy, v, error)
      if (valid && v != current.chunkingStrategy) {
        warnings += "Changing chunking strategy only affects newly ingested documents"
      }
    }

    request.chunkSize.foreach { v =>
      val (valid, error) = if (v < 100) (false, Some("chunkSize must be at least 100"))
        else if (v > 10000) (false, Some("chunkSize must be at most 10000"))
        else (true, None)
      validations += SettingValidation("chunkSize", valid, current.chunkSize.toString, v.toString, error)
      if (valid && v != current.chunkSize) {
        warnings += "Changing chunk size only affects newly ingested documents"
      }
    }

    request.chunkOverlap.foreach { v =>
      val proposedSize = request.chunkSize.getOrElse(current.chunkSize)
      val (valid, error) = if (v < 0) (false, Some("chunkOverlap must be non-negative"))
        else if (v >= proposedSize) (false, Some("chunkOverlap must be less than chunkSize"))
        else (true, None)
      validations += SettingValidation("chunkOverlap", valid, current.chunkOverlap.toString, v.toString, error)
    }

    RuntimeConfigValidateResponse(
      valid = validations.forall(_.valid),
      validations = validations.toSeq,
      warnings = warnings.toSeq
    )
  }

  /**
   * Get configuration change history.
   */
  def getHistory(page: Int, pageSize: Int): IO[ConfigHistoryResponse] = IO {
    val offset = (page - 1) * pageSize

    // Get total count
    val countStmt = connection.createStatement()
    val total = try {
      val rs = countStmt.executeQuery("SELECT COUNT(*) FROM config_history")
      if (rs.next()) rs.getInt(1) else 0
    } finally {
      countStmt.close()
    }

    // Get page of changes
    val sql =
      """SELECT id, field, old_value, new_value, changed_at, changed_by
        |FROM config_history
        |ORDER BY changed_at DESC
        |LIMIT ? OFFSET ?""".stripMargin
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setInt(1, pageSize)
      stmt.setInt(2, offset)
      val rs = stmt.executeQuery()
      val changes = scala.collection.mutable.ArrayBuffer.empty[ConfigChange]
      while (rs.next()) {
        changes += ConfigChange(
          id = rs.getLong("id"),
          field = rs.getString("field"),
          oldValue = Option(rs.getString("old_value")).getOrElse(""),
          newValue = Option(rs.getString("new_value")).getOrElse(""),
          changedAt = rs.getTimestamp("changed_at").toInstant,
          changedBy = Option(rs.getString("changed_by"))
        )
      }
      ConfigHistoryResponse(
        changes = changes.toSeq,
        total = total,
        page = page,
        pageSize = pageSize,
        hasMore = offset + changes.size < total
      )
    } finally {
      stmt.close()
    }
  }

  /**
   * Record a configuration change to the database.
   */
  private def recordChange(field: String, oldValue: String, newValue: String, changedAt: Instant): Unit = {
    val sql = "INSERT INTO config_history (field, old_value, new_value, changed_at) VALUES (?, ?, ?, ?)"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, field)
      stmt.setString(2, oldValue)
      stmt.setString(3, newValue)
      stmt.setTimestamp(4, Timestamp.from(changedAt))
      stmt.executeUpdate()
    } finally {
      stmt.close()
    }
  }

  /**
   * Close database connection.
   */
  def close(): IO[Unit] = IO {
    if (connection != null && !connection.isClosed) {
      connection.close()
    }
  }
}

object RuntimeConfigManager {

  /**
   * Create and initialize a RuntimeConfigManager.
   */
  def apply(config: AppConfig): IO[RuntimeConfigManager] = {
    val manager = new RuntimeConfigManager(config, config.database)
    manager.initialize().as(manager)
  }
}
