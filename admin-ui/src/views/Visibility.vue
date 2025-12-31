<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import * as visibilityApi from '@/api/visibility'
import type { VisibilityStats, Chunk, CollectionStats } from '@/types/api'
import { type AppError, isAppError, getErrorMessage } from '@/composables/useApiError'
import ErrorAlert from '@/components/ErrorAlert.vue'
import { StatsCardSkeleton, ListSkeleton } from '@/components/skeletons'

const stats = ref<VisibilityStats | null>(null)
const collections = ref<CollectionStats[]>([])
const chunks = ref<Chunk[]>([])
const loading = ref(false)
const error = ref<AppError | null>(null)

const isInitialLoad = computed(() => loading.value && stats.value === null)
const page = ref(1)
const pageSize = ref(20)
const totalChunks = ref(0)

async function fetchData() {
  loading.value = true
  error.value = null
  try {
    stats.value = await visibilityApi.getVisibilityStats()
    collections.value = await visibilityApi.getCollections()
    await loadChunks()
  } catch (e) {
    error.value = isAppError(e) ? e : {
      code: 'FETCH_ERROR',
      message: getErrorMessage(e),
      retryable: true
    }
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)

async function loadChunks() {
  const result = await visibilityApi.getAllChunks({ page: page.value, pageSize: pageSize.value })
  chunks.value = result.chunks
  totalChunks.value = result.total
}

function onPageChange(newPage: number) {
  page.value = newPage
  loadChunks()
}
</script>

<template>
  <div>
    <h1 class="text-h4 mb-6">System Visibility</h1>

    <ErrorAlert
      :error="error"
      :on-retry="fetchData"
      dismissible
      @dismiss="error = null"
    />

    <!-- Stats Overview - Skeleton -->
    <StatsCardSkeleton v-if="isInitialLoad" :count="4" />

    <!-- Stats Overview -->
    <v-row v-else-if="stats">
      <v-col cols="6" sm="3">
        <v-card class="pa-4 text-center">
          <div class="text-h4">{{ stats.documentCount }}</div>
          <div class="text-caption">Documents</div>
        </v-card>
      </v-col>
      <v-col cols="6" sm="3">
        <v-card class="pa-4 text-center">
          <div class="text-h4">{{ stats.chunkCount }}</div>
          <div class="text-caption">Chunks</div>
        </v-card>
      </v-col>
      <v-col cols="6" sm="3">
        <v-card class="pa-4 text-center">
          <div class="text-h4">{{ stats.collectionCount }}</div>
          <div class="text-caption">Collections</div>
        </v-card>
      </v-col>
      <v-col cols="6" sm="3">
        <v-card class="pa-4 text-center">
          <div class="text-h4">{{ stats.avgChunksPerDocument?.toFixed(1) ?? '0' }}</div>
          <div class="text-caption">Avg Chunks/Doc</div>
        </v-card>
      </v-col>
    </v-row>

    <!-- Collections -->
    <h2 class="text-h5 mt-8 mb-4">Collections</h2>
    <ListSkeleton v-if="isInitialLoad" :items="3" show-avatar />
    <v-card v-else>
      <v-list v-if="collections?.length">
        <v-list-item v-for="col in collections" :key="col.name">
          <template v-slot:prepend>
            <v-icon>mdi-folder</v-icon>
          </template>
          <v-list-item-title>{{ col.name || 'default' }}</v-list-item-title>
          <v-list-item-subtitle>
            {{ col.documentCount }} documents, {{ col.chunkCount }} chunks
          </v-list-item-subtitle>
        </v-list-item>
      </v-list>
      <div v-else class="text-center pa-8 text-grey">
        No collections found
      </div>
    </v-card>

    <!-- Chunk Browser -->
    <h2 class="text-h5 mt-8 mb-4">Chunk Browser</h2>
    <ListSkeleton v-if="isInitialLoad" :items="5" />
    <v-card v-else>
      <v-expansion-panels v-if="chunks?.length">
        <v-expansion-panel v-for="chunk in chunks" :key="chunk.id">
          <v-expansion-panel-title>
            <v-chip size="x-small" class="mr-2">#{{ chunk.chunkIndex }}</v-chip>
            <span class="text-truncate">{{ chunk.content.substring(0, 80) }}...</span>
          </v-expansion-panel-title>
          <v-expansion-panel-text>
            <div class="text-caption text-grey mb-2">
              Document: {{ chunk.documentId.substring(0, 8) }}...
            </div>
            <pre style="white-space: pre-wrap; font-size: 0.875rem;">{{ chunk.content }}</pre>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>

      <div v-else class="text-center pa-8 text-grey">
        No chunks found
      </div>

      <div class="d-flex justify-center pa-4" v-if="Math.ceil(totalChunks / pageSize) > 1">
        <v-pagination
          :length="Math.ceil(totalChunks / pageSize)"
          :model-value="page"
          @update:model-value="onPageChange"
        ></v-pagination>
      </div>
    </v-card>
  </div>
</template>
