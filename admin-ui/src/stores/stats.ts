import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Stats, VisibilityStats } from '@/types/api'
import * as visibilityApi from '@/api/visibility'

export const useStatsStore = defineStore('stats', () => {
  const stats = ref<Stats | null>(null)
  const visibilityStats = ref<VisibilityStats | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchStats() {
    loading.value = true
    error.value = null
    try {
      visibilityStats.value = await visibilityApi.getVisibilityStats()
      stats.value = {
        documentCount: visibilityStats.value.documentCount,
        chunkCount: visibilityStats.value.chunkCount,
        collectionCount: visibilityStats.value.collectionCount,
        collections: []
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch stats'
    } finally {
      loading.value = false
    }
  }

  async function fetchCollections() {
    try {
      const collections = await visibilityApi.getCollections()
      if (stats.value) {
        stats.value.collections = collections
      }
    } catch (e) {
      console.error('Failed to fetch collections', e)
    }
  }

  return {
    stats,
    visibilityStats,
    loading,
    error,
    fetchStats,
    fetchCollections
  }
})
