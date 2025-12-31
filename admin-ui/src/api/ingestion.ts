import client from './client'
import type { IngestionSource, IngestionStatus } from '@/types/api'

export async function getSources(): Promise<IngestionSource[]> {
  const response = await client.get('/ingest/sources')
  return response.data.sources
}

export async function getStatus(): Promise<IngestionStatus> {
  const response = await client.get('/ingest/status')
  return response.data
}

export async function runIngestion(): Promise<{ message: string }> {
  const response = await client.post('/ingest/run')
  return response.data
}

export async function runSourceIngestion(sourceId: string): Promise<{ message: string }> {
  const response = await client.post(`/ingest/run/${sourceId}`)
  return response.data
}

export async function ingestDirectory(path: string, params?: {
  collection?: string
  recursive?: boolean
  patterns?: string[]
}): Promise<{ documentsProcessed: number }> {
  const response = await client.post('/ingest/directory', { path, ...params })
  return response.data
}

export async function ingestUrl(url: string, params?: {
  collection?: string
  metadata?: Record<string, string>
}): Promise<{ documentId: string }> {
  const response = await client.post('/ingest/url', { url, ...params })
  return response.data
}
