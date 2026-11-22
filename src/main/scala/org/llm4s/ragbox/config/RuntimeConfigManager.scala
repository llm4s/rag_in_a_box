package org.llm4s.ragbox.config

import cats.effect.IO
import cats.effect.std.Mutex
import org.llm4s.ragbox.db.ConnectionPool
import org.llm4s.ragbox.model._

import java.sql.{Connection, Timestamp}
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * Manages runtime-modifiable configuration.
 *
 * Provides thread-safe access to hot/warm settings that can be changed without server restart.
 * `RAGService` reads [[current]] on every query, chat message and ingest, so a change applies to
 * the next request it concerns (see [[RuntimeConfigManager.settingNote]] for the exceptions).
 *
 * Every setting changed at runtime is kept in the `runtime_config` table (one row per setting)
 * and applied over the configuration file's value at startup, so changes survive a restart.
 * Setting a value back to the configuration file's value removes its row: the setting then
 * follows the configuration file again. `config_history` keeps the audit trail.
 *
 * Reads are lock-free (an `AtomicReference`, so hot-path getters stay synchronous). Updates are
 * serialised by a mutex: each one computes its changes against the current config, writes the
 * history and stored values in one transaction and only then publishes the new config. Two
 * concurrent updates therefore never lose each other's changes, and what is stored always matches
 * the published config (a failed write changes nothing).
 */
class RuntimeConfigManager private (
  initialConfig: AppConfig,
  dataSource: DataSource,
  updateLock: Mutex[IO],
  permissionMode: Boolean
) extends RuntimeSettings {
  import RuntimeConfigManager._

  /** The configuration file's settings: what a setting without a stored runtime value uses. */
  private val fileConfig: RuntimeConfig = RuntimeSettings.initial(initialConfig)

  private val currentConfig: AtomicReference[RuntimeConfig] = new AtomicReference(fileConfig)

  @volatile private var lastModified: Option[Instant] = None

  override def current: RuntimeConfig = currentConfig.get()

  /**
   * Apply the stored runtime settings. A stored value that no longer parses or validates (the
   * valid range changed, say) is skipped with a warning, and the configuration file's value is used.
   */
  private def loadStored(): IO[Unit] =
    ConnectionPool.withConnection(dataSource) { connection =>
      val stmt = connection.createStatement()
      try {
        val rs = stmt.executeQuery("SELECT field, value, updated_at FROM runtime_config")
        val rows = scala.collection.mutable.ArrayBuffer.empty[(String, String, Instant)]
        while (rs.next()) rows += ((rs.getString("field"), rs.getString("value"), rs.getTimestamp("updated_at").toInstant))
        rows.toSeq
      } finally {
        stmt.close()
      }
    }.flatMap { rows =>
      val (request, unparsed) = storedRequest(rows.map(r => r._1 -> r._2))
      val invalid = validateAgainst(fileConfig, request).validations.filterNot(_.valid).map(_.field).toSet
      val (loaded, applied) = applyRequest(fileConfig, withoutFields(request, invalid))
      IO {
        (unparsed ++ invalid).foreach { field =>
          logger.warn(s"Ignoring stored runtime setting '$field': not a valid value; using the configuration file's value")
        }
        currentConfig.set(loaded)
        lastModified = rows.map(_._3).maxOption
        if (applied.nonEmpty) logger.info(s"Runtime settings restored from the database: ${applied.map(_.field).mkString(", ")}")
      }
    }

  /**
   * Get the current runtime configuration.
   */
  def get: IO[RuntimeConfigResponse] = IO {
    RuntimeConfigResponse(
      config = currentConfig.get(),
      lastModified = lastModified,
      hotSettings = HotSettings,
      warmSettings = WarmSettings
    )
  }

  /** Where `field` does not apply in this deployment, if anywhere. */
  def noteFor(field: String): Option[String] = settingNote(field, permissionMode)

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
   * Update runtime configuration: record and store the changed settings, then publish them.
   */
  def update(request: RuntimeConfigUpdateRequest): IO[RuntimeConfigUpdateResponse] =
    updateLock.lock.surround {
      for {
        now <- IO.realTimeInstant
        current <- IO(currentConfig.get())
        (updated, changes) = applyRequest(current, request)
        _ <- IO.whenA(changes.nonEmpty)(
          ConnectionPool.withTransaction(dataSource) { connection =>
            changes.foreach { c =>
              recordChange(connection, c.field, c.oldValue, c.newValue, now)
              storeValue(connection, c.field, c.newValue, now)
            }
          } *> IO {
            currentConfig.set(updated)
            lastModified = Some(now)
          }
        )
      } yield updateResponse(updated, changes.map(c => resultFor(c, permissionMode)))
    }

  /**
   * Validate proposed configuration changes.
   */
  def validate(request: RuntimeConfigUpdateRequest): IO[RuntimeConfigValidateResponse] =
    IO(validateAgainst(currentConfig.get(), request))

  /**
   * Get configuration change history.
   */
  def getHistory(page: Int, pageSize: Int): IO[ConfigHistoryResponse] = ConnectionPool.withConnection(dataSource) { connection =>
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
  private def recordChange(connection: Connection, field: String, oldValue: String, newValue: String, changedAt: Instant): Unit = {
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
   * Keep `field`'s runtime value, or forget it when it is the configuration file's value again
   * (the setting then follows the configuration file).
   */
  private def storeValue(connection: Connection, field: String, value: String, changedAt: Instant): Unit =
    if (storedValue(fileConfig, field).contains(value)) {
      val stmt = connection.prepareStatement("DELETE FROM runtime_config WHERE field = ?")
      try {
        stmt.setString(1, field)
        stmt.executeUpdate()
      } finally {
        stmt.close()
      }
    } else {
      val stmt = connection.prepareStatement(
        """INSERT INTO runtime_config (field, value, updated_at) VALUES (?, ?, ?)
          |ON CONFLICT (field) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at""".stripMargin
      )
      try {
        stmt.setString(1, field)
        stmt.setString(2, value)
        stmt.setTimestamp(3, Timestamp.from(changedAt))
        stmt.executeUpdate()
      } finally {
        stmt.close()
      }
    }

  /**
   * Nothing to close: the connection pool is owned by the caller.
   */
  def close(): IO[Unit] = IO.unit
}

object RuntimeConfigManager {

  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[RuntimeConfigManager])

  /** Settings queries and chat messages read (each one's note says where it does not apply). */
  val HotSettings: Seq[String] = Seq("topK", "fusionStrategy", "rrfK", "systemPrompt", "llmTemperature")

  /** Settings ingestion reads: they apply to documents ingested from then on. */
  val WarmSettings: Seq[String] = Seq("chunkingStrategy", "chunkSize", "chunkOverlap")

  private val validFusionStrategies = Set("rrf", "weighted", "vector_only", "keyword_only")
  private val validChunkingStrategies = Set("simple", "sentence", "markdown", "semantic")

  /** What llm4s 0.6.0 does with the answer temperature of `/query` (llm4s/llm4s#2715). */
  val TemperatureNote: String =
    "Applies to chat answers. /query answers are generated by llm4s RAG.queryWithAnswer, which uses " +
      "temperature 0.0 in llm4s 0.6.0 (llm4s/llm4s#2715, fixed upstream for the next release)."

  /** Why fusion settings do nothing in permission mode (llm4s/llm4s#2716). */
  val PermissionModeFusionNote: String =
    "No effect in permission mode: llm4s permission-filtered search is vector-only (llm4s/llm4s#2716)."

  /** What a chunking change applies to. */
  val ChunkingNote: String =
    "Applies to documents ingested from now on, except in collections whose chunking config sets it. " +
      "Documents already indexed keep their chunks until they are re-ingested."

  /** Where `field` does not apply, if anywhere. */
  def settingNote(field: String, permissionMode: Boolean): Option[String] = field match {
    case "llmTemperature" => Some(TemperatureNote)
    case "fusionStrategy" | "rrfK" if permissionMode => Some(PermissionModeFusionNote)
    case f if WarmSettings.contains(f) => Some(ChunkingNote)
    case _ => None
  }

  /** One changed setting: its history row (and stored value), and the values the response shows. */
  private[config] final case class Change(field: String, oldValue: String, newValue: String, shownOld: String, shownNew: String)

  private def change(field: String, oldValue: String, newValue: String): Change =
    Change(field, oldValue, newValue, oldValue, newValue)

  /**
   * The update response entry for a change. Every setting is read by the next request it applies
   * to; fusion in permission mode is the exception, as it has no effect there at all.
   */
  private def resultFor(change: Change, permissionMode: Boolean): SettingUpdateResult = {
    val noEffect = permissionMode && (change.field == "fusionStrategy" || change.field == "rrfK")
    SettingUpdateResult(
      field = change.field,
      oldValue = change.shownOld,
      newValue = change.shownNew,
      changeability = if (WarmSettings.contains(change.field)) "warm" else "hot",
      effectiveImmediately = !noEffect,
      note = settingNote(change.field, permissionMode)
    )
  }

  /**
   * Apply `request` to `current`: the resulting config and the settings that actually changed.
   * Pure, so the update can be computed inside the update lock without side effects.
   */
  private[config] def applyRequest(current: RuntimeConfig, request: RuntimeConfigUpdateRequest): (RuntimeConfig, Seq[Change]) = {
    var updated = current
    val changes = Vector.newBuilder[Change]

    request.topK.filter(_ != current.topK).foreach { v =>
      changes += change("topK", current.topK.toString, v.toString)
      updated = updated.copy(topK = v)
    }
    request.fusionStrategy.filter(_ != current.fusionStrategy).foreach { v =>
      changes += change("fusionStrategy", current.fusionStrategy, v)
      updated = updated.copy(fusionStrategy = v)
    }
    request.rrfK.filter(_ != current.rrfK).foreach { v =>
      changes += change("rrfK", current.rrfK.toString, v.toString)
      updated = updated.copy(rrfK = v)
    }
    request.systemPrompt.filter(_ != current.systemPrompt).foreach { v =>
      changes += Change("systemPrompt", current.systemPrompt, v, current.systemPrompt.take(50) + "...", v.take(50) + "...")
      updated = updated.copy(systemPrompt = v)
    }
    request.llmTemperature.filter(_ != current.llmTemperature).foreach { v =>
      changes += change("llmTemperature", current.llmTemperature.toString, v.toString)
      updated = updated.copy(llmTemperature = v)
    }
    request.chunkingStrategy.filter(_ != current.chunkingStrategy).foreach { v =>
      changes += change("chunkingStrategy", current.chunkingStrategy, v)
      updated = updated.copy(chunkingStrategy = v)
    }
    request.chunkSize.filter(_ != current.chunkSize).foreach { v =>
      changes += change("chunkSize", current.chunkSize.toString, v.toString)
      updated = updated.copy(chunkSize = v)
    }
    request.chunkOverlap.filter(_ != current.chunkOverlap).foreach { v =>
      changes += change("chunkOverlap", current.chunkOverlap.toString, v.toString)
      updated = updated.copy(chunkOverlap = v)
    }

    (updated, changes.result())
  }

  /** `field`'s value in `config`, as `runtime_config` stores it. */
  private def storedValue(config: RuntimeConfig, field: String): Option[String] = field match {
    case "topK" => Some(config.topK.toString)
    case "fusionStrategy" => Some(config.fusionStrategy)
    case "rrfK" => Some(config.rrfK.toString)
    case "systemPrompt" => Some(config.systemPrompt)
    case "llmTemperature" => Some(config.llmTemperature.toString)
    case "chunkingStrategy" => Some(config.chunkingStrategy)
    case "chunkSize" => Some(config.chunkSize.toString)
    case "chunkOverlap" => Some(config.chunkOverlap.toString)
    case _ => None
  }

  /**
   * The update stored `(field, value)` rows amount to, and the fields whose stored value does
   * not parse (or that are no longer settings).
   */
  private[config] def storedRequest(rows: Seq[(String, String)]): (RuntimeConfigUpdateRequest, Seq[String]) =
    rows.foldLeft((RuntimeConfigUpdateRequest(), Seq.empty[String])) { case ((req, bad), (field, value)) =>
      val parsed: Option[RuntimeConfigUpdateRequest] = field match {
        case "topK" => value.toIntOption.map(v => req.copy(topK = Some(v)))
        case "fusionStrategy" => Some(req.copy(fusionStrategy = Some(value)))
        case "rrfK" => value.toIntOption.map(v => req.copy(rrfK = Some(v)))
        case "systemPrompt" => Some(req.copy(systemPrompt = Some(value)))
        case "llmTemperature" => value.toDoubleOption.map(v => req.copy(llmTemperature = Some(v)))
        case "chunkingStrategy" => Some(req.copy(chunkingStrategy = Some(value)))
        case "chunkSize" => value.toIntOption.map(v => req.copy(chunkSize = Some(v)))
        case "chunkOverlap" => value.toIntOption.map(v => req.copy(chunkOverlap = Some(v)))
        case _ => None
      }
      parsed.fold((req, bad :+ field))(r => (r, bad))
    }

  private def withoutFields(request: RuntimeConfigUpdateRequest, fields: Set[String]): RuntimeConfigUpdateRequest =
    RuntimeConfigUpdateRequest(
      topK = request.topK.filterNot(_ => fields("topK")),
      fusionStrategy = request.fusionStrategy.filterNot(_ => fields("fusionStrategy")),
      rrfK = request.rrfK.filterNot(_ => fields("rrfK")),
      systemPrompt = request.systemPrompt.filterNot(_ => fields("systemPrompt")),
      llmTemperature = request.llmTemperature.filterNot(_ => fields("llmTemperature")),
      chunkingStrategy = request.chunkingStrategy.filterNot(_ => fields("chunkingStrategy")),
      chunkSize = request.chunkSize.filterNot(_ => fields("chunkSize")),
      chunkOverlap = request.chunkOverlap.filterNot(_ => fields("chunkOverlap"))
    )

  /**
   * Validate proposed changes against `current`. Pure.
   */
  private[config] def validateAgainst(current: RuntimeConfig, request: RuntimeConfigUpdateRequest): RuntimeConfigValidateResponse = {
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

    // The overlap must stay below the chunk size it is used with - also when only the size changes
    if (request.chunkOverlap.isDefined || request.chunkSize.isDefined) {
      val proposedSize = request.chunkSize.getOrElse(current.chunkSize)
      val proposedOverlap = request.chunkOverlap.getOrElse(current.chunkOverlap)
      val (valid, error) = if (proposedOverlap < 0) (false, Some("chunkOverlap must be non-negative"))
        else if (proposedOverlap >= proposedSize) (false, Some("chunkOverlap must be less than chunkSize"))
        else (true, None)
      if (request.chunkOverlap.isDefined || !valid)
        validations += SettingValidation("chunkOverlap", valid, current.chunkOverlap.toString, proposedOverlap.toString, error)
    }

    RuntimeConfigValidateResponse(
      valid = validations.forall(_.valid),
      validations = validations.toSeq,
      warnings = warnings.toSeq
    )
  }

  private def updateResponse(updated: RuntimeConfig, changes: Seq[SettingUpdateResult]): RuntimeConfigUpdateResponse = {
    val hotChanges = changes.count(_.changeability == "hot")
    val warmChanges = changes.count(_.changeability == "warm")
    val message = if (changes.isEmpty) {
      "No changes applied"
    } else {
      Seq(
        Option.when(hotChanges > 0)(s"$hotChanges hot setting(s) updated (applies to the next query)"),
        Option.when(warmChanges > 0)(s"$warmChanges warm setting(s) updated (applies to documents ingested from now on)")
      ).flatten.mkString(", ")
    }
    RuntimeConfigUpdateResponse(success = true, changes = changes, current = updated, message = message)
  }

  /**
   * Create a RuntimeConfigManager over the shared (migrated) connection pool: the stored
   * runtime settings are applied over `config`'s.
   *
   * @param permissionMode whether the service runs with a permission `SearchIndex`, where fusion
   *                       settings have no effect (update responses say so)
   */
  def apply(config: AppConfig, dataSource: DataSource, permissionMode: Boolean = false): IO[RuntimeConfigManager] =
    for {
      lock <- Mutex[IO]
      manager = new RuntimeConfigManager(config, dataSource, lock, permissionMode)
      _ <- manager.loadStored()
    } yield manager
}
