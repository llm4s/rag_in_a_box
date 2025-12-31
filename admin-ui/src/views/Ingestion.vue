<script setup lang="ts">
import { ref, onMounted } from 'vue'
import * as ingestionApi from '@/api/ingestion'
import type { IngestionStatus, IngestionSource } from '@/types/api'

const status = ref<IngestionStatus | null>(null)
const sources = ref<IngestionSource[]>([])
const loading = ref(false)
const running = ref(false)
const message = ref<{ type: 'success' | 'error'; text: string } | null>(null)

// Manual ingestion
const urlInput = ref('')
const urlCollection = ref('')

onMounted(async () => {
  loading.value = true
  try {
    status.value = await ingestionApi.getStatus()
    sources.value = await ingestionApi.getSources()
  } finally {
    loading.value = false
  }
})

async function runFullIngestion() {
  running.value = true
  message.value = null
  try {
    const result = await ingestionApi.runIngestion()
    message.value = { type: 'success', text: result.message }
    status.value = await ingestionApi.getStatus()
  } catch (e) {
    message.value = { type: 'error', text: e instanceof Error ? e.message : 'Ingestion failed' }
  } finally {
    running.value = false
  }
}

async function ingestUrl() {
  if (!urlInput.value.trim()) return

  running.value = true
  message.value = null
  try {
    const result = await ingestionApi.ingestUrl(urlInput.value, {
      collection: urlCollection.value || undefined
    })
    message.value = { type: 'success', text: `Document created: ${result.documentId}` }
    urlInput.value = ''
    urlCollection.value = ''
  } catch (e) {
    message.value = { type: 'error', text: e instanceof Error ? e.message : 'URL ingestion failed' }
  } finally {
    running.value = false
  }
}
</script>

<template>
  <div>
    <h1 class="text-h4 mb-6">Ingestion</h1>

    <v-progress-linear v-if="loading" indeterminate class="mb-4"></v-progress-linear>

    <v-alert v-if="message" :type="message.type" class="mb-4" closable @click:close="message = null">
      {{ message.text }}
    </v-alert>

    <!-- Status Card -->
    <v-card class="mb-4">
      <v-card-title>Status</v-card-title>
      <v-card-text>
        <v-row v-if="status">
          <v-col cols="6" sm="3">
            <div class="text-caption text-grey">Status</div>
            <v-chip :color="status.running ? 'warning' : 'success'" size="small">
              {{ status.running ? 'Running' : 'Idle' }}
            </v-chip>
          </v-col>
          <v-col cols="6" sm="3" v-if="status.lastCompletedAt">
            <div class="text-caption text-grey">Last Run</div>
            <div>{{ new Date(status.lastCompletedAt).toLocaleString() }}</div>
          </v-col>
          <v-col cols="6" sm="3" v-if="status.lastResult">
            <div class="text-caption text-grey">Last Result</div>
            <div>{{ status.lastResult }}</div>
          </v-col>
          <v-col cols="6" sm="3">
            <v-btn
              color="primary"
              @click="runFullIngestion"
              :loading="running"
              :disabled="status.running"
            >
              <v-icon start>mdi-play</v-icon>
              Run Ingestion
            </v-btn>
          </v-col>
        </v-row>
      </v-card-text>
    </v-card>

    <!-- URL Ingestion -->
    <v-card class="mb-4">
      <v-card-title>Ingest URL</v-card-title>
      <v-card-text>
        <v-row>
          <v-col cols="12" sm="6">
            <v-text-field
              v-model="urlInput"
              label="URL"
              placeholder="https://example.com/document.html"
              prepend-inner-icon="mdi-link"
            ></v-text-field>
          </v-col>
          <v-col cols="12" sm="4">
            <v-text-field
              v-model="urlCollection"
              label="Collection (optional)"
              placeholder="default"
            ></v-text-field>
          </v-col>
          <v-col cols="12" sm="2" class="d-flex align-center">
            <v-btn
              color="primary"
              @click="ingestUrl"
              :loading="running"
              :disabled="!urlInput.trim()"
            >
              Ingest
            </v-btn>
          </v-col>
        </v-row>
      </v-card-text>
    </v-card>

    <!-- Sources -->
    <v-card>
      <v-card-title>Configured Sources</v-card-title>
      <v-card-text>
        <v-list v-if="sources?.length">
          <v-list-item v-for="source in sources" :key="source.id">
            <template v-slot:prepend>
              <v-icon>{{ source.type === 'directory' ? 'mdi-folder' : 'mdi-web' }}</v-icon>
            </template>
            <v-list-item-title>{{ source.name }}</v-list-item-title>
            <v-list-item-subtitle>
              {{ source.type }} | {{ source.path || source.url }}
              <span v-if="source.schedule"> | Schedule: {{ source.schedule }}</span>
            </v-list-item-subtitle>
            <template v-slot:append>
              <v-chip :color="source.enabled ? 'success' : 'grey'" size="small">
                {{ source.enabled ? 'Enabled' : 'Disabled' }}
              </v-chip>
            </template>
          </v-list-item>
        </v-list>

        <div v-else class="text-center pa-8 text-grey">
          <v-icon size="64">mdi-database-import-outline</v-icon>
          <p class="mt-4">No ingestion sources configured</p>
        </div>
      </v-card-text>
    </v-card>
  </div>
</template>
