package org.llm4s.ragbox.model

import io.circe.{Decoder, Encoder, Json, Printer}
import io.circe.parser.parse
import io.circe.syntax._
import org.llm4s.ragbox.testkit.NonFinite
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
 *
 * Nullability (#64): the backend sends `null` for a `None`, so every TypeScript
 * field for an `Option` must accept `null`. A second fixture,
 * `api-contract-nulls.json`, holds each sample with every `Option` field set to
 * `null`, and contract.check.ts checks the TypeScript types accept it too. The
 * `Option` fields are found from the models' own codecs, so a new one is covered
 * without editing this file: a top-level field is nullable when the sample with
 * that field set to `null` decodes and encodes back with the field `null`, and
 * the sample without the field decodes too (which tells an Option from a Double:
 * circe decodes a `null` Double as NaN).
 */
class ApiContractSpec extends AnyFlatSpec with Matchers {

  private val contractDir: Path = Paths.get("admin-ui", "src", "types", "contract")
  private val fixturePath: Path = contractDir.resolve("api-contract.json")
  private val nullsFixturePath: Path = contractDir.resolve("api-contract-nulls.json")

  private val t0 = Instant.parse("2024-01-01T00:00:00Z")

  /** A model's sample, and the same sample with every Option field `null` (see the class comment). */
  private final case class Sample(full: Json, nulls: Json)

  private def entry[A: Encoder: Decoder](name: String, value: A): (String, Sample) = {
    // A NaN or infinite Double encodes as null (#66): never a valid sample
    withClue(s"$name has non-finite numbers: ")(NonFinite.paths(value) shouldBe empty)
    val full = value.asJson
    val obj = full.asObject.getOrElse(fail(s"$name does not encode as a JSON object"))
    def roundTrip(json: Json): Option[Json] = json.as[A].toOption.map(_.asJson)
    // An Option field decodes from null and from a missing key; a Double decodes null too (as NaN,
    // which encodes as null) but not a missing key
    val nullable = obj.keys.filter { key =>
      roundTrip(Json.fromJsonObject(obj.add(key, Json.Null))).exists(_.hcursor.downField(key).focus.contains(Json.Null)) &&
        roundTrip(Json.fromJsonObject(obj.remove(key))).isDefined
    }.toSeq
    val nulls = roundTrip(Json.fromJsonObject(nullable.foldLeft(obj)((o, key) => o.add(key, Json.Null))))
      .getOrElse(fail(s"$name does not decode with all of its Option fields (${nullable.mkString(", ")}) null"))
    name -> Sample(full, nulls)
  }

  // --- Analytics summary and suggestions -----------------------------------------
  private val collectionQueryStats = CollectionQueryStats("docs", queryCount = 30, averageLatencyMs = 640.0, averageRating = Some(4.0))
  private val suggestionEvidence = SuggestionEvidence("chunk_utilization", 0.3, 0.4, 42, "Last 7 days")
  private val optimizationSuggestion = OptimizationSuggestion(
    id = "s-1", suggestionType = "chunking", severity = "warning", title = "Low Chunk Utilization",
    description = "Only 30% of retrieved chunks are used.", collection = Some("docs"), evidence = suggestionEvidence,
    recommendation = "Reduce chunk size.", estimatedImpact = "Better precision", currentValue = Some("800 chars"),
    suggestedValue = Some("600 chars"), createdAt = t0
  )

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
  private val queryMetrics = QueryMetrics(
    embeddingLatencyMs = Some(12), searchLatencyMs = Some(8), llmLatencyMs = Some(900), chunksRetrieved = 5,
    chunksUsed = 5, embeddingTokens = Some(7), promptTokens = Some(1200), completionTokens = Some(80), costUsd = Some(0.0042)
  )
  private val contextItem = ContextItem("Solar panels convert light.", 0.92, Map("collection" -> "docs"), Some("doc-1"), Some(0))
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

  // --- Analytics --------------------------------------------------------------
  private val queryConfigSnapshot = QueryConfigSnapshot(
    topK = 5, fusionStrategy = Some("rrf"), systemPrompt = Some("You are helpful."), llmTemperature = Some(0.1),
    similarityThreshold = Some(0.25), rrfK = Some(60), reranking = Some("cohere"), searchMode = Some("hybrid")
  )
  private val queryLogEntry = QueryLogEntry(
    id = "5f0c7a9e-1b2d-4c3e-8f40-123456789abc", queryText = "What is solar power?", collectionPattern = Some("docs"),
    userId = Some("alice"), embeddingLatencyMs = Some(12), searchLatencyMs = Some(8), llmLatencyMs = Some(900),
    totalLatencyMs = 930, chunksRetrieved = 5, chunksUsed = 4, answerTokens = Some(80), userRating = Some(4),
    experimentId = Some("exp-1"), configSnapshot = Some(queryConfigSnapshot), createdAt = t0, promptTokens = Some(1200),
    embeddingTokens = Some(7), costUsd = Some(0.0042), variant = Some("variant"), failed = true,
    errorMessage = Some("LLM timed out")
  )

  // --- Experiments ------------------------------------------------------------
  private val experimentConfig = ExperimentConfigSnapshot(
    topK = Some(8), fusionStrategy = Some("rrf"), systemPrompt = Some("Be brief."), llmTemperature = Some(0.2),
    rrfK = Some(30), reranking = Some("cohere")
  )
  private val experiment = Experiment(
    id = "exp-1", name = "prompt test", description = Some("Shorter answers"), status = ExperimentStatus.Running,
    baselineConfig = experimentConfig, variantConfig = experimentConfig, trafficSplit = 0.5, collection = Some("docs"),
    createdAt = t0, startedAt = Some(t0), endedAt = Some(t0)
  )
  private val variantMetrics = ExperimentVariantMetrics(
    queryCount = 10, avgLatencyMs = 120.0, avgRating = Some(4.2), ratedCount = 5, avgChunksUsed = 3.0,
    avgChunksRetrieved = 5.0, failedCount = 1, errorRate = 0.1, latencyStdDevMs = Some(12.5), ratingStdDev = Some(0.4),
    positiveCount = 4
  )
  private val significanceTest = SignificanceTest("latencyMs", "welch-t", 120.0, 110.0, -1.2, 0.25, significant = false)
  private val experimentAnalysis = ExperimentAnalysis("-8.3%", Some("+4.8%"), "+0.0%", "-50.0%", Seq(significanceTest))

  /** Model name (matching the key used in contract.check.ts) -> its samples. */
  private val samples: Seq[(String, Sample)] = Seq(
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
    entry("QueryMetrics", queryMetrics),
    // POST /query: the answer with the query-log id POST /feedback takes (#54)
    entry("ContextItem", contextItem),
    entry("UsageInfo", UsageInfo(1200, 80, 1280)),
    entry("QueryResponse", QueryResponse(
      answer = "An answer.", contexts = Seq(contextItem), usage = Some(UsageInfo(1200, 80, 1280)),
      metrics = Some(queryMetrics), queryId = Some("5f0c7a9e-1b2d-4c3e-8f40-123456789abc")
    )),
    // Analytics: the query log (GET /analytics/queries, /analytics/queries/{id})
    entry("QueryConfigSnapshot", queryConfigSnapshot),
    entry("QueryLogEntry", queryLogEntry),
    entry("QueryLogListResponse", QueryLogListResponse(Seq(queryLogEntry), total = 1, page = 1, pageSize = 20)),
    // Analytics: the summary (GET /analytics/queries/summary) - averages over no data are 0 or null (#66)
    entry("CollectionQueryStats", collectionQueryStats),
    entry("QueryAnalyticsSummary", QueryAnalyticsSummary(
      totalQueries = 42, averageLatencyMs = 812.5, p50LatencyMs = 700, p95LatencyMs = 1900, p99LatencyMs = 2400,
      averageChunksRetrieved = 5.0, averageChunksUsed = 3.5, averageRating = Some(4.2), ratedQueriesCount = 10,
      queriesWithFeedback = 10, topCollections = Seq(collectionQueryStats), periodStart = t0, periodEnd = t0,
      p95RetrievalLatencyMs = Some(300), totalCostUsd = Some(0.42)
    )),
    // Analytics: suggestions (GET /analytics/suggestions, /analytics/suggestions/summary)
    entry("SuggestionEvidence", suggestionEvidence),
    entry("OptimizationSuggestion", optimizationSuggestion),
    entry("SuggestionsResponse", SuggestionsResponse(Seq(optimizationSuggestion), analyzedQueries = 42, analyzedCollections = 2, analysisTimestamp = t0)),
    entry("SuggestionsSummary", SuggestionsSummary(1, 2, 3, 6, Seq("High Retrieval Latency"))),
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
    entry("ErrorResponse", ErrorResponse("validation_failed", "Configuration validation failed", Some("topK: too large"))),
    // Experiments
    entry("ExperimentConfigSnapshot", experimentConfig),
    entry("Experiment", experiment),
    entry("ExperimentListResponse", ExperimentListResponse(Seq(experiment), 1)),
    entry("CreateExperimentResponse", CreateExperimentResponse(experiment, "Created")),
    entry("ExperimentOperationResponse", ExperimentOperationResponse(success = true, "Started", Some(experiment))),
    entry("ExperimentVariantMetrics", variantMetrics),
    entry("SignificanceTest", significanceTest),
    entry("ExperimentAnalysis", experimentAnalysis),
    entry("ExperimentResults", ExperimentResults(
      experimentId = "exp-1", experimentName = "prompt test", status = ExperimentStatus.Completed, duration = Some("2h 5m"),
      baseline = variantMetrics, variant = variantMetrics, analysis = experimentAnalysis
    ))
  )

  private val contract: Json = Json.fromFields(samples.map { case (name, sample) => name -> sample.full })
  private val nullsContract: Json = Json.fromFields(samples.map { case (name, sample) => name -> sample.nulls })

  private val printer = Printer.spaces2SortKeys

  private def updating: Boolean = sys.env.get("UPDATE_API_CONTRACT").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  /** Fail with the models that differ between `expected` and the checked-in fixture at `path`. */
  private def checkFixture(path: Path, expected: Json): Unit = {
    val rendered = printer.print(expected) + "\n"

    if (updating) {
      Files.createDirectories(path.getParent)
      Files.write(path, rendered.getBytes(StandardCharsets.UTF_8))
      info(s"Wrote $path")
    }

    withClue(s"$path is missing; run with UPDATE_API_CONTRACT=1 to create it. ") {
      Files.exists(path) shouldBe true
    }

    val existing = parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      .fold(e => fail(s"Could not parse $path: ${e.getMessage}"), identity)

    val expectedModels = expected.asObject.get.toMap
    val actualModels = existing.asObject.map(_.toMap).getOrElse(Map.empty)
    val drift = (expectedModels.keySet ++ actualModels.keySet).toSeq.sorted.filter { name =>
      expectedModels.get(name) != actualModels.get(name)
    }

    withClue(
      s"API response models drifted from $path for: ${drift.mkString(", ")}.\n" +
        "If the change is intentional, run `UPDATE_API_CONTRACT=1 sbt \"testOnly org.llm4s.ragbox.model.ApiContractSpec\"`, " +
        "then fix admin-ui/src/types/api.ts until `npx vue-tsc --noEmit` passes.\n"
    ) {
      drift shouldBe empty
    }
  }

  "The API contract fixture" should "match the encoded Scala response models" in {
    checkFixture(fixturePath, contract)
  }

  "The nulls fixture" should "match the samples with every Option field null" in {
    checkFixture(nullsFixturePath, nullsContract)
  }

  it should "null exactly the Option fields" in {
    val nulls = samples.toMap.view.mapValues(_.nulls).toMap
    def nullKeys(model: String): Set[String] =
      nulls(model).asObject.get.toList.collect { case (k, v) if v.isNull => k }.toSet
    nullKeys("QueryLogEntry") shouldBe Set(
      "collectionPattern", "userId", "embeddingLatencyMs", "searchLatencyMs", "llmLatencyMs", "answerTokens",
      "userRating", "experimentId", "configSnapshot", "promptTokens", "embeddingTokens", "costUsd", "variant",
      "errorMessage"
    )
    nullKeys("QueryConfigSnapshot") shouldBe Set(
      "fusionStrategy", "systemPrompt", "llmTemperature", "similarityThreshold", "rrfK", "reranking", "searchMode"
    )
    // No Option fields: the same as the full sample
    nullKeys("RuntimeConfig") shouldBe empty
    nulls("RuntimeConfig") shouldBe samples.toMap.apply("RuntimeConfig").full
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

  "The non-finite number guard" should "find a NaN or infinite Double anywhere in a model" in {
    // circe would encode these as null in a number field (#66)
    val metrics = ExperimentVariantMetrics(0, Double.NaN, Some(Double.PositiveInfinity), 0, 0.0, 0.0)
    NonFinite.paths(metrics) shouldBe Seq("$.avgLatencyMs = NaN", "$.avgRating = Infinity")
    NonFinite.paths(Seq(Map("k" -> metrics.copy(avgLatencyMs = 1.0)))) shouldBe Seq("$[0].k.avgRating = Infinity")
    metrics.asJson.hcursor.downField("avgLatencyMs").focus shouldBe Some(Json.Null)
  }
}
