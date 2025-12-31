import client from './client'
import type { ChunkingStrategy, ChunkingPreset, ChunkPreview, ChunkingCompareResult } from '@/types/api'

export async function getStrategies(): Promise<ChunkingStrategy[]> {
  const response = await client.get('/chunking/strategies')
  return response.data.strategies
}

export async function getPresets(): Promise<ChunkingPreset[]> {
  const response = await client.get('/chunking/presets')
  return response.data.presets
}

export async function previewChunking(params: {
  text: string
  strategy?: string
  chunkSize?: number
  chunkOverlap?: number
}): Promise<ChunkPreview> {
  // Map frontend params to backend expected format
  const requestBody = {
    content: params.text,
    strategy: params.strategy,
    targetSize: params.chunkSize,
    overlap: params.chunkOverlap
  }
  const response = await client.post('/chunking/preview', requestBody)

  // Transform backend response to frontend format
  const data = response.data
  return {
    chunks: data.chunks.map((chunk: { index: number; content: string; length: number }) => ({
      index: chunk.index,
      content: chunk.content,
      size: chunk.length,
      startOffset: 0,  // Not provided by backend
      endOffset: chunk.length
    })),
    totalChunks: data.stats.chunkCount,
    totalCharacters: data.stats.totalLength,
    avgChunkSize: data.stats.avgChunkSize
  }
}

export async function compareStrategies(params: {
  text: string
  strategies: string[]
  chunkSize?: number
  chunkOverlap?: number
}): Promise<ChunkingCompareResult> {
  // Map frontend params to backend expected format
  const requestBody = {
    content: params.text,           // Backend expects 'content'
    strategies: params.strategies,
    targetSize: params.chunkSize,   // Backend expects 'targetSize'
    overlap: params.chunkOverlap    // Backend expects 'overlap'
  }
  const response = await client.post('/chunking/compare', requestBody)
  return response.data
}
