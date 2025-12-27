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
}
