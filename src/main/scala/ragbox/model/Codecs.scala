package ragbox.model

import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._

import java.time.Instant

/**
 * Circe JSON codecs for API models.
 */
object Codecs {

  // ============================================================
  // Common Codecs
  // ============================================================

  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  implicit val instantDecoder: Decoder[Instant] = Decoder.decodeString.emap { str =>
    try Right(Instant.parse(str))
    catch { case _: Exception => Left(s"Invalid instant: $str") }
  }

  // ============================================================
  // Document Codecs
  // ============================================================

  implicit val documentUploadRequestDecoder: Decoder[DocumentUploadRequest] = deriveDecoder
  implicit val documentUploadRequestEncoder: Encoder[DocumentUploadRequest] = deriveEncoder

  implicit val documentUploadResponseEncoder: Encoder[DocumentUploadResponse] = deriveEncoder
  implicit val documentUploadResponseDecoder: Decoder[DocumentUploadResponse] = deriveDecoder

  implicit val documentInfoEncoder: Encoder[DocumentInfo] = deriveEncoder
  implicit val documentInfoDecoder: Decoder[DocumentInfo] = deriveDecoder

  implicit val documentListResponseEncoder: Encoder[DocumentListResponse] = deriveEncoder
  implicit val documentListResponseDecoder: Decoder[DocumentListResponse] = deriveDecoder

  implicit val urlIngestionRequestDecoder: Decoder[UrlIngestionRequest] = deriveDecoder
  implicit val urlIngestionRequestEncoder: Encoder[UrlIngestionRequest] = deriveEncoder

  // ============================================================
  // Upsert Codecs
  // ============================================================

  implicit val documentUpsertRequestDecoder: Decoder[DocumentUpsertRequest] = deriveDecoder
  implicit val documentUpsertRequestEncoder: Encoder[DocumentUpsertRequest] = deriveEncoder

  implicit val documentUpsertResponseEncoder: Encoder[DocumentUpsertResponse] = deriveEncoder
  implicit val documentUpsertResponseDecoder: Decoder[DocumentUpsertResponse] = deriveDecoder

  implicit val syncPruneRequestDecoder: Decoder[SyncPruneRequest] = deriveDecoder
  implicit val syncPruneRequestEncoder: Encoder[SyncPruneRequest] = deriveEncoder

  implicit val syncPruneResponseEncoder: Encoder[SyncPruneResponse] = deriveEncoder
  implicit val syncPruneResponseDecoder: Decoder[SyncPruneResponse] = deriveDecoder

  implicit val documentSyncStateEncoder: Encoder[DocumentSyncState] = deriveEncoder
  implicit val documentSyncStateDecoder: Decoder[DocumentSyncState] = deriveDecoder

  implicit val documentSyncListResponseEncoder: Encoder[DocumentSyncListResponse] = deriveEncoder
  implicit val documentSyncListResponseDecoder: Decoder[DocumentSyncListResponse] = deriveDecoder

  implicit val syncCheckRequestDecoder: Decoder[SyncCheckRequest] = deriveDecoder
  implicit val syncCheckRequestEncoder: Encoder[SyncCheckRequest] = deriveEncoder

  implicit val syncCheckResponseEncoder: Encoder[SyncCheckResponse] = deriveEncoder
  implicit val syncCheckResponseDecoder: Decoder[SyncCheckResponse] = deriveDecoder

  implicit val batchDeleteRequestDecoder: Decoder[BatchDeleteRequest] = deriveDecoder
  implicit val batchDeleteRequestEncoder: Encoder[BatchDeleteRequest] = deriveEncoder

  implicit val batchDeleteResponseEncoder: Encoder[BatchDeleteResponse] = deriveEncoder
  implicit val batchDeleteResponseDecoder: Decoder[BatchDeleteResponse] = deriveDecoder

  implicit val syncStatusResponseEncoder: Encoder[SyncStatusResponse] = deriveEncoder
  implicit val syncStatusResponseDecoder: Decoder[SyncStatusResponse] = deriveDecoder

  // ============================================================
  // Query Codecs
  // ============================================================

  implicit val queryRequestDecoder: Decoder[QueryRequest] = deriveDecoder
  implicit val queryRequestEncoder: Encoder[QueryRequest] = deriveEncoder

  implicit val usageInfoEncoder: Encoder[UsageInfo] = deriveEncoder
  implicit val usageInfoDecoder: Decoder[UsageInfo] = deriveDecoder

  implicit val contextItemEncoder: Encoder[ContextItem] = deriveEncoder
  implicit val contextItemDecoder: Decoder[ContextItem] = deriveDecoder

  implicit val queryResponseEncoder: Encoder[QueryResponse] = deriveEncoder
  implicit val queryResponseDecoder: Decoder[QueryResponse] = deriveDecoder

  implicit val searchRequestDecoder: Decoder[SearchRequest] = deriveDecoder
  implicit val searchRequestEncoder: Encoder[SearchRequest] = deriveEncoder

  implicit val searchResponseEncoder: Encoder[SearchResponse] = deriveEncoder
  implicit val searchResponseDecoder: Decoder[SearchResponse] = deriveDecoder

  // ============================================================
  // Configuration Codecs
  // ============================================================

  implicit val embeddingConfigInfoEncoder: Encoder[EmbeddingConfigInfo] = deriveEncoder
  implicit val embeddingConfigInfoDecoder: Decoder[EmbeddingConfigInfo] = deriveDecoder

  implicit val llmConfigInfoEncoder: Encoder[LLMConfigInfo] = deriveEncoder
  implicit val llmConfigInfoDecoder: Decoder[LLMConfigInfo] = deriveDecoder

  implicit val ragConfigInfoEncoder: Encoder[RAGConfigInfo] = deriveEncoder
  implicit val ragConfigInfoDecoder: Decoder[RAGConfigInfo] = deriveDecoder

  implicit val databaseConfigInfoEncoder: Encoder[DatabaseConfigInfo] = deriveEncoder
  implicit val databaseConfigInfoDecoder: Decoder[DatabaseConfigInfo] = deriveDecoder

  implicit val configResponseEncoder: Encoder[ConfigResponse] = deriveEncoder
  implicit val configResponseDecoder: Decoder[ConfigResponse] = deriveDecoder

  implicit val providerInfoEncoder: Encoder[ProviderInfo] = deriveEncoder
  implicit val providerInfoDecoder: Decoder[ProviderInfo] = deriveDecoder

  implicit val providersResponseEncoder: Encoder[ProvidersResponse] = deriveEncoder
  implicit val providersResponseDecoder: Decoder[ProvidersResponse] = deriveDecoder

  // ============================================================
  // Health Codecs
  // ============================================================

  implicit val healthResponseEncoder: Encoder[HealthResponse] = deriveEncoder
  implicit val healthResponseDecoder: Decoder[HealthResponse] = deriveDecoder

  implicit val checkStatusEncoder: Encoder[CheckStatus] = deriveEncoder
  implicit val checkStatusDecoder: Decoder[CheckStatus] = deriveDecoder

  implicit val readinessResponseEncoder: Encoder[ReadinessResponse] = deriveEncoder
  implicit val readinessResponseDecoder: Decoder[ReadinessResponse] = deriveDecoder

  // ============================================================
  // Statistics Codecs
  // ============================================================

  implicit val collectionStatsEncoder: Encoder[CollectionStats] = deriveEncoder
  implicit val collectionStatsDecoder: Decoder[CollectionStats] = deriveDecoder

  implicit val statsResponseEncoder: Encoder[StatsResponse] = deriveEncoder
  implicit val statsResponseDecoder: Decoder[StatsResponse] = deriveDecoder

  // ============================================================
  // Ingestion Codecs
  // ============================================================

  implicit val directoryIngestRequestDecoder: Decoder[DirectoryIngestRequest] = deriveDecoder
  implicit val directoryIngestRequestEncoder: Encoder[DirectoryIngestRequest] = deriveEncoder

  implicit val urlIngestRequestDecoder: Decoder[UrlIngestRequest] = deriveDecoder
  implicit val urlIngestRequestEncoder: Encoder[UrlIngestRequest] = deriveEncoder

  implicit val ingestResponseEncoder: Encoder[IngestResponse] = deriveEncoder
  implicit val ingestResponseDecoder: Decoder[IngestResponse] = deriveDecoder

  implicit val sourceInfoEncoder: Encoder[SourceInfo] = deriveEncoder
  implicit val sourceInfoDecoder: Decoder[SourceInfo] = deriveDecoder

  implicit val ingestionStatusResponseEncoder: Encoder[IngestionStatusResponse] = deriveEncoder
  implicit val ingestionStatusResponseDecoder: Decoder[IngestionStatusResponse] = deriveDecoder

  // ============================================================
  // Error Codecs
  // ============================================================

  implicit val errorResponseEncoder: Encoder[ErrorResponse] = deriveEncoder
  implicit val errorResponseDecoder: Decoder[ErrorResponse] = deriveDecoder

  // ============================================================
  // Visibility Codecs
  // ============================================================

  implicit val changeabilityInfoEncoder: Encoder[ChangeabilityInfo] = deriveEncoder
  implicit val changeabilityInfoDecoder: Decoder[ChangeabilityInfo] = deriveDecoder

  implicit val configSettingEncoder: Encoder[ConfigSetting] = deriveEncoder
  implicit val configSettingDecoder: Decoder[ConfigSetting] = deriveDecoder

  implicit val visibilityEmbeddingConfigEncoder: Encoder[VisibilityEmbeddingConfig] = deriveEncoder
  implicit val visibilityEmbeddingConfigDecoder: Decoder[VisibilityEmbeddingConfig] = deriveDecoder

  implicit val visibilityLLMConfigEncoder: Encoder[VisibilityLLMConfig] = deriveEncoder
  implicit val visibilityLLMConfigDecoder: Decoder[VisibilityLLMConfig] = deriveDecoder

  implicit val detailedRAGConfigInfoEncoder: Encoder[DetailedRAGConfigInfo] = deriveEncoder
  implicit val detailedRAGConfigInfoDecoder: Decoder[DetailedRAGConfigInfo] = deriveDecoder

  implicit val visibilityDatabaseConfigEncoder: Encoder[VisibilityDatabaseConfig] = deriveEncoder
  implicit val visibilityDatabaseConfigDecoder: Decoder[VisibilityDatabaseConfig] = deriveDecoder

  implicit val runtimeConfigurableInfoEncoder: Encoder[RuntimeConfigurableInfo] = deriveEncoder
  implicit val runtimeConfigurableInfoDecoder: Decoder[RuntimeConfigurableInfo] = deriveDecoder

  implicit val detailedConfigResponseEncoder: Encoder[DetailedConfigResponse] = deriveEncoder
  implicit val detailedConfigResponseDecoder: Decoder[DetailedConfigResponse] = deriveDecoder

  implicit val chunkMetadataInfoEncoder: Encoder[ChunkMetadataInfo] = deriveEncoder
  implicit val chunkMetadataInfoDecoder: Decoder[ChunkMetadataInfo] = deriveDecoder

  implicit val chunkInfoEncoder: Encoder[ChunkInfo] = deriveEncoder
  implicit val chunkInfoDecoder: Decoder[ChunkInfo] = deriveDecoder

  implicit val chunkListResponseEncoder: Encoder[ChunkListResponse] = deriveEncoder
  implicit val chunkListResponseDecoder: Decoder[ChunkListResponse] = deriveDecoder

  implicit val chunkConfigSnapshotEncoder: Encoder[ChunkConfigSnapshot] = deriveEncoder
  implicit val chunkConfigSnapshotDecoder: Decoder[ChunkConfigSnapshot] = deriveDecoder

  implicit val documentChunksResponseEncoder: Encoder[DocumentChunksResponse] = deriveEncoder
  implicit val documentChunksResponseDecoder: Decoder[DocumentChunksResponse] = deriveDecoder

  implicit val sizeBucketEncoder: Encoder[SizeBucket] = deriveEncoder
  implicit val sizeBucketDecoder: Decoder[SizeBucket] = deriveDecoder

  implicit val chunkSizeDistributionEncoder: Encoder[ChunkSizeDistribution] = deriveEncoder
  implicit val chunkSizeDistributionDecoder: Decoder[ChunkSizeDistribution] = deriveDecoder

  implicit val detailedCollectionStatsEncoder: Encoder[DetailedCollectionStats] = deriveEncoder
  implicit val detailedCollectionStatsDecoder: Decoder[DetailedCollectionStats] = deriveDecoder

  implicit val detailedStatsResponseEncoder: Encoder[DetailedStatsResponse] = deriveEncoder
  implicit val detailedStatsResponseDecoder: Decoder[DetailedStatsResponse] = deriveDecoder

  implicit val collectionChunkingInfoEncoder: Encoder[CollectionChunkingInfo] = deriveEncoder
  implicit val collectionChunkingInfoDecoder: Decoder[CollectionChunkingInfo] = deriveDecoder

  implicit val effectiveChunkingInfoEncoder: Encoder[EffectiveChunkingInfo] = deriveEncoder
  implicit val effectiveChunkingInfoDecoder: Decoder[EffectiveChunkingInfo] = deriveDecoder

  implicit val collectionDetailInfoEncoder: Encoder[CollectionDetailInfo] = deriveEncoder
  implicit val collectionDetailInfoDecoder: Decoder[CollectionDetailInfo] = deriveDecoder

  implicit val collectionsResponseEncoder: Encoder[CollectionsResponse] = deriveEncoder
  implicit val collectionsResponseDecoder: Decoder[CollectionsResponse] = deriveDecoder

  // ============================================================
  // Chunking Preview Codecs
  // ============================================================

  implicit val chunkingPreviewRequestDecoder: Decoder[ChunkingPreviewRequest] = deriveDecoder
  implicit val chunkingPreviewRequestEncoder: Encoder[ChunkingPreviewRequest] = deriveEncoder

  implicit val previewChunkEncoder: Encoder[PreviewChunk] = deriveEncoder
  implicit val previewChunkDecoder: Decoder[PreviewChunk] = deriveDecoder

  implicit val chunkingStatsEncoder: Encoder[ChunkingStats] = deriveEncoder
  implicit val chunkingStatsDecoder: Decoder[ChunkingStats] = deriveDecoder

  implicit val chunkingWarningEncoder: Encoder[ChunkingWarning] = deriveEncoder
  implicit val chunkingWarningDecoder: Decoder[ChunkingWarning] = deriveDecoder

  implicit val chunkingConfigUsedEncoder: Encoder[ChunkingConfigUsed] = deriveEncoder
  implicit val chunkingConfigUsedDecoder: Decoder[ChunkingConfigUsed] = deriveDecoder

  implicit val chunkingPreviewResponseEncoder: Encoder[ChunkingPreviewResponse] = deriveEncoder
  implicit val chunkingPreviewResponseDecoder: Decoder[ChunkingPreviewResponse] = deriveDecoder

  implicit val chunkingCompareRequestDecoder: Decoder[ChunkingCompareRequest] = deriveDecoder
  implicit val chunkingCompareRequestEncoder: Encoder[ChunkingCompareRequest] = deriveEncoder

  implicit val strategyResultEncoder: Encoder[StrategyResult] = deriveEncoder
  implicit val strategyResultDecoder: Decoder[StrategyResult] = deriveDecoder

  implicit val strategyRecommendationEncoder: Encoder[StrategyRecommendation] = deriveEncoder
  implicit val strategyRecommendationDecoder: Decoder[StrategyRecommendation] = deriveDecoder

  implicit val chunkingCompareResponseEncoder: Encoder[ChunkingCompareResponse] = deriveEncoder
  implicit val chunkingCompareResponseDecoder: Decoder[ChunkingCompareResponse] = deriveDecoder

  implicit val strategyInfoEncoder: Encoder[StrategyInfo] = deriveEncoder
  implicit val strategyInfoDecoder: Decoder[StrategyInfo] = deriveDecoder

  implicit val strategiesResponseEncoder: Encoder[StrategiesResponse] = deriveEncoder
  implicit val strategiesResponseDecoder: Decoder[StrategiesResponse] = deriveDecoder

  implicit val presetInfoEncoder: Encoder[PresetInfo] = deriveEncoder
  implicit val presetInfoDecoder: Decoder[PresetInfo] = deriveDecoder

  implicit val presetsResponseEncoder: Encoder[PresetsResponse] = deriveEncoder
  implicit val presetsResponseDecoder: Decoder[PresetsResponse] = deriveDecoder

  // ============================================================
  // Runtime Configuration Codecs
  // ============================================================

  implicit val runtimeConfigEncoder: Encoder[RuntimeConfig] = deriveEncoder
  implicit val runtimeConfigDecoder: Decoder[RuntimeConfig] = deriveDecoder

  implicit val runtimeConfigUpdateRequestDecoder: Decoder[RuntimeConfigUpdateRequest] = deriveDecoder
  implicit val runtimeConfigUpdateRequestEncoder: Encoder[RuntimeConfigUpdateRequest] = deriveEncoder

  implicit val settingUpdateResultEncoder: Encoder[SettingUpdateResult] = deriveEncoder
  implicit val settingUpdateResultDecoder: Decoder[SettingUpdateResult] = deriveDecoder

  implicit val runtimeConfigUpdateResponseEncoder: Encoder[RuntimeConfigUpdateResponse] = deriveEncoder
  implicit val runtimeConfigUpdateResponseDecoder: Decoder[RuntimeConfigUpdateResponse] = deriveDecoder

  implicit val runtimeConfigValidateRequestDecoder: Decoder[RuntimeConfigValidateRequest] = deriveDecoder
  implicit val runtimeConfigValidateRequestEncoder: Encoder[RuntimeConfigValidateRequest] = deriveEncoder

  implicit val settingValidationEncoder: Encoder[SettingValidation] = deriveEncoder
  implicit val settingValidationDecoder: Decoder[SettingValidation] = deriveDecoder

  implicit val runtimeConfigValidateResponseEncoder: Encoder[RuntimeConfigValidateResponse] = deriveEncoder
  implicit val runtimeConfigValidateResponseDecoder: Decoder[RuntimeConfigValidateResponse] = deriveDecoder

  implicit val configChangeEncoder: Encoder[ConfigChange] = deriveEncoder
  implicit val configChangeDecoder: Decoder[ConfigChange] = deriveDecoder

  implicit val configHistoryResponseEncoder: Encoder[ConfigHistoryResponse] = deriveEncoder
  implicit val configHistoryResponseDecoder: Decoder[ConfigHistoryResponse] = deriveDecoder

  implicit val runtimeConfigResponseEncoder: Encoder[RuntimeConfigResponse] = deriveEncoder
  implicit val runtimeConfigResponseDecoder: Decoder[RuntimeConfigResponse] = deriveDecoder

  // ============================================================
  // Collection Configuration Codecs
  // ============================================================

  implicit val collectionChunkingConfigEncoder: Encoder[CollectionChunkingConfig] = deriveEncoder
  implicit val collectionChunkingConfigDecoder: Decoder[CollectionChunkingConfig] = deriveDecoder

  implicit val collectionConfigUpdateRequestDecoder: Decoder[CollectionConfigUpdateRequest] = deriveDecoder
  implicit val collectionConfigUpdateRequestEncoder: Encoder[CollectionConfigUpdateRequest] = deriveEncoder

  implicit val effectiveCollectionConfigEncoder: Encoder[EffectiveCollectionConfig] = deriveEncoder
  implicit val effectiveCollectionConfigDecoder: Decoder[EffectiveCollectionConfig] = deriveDecoder

  implicit val collectionConfigResponseEncoder: Encoder[CollectionConfigResponse] = deriveEncoder
  implicit val collectionConfigResponseDecoder: Decoder[CollectionConfigResponse] = deriveDecoder

  implicit val collectionConfigUpdateResponseEncoder: Encoder[CollectionConfigUpdateResponse] = deriveEncoder
  implicit val collectionConfigUpdateResponseDecoder: Decoder[CollectionConfigUpdateResponse] = deriveDecoder

  implicit val collectionConfigDeleteResponseEncoder: Encoder[CollectionConfigDeleteResponse] = deriveEncoder
  implicit val collectionConfigDeleteResponseDecoder: Decoder[CollectionConfigDeleteResponse] = deriveDecoder

  implicit val effectiveConfigPreviewRequestDecoder: Decoder[EffectiveConfigPreviewRequest] = deriveDecoder
  implicit val effectiveConfigPreviewRequestEncoder: Encoder[EffectiveConfigPreviewRequest] = deriveEncoder

  implicit val effectiveConfigPreviewResponseEncoder: Encoder[EffectiveConfigPreviewResponse] = deriveEncoder
  implicit val effectiveConfigPreviewResponseDecoder: Decoder[EffectiveConfigPreviewResponse] = deriveDecoder

  implicit val allCollectionConfigsResponseEncoder: Encoder[AllCollectionConfigsResponse] = deriveEncoder
  implicit val allCollectionConfigsResponseDecoder: Decoder[AllCollectionConfigsResponse] = deriveDecoder

  // ============================================================
  // Query Analytics Codecs
  // ============================================================

  implicit val queryLogEntryEncoder: Encoder[QueryLogEntry] = deriveEncoder
  implicit val queryLogEntryDecoder: Decoder[QueryLogEntry] = deriveDecoder

  implicit val queryFeedbackRequestEncoder: Encoder[QueryFeedbackRequest] = deriveEncoder
  implicit val queryFeedbackRequestDecoder: Decoder[QueryFeedbackRequest] = deriveDecoder

  implicit val queryFeedbackResponseEncoder: Encoder[QueryFeedbackResponse] = deriveEncoder
  implicit val queryFeedbackResponseDecoder: Decoder[QueryFeedbackResponse] = deriveDecoder

  implicit val collectionQueryStatsEncoder: Encoder[CollectionQueryStats] = deriveEncoder
  implicit val collectionQueryStatsDecoder: Decoder[CollectionQueryStats] = deriveDecoder

  implicit val queryAnalyticsSummaryEncoder: Encoder[QueryAnalyticsSummary] = deriveEncoder
  implicit val queryAnalyticsSummaryDecoder: Decoder[QueryAnalyticsSummary] = deriveDecoder

  implicit val queryLogListResponseEncoder: Encoder[QueryLogListResponse] = deriveEncoder
  implicit val queryLogListResponseDecoder: Decoder[QueryLogListResponse] = deriveDecoder
}
