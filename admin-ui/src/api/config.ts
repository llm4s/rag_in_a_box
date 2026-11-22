import client from './client'
import type {
  RuntimeConfigResponse,
  RuntimeConfigUpdate,
  RuntimeConfigUpdateResponse,
  RuntimeConfigValidateResponse,
  ConfigMeta,
  ConfigHistoryResponse,
  AllCollectionConfigsResponse,
  CollectionConfigResponse,
  CollectionConfigUpdateRequest,
  CollectionConfigUpdateResponse,
  EffectiveConfigPreviewResponse
} from '@/types/api'

// Runtime Config

export async function getRuntimeConfig(): Promise<RuntimeConfigResponse> {
  const response = await client.get<RuntimeConfigResponse>('/config/runtime')
  return response.data
}

/** Send only the fields that changed; omitted fields are left untouched by the backend. */
export async function updateRuntimeConfig(update: RuntimeConfigUpdate): Promise<RuntimeConfigUpdateResponse> {
  const response = await client.put<RuntimeConfigUpdateResponse>('/config/runtime', update)
  return response.data
}

export async function validateRuntimeConfig(update: RuntimeConfigUpdate): Promise<RuntimeConfigValidateResponse> {
  const response = await client.post<RuntimeConfigValidateResponse>('/config/runtime/validate', update)
  return response.data
}

export async function getConfigMeta(): Promise<ConfigMeta[]> {
  const response = await client.get<ConfigMeta[]>('/config/runtime/meta')
  return response.data
}

export async function getConfigHistory(params?: { page?: number; pageSize?: number }): Promise<ConfigHistoryResponse> {
  const response = await client.get<ConfigHistoryResponse>('/config/runtime/history', { params })
  return response.data
}

// Collection Config

export async function getCollectionConfigs(): Promise<AllCollectionConfigsResponse> {
  const response = await client.get<AllCollectionConfigsResponse>('/collections/configs')
  return response.data
}

export async function getCollectionConfig(name: string): Promise<CollectionConfigResponse> {
  const response = await client.get<CollectionConfigResponse>(`/collections/${encodeURIComponent(name)}/config`)
  return response.data
}

export async function updateCollectionConfig(
  name: string,
  config: CollectionConfigUpdateRequest
): Promise<CollectionConfigUpdateResponse> {
  const response = await client.put<CollectionConfigUpdateResponse>(
    `/collections/${encodeURIComponent(name)}/config`,
    config
  )
  return response.data
}

export async function deleteCollectionConfig(name: string): Promise<void> {
  await client.delete(`/collections/${encodeURIComponent(name)}/config`)
}

/** Resolve the effective chunking config a file would get in a collection. */
export async function previewEffectiveConfig(
  collection: string,
  filename?: string
): Promise<EffectiveConfigPreviewResponse> {
  const response = await client.post<EffectiveConfigPreviewResponse>(
    `/collections/${encodeURIComponent(collection)}/config/preview`,
    { collection, filename: filename || undefined }
  )
  return response.data
}
