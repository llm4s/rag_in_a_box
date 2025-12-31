import client from './client'
import type { Document, DocumentListResponse, Chunk } from '@/types/api'

export async function getDocuments(params?: {
  page?: number
  pageSize?: number
  collection?: string
  search?: string
}): Promise<DocumentListResponse> {
  const response = await client.get('/documents', { params })
  return response.data
}

export async function getDocument(id: string): Promise<Document> {
  const response = await client.get(`/documents/${id}`)
  return response.data
}

export async function createDocument(data: {
  content: string
  collection?: string
  metadata?: Record<string, string>
  filename?: string
}): Promise<Document> {
  const response = await client.post('/documents', data)
  return response.data
}

export async function updateDocument(id: string, data: {
  content?: string
  collection?: string
  metadata?: Record<string, string>
}): Promise<Document> {
  const response = await client.put(`/documents/${id}`, data)
  return response.data
}

export async function deleteDocument(id: string): Promise<void> {
  await client.delete(`/documents/${id}`)
}

export async function getDocumentChunks(docId: string): Promise<Chunk[]> {
  const response = await client.get(`/visibility/chunks/${docId}`)
  return response.data.chunks
}

export async function uploadFile(file: File, collection?: string): Promise<Document> {
  const formData = new FormData()
  formData.append('file', file)
  if (collection) {
    formData.append('collection', collection)
  }
  const response = await client.post('/documents/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
  return response.data
}
