<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useStatsStore } from '@/stores/stats'
import { StatsCardSkeleton } from '@/components/skeletons'

const router = useRouter()
const statsStore = useStatsStore()

const stats = computed(() => statsStore.visibilityStats)
const loading = computed(() => statsStore.loading)
const hasData = computed(() => stats.value !== null)

onMounted(() => {
  statsStore.fetchStats()
  statsStore.fetchCollections()
})

function navigateTo(path: string) {
  router.push(path)
}
</script>

<template>
  <div>
    <h1 class="text-h4 mb-6">Dashboard</h1>

    <!-- Stats Cards - Skeleton -->
    <StatsCardSkeleton v-if="loading && !hasData" :count="4" />

    <!-- Stats Cards - Content -->
    <v-row v-else>
      <v-col cols="12" sm="6" md="3">
        <v-card class="pa-4" color="primary" variant="tonal" @click="navigateTo('/documents')">
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4">mdi-file-document-multiple</v-icon>
            <div>
              <div class="text-h4">{{ stats?.documentCount ?? 0 }}</div>
              <div class="text-subtitle-1">Documents</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card class="pa-4" color="secondary" variant="tonal" @click="navigateTo('/visibility')">
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4">mdi-puzzle</v-icon>
            <div>
              <div class="text-h4">{{ stats?.chunkCount ?? 0 }}</div>
              <div class="text-subtitle-1">Chunks</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card class="pa-4" color="accent" variant="tonal" @click="navigateTo('/config/collections')">
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4">mdi-folder-multiple</v-icon>
            <div>
              <div class="text-h4">{{ stats?.collectionCount ?? 0 }}</div>
              <div class="text-subtitle-1">Collections</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card class="pa-4" color="info" variant="tonal">
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4">mdi-chart-bar</v-icon>
            <div>
              <div class="text-h4">{{ stats?.avgChunksPerDocument?.toFixed(1) ?? '0' }}</div>
              <div class="text-subtitle-1">Avg Chunks/Doc</div>
            </div>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <!-- Quick Actions -->
    <h2 class="text-h5 mt-8 mb-4">Quick Actions</h2>
    <v-row>
      <v-col cols="12" sm="6" md="3">
        <v-btn
          block
          color="primary"
          size="large"
          prepend-icon="mdi-upload"
          @click="navigateTo('/documents/upload')"
        >
          Upload Document
        </v-btn>
      </v-col>
      <v-col cols="12" sm="6" md="3">
        <v-btn
          block
          color="secondary"
          size="large"
          prepend-icon="mdi-cog"
          @click="navigateTo('/config/runtime')"
        >
          Configure
        </v-btn>
      </v-col>
      <v-col cols="12" sm="6" md="3">
        <v-btn
          block
          color="accent"
          size="large"
          prepend-icon="mdi-scissors-cutting"
          @click="navigateTo('/chunking')"
        >
          Chunking Preview
        </v-btn>
      </v-col>
      <v-col cols="12" sm="6" md="3">
        <v-btn
          block
          color="info"
          size="large"
          prepend-icon="mdi-database-import"
          @click="navigateTo('/ingestion')"
        >
          Run Ingestion
        </v-btn>
      </v-col>
    </v-row>

    <!-- Chunk Size Distribution -->
    <div class="mt-8">
      <h2 class="text-h5 mb-4">Chunk Size Distribution</h2>

      <!-- Skeleton for distribution -->
      <v-card v-if="loading && !hasData" class="pa-4">
        <v-row>
          <v-col v-for="i in 4" :key="i" cols="6" sm="3">
            <div class="text-center">
              <v-skeleton-loader type="heading" class="mx-auto mb-1" width="60px" />
              <v-skeleton-loader type="text" class="mx-auto" width="80px" />
            </div>
          </v-col>
        </v-row>
      </v-card>

      <!-- Content -->
      <v-card v-else-if="stats?.chunkSizeDistribution" class="pa-4">
        <v-row>
          <v-col cols="6" sm="3">
            <div class="text-center">
              <div class="text-h6">{{ stats.chunkSizeDistribution.min }}</div>
              <div class="text-caption">Min Size</div>
            </div>
          </v-col>
          <v-col cols="6" sm="3">
            <div class="text-center">
              <div class="text-h6">{{ stats.chunkSizeDistribution.max }}</div>
              <div class="text-caption">Max Size</div>
            </div>
          </v-col>
          <v-col cols="6" sm="3">
            <div class="text-center">
              <div class="text-h6">{{ stats.chunkSizeDistribution.avg?.toFixed(0) ?? '0' }}</div>
              <div class="text-caption">Average Size</div>
            </div>
          </v-col>
          <v-col cols="6" sm="3">
            <div class="text-center">
              <div class="text-h6">{{ stats.chunkSizeDistribution.median }}</div>
              <div class="text-caption">Median Size</div>
            </div>
          </v-col>
        </v-row>
      </v-card>

      <!-- Empty state -->
      <v-card v-else class="pa-4 text-center text-grey">
        No chunk data available yet
      </v-card>
    </div>
  </div>
</template>
