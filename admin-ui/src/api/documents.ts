import client from './client'
import type {
  Document,
  DocumentListResponse,
  DocumentUploadResponse,
  DocumentChunksResponse,
  Chunk
} from '@/types/api'

export interface DocumentListParams {
  /** 1-based page number (backend default 1) */
  page?: number
  /** Items per page (backend default 50, max 500) */
  pageSize?: number
  /** Exact collection match */
  collection?: string
  /** Case-insensitive substring of id, filename or title */
  search?: string
}

export async function getDocuments(params?: DocumentListParams): Promise<DocumentListResponse> {
  // Drop empty filters so they aren't sent as `?collection=`
  const cleaned = Object.fromEntries(
    Object.entries(params ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== '')
  )
  const response = await client.get<DocumentListResponse>('/documents', { params: cleaned })
  return response.data
}

export async function getDocument(id: string): Promise<Document> {
  const response = await client.get<Document>(`/documents/${encodeURIComponent(id)}`)
  return response.data
}

export async function createDocument(data: {
  content: string
  /** Optional; the backend assigns `untitled-<id>.txt` when omitted. */
  filename?: string
  collection?: string
  metadata?: Record<string, string>
}): Promise<DocumentUploadResponse> {
  const response = await client.post<DocumentUploadResponse>('/documents', data)
  return response.data
}

/**
 * Upload a file as multipart/form-data to POST /documents/upload. The backend extracts its text
 * with Apache Tika (via llm4s), so PDF and DOCX work as well as text formats.
 */
export async function uploadDocumentFile(
  file: File,
  options?: { collection?: string; metadata?: Record<string, string> }
): Promise<DocumentUploadResponse> {
  const form = new FormData()
  form.append('file', file, file.name)
  if (options?.collection) form.append('collection', options.collection)
  if (options?.metadata && Object.keys(options.metadata).length > 0) {
    form.append('metadata', JSON.stringify(options.metadata))
  }
  // Overrides the client's JSON default (which would serialise the form as JSON); axios then
  // leaves the header to the browser, which adds the multipart boundary
  const response = await client.post<DocumentUploadResponse>('/documents/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    // Extraction plus embedding a long PDF takes longer than the client's default timeout
    timeout: 120000
  })
  return response.data
}

export async function deleteDocument(id: string): Promise<void> {
  await client.delete(`/documents/${encodeURIComponent(id)}`)
}

export async function getDocumentChunks(docId: string): Promise<Chunk[]> {
  const response = await client.get<DocumentChunksResponse>(`/visibility/chunks/${encodeURIComponent(docId)}`)
  return response.data.chunks
}
