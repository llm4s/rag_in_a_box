import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  SuggestionsResponse,
  SuggestionsSummary,
  OptimizationSuggestion,
  SuggestionType,
  SuggestionSeverity
} from '@/types/api'
import * as suggestionsApi from '@/api/suggestions'

export const useSuggestionsStore = defineStore('suggestions', () => {
  const suggestions = ref<SuggestionsResponse | null>(null)
  const summary = ref<SuggestionsSummary | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  // Filter state
  const selectedType = ref<SuggestionType | null>(null)
  const selectedSeverity = ref<SuggestionSeverity | null>(null)
  const selectedCollection = ref<string | null>(null)

  // Time range for analysis (default: last 7 days)
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

  // Filtered suggestions
  const filteredSuggestions = computed((): OptimizationSuggestion[] => {
    if (!suggestions.value) return []

    return suggestions.value.suggestions.filter((s) => {
      if (selectedType.value && s.suggestionType !== selectedType.value) return false
      if (selectedSeverity.value && s.severity !== selectedSeverity.value) return false
      if (selectedCollection.value && s.collection !== selectedCollection.value) return false
      return true
    })
  })

  // Counts by severity
  const criticalCount = computed(() =>
    suggestions.value?.suggestions.filter((s) => s.severity === 'critical').length || 0
  )
  const warningCount = computed(() =>
    suggestions.value?.suggestions.filter((s) => s.severity === 'warning').length || 0
  )
  const infoCount = computed(() =>
    suggestions.value?.suggestions.filter((s) => s.severity === 'info').length || 0
  )

  // Counts by type
  const chunkingCount = computed(() =>
    suggestions.value?.suggestions.filter((s) => s.suggestionType === 'chunking').length || 0
  )
  const searchCount = computed(() =>
    suggestions.value?.suggestions.filter((s) => s.suggestionType === 'search').length || 0
  )
  const embeddingCount = computed(() =>
    suggestions.value?.suggestions.filter((s) => s.suggestionType === 'embedding').length || 0
  )
  const contentCount = computed(() =>
    suggestions.value?.suggestions.filter((s) => s.suggestionType === 'content').length || 0
  )

  // Unique collections with suggestions
  const collectionsWithSuggestions = computed(() => {
    if (!suggestions.value) return []
    const collections = suggestions.value.suggestions
      .map((s) => s.collection)
      .filter((c): c is string => c !== undefined && c !== null)
    return [...new Set(collections)]
  })

  async function fetchSuggestions() {
    loading.value = true
    error.value = null
    try {
      const { from, to } = dateRange.value
      suggestions.value = await suggestionsApi.getSuggestions({
        from,
        to,
        collection: selectedCollection.value || undefined,
        type: selectedType.value || undefined,
        severity: selectedSeverity.value || undefined
      })
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch suggestions'
    } finally {
      loading.value = false
    }
  }

  async function fetchSummary() {
    loading.value = true
    error.value = null
    try {
      const { from, to } = dateRange.value
      summary.value = await suggestionsApi.getSuggestionsSummary(from, to)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch suggestions summary'
    } finally {
      loading.value = false
    }
  }

  async function fetchCollectionSuggestions(collectionName: string) {
    loading.value = true
    error.value = null
    try {
      const { from, to } = dateRange.value
      suggestions.value = await suggestionsApi.getCollectionSuggestions(collectionName, from, to)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch collection suggestions'
    } finally {
      loading.value = false
    }
  }

  function setTypeFilter(type: SuggestionType | null) {
    selectedType.value = type
  }

  function setSeverityFilter(severity: SuggestionSeverity | null) {
    selectedSeverity.value = severity
  }

  function setCollectionFilter(collection: string | null) {
    selectedCollection.value = collection
  }

  function clearFilters() {
    selectedType.value = null
    selectedSeverity.value = null
    selectedCollection.value = null
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
    // State
    suggestions,
    summary,
    loading,
    error,
    timeRange,
    dateRange,

    // Filters
    selectedType,
    selectedSeverity,
    selectedCollection,
    filteredSuggestions,
    collectionsWithSuggestions,

    // Computed counts
    criticalCount,
    warningCount,
    infoCount,
    chunkingCount,
    searchCount,
    embeddingCount,
    contentCount,

    // Actions
    fetchSuggestions,
    fetchSummary,
    fetchCollectionSuggestions,
    setTypeFilter,
    setSeverityFilter,
    setCollectionFilter,
    clearFilters,
    setTimeRange,
    setCustomRange
  }
})
