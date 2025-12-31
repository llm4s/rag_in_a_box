import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RuntimeConfig, ConfigMeta, ConfigHistory, CollectionConfig } from '@/types/api'
import * as configApi from '@/api/config'

export const useConfigStore = defineStore('config', () => {
  const runtimeConfig = ref<RuntimeConfig | null>(null)
  const configMeta = ref<ConfigMeta[]>([])
  const configHistory = ref<ConfigHistory[]>([])
  const collectionConfigs = ref<CollectionConfig[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchRuntimeConfig() {
    loading.value = true
    error.value = null
    try {
      runtimeConfig.value = await configApi.getRuntimeConfig()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch config'
    } finally {
      loading.value = false
    }
  }

  async function updateRuntimeConfig(config: Partial<RuntimeConfig>) {
    loading.value = true
    error.value = null
    try {
      runtimeConfig.value = await configApi.updateRuntimeConfig(config)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update config'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function validateConfig(config: Partial<RuntimeConfig>) {
    return configApi.validateRuntimeConfig(config)
  }

  async function fetchConfigMeta() {
    try {
      configMeta.value = await configApi.getConfigMeta()
    } catch {
      // Backend doesn't have this endpoint yet - use sensible defaults
      configMeta.value = [
        { key: 'topK', value: '', type: 'hot', description: 'Number of chunks to retrieve', requiresRestart: false },
        { key: 'fusionStrategy', value: '', type: 'hot', description: 'Search fusion strategy', requiresRestart: false },
        { key: 'rrfK', value: '', type: 'hot', description: 'RRF constant', requiresRestart: false },
        { key: 'systemPrompt', value: '', type: 'hot', description: 'System prompt for LLM', requiresRestart: false },
        { key: 'llmTemperature', value: '', type: 'hot', description: 'LLM temperature', requiresRestart: false },
        { key: 'chunkingStrategy', value: '', type: 'warm', description: 'Default chunking strategy', requiresRestart: false },
        { key: 'chunkSize', value: '', type: 'warm', description: 'Default chunk size', requiresRestart: false },
        { key: 'chunkOverlap', value: '', type: 'warm', description: 'Default chunk overlap', requiresRestart: false },
      ]
    }
  }

  async function fetchConfigHistory(key?: string) {
    try {
      configHistory.value = await configApi.getConfigHistory(key)
    } catch (e) {
      console.error('Failed to fetch config history', e)
    }
  }

  async function fetchCollectionConfigs() {
    loading.value = true
    error.value = null
    try {
      collectionConfigs.value = await configApi.getCollectionConfigs()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch collection configs'
    } finally {
      loading.value = false
    }
  }

  async function updateCollectionConfig(name: string, config: Partial<CollectionConfig>) {
    loading.value = true
    error.value = null
    try {
      const updated = await configApi.updateCollectionConfig(name, config)
      const index = collectionConfigs.value.findIndex(c => c.name === name)
      if (index >= 0) {
        collectionConfigs.value[index] = updated
      } else {
        collectionConfigs.value.push(updated)
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update collection config'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function deleteCollectionConfig(name: string) {
    loading.value = true
    error.value = null
    try {
      await configApi.deleteCollectionConfig(name)
      collectionConfigs.value = collectionConfigs.value.filter(c => c.name !== name)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete collection config'
      throw e
    } finally {
      loading.value = false
    }
  }

  return {
    runtimeConfig,
    configMeta,
    configHistory,
    collectionConfigs,
    loading,
    error,
    fetchRuntimeConfig,
    updateRuntimeConfig,
    validateConfig,
    fetchConfigMeta,
    fetchConfigHistory,
    fetchCollectionConfigs,
    updateCollectionConfig,
    deleteCollectionConfig
  }
})
