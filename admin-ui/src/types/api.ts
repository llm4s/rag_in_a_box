// Document types
export interface Document {
  id: string
  filename?: string
  collection: string
  metadata?: Record<string, string>
  content?: string
  chunkCount?: number
  createdAt?: string
  updatedAt?: string
}

export interface DocumentListResponse {
  documents: Document[]
  total: number
  page: number
  pageSize: number
}

export interface Chunk {
  id: string
  documentId: string
  content: string
  metadata?: Record<string, string>
  chunkIndex: number
  startOffset?: number
  endOffset?: number
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

export interface VisibilityStats {
  documentCount: number
  chunkCount: number
  collectionCount: number
  avgChunksPerDocument: number
  totalContentSize: number
  chunkSizeDistribution: ChunkSizeDistribution
  collections?: CollectionStats[]
}

export interface ChunkSizeDistribution {
  min: number
  max: number
  avg: number
  median: number
  buckets: { range: string; count: number }[]
}

// Config types
export interface RuntimeConfig {
  defaultChunkSize: number
  defaultChunkOverlap: number
  defaultChunkingStrategy: string
  maxContentLength: number
  enabledFileTypes: string[]
  llmProvider: string
  llmModel: string
  embeddingProvider: string
  embeddingModel: string
  embeddingDimensions: number
}

export interface ConfigMeta {
  key: string
  value: string
  type: 'hot' | 'warm' | 'cold'
  description: string
  requiresRestart: boolean
}

export interface ConfigHistory {
  id: number
  key: string
  oldValue: string
  newValue: string
  changedAt: string
  changedBy?: string
}

export interface CollectionConfig {
  name: string
  chunkingStrategy: string
  chunkSize: number
  chunkOverlap: number
  customSettings?: Record<string, string>
}

// Chunking types
export interface ChunkingStrategy {
  name: string
  description: string
  parameters: ChunkingParameter[]
}

export interface ChunkingParameter {
  name: string
  type: string
  defaultValue: string
  description: string
}

export interface ChunkingPreset {
  name: string
  strategy: string
  chunkSize: number
  chunkOverlap: number
  description: string
}

export interface ChunkPreview {
  chunks: PreviewChunk[]
  totalChunks: number
  totalCharacters: number
  avgChunkSize: number
}

export interface PreviewChunk {
  index: number
  content: string
  startOffset: number
  endOffset: number
  size: number
}

// Chunking comparison types (matches backend ChunkingCompareResponse)
export interface ChunkingCompareResult {
  results: StrategyResult[]
  recommendation?: StrategyRecommendation
}

export interface StrategyResult {
  strategy: string
  chunks: ComparePreviewChunk[]
  stats: ChunkingStats
  warnings: ChunkingWarning[]
}

export interface ComparePreviewChunk {
  index: number
  content: string
  length: number
  headings: string[]
  isCodeBlock: boolean
  language?: string
}

export interface ChunkingStats {
  chunkCount: number
  totalLength: number
  avgChunkSize: number
  minChunkSize: number
  maxChunkSize: number
  estimatedTokens: number
}

export interface ChunkingWarning {
  level: 'info' | 'warning' | 'error'
  message: string
  suggestion?: string
}

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
export interface QueryLogEntry {
  id: string
  queryText: string
  collectionPattern?: string
  userId?: string
  embeddingLatencyMs?: number
  searchLatencyMs?: number
  llmLatencyMs?: number
  totalLatencyMs: number
  chunksRetrieved: number
  chunksUsed: number
  answerTokens?: number
  userRating?: number
  createdAt: string
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
  averageRating?: number
}

export interface QueryAnalyticsSummary {
  totalQueries: number
  averageLatencyMs: number
  p50LatencyMs: number
  p95LatencyMs: number
  p99LatencyMs: number
  averageChunksRetrieved: number
  averageChunksUsed: number
  averageRating?: number
  ratedQueriesCount: number
  queriesWithFeedback: number
  topCollections: CollectionQueryStats[]
  periodStart: string
  periodEnd: string
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
  documentId?: string
  chunkIndex?: number
}

export interface UsageInfo {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export interface QueryResponse {
  answer: string
  contexts: ContextItem[]
  usage?: UsageInfo
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
  onChunk?: (chunk: string) => void
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
  collection?: string
  evidence: SuggestionEvidence
  recommendation: string
  estimatedImpact: string
  currentValue?: string
  suggestedValue?: string
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
  topK?: number
  fusionStrategy?: string
  systemPrompt?: string
  llmTemperature?: number
}

export interface Experiment {
  id: string
  name: string
  description?: string
  status: ExperimentStatus
  baselineConfig: ExperimentConfigSnapshot
  variantConfig: ExperimentConfigSnapshot
  trafficSplit: number
  collection?: string
  createdAt: string
  startedAt?: string
  endedAt?: string
}

export interface CreateExperimentRequest {
  name: string
  description?: string
  baselineConfig?: ExperimentConfigSnapshot
  variantConfig: ExperimentConfigSnapshot
  trafficSplit?: number
  collection?: string
}

export interface UpdateExperimentRequest {
  name?: string
  description?: string
  baselineConfig?: ExperimentConfigSnapshot
  variantConfig?: ExperimentConfigSnapshot
  trafficSplit?: number
}

export interface ExperimentVariantMetrics {
  queryCount: number
  avgLatencyMs: number
  avgRating?: number
  ratedCount: number
  avgChunksUsed: number
  avgChunksRetrieved: number
}

export interface ExperimentAnalysis {
  latencyChange: string
  ratingChange?: string
  chunksUsedChange: string
  statisticalSignificance?: number
}

export interface ExperimentResults {
  experimentId: string
  experimentName: string
  status: ExperimentStatus
  duration?: string
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
  experiment?: Experiment
}

// Query overrides for experiments
export interface QueryOverrides {
  topK?: number
  fusionStrategy?: string
  systemPrompt?: string
  llmTemperature?: number
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
  onAnswer?: (answer: string) => void
  onUsage?: (usage: UsageInfo) => void
  onComplete?: (queryId: string, totalContexts: number) => void
  onError?: (error: string, message: string) => void
}
