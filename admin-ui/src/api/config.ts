import client from './client'
import type { RuntimeConfig, ConfigMeta, ConfigHistory, CollectionConfig } from '@/types/api'

// Runtime Config
export async function getRuntimeConfig(): Promise<RuntimeConfig> {
  const response = await client.get('/config/runtime')
  return response.data
}

export async function updateRuntimeConfig(config: Partial<RuntimeConfig>): Promise<RuntimeConfig> {
  const response = await client.put('/config/runtime', config)
  return response.data
}

export async function validateRuntimeConfig(config: Partial<RuntimeConfig>): Promise<{ valid: boolean; errors?: string[] }> {
  const response = await client.post('/config/runtime/validate', config)
  return response.data
}

export async function getConfigMeta(): Promise<ConfigMeta[]> {
  const response = await client.get('/config/runtime/meta')
  return response.data
}

export async function getConfigHistory(key?: string): Promise<ConfigHistory[]> {
  const params = key ? { key } : undefined
  const response = await client.get('/config/runtime/history', { params })
  return response.data.history
}

// Collection Config
export async function getCollectionConfigs(): Promise<CollectionConfig[]> {
  const response = await client.get('/collections/configs')
  // Map backend response to frontend CollectionConfig type
  return response.data.collections.map((c: {
    collection: string
    hasCustomConfig: boolean
    config: unknown
    effectiveConfig: {
      strategy: string
      targetSize: number
      maxSize: number
      overlap: number
      source: string
    }
  }) => ({
    name: c.collection,
    chunkingStrategy: c.effectiveConfig.strategy,
    chunkSize: c.effectiveConfig.targetSize,
    chunkOverlap: c.effectiveConfig.overlap,
    customSettings: c.hasCustomConfig ? { source: 'custom' } : undefined
  }))
}

export async function getCollectionConfig(name: string): Promise<CollectionConfig> {
  const response = await client.get(`/collections/${name}/config`)
  return response.data
}

export async function updateCollectionConfig(name: string, config: Partial<CollectionConfig>): Promise<CollectionConfig> {
  const response = await client.put(`/collections/${name}/config`, config)
  return response.data
}

export async function deleteCollectionConfig(name: string): Promise<void> {
  await client.delete(`/collections/${name}/config`)
}

export async function getEffectiveConfig(collection: string, fileType?: string): Promise<CollectionConfig> {
  const params = fileType ? { fileType } : undefined
  const response = await client.get(`/collections/${collection}/effective-config`, { params })
  return response.data
}
