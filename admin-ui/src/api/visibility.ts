import client from './client'
import type { VisibilityStats, Chunk, CollectionStats } from '@/types/api'

export async function getVisibilityStats(): Promise<VisibilityStats> {
  const response = await client.get('/visibility/stats')
  return response.data
}

export async function getAllChunks(params?: {
  page?: number
  pageSize?: number
  documentId?: string
  collection?: string
}): Promise<{ chunks: Chunk[]; total: number }> {
  const response = await client.get('/visibility/chunks', { params })
  return response.data
}

export async function getDocumentChunks(docId: string): Promise<Chunk[]> {
  const response = await client.get(`/visibility/chunks/${docId}`)
  return response.data.chunks
}

export async function getCollections(): Promise<CollectionStats[]> {
  const response = await client.get('/visibility/collections')
  return response.data.collections
}

export async function getConfigVisibility(): Promise<Record<string, { value: string; type: string }>> {
  const response = await client.get('/visibility/config')
  return response.data
}
