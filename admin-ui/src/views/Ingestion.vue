<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import * as ingestionApi from '@/api/ingestion'
import type { IngestionStatus, IngestResult } from '@/types/api'
import { useWebSocketStore } from '@/stores/websocket'
import { useNotification } from '@/composables/useNotification'
import { type AppError, isAppError, getErrorMessage } from '@/composables/useApiError'
import ErrorAlert from '@/components/ErrorAlert.vue'

const notification = useNotification()
const wsStore = useWebSocketStore()

const status = ref<IngestionStatus | null>(null)
const loading = ref(false)
const runningSource = ref<string | null>(null)
const runningAll = ref(false)
const error = ref<AppError | null>(null)

// Polling interval for status updates
let statusInterval: ReturnType<typeof setInterval> | null = null

const sources = computed(() => status.value?.sources ?? [])
const lastResults = computed(() => status.value?.lastResults ?? [])

// Get progress for a source from WebSocket
function getSourceProgress(sourceName: string) {
  return wsStore.ingestionProgress.get(sourceName)
}

// Check if any source is running
const isAnyRunning = computed(() => {
  return status.value?.running || runningAll.value || runningSource.value !== null
})

async function loadStatus() {
  try {
    status.value = await ingestionApi.getStatus()
  } catch (e) {
    console.error('Failed to load ingestion status:', e)
  }
}

async function refreshStatus() {
  loading.value = true
  error.value = null
  try {
    await loadStatus()
  } catch (e) {
    error.value = isAppError(e) ? e : {
      code: 'LOAD_ERROR',
      message: getErrorMessage(e),
      retryable: true
    }
  } finally {
    loading.value = false
  }
}

async function runAllSources() {
  runningAll.value = true
  error.value = null
  try {
    const results = await ingestionApi.runAllSources()
    const totalDocs = results.reduce((sum, r) => sum + r.documentsAdded + r.documentsUpdated, 0)
    const failures = results.filter(r => r.error).length

    if (failures > 0) {
      notification.warning(`Ingestion completed with ${failures} error(s). ${totalDocs} documents processed.`)
    } else {
      notification.success(`Ingestion complete! ${totalDocs} documents processed.`)
    }
    await loadStatus()
  } catch (e) {
    error.value = isAppError(e) ? e : {
      code: 'INGESTION_ERROR',
      message: getErrorMessage(e),
      retryable: true
    }
  } finally {
    runningAll.value = false
  }
}

async function runSingleSource(sourceName: string) {
  runningSource.value = sourceName
  error.value = null
  try {
    const result = await ingestionApi.runSource(sourceName)
    const totalDocs = result.documentsAdded + result.documentsUpdated

    if (result.error) {
      notification.error(`${sourceName} failed: ${result.error}`)
    } else {
      notification.success(`${sourceName} complete! ${totalDocs} documents processed.`)
    }
    await loadStatus()
  } catch (e) {
    error.value = isAppError(e) ? e : {
      code: 'INGESTION_ERROR',
      message: getErrorMessage(e),
      retryable: true
    }
  } finally {
    runningSource.value = null
    wsStore.clearIngestionProgress(sourceName)
  }
}

function getSourceIcon(sourceType: string): string {
  switch (sourceType) {
    case 'directory': return 'mdi-folder'
    case 'url': return 'mdi-web'
    case 'database': return 'mdi-database'
    default: return 'mdi-file-document'
  }
}

function getSourceColor(sourceType: string): string {
  switch (sourceType) {
    case 'directory': return 'primary'
    case 'url': return 'secondary'
    case 'database': return 'accent'
    default: return 'grey'
  }
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60000).toFixed(1)}m`
}

function formatDate(dateStr: string | undefined): string {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString()
}

function getResultForSource(sourceName: string): IngestResult | undefined {
  return lastResults.value.find(r => r.sourceName === sourceName)
}

function getResultSummary(result: IngestResult): string {
  const parts: string[] = []
  if (result.documentsAdded > 0) parts.push(`+${result.documentsAdded}`)
  if (result.documentsUpdated > 0) parts.push(`~${result.documentsUpdated}`)
  if (result.documentsDeleted > 0) parts.push(`-${result.documentsDeleted}`)
  if (result.documentsFailed > 0) parts.push(`!${result.documentsFailed}`)
  return parts.length > 0 ? parts.join(' ') : 'No changes'
}

function getResultColor(result: IngestResult): string {
  if (result.error) return 'error'
  if (result.documentsFailed > 0) return 'warning'
  return 'success'
}

onMounted(() => {
  refreshStatus()
  // Poll for status updates every 5 seconds
  statusInterval = setInterval(loadStatus, 5000)
})

onUnmounted(() => {
  if (statusInterval) {
    clearInterval(statusInterval)
  }
})
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">Ingestion Dashboard</h1>
      <v-spacer></v-spacer>
      <v-chip
        v-if="wsStore.isConnected"
        color="success"
        size="small"
        variant="outlined"
        class="mr-2"
      >
        <v-icon start size="small">mdi-access-point</v-icon>
        Live
      </v-chip>
      <v-btn
        color="primary"
        @click="runAllSources"
        :loading="runningAll"
        :disabled="isAnyRunning"
      >
        <v-icon start>mdi-play</v-icon>
        Run All Sources
      </v-btn>
    </div>

    <ErrorAlert
      :error="error"
      dismissible
      @dismiss="error = null"
    />

    <!-- Overall Status Card -->
    <v-card class="mb-4">
      <v-card-title class="d-flex align-center">
        <v-icon start>mdi-information</v-icon>
        System Status
        <v-spacer></v-spacer>
        <v-btn icon size="small" @click="refreshStatus" :loading="loading">
          <v-icon>mdi-refresh</v-icon>
        </v-btn>
      </v-card-title>
      <v-card-text>
        <v-row>
          <v-col cols="6" sm="3">
            <div class="text-caption text-grey">Status</div>
            <v-chip :color="status?.running ? 'warning' : 'success'" size="small">
              <v-icon start size="small">
                {{ status?.running ? 'mdi-loading mdi-spin' : 'mdi-check-circle' }}
              </v-icon>
              {{ status?.running ? 'Running' : 'Idle' }}
            </v-chip>
          </v-col>
          <v-col cols="6" sm="3">
            <div class="text-caption text-grey">Sources</div>
            <div class="text-body-1">
              {{ sources.filter(s => s.enabled).length }} / {{ sources.length }} enabled
            </div>
          </v-col>
          <v-col cols="6" sm="3">
            <div class="text-caption text-grey">Last Run</div>
            <div class="text-body-2">{{ formatDate(status?.lastRun) }}</div>
          </v-col>
          <v-col cols="6" sm="3">
            <div class="text-caption text-grey">Next Scheduled</div>
            <div class="text-body-2">{{ formatDate(status?.nextScheduledRun) }}</div>
          </v-col>
        </v-row>
      </v-card-text>
    </v-card>

    <!-- Sources List -->
    <v-card class="mb-4">
      <v-card-title>
        <v-icon start>mdi-source-branch</v-icon>
        Ingestion Sources
      </v-card-title>

      <v-skeleton-loader v-if="loading && !sources.length" type="list-item@3"></v-skeleton-loader>

      <v-list v-else-if="sources.length" lines="three">
        <template v-for="(source, index) in sources" :key="source.name">
          <v-divider v-if="index > 0"></v-divider>

          <v-list-item>
            <template v-slot:prepend>
              <v-avatar :color="getSourceColor(source.sourceType)" size="40">
                <v-icon color="white">{{ getSourceIcon(source.sourceType) }}</v-icon>
              </v-avatar>
            </template>

            <v-list-item-title class="font-weight-medium">
              {{ source.name }}
              <v-chip
                size="x-small"
                :color="source.enabled ? 'success' : 'grey'"
                class="ml-2"
              >
                {{ source.enabled ? 'Enabled' : 'Disabled' }}
              </v-chip>
            </v-list-item-title>

            <v-list-item-subtitle>
              <div class="d-flex flex-wrap gap-2 mt-1">
                <v-chip size="x-small" variant="outlined">
                  {{ source.sourceType }}
                </v-chip>
                <v-chip
                  v-for="(value, key) in source.config"
                  :key="key"
                  size="x-small"
                  variant="text"
                >
                  {{ key }}: {{ value }}
                </v-chip>
              </div>
            </v-list-item-subtitle>

            <!-- Progress bar for running source -->
            <div v-if="getSourceProgress(source.name)" class="mt-2">
              <v-progress-linear
                :model-value="getSourceProgress(source.name)?.progress ?? 0"
                :color="getSourceProgress(source.name)?.status === 'failed' ? 'error' : 'primary'"
                height="6"
                rounded
              ></v-progress-linear>
              <div class="text-caption text-grey mt-1">
                {{ getSourceProgress(source.name)?.message || 'Processing...' }}
              </div>
            </div>

            <!-- Last result summary -->
            <div v-else-if="getResultForSource(source.name)" class="mt-2">
              <v-chip
                :color="getResultColor(getResultForSource(source.name)!)"
                size="x-small"
                class="mr-2"
              >
                {{ getResultSummary(getResultForSource(source.name)!) }}
              </v-chip>
              <span class="text-caption text-grey">
                {{ formatDuration(getResultForSource(source.name)!.durationMs) }}
              </span>
              <span v-if="getResultForSource(source.name)!.error" class="text-caption text-error ml-2">
                {{ getResultForSource(source.name)!.error }}
              </span>
            </div>

            <template v-slot:append>
              <v-btn
                icon
                variant="text"
                color="primary"
                @click="runSingleSource(source.name)"
                :loading="runningSource === source.name"
                :disabled="isAnyRunning || !source.enabled"
              >
                <v-icon>mdi-play</v-icon>
                <v-tooltip activator="parent" location="top">Run Now</v-tooltip>
              </v-btn>
            </template>
          </v-list-item>
        </template>
      </v-list>

      <div v-else class="text-center pa-8 text-grey">
        <v-icon size="64" class="mb-4">mdi-database-import-outline</v-icon>
        <p>No ingestion sources configured</p>
        <p class="text-caption">Add sources in your application.conf</p>
      </div>
    </v-card>

    <!-- Last Results Summary -->
    <v-card v-if="lastResults.length > 0">
      <v-card-title>
        <v-icon start>mdi-history</v-icon>
        Last Ingestion Results
      </v-card-title>

      <v-table density="compact">
        <thead>
          <tr>
            <th>Source</th>
            <th>Type</th>
            <th class="text-center">Added</th>
            <th class="text-center">Updated</th>
            <th class="text-center">Deleted</th>
            <th class="text-center">Unchanged</th>
            <th class="text-center">Failed</th>
            <th>Duration</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="result in lastResults" :key="result.sourceName">
            <td class="font-weight-medium">{{ result.sourceName }}</td>
            <td>
              <v-chip size="x-small" variant="outlined">
                {{ result.sourceType }}
              </v-chip>
            </td>
            <td class="text-center">
              <span :class="result.documentsAdded > 0 ? 'text-success' : 'text-grey'">
                {{ result.documentsAdded }}
              </span>
            </td>
            <td class="text-center">
              <span :class="result.documentsUpdated > 0 ? 'text-info' : 'text-grey'">
                {{ result.documentsUpdated }}
              </span>
            </td>
            <td class="text-center">
              <span :class="result.documentsDeleted > 0 ? 'text-warning' : 'text-grey'">
                {{ result.documentsDeleted }}
              </span>
            </td>
            <td class="text-center text-grey">{{ result.documentsUnchanged }}</td>
            <td class="text-center">
              <span :class="result.documentsFailed > 0 ? 'text-error' : 'text-grey'">
                {{ result.documentsFailed }}
              </span>
            </td>
            <td>{{ formatDuration(result.durationMs) }}</td>
            <td>
              <v-chip :color="getResultColor(result)" size="x-small">
                {{ result.error ? 'Error' : 'Success' }}
              </v-chip>
            </td>
          </tr>
        </tbody>
        <tfoot>
          <tr class="font-weight-bold">
            <td colspan="2">Total</td>
            <td class="text-center">{{ lastResults.reduce((s, r) => s + r.documentsAdded, 0) }}</td>
            <td class="text-center">{{ lastResults.reduce((s, r) => s + r.documentsUpdated, 0) }}</td>
            <td class="text-center">{{ lastResults.reduce((s, r) => s + r.documentsDeleted, 0) }}</td>
            <td class="text-center">{{ lastResults.reduce((s, r) => s + r.documentsUnchanged, 0) }}</td>
            <td class="text-center">{{ lastResults.reduce((s, r) => s + r.documentsFailed, 0) }}</td>
            <td colspan="2"></td>
          </tr>
        </tfoot>
      </v-table>
    </v-card>
  </div>
</template>
