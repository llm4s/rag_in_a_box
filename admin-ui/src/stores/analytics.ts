import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { QueryLogListResponse, QueryAnalyticsSummary, QueryLogEntry } from '@/types/api'
import * as analyticsApi from '@/api/analytics'

export const useAnalyticsStore = defineStore('analytics', () => {
  const summary = ref<QueryAnalyticsSummary | null>(null)
  const queryList = ref<QueryLogListResponse | null>(null)
  const selectedQuery = ref<QueryLogEntry | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  // Time range for queries (default: last 7 days)
  const timeRange = ref<'24h' | '7d' | '30d' | 'custom'>('7d')
  const customFrom = ref<string | null>(null)
  const customTo = ref<string | null>(null)

  const dateRange = computed(() => {
    const now = new Date()
    let from: Date

    switch (timeRange.value) {
      case '24h':
        from = new Date(now.getTime() - 24 * 60 * 60 * 1000)
        break
      case '7d':
        from = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
        break
      case '30d':
        from = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
        break
      case 'custom':
        return {
          from: customFrom.value || now.toISOString(),
          to: customTo.value || now.toISOString()
        }
    }

    return {
      from: from.toISOString(),
      to: now.toISOString()
    }
  })

  async function fetchSummary() {
    loading.value = true
    error.value = null
    try {
      const { from, to } = dateRange.value
      summary.value = await analyticsApi.getQuerySummary(from, to)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch analytics summary'
    } finally {
      loading.value = false
    }
  }

  async function fetchQueryList(page = 1, pageSize = 20, collection?: string) {
    loading.value = true
    error.value = null
    try {
      const { from, to } = dateRange.value
      queryList.value = await analyticsApi.getQueryList({
        from,
        to,
        collection,
        page,
        pageSize
      })
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch query list'
    } finally {
      loading.value = false
    }
  }

  async function fetchQueryById(id: string) {
    loading.value = true
    error.value = null
    try {
      selectedQuery.value = await analyticsApi.getQueryById(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch query details'
    } finally {
      loading.value = false
    }
  }

  async function submitFeedback(queryId: string, rating: number, comment?: string) {
    try {
      await analyticsApi.submitFeedback({ queryId, rating, comment })
      // Refresh the query to show updated rating
      await fetchQueryById(queryId)
      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to submit feedback'
      return false
    }
  }

  function setTimeRange(range: '24h' | '7d' | '30d' | 'custom') {
    timeRange.value = range
  }

  function setCustomRange(from: string, to: string) {
    customFrom.value = from
    customTo.value = to
    timeRange.value = 'custom'
  }

  return {
    summary,
    queryList,
    selectedQuery,
    loading,
    error,
    timeRange,
    dateRange,
    fetchSummary,
    fetchQueryList,
    fetchQueryById,
    submitFeedback,
    setTimeRange,
    setCustomRange
  }
})
