// ============================================================
// Backend response types
//
// Interfaces mirroring Scala response models are checked against
// `src/types/contract/api-contract.json` (generated from the Scala models by
// ApiContractSpec) in `src/types/contract/contract.check.ts`. Scala `Option`
// fields are encoded as `null` when absent, hence `?: T | null`.
// ============================================================

/** Error body returned by the backend (Scala `ErrorResponse`). */
export interface ApiErrorBody {
  error: string
  message: string
  details?: string | null
}

// Document types

/** Scala `DocumentInfo` (GET /documents, GET /documents/{id}). */
export interface Document {
  id: string
  filename: string
  chunkCount: number
  metadata: Record<string, string>
  createdAt?: string | null
  collection?: string | null
  updatedAt?: string | null
}

/** Scala `DocumentListResponse`. `total` counts all matches, not just this page. */
export interface DocumentListResponse {
  documents: Document[]
  total: number
  page: number
  pageSize: number
  hasMore: boolean
}

/** Scala `DocumentUploadResponse` (POST /documents). */
export interface DocumentUploadResponse {
  documentId: string
  chunks: number
  message: string
}

/** Scala `ChunkMetadataInfo`. */
export interface ChunkMetadataInfo {
  headings: string[]
  isCodeBlock: boolean
  language?: string | null
  startOffset?: number | null
  endOffset?: number | null
}

/** Scala `ChunkInfo` (visibility chunk endpoints). */
export interface Chunk {
  id: string
  documentId: string
  index: number
  content: string
  contentLength: number
  metadata: Record<string, string>
  chunkMetadata: ChunkMetadataInfo
}

/** Scala `ChunkConfigSnapshot`. */
export interface ChunkConfigSnapshot {
  strategy: string
  targetSize: number
  maxSize: number
  overlap: number
}

/** Scala `DocumentChunksResponse` (GET /visibility/chunks/{docId}). */
export interface DocumentChunksResponse {
  documentId: string
  filename?: string | null
  collection?: string | null
  chunkCount: number
  chunks: Chunk[]
  chunkingConfig: ChunkConfigSnapshot
}

// Stats types
export interface Stats {
  documentCount: number
  chunkCount: number
  collectionCount: number
  collections: CollectionStats[]
}

export interface CollectionStats {
  name: string
  documentCount: number
  chunkCount: number
}

/** Scala `DetailedCollectionStats`. */
export interface DetailedCollectionStats {
  name: string
  documentCount: number
  chunkCount: number
  avgChunksPerDoc: number
  avgChunkSize: number
  chunkingStrategy?: string | null
}

/** Scala `DetailedStatsResponse` (GET /visibility/stats). */
export interface VisibilityStats {
  documentCount: number
  chunkCount: number
  vectorCount: number
  collectionCount: number
  avgChunksPerDocument: number
  collections: DetailedCollectionStats[]
  chunkSizeDistribution: ChunkSizeDistribution
  indexedSince?: string | null
  lastIngestion?: string | null
  currentConfig: ChunkConfigSnapshot
}

/** Scala `SizeBucket`: chunk sizes in [rangeStart, rangeEnd). */
export interface SizeBucket {
  rangeStart: number
  rangeEnd: number
  count: number
}

/** Scala `ChunkSizeDistribution`. */
export interface ChunkSizeDistribution {
  min: number
  max: number
  avg: number
  median: number
  p90: number
  buckets: SizeBucket[]
}

// Runtime config types

/** Scala `RuntimeConfig`: the hot/warm settings that can change at runtime. */
export interface RuntimeConfig {
  topK: number
  fusionStrategy: string
  rrfK: number
  systemPrompt: string
  llmTemperature: number
  chunkingStrategy: string
  chunkSize: number
  chunkOverlap: number
}

/** Body for PUT /config/runtime and POST /config/runtime/validate (only changed fields). */
export type RuntimeConfigUpdate = Partial<RuntimeConfig>

/** Scala `RuntimeConfigResponse` (GET /config/runtime). */
export interface RuntimeConfigResponse {
  config: RuntimeConfig
  lastModified?: string | null
  hotSettings: string[]
  warmSettings: string[]
}

/** Scala `SettingUpdateResult`. */
export interface SettingUpdateResult {
  field: string
  oldValue: string
  newValue: string
  changeability: string
  effectiveImmediately: boolean
  /** Where the setting does not apply (null when it applies everywhere). */
  note?: string | null
}

/** Scala `RuntimeConfigUpdateResponse` (PUT /config/runtime). */
export interface RuntimeConfigUpdateResponse {
  success: boolean
  changes: SettingUpdateResult[]
  current: RuntimeConfig
  message: string
}

/** Scala `SettingValidation`. */
export interface SettingValidation {
  field: string
  valid: boolean
  currentValue: string
  proposedValue: string
  error?: string | null
  warning?: string | null
}

/** Scala `RuntimeConfigValidateResponse` (POST /config/runtime/validate). */
export interface RuntimeConfigValidateResponse {
  valid: boolean
  validations: SettingValidation[]
  warnings: string[]
}

/** Scala `ConfigMetaItem` (GET /config/runtime/meta). */
export interface ConfigMeta {
  key: string
  value: string
  type: 'hot' | 'warm' | 'cold'
  description: string
  requiresRestart: boolean
}

/** Scala `ConfigChange`. */
export interface ConfigChange {
  id: number
  field: string
  oldValue: string
  newValue: string
  changedAt: string
  changedBy?: string | null
}

/** Scala `ConfigHistoryResponse` (GET /config/runtime/history). */
export interface ConfigHistoryResponse {
  changes: ConfigChange[]
  total: number
  page: number
  pageSize: number
  hasMore: boolean
}

// Collection config types

/** Scala `CollectionChunkingConfig`: a collection's custom overrides. */
export interface CollectionChunkingConfig {
  collection: string
  strategy?: string | null
  targetSize?: number | null
  maxSize?: number | null
  overlap?: number | null
  fileTypeStrategies: Record<string, string>
  updatedAt?: string | null
}

/** Scala `EffectiveCollectionConfig`: overrides merged with defaults. */
export interface EffectiveCollectionConfig {
  strategy: string
  targetSize: number
  maxSize: number
  overlap: number
  source: string
  appliedFileTypeOverride?: string | null
}

/** Scala `CollectionConfigResponse` (GET /collections/{c}/config). */
export interface CollectionConfigResponse {
  collection: string
  hasCustomConfig: boolean
  config?: CollectionChunkingConfig | null
  effectiveConfig: EffectiveCollectionConfig
  documentCount: number
}

/** Scala `AllCollectionConfigsResponse` (GET /collections/configs). */
export interface AllCollectionConfigsResponse {
  collections: CollectionConfigResponse[]
  defaultConfig: EffectiveCollectionConfig
}

/** Scala `CollectionConfigUpdateRequest` (PUT /collections/{c}/config). */
export interface CollectionConfigUpdateRequest {
  strategy?: string
  targetSize?: number
  maxSize?: number
  overlap?: number
  fileTypeStrategies?: Record<string, string>
}

/** Scala `CollectionConfigUpdateResponse`. */
export interface CollectionConfigUpdateResponse {
  collection: string
  config: CollectionChunkingConfig
  message: string
}

/** Scala `EffectiveConfigPreviewResponse` (POST /collections/{c}/config/preview). */
export interface EffectiveConfigPreviewResponse {
  collection: string
  filename?: string | null
  effectiveConfig: EffectiveCollectionConfig
  configResolutionPath: string[]
}

// Chunking types

/** Scala `StrategyInfo`. */
export interface ChunkingStrategy {
  name: string
  displayName: string
  description: string
  bestFor: string[]
  tradeoffs: string[]
  requiresEmbeddings: boolean
}

/** Scala `StrategiesResponse` (GET /chunking/strategies). */
export interface StrategiesResponse {
  strategies: ChunkingStrategy[]
  currentDefault: string
}

/** Scala `PresetInfo`. */
export interface ChunkingPreset {
  name: string
  displayName: string
  description: string
  targetSize: number
  maxSize: number
  overlap: number
  bestFor: string[]
}

/** Scala `PresetsResponse` (GET /chunking/presets). */
export interface PresetsResponse {
  presets: ChunkingPreset[]
  current: ChunkingPreset
}

/** Frontend view of a single-strategy preview (derived from ChunkingPreviewResponse). */
export interface ChunkPreview {
  chunks: PreviewChunk[]
  totalChunks: number
  totalCharacters: number
  avgChunkSize: number
}

/** Frontend view of a preview chunk. */
export interface PreviewChunk {
  index: number
  content: string
  startOffset: number
  endOffset: number
  size: number
}

/** Scala `ChunkingCompareResponse`. */
export interface ChunkingCompareResult {
  results: StrategyResult[]
  recommendation?: StrategyRecommendation | null
}

/** Scala `StrategyResult`. */
export interface StrategyResult {
  strategy: string
  chunks: ComparePreviewChunk[]
  stats: ChunkingStats
  warnings: ChunkingWarning[]
}

/** Scala `PreviewChunk`. */
export interface ComparePreviewChunk {
  index: number
  content: string
  length: number
  headings: string[]
  isCodeBlock: boolean
  language?: string | null
}

/** Scala `ChunkingStats`. */
export interface ChunkingStats {
  chunkCount: number
  totalLength: number
  avgChunkSize: number
  minChunkSize: number
  maxChunkSize: number
  estimatedTokens: number
}

/** Scala `ChunkingWarning`. */
export interface ChunkingWarning {
  level: 'info' | 'warning' | 'error'
  message: string
  suggestion?: string | null
}

/** Scala `StrategyRecommendation`. */
export interface StrategyRecommendation {
  strategy: string
  reason: string
}

// Ingestion types
export interface IngestionSource {
  name: string
  sourceType: string
  enabled: boolean
  config: Record<string, string>
}

export interface IngestResult {
  sourceName: string
  sourceType: string
  documentsAdded: number
  documentsUpdated: number
  documentsDeleted: number
  documentsUnchanged: number
  documentsFailed: number
  durationMs: number
  error?: string
}

export interface IngestionStatus {
  running: boolean
  lastRun?: string
  lastResults: IngestResult[]
  nextScheduledRun?: string
  sources: IngestionSource[]
}

// Health types
export interface HealthResponse {
  status: string
  checks: Record<string, { status: string; message?: string }>
}

// Analytics types
/**
 * The effective configuration a logged query ran with. Fields added later are absent from
 * entries logged before them.
 */
/**
 * The settings a logged query ran with. Queries logged in permission mode by earlier versions,
 * where search was vector-only then, have `searchMode` `vector-only` and null fusion, rrfK and
 * reranking.
 */
export interface QueryConfigSnapshot {
  topK: number
  /** Fusion strategy (null in `vector-only` entries). */
  fusionStrategy?: string | null
  systemPrompt?: string | null
  /** The temperature the answer was generated at (null in entries logged before it was applied). */
  llmTemperature?: number | null
  /** Minimum chunk similarity the query applied (null: none). */
  similarityThreshold?: number | null
  /** RRF constant, with `rrf` fusion. */
  rrfK?: number | null
  /** Reranking strategy: `none`, `cohere` or `llm` (null in `vector-only` entries). */
  reranking?: string | null
  /** `hybrid`; `vector-only` in earlier permission-mode entries; null in entries logged before it was recorded. */
  searchMode?: QuerySearchMode | null
}

export type QuerySearchMode = 'hybrid' | 'vector-only'

export interface QueryLogEntry {
  id: string
  queryText: string
  collectionPattern?: string | null
  userId?: string | null
  embeddingLatencyMs?: number | null
  searchLatencyMs?: number | null
  llmLatencyMs?: number | null
  totalLatencyMs: number
  chunksRetrieved: number
  chunksUsed: number
  answerTokens?: number | null
  userRating?: number | null
  /** Experiment the query was routed through. */
  experimentId?: string | null
  /** The configuration the query ran with (absent for queries logged without one). */
  configSnapshot?: QueryConfigSnapshot | null
  createdAt: string
  promptTokens?: number | null
  embeddingTokens?: number | null
  costUsd?: number | null
  /** Experiment group (`baseline` / `variant`) the query was routed to. */
  variant?: string | null
  /** Whether the query failed (failed queries count in experiment error rates). */
  failed?: boolean
  errorMessage?: string | null
}

export interface QueryLogListResponse {
  queries: QueryLogEntry[]
  total: number
  page: number
  pageSize: number
}

export interface CollectionQueryStats {
  collection: string
  queryCount: number
  averageLatencyMs: number
  /** null when no query in the collection was rated */
  averageRating?: number | null
}

export interface QueryAnalyticsSummary {
  totalQueries: number
  averageLatencyMs: number
  p50LatencyMs: number
  p95LatencyMs: number
  p99LatencyMs: number
  averageChunksRetrieved: number
  averageChunksUsed: number
  /** null when no query in the period was rated */
  averageRating?: number | null
  ratedQueriesCount: number
  queriesWithFeedback: number
  topCollections: CollectionQueryStats[]
  periodStart: string
  periodEnd: string
  /** p95 of retrieval latency (total minus answer generation); null when no LLM latency was recorded */
  p95RetrievalLatencyMs?: number | null
  /** Estimated cost of the period's queries; null when no cost is known */
  totalCostUsd?: number | null
}

export interface QueryFeedbackRequest {
  queryId: string
  rating: number
  relevantChunks?: string[]
  comment?: string
}

export interface QueryFeedbackResponse {
  success: boolean
  message: string
}

// Query types
export interface QueryRequest {
  question: string
  collection?: string
  topK?: number
  includeMetadata?: boolean
}

export interface ContextItem {
  content: string
  score: number
  metadata: Record<string, string>
  documentId?: string | null
  chunkIndex?: number | null
}

export interface UsageInfo {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

/** Scala `QueryMetrics`: per-query latencies, tokens and cost traced by llm4s. */
export interface QueryMetrics {
  embeddingLatencyMs?: number | null
  searchLatencyMs?: number | null
  llmLatencyMs?: number | null
  chunksRetrieved: number
  chunksUsed: number
  embeddingTokens?: number | null
  promptTokens?: number | null
  completionTokens?: number | null
  costUsd?: number | null
}

export interface QueryResponse {
  answer: string
  contexts: ContextItem[]
  usage?: UsageInfo | null
  metrics?: QueryMetrics | null
  /** The query-log id, for POST /feedback (only its asker, or an admin, may rate it) */
  queryId?: string | null
}

/** Scala `RAGConfigInfo` (part of GET /config). */
export interface RAGConfigInfo {
  chunkingStrategy: string
  chunkSize: number
  chunkOverlap: number
  topK: number
  fusionStrategy: string
  rrfK: number
  /** Always `hybrid` (earlier versions: `vector` in permission mode). */
  searchMode: string
  /** Whether fusionStrategy and rrfK affect search */
  fusionApplies: boolean
  reranking: string
  queryTransform: string
  /** Settings that do not take effect, and why */
  limitations: string[]
}

/** Scala `ConfigResponse` (GET /config). */
export interface PipelineConfigResponse {
  embedding: { provider: string; model: string; dimensions?: number | null }
  llm: { model: string; temperature: number }
  rag: RAGConfigInfo
  database: { host: string; port: number; database: string; tableName: string; connected: boolean }
}

// Chat types (frontend-only)
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  contexts?: ContextItem[]
  usage?: UsageInfo
  timestamp: Date
  rating?: number
  queryLogId?: string
  isStreaming?: boolean
}

// SSE Streaming types
export type QueryStreamEventType = 'start' | 'context' | 'chunk' | 'answer' | 'usage' | 'complete' | 'error'

export interface QueryStreamEvent {
  event: QueryStreamEventType
  timestamp: string
}

export interface QueryStartEvent extends QueryStreamEvent {
  event: 'start'
  queryId: string
}

export interface QueryContextEvent extends QueryStreamEvent {
  event: 'context'
  context: ContextItem
  index: number
}

export interface QueryChunkEvent extends QueryStreamEvent {
  event: 'chunk'
  chunk: string
}

export interface QueryAnswerEvent extends QueryStreamEvent {
  event: 'answer'
  answer: string
}

export interface QueryUsageEvent extends QueryStreamEvent {
  event: 'usage'
  usage: UsageInfo
}

export interface QueryCompleteEvent extends QueryStreamEvent {
  event: 'complete'
  queryId: string
  totalContexts: number
}

export interface QueryErrorEvent extends QueryStreamEvent {
  event: 'error'
  error: string
  message: string
}

export interface StreamingCallbacks {
  onStart?: (queryId: string) => void
  onContext?: (context: ContextItem, index: number) => void
  /** The next piece of the answer, as the LLM generates it. */
  onChunk?: (chunk: string) => void
  /** The whole answer, after its chunks. */
  onAnswer?: (answer: string) => void
  onUsage?: (usage: UsageInfo) => void
  onComplete?: (queryId: string, totalContexts: number) => void
  onError?: (error: string, message: string) => void
}

// User management types
export interface User {
  id: number
  username: string
  role: string
}

export interface UserListResponse {
  users: User[]
  total: number
}

export interface CreateUserRequest {
  username: string
  password: string
  role?: string
}

// Principal types
export interface Principal {
  id: number
  externalId: string
  principalType: string
}

export interface PrincipalListResponse {
  principals: Principal[]
  total: number
}

// Collection permission types
export interface CollectionPermission {
  id: number
  path: string
  parentPath?: string
  queryableBy: number[]
  isLeaf: boolean
  isPublic: boolean
  metadata: Record<string, string>
}

export interface CollectionListResponse {
  collections: CollectionPermission[]
}

export interface CreateCollectionRequest {
  path: string
  description?: string
  queryableBy?: string[]
  isLeaf?: boolean
  metadata?: Record<string, string>
}

export interface UpdatePermissionsRequest {
  queryableBy: string[]
}

// API response wrapper
export interface ApiResponse<T> {
  data?: T
  error?: string
  message?: string
}

// Optimization Suggestion types
export type SuggestionType = 'chunking' | 'search' | 'embedding' | 'content'
export type SuggestionSeverity = 'critical' | 'warning' | 'info'

export interface SuggestionEvidence {
  metric: string
  currentValue: number
  threshold: number
  sampleSize: number
  timeRange: string
}

export interface OptimizationSuggestion {
  id: string
  suggestionType: SuggestionType
  severity: SuggestionSeverity
  title: string
  description: string
  collection?: string | null
  evidence: SuggestionEvidence
  recommendation: string
  estimatedImpact: string
  currentValue?: string | null
  suggestedValue?: string | null
  createdAt: string
}

export interface SuggestionsResponse {
  suggestions: OptimizationSuggestion[]
  analyzedQueries: number
  analyzedCollections: number
  analysisTimestamp: string
}

export interface SuggestionsSummary {
  criticalCount: number
  warningCount: number
  infoCount: number
  total: number
  topIssues: string[]
}

// Experiment types
export type ExperimentStatus = 'draft' | 'running' | 'completed' | 'archived'

export interface ExperimentConfigSnapshot {
  topK?: number | null
  fusionStrategy?: string | null
  systemPrompt?: string | null
  /** The temperature the group's answers are generated at (0 to 2). */
  llmTemperature?: number | null
  rrfK?: number | null
  /** `none`, `cohere` or `llm`. */
  reranking?: string | null
}

export interface Experiment {
  id: string
  name: string
  description?: string | null
  status: ExperimentStatus
  baselineConfig: ExperimentConfigSnapshot
  variantConfig: ExperimentConfigSnapshot
  trafficSplit: number
  collection?: string | null
  createdAt: string
  startedAt?: string | null
  endedAt?: string | null
}

export interface CreateExperimentRequest {
  name: string
  description?: string
  baselineConfig?: ExperimentConfigSnapshot
  variantConfig: ExperimentConfigSnapshot
  trafficSplit?: number
  collection?: string
}

/**
 * PATCH /experiments/{id}: fields left out are unchanged; `null` clears `description` or
 * `collection` (the other fields cannot be cleared).
 */
export interface UpdateExperimentRequest {
  name?: string
  description?: string | null
  baselineConfig?: ExperimentConfigSnapshot
  variantConfig?: ExperimentConfigSnapshot
  trafficSplit?: number
  collection?: string | null
}

/** One experiment group's metrics; latency, chunks and ratings are over successful queries. */
export interface ExperimentVariantMetrics {
  queryCount: number
  avgLatencyMs: number
  avgRating?: number | null
  ratedCount: number
  avgChunksUsed: number
  avgChunksRetrieved: number
  failedCount?: number
  errorRate?: number
  latencyStdDevMs?: number | null
  ratingStdDev?: number | null
  positiveCount?: number
}

/** A two-sample test of one metric, variant against baseline. */
export interface SignificanceTest {
  /** `latencyMs`, `rating`, `errorRate` or `positiveRate`. */
  metric: string
  /** `welch-t` or `two-proportion-z`. */
  test: string
  baseline: number
  variant: number
  statistic: number
  /** Two-sided p-value. */
  pValue: number
  /** pValue below 0.05. */
  significant: boolean
}

export interface ExperimentAnalysis {
  latencyChange: string
  ratingChange?: string | null
  chunksUsedChange: string
  errorRateChange?: string
  /** A test per metric with enough data. */
  tests?: SignificanceTest[]
}

export interface ExperimentResults {
  experimentId: string
  experimentName: string
  status: ExperimentStatus
  duration?: string | null
  baseline: ExperimentVariantMetrics
  variant: ExperimentVariantMetrics
  analysis: ExperimentAnalysis
}

export interface ExperimentListResponse {
  experiments: Experiment[]
  total: number
}

export interface CreateExperimentResponse {
  experiment: Experiment
  message: string
}

export interface ExperimentOperationResponse {
  success: boolean
  message: string
  experiment?: Experiment | null
}

// Query overrides for experiments
export interface QueryOverrides {
  topK?: number
  fusionStrategy?: string
  systemPrompt?: string
  /** Minimum vector similarity (0 to 1). */
  similarityThreshold?: number
}

// Extended query request with experiment support
export interface ExperimentQueryRequest extends QueryRequest {
  experimentId?: string
  overrides?: QueryOverrides
}

// ============================================================
// Chat Session Types (Backend-Persisted)
// ============================================================

/**
 * A chat session stored in the database.
 */
export interface ChatSession {
  id: string
  title?: string
  collectionPattern: string
  messageCount: number
  createdAt: string
  updatedAt: string
  isArchived: boolean
}

/**
 * A chat message record stored in the database.
 */
export interface ChatMessageRecord {
  id: string
  sessionId: string
  role: string
  content: string
  contexts?: ContextItem[]
  usage?: UsageInfo
  rating?: number
  queryLogId?: string
  createdAt: string
  messageIndex: number
}

/**
 * Request to create a new chat session.
 */
export interface CreateChatSessionRequest {
  title?: string
  collectionPattern?: string
}

/**
 * Response after creating a chat session.
 */
export interface CreateChatSessionResponse {
  session: ChatSession
  message: string
}

/**
 * Request to update a chat session.
 */
export interface UpdateChatSessionRequest {
  title?: string
  collectionPattern?: string
  isArchived?: boolean
}

/**
 * Response for listing chat sessions.
 */
export interface ChatSessionListResponse {
  sessions: ChatSession[]
  total: number
}

/**
 * Response for a chat session with all its messages.
 */
export interface ChatSessionDetailResponse {
  session: ChatSession
  messages: ChatMessageRecord[]
}

/**
 * Request to send a message in a chat session.
 */
export interface SendChatMessageRequest {
  content: string
}

/**
 * Request to rate a chat message.
 */
export interface RateChatMessageRequest {
  rating: number
}

/**
 * Response for chat operations.
 */
export interface ChatOperationResponse {
  success: boolean
  message: string
}

/**
 * SSE start event for chat streaming (includes userMessageId).
 */
export interface ChatStartEvent {
  queryId: string
  userMessageId: string
}

/**
 * SSE complete event for chat streaming.
 */
export interface ChatCompleteEvent {
  queryId: string
  totalContexts: number
}

/**
 * Callbacks for chat SSE streaming.
 */
export interface ChatStreamingCallbacks {
  onStart?: (queryId: string, userMessageId: string) => void
  onContext?: (context: ContextItem, index: number) => void
  /** The next piece of the answer, as the LLM generates it. */
  onChunk?: (chunk: string) => void
  /** The whole answer, after its chunks. */
  onAnswer?: (answer: string) => void
  onUsage?: (usage: UsageInfo) => void
  onComplete?: (queryId: string, totalContexts: number) => void
  onError?: (error: string, message: string) => void
}

// ============================================================
// Authentication (`/api/v1/auth/*`)
// ============================================================

/** Server auth mode: `open` (optionally API-key protected), `basic` (JWT) or `oauth` (SSO). */
export type AuthModeName = 'open' | 'basic' | 'oauth'

/** Login methods the server accepts. */
export type AuthMethodName = 'password' | 'sso' | 'api-key'

/** Response of `GET /auth/mode` (no authentication required). */
export interface AuthModeResponse {
  mode: AuthModeName
  /** False means open mode without an API key: no login, everything allowed. */
  authRequired: boolean
  methods: AuthMethodName[]
  /** SSO entry point (e.g. `/api/v1/oauth/login`) in oauth mode, otherwise null. */
  oauthLoginUrl: string | null
}

/** Request body of `POST /auth/login`. */
export interface LoginRequest {
  username: string
  password: string
}

/** Response of `POST /auth/login`. */
export interface LoginResponse {
  token: string
  username: string
  role: string
  expiresIn: number
}

/** How the current principal was authenticated. */
export type AuthenticationMethod = 'open' | 'api-key' | 'password' | 'access-token' | 'oidc'

/** Response of `GET /auth/me`: the authenticated principal. */
export interface AuthMe {
  id: string
  username: string
  role: 'admin' | 'user'
  isAdmin: boolean
  authMethod: AuthenticationMethod
  groups: string[]
  scopes: string[] | null
  collections: string[] | null
  userId: number | null
}
