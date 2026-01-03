<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useAnalyticsStore } from '@/stores/analytics'
import { LatencyChart } from '@/components/charts'

const analyticsStore = useAnalyticsStore()

const summary = computed(() => analyticsStore.summary)
const queryList = computed(() => analyticsStore.queryList)
const loading = computed(() => analyticsStore.loading)
const timeRange = computed(() => analyticsStore.timeRange)

const currentPage = ref(1)
const pageSize = ref(20)
const selectedQueryId = ref<string | null>(null)
const feedbackDialog = ref(false)
const feedbackRating = ref(3)
const feedbackComment = ref('')

const timeRangeOptions = [
  { title: 'Last 24 Hours', value: '24h' },
  { title: 'Last 7 Days', value: '7d' },
  { title: 'Last 30 Days', value: '30d' }
]

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString()
}

function formatLatency(ms: number | undefined) {
  if (ms === undefined) return '-'
  if (ms < 1000) return `${ms.toFixed(0)}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function getRatingColor(rating: number | undefined) {
  if (rating === undefined) return 'grey'
  if (rating >= 4) return 'success'
  if (rating >= 3) return 'warning'
  return 'error'
}

function getRatingIcon(rating: number | undefined) {
  if (rating === undefined) return 'mdi-star-outline'
  if (rating >= 4) return 'mdi-star'
  if (rating >= 2) return 'mdi-star-half-full'
  return 'mdi-star-off'
}

async function loadData() {
  await Promise.all([
    analyticsStore.fetchSummary(),
    analyticsStore.fetchQueryList(currentPage.value, pageSize.value)
  ])
}

function onTimeRangeChange(value: '24h' | '7d' | '30d' | 'custom') {
  if (value !== 'custom') {
    analyticsStore.setTimeRange(value)
    currentPage.value = 1
    loadData()
  }
}

function onPageChange(page: number) {
  currentPage.value = page
  analyticsStore.fetchQueryList(page, pageSize.value)
}

function openFeedbackDialog(queryId: string) {
  selectedQueryId.value = queryId
  feedbackRating.value = 3
  feedbackComment.value = ''
  feedbackDialog.value = true
}

async function submitFeedback() {
  if (selectedQueryId.value) {
    const success = await analyticsStore.submitFeedback(
      selectedQueryId.value,
      feedbackRating.value,
      feedbackComment.value || undefined
    )
    if (success) {
      feedbackDialog.value = false
      await analyticsStore.fetchQueryList(currentPage.value, pageSize.value)
    }
  }
}

onMounted(() => {
  loadData()
})

// Pagination info
const totalPages = computed(() => {
  if (!queryList.value) return 1
  return Math.ceil(queryList.value.total / pageSize.value)
})
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">Query Analytics</h1>
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
    </div>

    <!-- Summary Cards -->
    <v-row v-if="loading && !summary">
      <v-col v-for="i in 4" :key="i" cols="12" sm="6" md="3">
        <v-skeleton-loader type="card" height="120"></v-skeleton-loader>
      </v-col>
    </v-row>

    <v-row v-else-if="summary">
      <v-col cols="12" sm="6" md="3">
        <v-card class="pa-4" color="primary" variant="tonal">
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4">mdi-magnify</v-icon>
            <div>
              <div class="text-h4">{{ summary.totalQueries }}</div>
              <div class="text-subtitle-1">Total Queries</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card class="pa-4" color="secondary" variant="tonal">
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4">mdi-timer</v-icon>
            <div>
              <div class="text-h4">{{ formatLatency(summary.averageLatencyMs) }}</div>
              <div class="text-subtitle-1">Avg Latency</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card class="pa-4" color="accent" variant="tonal">
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4">mdi-star</v-icon>
            <div>
              <div class="text-h4">
                {{ summary.averageRating?.toFixed(1) ?? '-' }}
                <span class="text-body-2">/5</span>
              </div>
              <div class="text-subtitle-1">Avg Rating</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card class="pa-4" color="info" variant="tonal">
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4">mdi-comment-text</v-icon>
            <div>
              <div class="text-h4">{{ summary.queriesWithFeedback }}</div>
              <div class="text-subtitle-1">With Feedback</div>
            </div>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <!-- Latency Distribution & Top Collections -->
    <v-row class="mt-4">
      <v-col cols="12" md="6">
        <v-card class="pa-4">
          <h3 class="text-subtitle-1 font-weight-medium mb-4">Latency Percentiles</h3>
          <v-skeleton-loader v-if="loading && !summary" type="image" height="200"></v-skeleton-loader>
          <LatencyChart
            v-else-if="summary"
            :p50="summary.p50LatencyMs"
            :p95="summary.p95LatencyMs"
            :p99="summary.p99LatencyMs"
            :average="summary.averageLatencyMs"
          />
          <div v-else class="text-center text-grey py-8">No data available</div>
        </v-card>
      </v-col>

      <v-col cols="12" md="6">
        <v-card class="pa-4">
          <h3 class="text-subtitle-1 font-weight-medium mb-4">Top Collections</h3>
          <v-skeleton-loader v-if="loading && !summary" type="list-item-three-line@3"></v-skeleton-loader>
          <v-list v-else-if="summary?.topCollections?.length" density="compact">
            <v-list-item
              v-for="col in summary.topCollections"
              :key="col.collection"
              :title="col.collection"
              :subtitle="`${col.queryCount} queries, avg ${formatLatency(col.averageLatencyMs)}`"
            >
              <template v-slot:append>
                <v-chip
                  v-if="col.averageRating"
                  :color="getRatingColor(col.averageRating)"
                  size="small"
                >
                  <v-icon start size="small">{{ getRatingIcon(col.averageRating) }}</v-icon>
                  {{ col.averageRating.toFixed(1) }}
                </v-chip>
              </template>
            </v-list-item>
          </v-list>
          <div v-else class="text-center text-grey py-8">No collection data available</div>
        </v-card>
      </v-col>
    </v-row>

    <!-- Chunk Utilization -->
    <v-row class="mt-4" v-if="summary">
      <v-col cols="12">
        <v-card class="pa-4">
          <h3 class="text-subtitle-1 font-weight-medium mb-4">Retrieval Statistics</h3>
          <v-row>
            <v-col cols="6" sm="3">
              <div class="text-center">
                <div class="text-h5">{{ summary.averageChunksRetrieved.toFixed(1) }}</div>
                <div class="text-caption">Avg Chunks Retrieved</div>
              </div>
            </v-col>
            <v-col cols="6" sm="3">
              <div class="text-center">
                <div class="text-h5">{{ summary.averageChunksUsed.toFixed(1) }}</div>
                <div class="text-caption">Avg Chunks Used</div>
              </div>
            </v-col>
            <v-col cols="6" sm="3">
              <div class="text-center">
                <div class="text-h5">
                  {{ summary.averageChunksRetrieved > 0
                    ? ((summary.averageChunksUsed / summary.averageChunksRetrieved) * 100).toFixed(0)
                    : 0 }}%
                </div>
                <div class="text-caption">Utilization Rate</div>
              </div>
            </v-col>
            <v-col cols="6" sm="3">
              <div class="text-center">
                <div class="text-h5">{{ summary.ratedQueriesCount }}</div>
                <div class="text-caption">Rated Queries</div>
              </div>
            </v-col>
          </v-row>
        </v-card>
      </v-col>
    </v-row>

    <!-- Recent Queries Table -->
    <v-card class="mt-6">
      <v-card-title class="d-flex align-center">
        <span>Recent Queries</span>
        <v-spacer></v-spacer>
        <v-btn icon size="small" @click="loadData" :loading="loading">
          <v-icon>mdi-refresh</v-icon>
        </v-btn>
      </v-card-title>

      <v-skeleton-loader v-if="loading && !queryList" type="table"></v-skeleton-loader>

      <v-table v-else-if="queryList?.queries?.length" density="comfortable">
        <thead>
          <tr>
            <th>Query</th>
            <th>Collection</th>
            <th>Latency</th>
            <th>Chunks</th>
            <th>Rating</th>
            <th>Time</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="query in queryList.queries" :key="query.id">
            <td class="text-truncate" style="max-width: 300px;">
              {{ query.queryText }}
            </td>
            <td>
              <v-chip size="small" variant="outlined">
                {{ query.collectionPattern || '*' }}
              </v-chip>
            </td>
            <td>{{ formatLatency(query.totalLatencyMs) }}</td>
            <td>{{ query.chunksUsed }}/{{ query.chunksRetrieved }}</td>
            <td>
              <v-chip
                v-if="query.userRating"
                :color="getRatingColor(query.userRating)"
                size="small"
              >
                <v-icon start size="small">{{ getRatingIcon(query.userRating) }}</v-icon>
                {{ query.userRating }}
              </v-chip>
              <span v-else class="text-grey">-</span>
            </td>
            <td class="text-caption">{{ formatDate(query.createdAt) }}</td>
            <td>
              <v-btn
                icon
                size="small"
                variant="text"
                @click="openFeedbackDialog(query.id)"
                :disabled="!!query.userRating"
              >
                <v-icon>mdi-star-plus</v-icon>
                <v-tooltip activator="parent" location="top">Add Rating</v-tooltip>
              </v-btn>
            </td>
          </tr>
        </tbody>
      </v-table>

      <div v-else class="text-center text-grey py-8">
        No queries found for this time period
      </div>

      <!-- Pagination -->
      <v-card-actions v-if="queryList && queryList.total > pageSize" class="justify-center">
        <v-pagination
          :model-value="currentPage"
          @update:model-value="onPageChange"
          :length="totalPages"
          :total-visible="5"
        ></v-pagination>
      </v-card-actions>
    </v-card>

    <!-- Feedback Dialog -->
    <v-dialog v-model="feedbackDialog" max-width="400">
      <v-card>
        <v-card-title>Rate This Query</v-card-title>
        <v-card-text>
          <div class="text-center mb-4">
            <v-rating
              v-model="feedbackRating"
              color="amber"
              hover
              length="5"
              size="large"
            ></v-rating>
          </div>
          <v-textarea
            v-model="feedbackComment"
            label="Comment (optional)"
            rows="3"
            variant="outlined"
            placeholder="What could be improved?"
          ></v-textarea>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="feedbackDialog = false">Cancel</v-btn>
          <v-btn color="primary" @click="submitFeedback" :loading="loading">Submit</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
