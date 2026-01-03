import client from './client'
import type { IngestionStatus, IngestResult } from '@/types/api'

export async function getStatus(): Promise<IngestionStatus> {
  const response = await client.get<IngestionStatus>('/ingest/status')
  return response.data
}

export async function runAllSources(): Promise<IngestResult[]> {
  const response = await client.post<IngestResult[]>('/ingest/run')
  return response.data
}

export async function runSource(sourceName: string): Promise<IngestResult> {
  const response = await client.post<IngestResult>(`/ingest/run/${sourceName}`)
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
