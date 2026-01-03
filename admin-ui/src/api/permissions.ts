import client from './client'
import type {
  CollectionPermission,
  CollectionListResponse,
  CreateCollectionRequest,
  UpdatePermissionsRequest
} from '@/types/api'

export async function getCollections(pattern?: string): Promise<CollectionListResponse> {
  const response = await client.get<CollectionListResponse>('/collections', {
    params: pattern ? { pattern } : undefined
  })
  return response.data
}

export async function getCollection(path: string): Promise<CollectionPermission> {
  const response = await client.get<CollectionPermission>(`/collections/${path}`)
  return response.data
}

export async function createCollection(request: CreateCollectionRequest): Promise<CollectionPermission> {
  const response = await client.post<CollectionPermission>('/collections', request)
  return response.data
}

export async function updatePermissions(
  path: string,
  request: UpdatePermissionsRequest
): Promise<CollectionPermission> {
  const response = await client.put<CollectionPermission>(`/collections/${path}/permissions`, request)
  return response.data
}

export async function deleteCollection(path: string): Promise<void> {
  await client.delete(`/collections/${path}`)
}

export interface CollectionStats {
  path: string
  documentCount: number
  chunkCount: number
}

export async function getCollectionStats(path: string): Promise<CollectionStats> {
  const response = await client.get<CollectionStats>(`/collections/${path}/stats`)
  return response.data
}
