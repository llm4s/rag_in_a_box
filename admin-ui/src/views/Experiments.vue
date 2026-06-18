<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { useExperimentsStore } from '@/stores/experiments'
import type { ExperimentStatus, CreateExperimentRequest, ExperimentConfigSnapshot, Experiment } from '@/types/api'

const experimentsStore = useExperimentsStore()

const experiments = computed(() => experimentsStore.filteredExperiments)
const loading = computed(() => experimentsStore.loading)
const error = computed(() => experimentsStore.error)
const runningExperiment = computed(() => experimentsStore.runningExperiment)

// Counts
const totalCount = computed(() => experimentsStore.totalCount)
const draftCount = computed(() => experimentsStore.draftCount)
const runningCount = computed(() => experimentsStore.runningCount)
const completedCount = computed(() => experimentsStore.completedCount)

// Filter
const statusFilter = computed({
  get: () => experimentsStore.statusFilter,
  set: (value) => experimentsStore.setStatusFilter(value)
})

// Create dialog
const createDialog = ref(false)
const newExperiment = ref<CreateExperimentRequest>({
  name: '',
  description: '',
  variantConfig: {},
  baselineConfig: {},
  trafficSplit: 0.5,
  collection: ''
})

// Results dialog
const resultsDialog = ref(false)
const selectedExperiment = ref<Experiment | null>(null)
const currentResults = computed(() => experimentsStore.currentResults)

const statusColors: Record<ExperimentStatus, string> = {
  draft: 'grey',
  running: 'success',
  completed: 'primary',
  archived: 'warning'
}

const statusIcons: Record<ExperimentStatus, string> = {
  draft: 'mdi-pencil',
  running: 'mdi-play-circle',
  completed: 'mdi-check-circle',
  archived: 'mdi-archive'
}

function formatDate(dateStr?: string): string {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function formatTrafficSplit(split: number): string {
  return `${Math.round((1 - split) * 100)}% / ${Math.round(split * 100)}%`
}

function formatConfigSnapshot(config: ExperimentConfigSnapshot): string {
  const parts: string[] = []
  if (config.topK !== undefined) parts.push(`topK: ${config.topK}`)
  if (config.fusionStrategy) parts.push(`fusion: ${config.fusionStrategy}`)
  if (config.llmTemperature !== undefined) parts.push(`temp: ${config.llmTemperature}`)
  return parts.length > 0 ? parts.join(', ') : 'Default'
}

async function loadData() {
  await Promise.all([
    experimentsStore.fetchExperiments(),
    experimentsStore.fetchRunningExperiment()
  ])
}

function openCreateDialog() {
  newExperiment.value = {
    name: '',
    description: '',
    variantConfig: {},
    baselineConfig: {},
    trafficSplit: 0.5,
    collection: ''
  }
  createDialog.value = true
}

async function createExperiment() {
  const request = { ...newExperiment.value }
  // Clean up empty optional fields
  if (!request.description) delete request.description
  if (!request.collection) delete request.collection
  if (Object.keys(request.baselineConfig || {}).length === 0) delete request.baselineConfig

  const created = await experimentsStore.createExperiment(request)
  if (created) {
    createDialog.value = false
  }
}

async function startExperiment(id: string) {
  await experimentsStore.startExperiment(id)
}

async function stopExperiment(id: string) {
  await experimentsStore.stopExperiment(id)
}

async function archiveExperiment(id: string) {
  await experimentsStore.archiveExperiment(id)
}

async function deleteExperiment(id: string) {
  if (confirm('Are you sure you want to delete this experiment?')) {
    await experimentsStore.deleteExperiment(id)
  }
}

async function viewResults(experiment: Experiment) {
  selectedExperiment.value = experiment
  await experimentsStore.fetchResults(experiment.id)
  resultsDialog.value = true
}

function getChangeColor(change: string): string {
  if (change.startsWith('+')) return 'success'
  if (change.startsWith('-')) return 'error'
  return 'grey'
}

function getSignificanceLabel(significance?: number): string {
  if (significance === undefined) return 'Insufficient data'
  if (significance >= 0.95) return 'Highly significant'
  if (significance >= 0.90) return 'Significant'
  if (significance >= 0.80) return 'Marginally significant'
  return 'Not significant'
}

function getSignificanceColor(significance?: number): string {
  if (significance === undefined) return 'grey'
  if (significance >= 0.95) return 'success'
  if (significance >= 0.90) return 'primary'
  if (significance >= 0.80) return 'warning'
  return 'grey'
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">A/B Experiments</h1>
      <v-spacer></v-spacer>
      <v-btn color="primary" prepend-icon="mdi-plus" @click="openCreateDialog">
        New Experiment
      </v-btn>
      <v-btn class="ml-2" icon="mdi-refresh" variant="text" @click="loadData" :loading="loading" />
    </div>

    <!-- Error Alert -->
    <v-alert v-if="error" type="error" class="mb-4" closable @click:close="experimentsStore.clearError()">
      {{ error }}
    </v-alert>

    <!-- Running Experiment Banner -->
    <v-alert v-if="runningExperiment" type="success" variant="tonal" class="mb-4">
      <div class="d-flex align-center">
        <v-icon start>mdi-play-circle</v-icon>
        <div>
          <strong>Running:</strong> {{ runningExperiment.name }}
          <span class="text-grey ml-2">({{ formatTrafficSplit(runningExperiment.trafficSplit) }} baseline/variant)</span>
        </div>
        <v-spacer />
        <v-btn color="error" size="small" variant="text" @click="stopExperiment(runningExperiment.id)">
          Stop Experiment
        </v-btn>
        <v-btn color="primary" size="small" variant="text" class="ml-2" @click="viewResults(runningExperiment)">
          View Results
        </v-btn>
      </div>
    </v-alert>

    <!-- Summary Cards -->
    <v-row v-if="loading && experiments.length === 0">
      <v-col v-for="i in 4" :key="i" cols="12" sm="6" md="3">
        <v-skeleton-loader type="card" height="100"></v-skeleton-loader>
      </v-col>
    </v-row>

    <v-row v-else>
      <v-col cols="12" sm="6" md="3">
        <v-card
          class="pa-4 cursor-pointer"
          :variant="statusFilter === null ? 'elevated' : 'outlined'"
          @click="statusFilter = null"
        >
          <div class="d-flex align-center">
            <v-icon size="36" class="mr-3" color="primary">mdi-flask</v-icon>
            <div>
              <div class="text-h5">{{ totalCount }}</div>
              <div class="text-subtitle-2 text-grey">Total</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card
          class="pa-4 cursor-pointer"
          :variant="statusFilter === 'draft' ? 'elevated' : 'outlined'"
          @click="statusFilter = statusFilter === 'draft' ? null : 'draft'"
        >
          <div class="d-flex align-center">
            <v-icon size="36" class="mr-3" color="grey">mdi-pencil</v-icon>
            <div>
              <div class="text-h5">{{ draftCount }}</div>
              <div class="text-subtitle-2 text-grey">Draft</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card
          class="pa-4 cursor-pointer"
          :color="runningCount > 0 ? 'success' : undefined"
          :variant="statusFilter === 'running' ? 'elevated' : (runningCount > 0 ? 'tonal' : 'outlined')"
          @click="statusFilter = statusFilter === 'running' ? null : 'running'"
        >
          <div class="d-flex align-center">
            <v-icon size="36" class="mr-3" :color="runningCount > 0 ? 'success' : 'grey'">mdi-play-circle</v-icon>
            <div>
              <div class="text-h5">{{ runningCount }}</div>
              <div class="text-subtitle-2 text-grey">Running</div>
            </div>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" sm="6" md="3">
        <v-card
          class="pa-4 cursor-pointer"
          :variant="statusFilter === 'completed' ? 'elevated' : 'outlined'"
          @click="statusFilter = statusFilter === 'completed' ? null : 'completed'"
        >
          <div class="d-flex align-center">
            <v-icon size="36" class="mr-3" color="primary">mdi-check-circle</v-icon>
            <div>
              <div class="text-h5">{{ completedCount }}</div>
              <div class="text-subtitle-2 text-grey">Completed</div>
            </div>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <!-- Experiments List -->
    <v-progress-linear v-if="loading" indeterminate color="primary" class="my-4" />

    <div v-if="experiments.length === 0 && !loading" class="text-center py-12">
      <v-icon size="64" color="grey" class="mb-4">mdi-flask-empty-outline</v-icon>
      <h3 class="text-h5 mb-2">No Experiments Yet</h3>
      <p class="text-body-1 text-grey mb-4">
        Create your first A/B test experiment to compare different RAG configurations.
      </p>
      <v-btn color="primary" @click="openCreateDialog">Create Experiment</v-btn>
    </div>

    <v-card v-for="experiment in experiments" :key="experiment.id" class="mb-3">
      <v-card-item>
        <template #prepend>
          <v-icon :color="statusColors[experiment.status]" size="large">
            {{ statusIcons[experiment.status] }}
          </v-icon>
        </template>

        <v-card-title>
          {{ experiment.name }}
          <v-chip size="x-small" :color="statusColors[experiment.status]" class="ml-2">
            {{ experiment.status }}
          </v-chip>
        </v-card-title>

        <v-card-subtitle>
          Created {{ formatDate(experiment.createdAt) }}
          <span v-if="experiment.startedAt"> | Started {{ formatDate(experiment.startedAt) }}</span>
          <span v-if="experiment.endedAt"> | Ended {{ formatDate(experiment.endedAt) }}</span>
        </v-card-subtitle>

        <template #append>
          <v-btn
            v-if="experiment.status === 'draft'"
            color="success"
            size="small"
            variant="tonal"
            class="mr-2"
            @click="startExperiment(experiment.id)"
          >
            Start
          </v-btn>
          <v-btn
            v-if="experiment.status === 'running'"
            color="error"
            size="small"
            variant="tonal"
            class="mr-2"
            @click="stopExperiment(experiment.id)"
          >
            Stop
          </v-btn>
          <v-btn
            v-if="experiment.status === 'completed' || experiment.status === 'running'"
            color="primary"
            size="small"
            variant="text"
            class="mr-2"
            @click="viewResults(experiment)"
          >
            Results
          </v-btn>
          <v-menu>
            <template v-slot:activator="{ props }">
              <v-btn icon="mdi-dots-vertical" variant="text" v-bind="props" />
            </template>
            <v-list density="compact">
              <v-list-item
                v-if="experiment.status === 'completed' || experiment.status === 'draft'"
                @click="archiveExperiment(experiment.id)"
              >
                <v-list-item-title>Archive</v-list-item-title>
              </v-list-item>
              <v-list-item
                v-if="experiment.status === 'draft'"
                @click="deleteExperiment(experiment.id)"
              >
                <v-list-item-title class="text-error">Delete</v-list-item-title>
              </v-list-item>
            </v-list>
          </v-menu>
        </template>
      </v-card-item>

      <v-card-text v-if="experiment.description">
        <p class="text-body-2">{{ experiment.description }}</p>
      </v-card-text>

      <v-divider />

      <v-card-text>
        <v-row>
          <v-col cols="12" md="4">
            <div class="text-subtitle-2 text-grey mb-1">Traffic Split</div>
            <div>{{ formatTrafficSplit(experiment.trafficSplit) }}</div>
            <div class="text-caption text-grey">Baseline / Variant</div>
          </v-col>
          <v-col cols="12" md="4">
            <div class="text-subtitle-2 text-grey mb-1">Baseline Config</div>
            <div class="text-body-2">{{ formatConfigSnapshot(experiment.baselineConfig) }}</div>
          </v-col>
          <v-col cols="12" md="4">
            <div class="text-subtitle-2 text-grey mb-1">Variant Config</div>
            <div class="text-body-2 text-primary">{{ formatConfigSnapshot(experiment.variantConfig) }}</div>
          </v-col>
        </v-row>
      </v-card-text>
    </v-card>

    <!-- Create Experiment Dialog -->
    <v-dialog v-model="createDialog" max-width="700">
      <v-card>
        <v-card-title>Create New Experiment</v-card-title>
        <v-card-text>
          <v-text-field
            v-model="newExperiment.name"
            label="Experiment Name"
            placeholder="e.g., Test higher topK"
            required
            class="mb-4"
          />

          <v-textarea
            v-model="newExperiment.description"
            label="Description (optional)"
            placeholder="Describe what you're testing and why"
            rows="2"
            class="mb-4"
          />

          <v-text-field
            v-model="newExperiment.collection"
            label="Collection Filter (optional)"
            placeholder="Leave empty to apply to all collections"
            class="mb-4"
          />

          <v-slider
            v-model="newExperiment.trafficSplit"
            label="Traffic Split (% to Variant)"
            :min="0.1"
            :max="0.9"
            :step="0.1"
            thumb-label
            class="mb-4"
          >
            <template v-slot:append>
              <span class="text-body-2">{{ Math.round((newExperiment.trafficSplit || 0.5) * 100) }}%</span>
            </template>
          </v-slider>

          <v-divider class="mb-4" />

          <h4 class="text-subtitle-1 mb-2">Variant Configuration</h4>
          <p class="text-caption text-grey mb-3">Configure the parameters you want to test. Leave blank to use defaults.</p>

          <v-row>
            <v-col cols="12" md="4">
              <v-text-field
                v-model.number="newExperiment.variantConfig!.topK"
                label="Top K"
                type="number"
                placeholder="5"
                density="compact"
              />
            </v-col>
            <v-col cols="12" md="4">
              <v-select
                v-model="newExperiment.variantConfig!.fusionStrategy"
                label="Fusion Strategy"
                :items="['rrf', 'weighted', 'simple']"
                density="compact"
                clearable
              />
            </v-col>
            <v-col cols="12" md="4">
              <v-text-field
                v-model.number="newExperiment.variantConfig!.llmTemperature"
                label="LLM Temperature"
                type="number"
                placeholder="0.7"
                step="0.1"
                density="compact"
              />
            </v-col>
          </v-row>
        </v-card-text>

        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="createDialog = false">Cancel</v-btn>
          <v-btn
            color="primary"
            variant="elevated"
            @click="createExperiment"
            :disabled="!newExperiment.name"
          >
            Create Experiment
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Results Dialog -->
    <v-dialog v-model="resultsDialog" max-width="900">
      <v-card v-if="currentResults">
        <v-card-title class="d-flex align-center">
          <span>Experiment Results: {{ currentResults.experimentName }}</span>
          <v-chip size="small" :color="statusColors[currentResults.status]" class="ml-2">
            {{ currentResults.status }}
          </v-chip>
        </v-card-title>

        <v-card-subtitle v-if="currentResults.duration">
          Duration: {{ currentResults.duration }}
        </v-card-subtitle>

        <v-card-text>
          <!-- Metrics Comparison -->
          <v-row>
            <v-col cols="12" md="6">
              <v-card variant="outlined">
                <v-card-title class="text-subtitle-1">
                  <v-icon start color="grey">mdi-target</v-icon>
                  Baseline
                </v-card-title>
                <v-card-text>
                  <v-table density="compact">
                    <tbody>
                      <tr>
                        <td>Queries</td>
                        <td class="text-right">{{ currentResults.baseline.queryCount }}</td>
                      </tr>
                      <tr>
                        <td>Avg Latency</td>
                        <td class="text-right">{{ currentResults.baseline.avgLatencyMs.toFixed(0) }}ms</td>
                      </tr>
                      <tr>
                        <td>Avg Rating</td>
                        <td class="text-right">{{ currentResults.baseline.avgRating?.toFixed(2) || '-' }}</td>
                      </tr>
                      <tr>
                        <td>Rated Queries</td>
                        <td class="text-right">{{ currentResults.baseline.ratedCount }}</td>
                      </tr>
                      <tr>
                        <td>Avg Chunks Used</td>
                        <td class="text-right">{{ currentResults.baseline.avgChunksUsed.toFixed(1) }}</td>
                      </tr>
                    </tbody>
                  </v-table>
                </v-card-text>
              </v-card>
            </v-col>

            <v-col cols="12" md="6">
              <v-card variant="outlined" color="primary">
                <v-card-title class="text-subtitle-1">
                  <v-icon start color="primary">mdi-flask</v-icon>
                  Variant
                </v-card-title>
                <v-card-text>
                  <v-table density="compact">
                    <tbody>
                      <tr>
                        <td>Queries</td>
                        <td class="text-right">{{ currentResults.variant.queryCount }}</td>
                      </tr>
                      <tr>
                        <td>Avg Latency</td>
                        <td class="text-right">{{ currentResults.variant.avgLatencyMs.toFixed(0) }}ms</td>
                      </tr>
                      <tr>
                        <td>Avg Rating</td>
                        <td class="text-right">{{ currentResults.variant.avgRating?.toFixed(2) || '-' }}</td>
                      </tr>
                      <tr>
                        <td>Rated Queries</td>
                        <td class="text-right">{{ currentResults.variant.ratedCount }}</td>
                      </tr>
                      <tr>
                        <td>Avg Chunks Used</td>
                        <td class="text-right">{{ currentResults.variant.avgChunksUsed.toFixed(1) }}</td>
                      </tr>
                    </tbody>
                  </v-table>
                </v-card-text>
              </v-card>
            </v-col>
          </v-row>

          <!-- Analysis -->
          <v-card variant="tonal" class="mt-4">
            <v-card-title class="text-subtitle-1">
              <v-icon start>mdi-chart-bar</v-icon>
              Analysis
            </v-card-title>
            <v-card-text>
              <v-row>
                <v-col cols="12" sm="3">
                  <div class="text-center">
                    <div class="text-h5" :class="`text-${getChangeColor(currentResults.analysis.latencyChange)}`">
                      {{ currentResults.analysis.latencyChange }}
                    </div>
                    <div class="text-caption">Latency Change</div>
                  </div>
                </v-col>
                <v-col cols="12" sm="3">
                  <div class="text-center">
                    <div class="text-h5" :class="currentResults.analysis.ratingChange ? `text-${getChangeColor(currentResults.analysis.ratingChange)}` : 'text-grey'">
                      {{ currentResults.analysis.ratingChange || '-' }}
                    </div>
                    <div class="text-caption">Rating Change</div>
                  </div>
                </v-col>
                <v-col cols="12" sm="3">
                  <div class="text-center">
                    <div class="text-h5" :class="`text-${getChangeColor(currentResults.analysis.chunksUsedChange)}`">
                      {{ currentResults.analysis.chunksUsedChange }}
                    </div>
                    <div class="text-caption">Chunks Used Change</div>
                  </div>
                </v-col>
                <v-col cols="12" sm="3">
                  <div class="text-center">
                    <div class="text-h5" :class="`text-${getSignificanceColor(currentResults.analysis.statisticalSignificance)}`">
                      {{ currentResults.analysis.statisticalSignificance ? `${(currentResults.analysis.statisticalSignificance * 100).toFixed(0)}%` : '-' }}
                    </div>
                    <div class="text-caption">{{ getSignificanceLabel(currentResults.analysis.statisticalSignificance) }}</div>
                  </div>
                </v-col>
              </v-row>
            </v-card-text>
          </v-card>
        </v-card-text>

        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="resultsDialog = false">Close</v-btn>
        </v-card-actions>
      </v-card>

      <v-card v-else>
        <v-card-text class="text-center py-8">
          <v-progress-circular indeterminate color="primary" />
          <div class="mt-4">Loading results...</div>
        </v-card-text>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.cursor-pointer {
  cursor: pointer;
}
</style>
