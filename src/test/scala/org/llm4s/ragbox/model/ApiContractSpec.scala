package org.llm4s.ragbox.model

import io.circe.{Encoder, Json, Printer}
import io.circe.parser.parse
import io.circe.syntax._
import org.llm4s.ragbox.model.Codecs._
import org.llm4s.ragbox.routes.ConfigMetaItem
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant

/**
 * API contract check between the Scala response models and the admin UI.
 *
 * Encodes one representative instance of every response model the admin UI
 * consumes and compares the result with the checked-in fixture
 * `admin-ui/src/types/contract/api-contract.json`. The admin UI type-checks
 * that same fixture against its TypeScript interfaces
 * (`admin-ui/src/types/contract/contract.check.ts`, run by `vue-tsc`), so a
 * field rename on either side fails CI instead of silently breaking a page.
 *
 * After an intentional model change, regenerate the fixture with:
 * {{{
 *   UPDATE_API_CONTRACT=1 sbt "testOnly org.llm4s.ragbox.model.ApiContractSpec"
 * }}}
 * then update the TypeScript types until `npx vue-tsc --noEmit` passes.
 *
 * Samples must populate every Option and collection: `None` encodes as `null`
 * and empty arrays as `never[]`, which would weaken the TypeScript check.
 */
class ApiContractSpec extends AnyFlatSpec with Matchers {

  private val fixturePath: Path = Paths.get("admin-ui", "src", "types", "contract", "api-contract.json")

  private val t0 = Instant.parse("2024-01-01T00:00:00Z")

  private def entry[A: Encoder](name: String, value: A): (String, Json) = name -> value.asJson

  // --- Runtime config -------------------------------------------------------
  private val runtimeConfig = RuntimeConfig(
    topK = 5, fusionStrategy = "rrf", rrfK = 60, systemPrompt = "You are helpful.",
    llmTemperature = 0.1, chunkingStrategy = "sentence", chunkSize = 800, chunkOverlap = 150
  )
  private val settingUpdate = SettingUpdateResult("llmTemperature", "0.7", "0.2", "hot", effectiveImmediately = true, note = Some("Applies to chat answers."))
  private val settingValidation = SettingValidation(
    field = "topK", valid = false, currentValue = "5", proposedValue = "500",
    error = Some("topK must be between 1 and 100"), warning = Some("Large topK increases latency")
  )
  private val configChange = ConfigChange(1L, "topK", "5", "8", t0, Some("admin"))

  // --- Collection config ----------------------------------------------------
  private val collectionChunkingConfig = CollectionChunkingConfig(
    collection = "docs", strategy = Some("markdown"), targetSize = Some(800), maxSize = Some(1600),
    overlap = Some(100), fileTypeStrategies = Map(".md" -> "markdown"), updatedAt = Some(t0)
  )
  private val effectiveConfig = EffectiveCollectionConfig(
    strategy = "markdown", targetSize = 800, maxSize = 1600, overlap = 100,
    source = "file-type", appliedFileTypeOverride = Some(".md")
  )
  private val collectionConfigResponse = CollectionConfigResponse(
    collection = "docs", hasCustomConfig = true, config = Some(collectionChunkingConfig),
    effectiveConfig = effectiveConfig, documentCount = 3
  )

  // --- Chunking -------------------------------------------------------------
  private val strategyInfo = StrategyInfo(
    name = "sentence", displayName = "Sentence", description = "Splits on sentence boundaries",
    bestFor = Seq("prose"), tradeoffs = Seq("slower"), requiresEmbeddings = false
  )
  private val presetInfo = PresetInfo(
    name = "balanced", displayName = "Balanced", description = "Good default",
    targetSize = 800, maxSize = 1200, overlap = 150, bestFor = Seq("general")
  )
  private val previewChunk = PreviewChunk(0, "Hello world.", 12, Seq("Intro"), isCodeBlock = false, Some("scala"))
  private val chunkingStats = ChunkingStats(1, 12, 12.0, 12, 12, 3)
  private val chunkingWarning = ChunkingWarning("info", "Short document", Some("Use smaller chunks"))
  private val strategyResult = StrategyResult("sentence", Seq(previewChunk), chunkingStats, Seq(chunkingWarning))

  // --- Documents ------------------------------------------------------------
  private val documentInfo = DocumentInfo(
    id = "doc-1", filename = "guide.md", chunkCount = 3, metadata = Map("author" -> "alice"),
    createdAt = Some(t0), collection = Some("docs"), updatedAt = Some(t0)
  )
  private val chunkMetadata = ChunkMetadataInfo(Seq("Intro"), isCodeBlock = false, Some("scala"), Some(0), Some(12))
  private val chunkInfo = ChunkInfo(
    id = "doc-1-0", documentId = "doc-1", index = 0, content = "Hello world.", contentLength = 12,
    metadata = Map("collection" -> "docs"), chunkMetadata = chunkMetadata
  )
  private val chunkConfigSnapshot = ChunkConfigSnapshot("sentence", 800, 1200, 150)

  // --- Stats ----------------------------------------------------------------
  private val sizeBucket = SizeBucket(0, 500, 10)
  private val sizeDistribution = ChunkSizeDistribution(10, 900, 450.0, 430.0, 800.0, Seq(sizeBucket))
  private val detailedCollectionStats = DetailedCollectionStats("docs", 3, 9, 3.0, 450.0, Some("sentence"))

  /** Model name (matching the key used in contract.check.ts) -> encoded sample. */
  private val contract: Json = Json.obj(
    // Runtime config
    entry("RuntimeConfig", runtimeConfig),
    entry("RuntimeConfigResponse", RuntimeConfigResponse(runtimeConfig, Some(t0), Seq("topK"), Seq("chunkSize"))),
    entry("SettingUpdateResult", settingUpdate),
    entry("RuntimeConfigUpdateResponse", RuntimeConfigUpdateResponse(true, Seq(settingUpdate), runtimeConfig, "Updated")),
    entry("SettingValidation", settingValidation),
    entry("RuntimeConfigValidateResponse", RuntimeConfigValidateResponse(false, Seq(settingValidation), Seq("warning"))),
    entry("ConfigChange", configChange),
    entry("ConfigHistoryResponse", ConfigHistoryResponse(Seq(configChange), 1, 1, 50, hasMore = false)),
    entry("ConfigMetaItem", ConfigMetaItem("topK", "5", "hot", "Number of chunks", requiresRestart = false)),
    // Collection config
    entry("CollectionChunkingConfig", collectionChunkingConfig),
    entry("EffectiveCollectionConfig", effectiveConfig),
    entry("CollectionConfigResponse", collectionConfigResponse),
    entry("AllCollectionConfigsResponse", AllCollectionConfigsResponse(Seq(collectionConfigResponse), effectiveConfig)),
    entry("CollectionConfigUpdateResponse", CollectionConfigUpdateResponse("docs", collectionChunkingConfig, "Updated")),
    entry("EffectiveConfigPreviewResponse",
      EffectiveConfigPreviewResponse("docs", Some("guide.md"), effectiveConfig, Seq("Using collection config"))),
    // Chunking
    entry("StrategyInfo", strategyInfo),
    entry("StrategiesResponse", StrategiesResponse(Seq(strategyInfo), "sentence")),
    entry("PresetInfo", presetInfo),
    entry("PresetsResponse", PresetsResponse(Seq(presetInfo), presetInfo)),
    entry("PreviewChunk", previewChunk),
    entry("ChunkingStats", chunkingStats),
    entry("ChunkingWarning", chunkingWarning),
    entry("StrategyResult", strategyResult),
    entry("ChunkingCompareResponse",
      ChunkingCompareResponse(Seq(strategyResult), Some(StrategyRecommendation("sentence", "Fewest warnings")))),
    // Documents
    entry("DocumentInfo", documentInfo),
    entry("DocumentListResponse", DocumentListResponse(Seq(documentInfo), total = 1, page = 1, pageSize = 50, hasMore = false)),
    entry("DocumentUploadResponse", DocumentUploadResponse("doc-1", 3, "Successfully ingested")),
    entry("ChunkMetadataInfo", chunkMetadata),
    entry("ChunkInfo", chunkInfo),
    entry("DocumentChunksResponse",
      DocumentChunksResponse("doc-1", Some("guide.md"), Some("docs"), 1, Seq(chunkInfo), chunkConfigSnapshot)),
    // Stats
    entry("SizeBucket", sizeBucket),
    entry("ChunkSizeDistribution", sizeDistribution),
    entry("DetailedCollectionStats", detailedCollectionStats),
    entry("DetailedStatsResponse", DetailedStatsResponse(
      documentCount = 3, chunkCount = 9, vectorCount = 9L, collectionCount = 1, avgChunksPerDocument = 3.0,
      collections = Seq(detailedCollectionStats), chunkSizeDistribution = sizeDistribution,
      indexedSince = Some(t0), lastIngestion = Some(t0), currentConfig = chunkConfigSnapshot
    )),
    // Pipeline config and query metrics
    entry("RAGConfigInfo", RAGConfigInfo(
      chunkingStrategy = "sentence", chunkSize = 800, chunkOverlap = 150, topK = 5, fusionStrategy = "rrf",
      rrfK = 60, searchMode = "vector", fusionApplies = false, reranking = "none", queryTransform = "none",
      limitations = Seq("fusionStrategy and rrfK have no effect (llm4s/llm4s#2716).")
    )),
    entry("QueryMetrics", QueryMetrics(
      embeddingLatencyMs = Some(12), searchLatencyMs = Some(8), llmLatencyMs = Some(900), chunksRetrieved = 5,
      chunksUsed = 5, embeddingTokens = Some(7), promptTokens = Some(1200), completionTokens = Some(80), costUsd = Some(0.0042)
    )),
    // Authentication
    entry("AuthModeResponse", org.llm4s.ragbox.routes.AuthModeResponse(
      mode = "basic", authRequired = true, methods = Seq("password", "api-key"), oauthLoginUrl = Some("/api/v1/oauth/login")
    )),
    entry("LoginResponse", org.llm4s.ragbox.routes.LoginResponse("jwt", "admin", "admin", 3600L)),
    entry("PrincipalResponse", org.llm4s.ragbox.routes.PrincipalResponse(
      id = "token:1", username = "ingester", role = "user", isAdmin = false, authMethod = "access-token",
      groups = Seq("eng"), scopes = Some(Seq("query")), collections = Some(Seq("eng")), userId = Some(1)
    )),
    // Errors
    entry("ErrorResponse", ErrorResponse("validation_failed", "Configuration validation failed", Some("topK: too large")))
  )

  private val printer = Printer.spaces2SortKeys

  "The API contract fixture" should "match the encoded Scala response models" in {
    val rendered = printer.print(contract) + "\n"

    if (sys.env.get("UPDATE_API_CONTRACT").exists(v => v == "1" || v.equalsIgnoreCase("true"))) {
      Files.createDirectories(fixturePath.getParent)
      Files.write(fixturePath, rendered.getBytes(StandardCharsets.UTF_8))
      info(s"Wrote $fixturePath")
    }

    withClue(s"$fixturePath is missing; run with UPDATE_API_CONTRACT=1 to create it. ") {
      Files.exists(fixturePath) shouldBe true
    }

    val existing = parse(new String(Files.readAllBytes(fixturePath), StandardCharsets.UTF_8))
      .fold(e => fail(s"Could not parse $fixturePath: ${e.getMessage}"), identity)

    val expectedModels = contract.asObject.get.toMap
    val actualModels = existing.asObject.map(_.toMap).getOrElse(Map.empty)
    val drift = (expectedModels.keySet ++ actualModels.keySet).toSeq.sorted.filter { name =>
      expectedModels.get(name) != actualModels.get(name)
    }

    withClue(
      s"API response models drifted from $fixturePath for: ${drift.mkString(", ")}.\n" +
        "If the change is intentional, run `UPDATE_API_CONTRACT=1 sbt \"testOnly org.llm4s.ragbox.model.ApiContractSpec\"`, " +
        "then fix admin-ui/src/types/api.ts until `npx vue-tsc --noEmit` passes.\n"
    ) {
      drift shouldBe empty
    }
  }

  it should "not encode any null values (they would weaken the TypeScript check)" in {
    def nullPaths(json: Json, path: String): Seq[String] = json.fold(
      jsonNull = Seq(path),
      jsonBoolean = _ => Nil,
      jsonNumber = _ => Nil,
      jsonString = _ => Nil,
      jsonArray = arr =>
        (if (arr.isEmpty) Seq(s"$path (empty array)") else Nil) ++
          arr.zipWithIndex.flatMap { case (j, i) => nullPaths(j, s"$path[$i]") },
      jsonObject = obj => obj.toList.flatMap { case (k, v) => nullPaths(v, s"$path.$k") }
    )
    nullPaths(contract, "$") shouldBe empty
  }
}
