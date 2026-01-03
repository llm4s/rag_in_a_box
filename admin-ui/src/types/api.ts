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
  id: string
  name: string
  type: string
  path?: string
  url?: string
  enabled: boolean
  schedule?: string
  lastRun?: string
  lastStatus?: string
}

export interface IngestionStatus {
  running: boolean
  currentSource?: string
  documentsProcessed?: number
  documentsTotal?: number
  startedAt?: string
  lastCompletedAt?: string
  lastResult?: string
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

// API response wrapper
export interface ApiResponse<T> {
  data?: T
  error?: string
  message?: string
}
