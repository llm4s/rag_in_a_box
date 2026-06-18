<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useSuggestionsStore } from '@/stores/suggestions'
import type { SuggestionType, SuggestionSeverity } from '@/types/api'

const suggestionsStore = useSuggestionsStore()

const suggestions = computed(() => suggestionsStore.filteredSuggestions)
const loading = computed(() => suggestionsStore.loading)
const timeRange = computed(() => suggestionsStore.timeRange)
const analyzedQueries = computed(() => suggestionsStore.suggestions?.analyzedQueries || 0)

// Filter counts
const criticalCount = computed(() => suggestionsStore.criticalCount)
const warningCount = computed(() => suggestionsStore.warningCount)
const infoCount = computed(() => suggestionsStore.infoCount)
const chunkingCount = computed(() => suggestionsStore.chunkingCount)
const searchCount = computed(() => suggestionsStore.searchCount)
const embeddingCount = computed(() => suggestionsStore.embeddingCount)
const contentCount = computed(() => suggestionsStore.contentCount)

// Collections with suggestions
const collections = computed(() => suggestionsStore.collectionsWithSuggestions)

// Current filters
const selectedType = computed({
  get: () => suggestionsStore.selectedType,
  set: (value) => suggestionsStore.setTypeFilter(value)
})
const selectedSeverity = computed({
  get: () => suggestionsStore.selectedSeverity,
  set: (value) => suggestionsStore.setSeverityFilter(value)
})
const selectedCollection = computed({
  get: () => suggestionsStore.selectedCollection,
  set: (value) => suggestionsStore.setCollectionFilter(value)
})

// Expanded cards
const expandedCards = ref<Set<string>>(new Set())

const timeRangeOptions = [
  { title: 'Last 24 Hours', value: '24h' },
  { title: 'Last 7 Days', value: '7d' },
  { title: 'Last 30 Days', value: '30d' }
]

const typeOptions = [
  { title: 'All Types', value: null },
  { title: 'Chunking', value: 'chunking' },
  { title: 'Search', value: 'search' },
  { title: 'Embedding', value: 'embedding' },
  { title: 'Content', value: 'content' }
]

const severityOptions = [
  { title: 'All Severities', value: null },
  { title: 'Critical', value: 'critical' },
  { title: 'Warning', value: 'warning' },
  { title: 'Info', value: 'info' }
]

function getSeverityColor(severity: SuggestionSeverity): string {
  switch (severity) {
    case 'critical':
      return 'error'
    case 'warning':
      return 'warning'
    case 'info':
      return 'info'
    default:
      return 'grey'
  }
}

function getSeverityIcon(severity: SuggestionSeverity): string {
  switch (severity) {
    case 'critical':
      return 'mdi-alert-circle'
    case 'warning':
      return 'mdi-alert'
    case 'info':
      return 'mdi-information'
    default:
      return 'mdi-help-circle'
  }
}

function getTypeIcon(type: SuggestionType): string {
  switch (type) {
    case 'chunking':
      return 'mdi-file-document-multiple'
    case 'search':
      return 'mdi-magnify'
    case 'embedding':
      return 'mdi-vector-polyline'
    case 'content':
      return 'mdi-file-document'
    default:
      return 'mdi-help-circle'
  }
}

function getTypeColor(type: SuggestionType): string {
  switch (type) {
    case 'chunking':
      return 'purple'
    case 'search':
      return 'blue'
    case 'embedding':
      return 'teal'
    case 'content':
      return 'orange'
    default:
      return 'grey'
  }
}

function toggleExpanded(id: string) {
  if (expandedCards.value.has(id)) {
    expandedCards.value.delete(id)
  } else {
    expandedCards.value.add(id)
  }
}

function isExpanded(id: string): boolean {
  return expandedCards.value.has(id)
}

async function loadData() {
  await suggestionsStore.fetchSuggestions()
}

function onTimeRangeChange(value: '24h' | '7d' | '30d' | 'custom') {
  if (value !== 'custom') {
    suggestionsStore.setTimeRange(value)
    loadData()
  }
}

function clearFilters() {
  suggestionsStore.clearFilters()
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">Optimization Suggestions</h1>
      <v-spacer></v-spacer>
      <v-btn-toggle
        :model-value="timeRange"
        @update:model-value="onTimeRangeChange"
        mandatory
        color="primary"
        variant="outlined"
      >
        <v-btn v-for="option in timeRangeOptions" :key="option.value" :value="option.value" size="small">
          {{ option.title }}
        </v-btn>
      </v-btn-toggle>
      <v-btn class="ml-4" icon="mdi-refresh" variant="text" @click="loadData" :loading="loading" />
    </div>

    <!-- Summary Cards -->
    <v-row v-if="loading && !suggestionsStore.suggestions">
      <v-col v-for="i in 4" :key="i" cols="12" sm="6" md="3">
        <v-skeleton-loader type="card" height="120"></v-skeleton-loader>
      </v-col>
    </v-row>

    <v-row v-else>
      <v-col cols="12" sm="6" md="3">
        <v-card
          class="pa-4 cursor-pointer"
          :class="{ 'border-error': criticalCount > 0 }"
          :color="criticalCount > 0 ? 'error' : undefined"
          :variant="criticalCount > 0 ? 'tonal' : 'outlined'"
          @click="selectedSeverity = selectedSeverity === 'critical' ? null : 'critical'"
        >
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4" :color="criticalCount > 0 ? 'error' : 'grey'">mdi-alert-circle</v-icon>
            <div>
              <div class="text-h4">{{ criticalCount }}</div>
              <div class="text-subtitle-1">Critical Issues</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card
          class="pa-4 cursor-pointer"
          :color="warningCount > 0 ? 'warning' : undefined"
          :variant="warningCount > 0 ? 'tonal' : 'outlined'"
          @click="selectedSeverity = selectedSeverity === 'warning' ? null : 'warning'"
        >
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4" :color="warningCount > 0 ? 'warning' : 'grey'">mdi-alert</v-icon>
            <div>
              <div class="text-h4">{{ warningCount }}</div>
              <div class="text-subtitle-1">Warnings</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card
          class="pa-4 cursor-pointer"
          :color="infoCount > 0 ? 'info' : undefined"
          :variant="infoCount > 0 ? 'tonal' : 'outlined'"
          @click="selectedSeverity = selectedSeverity === 'info' ? null : 'info'"
        >
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4" :color="infoCount > 0 ? 'info' : 'grey'">mdi-information</v-icon>
            <div>
              <div class="text-h4">{{ infoCount }}</div>
              <div class="text-subtitle-1">Suggestions</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card class="pa-4" variant="outlined">
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4" color="primary">mdi-chart-line</v-icon>
            <div>
              <div class="text-h4">{{ analyzedQueries }}</div>
              <div class="text-subtitle-1">Queries Analyzed</div>
            </div>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <!-- Filters -->
    <v-row class="mt-4">
      <v-col cols="12" sm="4">
        <v-select
          v-model="selectedType"
          :items="typeOptions"
          item-title="title"
          item-value="value"
          label="Filter by Type"
          density="compact"
          variant="outlined"
          clearable
        />
      </v-col>
      <v-col cols="12" sm="4">
        <v-select
          v-model="selectedSeverity"
          :items="severityOptions"
          item-title="title"
          item-value="value"
          label="Filter by Severity"
          density="compact"
          variant="outlined"
          clearable
        />
      </v-col>
      <v-col cols="12" sm="4">
        <v-select
          v-model="selectedCollection"
          :items="[{ title: 'All Collections', value: null }, ...collections.map(c => ({ title: c, value: c }))]"
          item-title="title"
          item-value="value"
          label="Filter by Collection"
          density="compact"
          variant="outlined"
          clearable
        />
      </v-col>
    </v-row>

    <!-- Type chips -->
    <div class="mb-4 d-flex flex-wrap gap-2">
      <v-chip
        :color="selectedType === 'chunking' ? 'purple' : undefined"
        :variant="selectedType === 'chunking' ? 'elevated' : 'outlined'"
        @click="selectedType = selectedType === 'chunking' ? null : 'chunking'"
      >
        <v-icon start>mdi-file-document-multiple</v-icon>
        Chunking ({{ chunkingCount }})
      </v-chip>
      <v-chip
        :color="selectedType === 'search' ? 'blue' : undefined"
        :variant="selectedType === 'search' ? 'elevated' : 'outlined'"
        @click="selectedType = selectedType === 'search' ? null : 'search'"
      >
        <v-icon start>mdi-magnify</v-icon>
        Search ({{ searchCount }})
      </v-chip>
      <v-chip
        :color="selectedType === 'embedding' ? 'teal' : undefined"
        :variant="selectedType === 'embedding' ? 'elevated' : 'outlined'"
        @click="selectedType = selectedType === 'embedding' ? null : 'embedding'"
      >
        <v-icon start>mdi-vector-polyline</v-icon>
        Embedding ({{ embeddingCount }})
      </v-chip>
      <v-chip
        :color="selectedType === 'content' ? 'orange' : undefined"
        :variant="selectedType === 'content' ? 'elevated' : 'outlined'"
        @click="selectedType = selectedType === 'content' ? null : 'content'"
      >
        <v-icon start>mdi-file-document</v-icon>
        Content ({{ contentCount }})
      </v-chip>
      <v-btn v-if="selectedType || selectedSeverity || selectedCollection" variant="text" size="small" @click="clearFilters">
        Clear Filters
      </v-btn>
    </div>

    <!-- Suggestions List -->
    <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-4" />

    <div v-if="suggestions.length === 0 && !loading" class="text-center py-12">
      <v-icon size="64" color="success" class="mb-4">mdi-check-circle-outline</v-icon>
      <h3 class="text-h5 mb-2">No Issues Found</h3>
      <p class="text-body-1 text-grey">
        Your RAG system is performing well based on the analyzed queries. Keep monitoring!
      </p>
    </div>

    <v-card v-for="suggestion in suggestions" :key="suggestion.id" class="mb-3">
      <v-card-item>
        <template #prepend>
          <v-icon :color="getSeverityColor(suggestion.severity)" size="large">
            {{ getSeverityIcon(suggestion.severity) }}
          </v-icon>
        </template>

        <v-card-title class="d-flex align-center">
          {{ suggestion.title }}
          <v-chip v-if="suggestion.collection" size="x-small" class="ml-2" variant="outlined">
            {{ suggestion.collection }}
          </v-chip>
        </v-card-title>

        <v-card-subtitle>
          <v-chip size="x-small" :color="getTypeColor(suggestion.suggestionType)" variant="tonal" class="mr-2">
            <v-icon start size="x-small">{{ getTypeIcon(suggestion.suggestionType) }}</v-icon>
            {{ suggestion.suggestionType }}
          </v-chip>
          <v-chip size="x-small" :color="getSeverityColor(suggestion.severity)" variant="tonal">
            {{ suggestion.severity }}
          </v-chip>
        </v-card-subtitle>

        <template #append>
          <v-btn
            icon
            variant="text"
            @click="toggleExpanded(suggestion.id)"
          >
            <v-icon>{{ isExpanded(suggestion.id) ? 'mdi-chevron-up' : 'mdi-chevron-down' }}</v-icon>
          </v-btn>
        </template>
      </v-card-item>

      <v-card-text>
        <p>{{ suggestion.description }}</p>

        <v-alert type="info" variant="tonal" density="compact" class="mt-3">
          <strong>Recommendation:</strong> {{ suggestion.recommendation }}
        </v-alert>
      </v-card-text>

      <v-expand-transition>
        <div v-show="isExpanded(suggestion.id)">
          <v-divider />
          <v-card-text>
            <v-row>
              <v-col cols="12" md="6">
                <div class="text-subtitle-2 mb-2">Evidence</div>
                <v-table density="compact">
                  <tbody>
                    <tr>
                      <td class="text-grey">Metric</td>
                      <td>{{ suggestion.evidence.metric }}</td>
                    </tr>
                    <tr>
                      <td class="text-grey">Current Value</td>
                      <td>{{ suggestion.evidence.currentValue.toFixed(2) }}</td>
                    </tr>
                    <tr>
                      <td class="text-grey">Threshold</td>
                      <td>{{ suggestion.evidence.threshold.toFixed(2) }}</td>
                    </tr>
                    <tr>
                      <td class="text-grey">Sample Size</td>
                      <td>{{ suggestion.evidence.sampleSize }} queries</td>
                    </tr>
                    <tr>
                      <td class="text-grey">Time Range</td>
                      <td>{{ suggestion.evidence.timeRange }}</td>
                    </tr>
                  </tbody>
                </v-table>
              </v-col>
              <v-col cols="12" md="6">
                <div class="text-subtitle-2 mb-2">Configuration Change</div>
                <v-table density="compact">
                  <tbody>
                    <tr v-if="suggestion.currentValue">
                      <td class="text-grey">Current</td>
                      <td><code>{{ suggestion.currentValue }}</code></td>
                    </tr>
                    <tr v-if="suggestion.suggestedValue">
                      <td class="text-grey">Suggested</td>
                      <td><code class="text-success">{{ suggestion.suggestedValue }}</code></td>
                    </tr>
                    <tr>
                      <td class="text-grey">Est. Impact</td>
                      <td>{{ suggestion.estimatedImpact }}</td>
                    </tr>
                  </tbody>
                </v-table>
              </v-col>
            </v-row>
          </v-card-text>
        </div>
      </v-expand-transition>
    </v-card>
  </div>
</template>

<style scoped>
.cursor-pointer {
  cursor: pointer;
}

.gap-2 {
  gap: 8px;
}
</style>
