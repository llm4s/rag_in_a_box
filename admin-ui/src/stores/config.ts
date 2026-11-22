import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  RuntimeConfig,
  RuntimeConfigUpdate,
  RuntimeConfigUpdateResponse,
  RuntimeConfigValidateResponse,
  ConfigMeta,
  ConfigChange,
  CollectionConfigResponse,
  CollectionConfigUpdateRequest,
  EffectiveCollectionConfig,
  ChunkingStrategy
} from '@/types/api'
import * as configApi from '@/api/config'
import * as chunkingApi from '@/api/chunking'
import { getErrorMessage } from '@/composables/useApiError'

export const useConfigStore = defineStore('config', () => {
  const runtimeConfig = ref<RuntimeConfig | null>(null)
  const hotSettings = ref<string[]>([])
  const warmSettings = ref<string[]>([])
  const lastModified = ref<string | null>(null)
  const configMeta = ref<ConfigMeta[]>([])
  const configHistory = ref<ConfigChange[]>([])
  const collectionConfigs = ref<CollectionConfigResponse[]>([])
  const defaultCollectionConfig = ref<EffectiveCollectionConfig | null>(null)
  const chunkingStrategies = ref<ChunkingStrategy[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchRuntimeConfig() {
    loading.value = true
    error.value = null
    try {
      const response = await configApi.getRuntimeConfig()
      runtimeConfig.value = response.config
      hotSettings.value = response.hotSettings
      warmSettings.value = response.warmSettings
      lastModified.value = response.lastModified ?? null
    } catch (e) {
      error.value = getErrorMessage(e)
    } finally {
      loading.value = false
    }
  }

  /**
   * Apply a partial update. Only the keys present in `update` are changed.
   * Refreshes history afterwards so the change shows up immediately.
   */
  async function updateRuntimeConfig(update: RuntimeConfigUpdate): Promise<RuntimeConfigUpdateResponse> {
    loading.value = true
    error.value = null
    try {
      const result = await configApi.updateRuntimeConfig(update)
      runtimeConfig.value = result.current
      void fetchConfigHistory()
      return result
    } catch (e) {
      error.value = getErrorMessage(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function validateConfig(update: RuntimeConfigUpdate): Promise<RuntimeConfigValidateResponse> {
    return configApi.validateRuntimeConfig(update)
  }

  async function fetchConfigMeta() {
    try {
      configMeta.value = await configApi.getConfigMeta()
    } catch (e) {
      console.error('Failed to fetch config metadata', e)
      configMeta.value = []
    }
  }

  async function fetchConfigHistory() {
    try {
      const response = await configApi.getConfigHistory({ page: 1, pageSize: 20 })
      configHistory.value = response.changes
    } catch (e) {
      console.error('Failed to fetch config history', e)
    }
  }

  async function fetchCollectionConfigs() {
    loading.value = true
    error.value = null
    try {
      const response = await configApi.getCollectionConfigs()
      collectionConfigs.value = response.collections
      defaultCollectionConfig.value = response.defaultConfig
    } catch (e) {
      error.value = getErrorMessage(e)
    } finally {
      loading.value = false
    }
  }

  /** Strategy names accepted by the backend, fetched rather than hard-coded. */
  async function fetchChunkingStrategies() {
    if (chunkingStrategies.value.length > 0) return
    try {
      chunkingStrategies.value = await chunkingApi.getStrategies()
    } catch (e) {
      error.value = getErrorMessage(e)
    }
  }

  async function updateCollectionConfig(name: string, config: CollectionConfigUpdateRequest) {
    loading.value = true
    error.value = null
    try {
      await configApi.updateCollectionConfig(name, config)
      // The PUT response only echoes the stored overrides; reload to get the
      // merged effective config and document count for the list.
      const refreshed = await configApi.getCollectionConfig(name)
      const index = collectionConfigs.value.findIndex(c => c.collection === name)
      if (index >= 0) {
        collectionConfigs.value[index] = refreshed
      } else {
        collectionConfigs.value.push(refreshed)
      }
    } catch (e) {
      error.value = getErrorMessage(e)
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
      // The collection still exists; it just falls back to defaults.
      const refreshed = await configApi.getCollectionConfig(name)
      const index = collectionConfigs.value.findIndex(c => c.collection === name)
      if (index >= 0) {
        if (refreshed.documentCount > 0) {
          collectionConfigs.value[index] = refreshed
        } else {
          collectionConfigs.value.splice(index, 1)
        }
      }
    } catch (e) {
      error.value = getErrorMessage(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  return {
    runtimeConfig,
    hotSettings,
    warmSettings,
    lastModified,
    configMeta,
    configHistory,
    collectionConfigs,
    defaultCollectionConfig,
    chunkingStrategies,
    loading,
    error,
    fetchRuntimeConfig,
    updateRuntimeConfig,
    validateConfig,
    fetchConfigMeta,
    fetchConfigHistory,
    fetchCollectionConfigs,
    fetchChunkingStrategies,
    updateCollectionConfig,
    deleteCollectionConfig
  }
})
