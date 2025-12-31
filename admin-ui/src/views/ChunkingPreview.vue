<script setup lang="ts">
import { ref } from 'vue'
import * as chunkingApi from '@/api/chunking'
import type { ChunkPreview, ChunkingStrategy, ChunkingCompareResult } from '@/types/api'

// Shared state
const text = ref('')
const chunkSize = ref(1000)
const chunkOverlap = ref(200)
const loading = ref(false)
const strategies = ref<ChunkingStrategy[]>([])
const activeTab = ref('preview')

// Preview mode state
const strategy = ref('simple')
const preview = ref<ChunkPreview | null>(null)

// Compare mode state
const selectedStrategies = ref<string[]>(['simple', 'sentence', 'markdown'])
const comparison = ref<ChunkingCompareResult | null>(null)

async function loadStrategies() {
  try {
    strategies.value = await chunkingApi.getStrategies()
    if (strategies.value.length > 0) {
      strategy.value = strategies.value[0].name
    }
  } catch (e) {
    console.error('Failed to load strategies', e)
  }
}

loadStrategies()

async function runPreview() {
  if (!text.value.trim()) return

  loading.value = true
  try {
    preview.value = await chunkingApi.previewChunking({
      text: text.value,
      strategy: strategy.value,
      chunkSize: chunkSize.value,
      chunkOverlap: chunkOverlap.value
    })
  } catch (e) {
    console.error('Preview failed', e)
  } finally {
    loading.value = false
  }
}

async function runComparison() {
  if (!text.value.trim() || selectedStrategies.value.length === 0) return

  loading.value = true
  try {
    comparison.value = await chunkingApi.compareStrategies({
      text: text.value,
      strategies: selectedStrategies.value,
      chunkSize: chunkSize.value,
      chunkOverlap: chunkOverlap.value
    })
  } catch (e) {
    console.error('Comparison failed', e)
  } finally {
    loading.value = false
  }
}

function getChunkColor(index: number): string {
  const colors = ['blue', 'green', 'purple', 'orange', 'teal', 'red', 'indigo']
  return colors[index % colors.length]
}

function getStrategyColor(strategyName: string): string {
  const colors: Record<string, string> = {
    'simple': 'blue',
    'sentence': 'green',
    'markdown': 'purple',
    'semantic': 'orange'
  }
  return colors[strategyName] || 'grey'
}

function getWarningColor(level: string): 'info' | 'warning' | 'error' | 'success' {
  switch (level) {
    case 'error': return 'error'
    case 'warning': return 'warning'
    default: return 'info'
  }
}
</script>

<template>
  <div>
    <h1 class="text-h4 mb-6">Chunking Preview</h1>

    <v-tabs v-model="activeTab" class="mb-4">
      <v-tab value="preview">Preview</v-tab>
      <v-tab value="compare">Compare Strategies</v-tab>
    </v-tabs>

    <v-row>
      <!-- Left column: Input -->
      <v-col cols="12" md="6">
        <v-card class="pa-4">
          <h3 class="text-h6 mb-4">Input</h3>

          <v-textarea
            v-model="text"
            label="Text to chunk"
            placeholder="Paste your text here to see how it will be chunked..."
            rows="12"
          ></v-textarea>

          <!-- Preview mode controls -->
          <template v-if="activeTab === 'preview'">
            <v-row class="mt-2">
              <v-col cols="12" sm="4">
                <v-select
                  v-model="strategy"
                  :items="strategies.map(s => s.name)"
                  label="Strategy"
                  density="compact"
                ></v-select>
              </v-col>
              <v-col cols="6" sm="4">
                <v-text-field
                  v-model.number="chunkSize"
                  label="Chunk Size"
                  type="number"
                  density="compact"
                ></v-text-field>
              </v-col>
              <v-col cols="6" sm="4">
                <v-text-field
                  v-model.number="chunkOverlap"
                  label="Overlap"
                  type="number"
                  density="compact"
                ></v-text-field>
              </v-col>
            </v-row>

            <v-btn
              color="primary"
              block
              class="mt-4"
              @click="runPreview"
              :loading="loading"
              :disabled="!text.trim()"
            >
              Preview Chunks
            </v-btn>
          </template>

          <!-- Compare mode controls -->
          <template v-else>
            <div class="mt-4">
              <div class="text-subtitle-2 mb-2">Strategies to Compare</div>
              <v-chip-group
                v-model="selectedStrategies"
                multiple
                column
              >
                <v-chip
                  v-for="s in strategies"
                  :key="s.name"
                  :value="s.name"
                  :color="getStrategyColor(s.name)"
                  filter
                  variant="outlined"
                >
                  {{ s.name }}
                </v-chip>
              </v-chip-group>
            </div>

            <v-row class="mt-2">
              <v-col cols="6">
                <v-text-field
                  v-model.number="chunkSize"
                  label="Chunk Size"
                  type="number"
                  density="compact"
                ></v-text-field>
              </v-col>
              <v-col cols="6">
                <v-text-field
                  v-model.number="chunkOverlap"
                  label="Overlap"
                  type="number"
                  density="compact"
                ></v-text-field>
              </v-col>
            </v-row>

            <v-btn
              color="primary"
              block
              class="mt-4"
              @click="runComparison"
              :loading="loading"
              :disabled="!text.trim() || selectedStrategies.length === 0"
            >
              Compare Strategies
            </v-btn>
          </template>
        </v-card>
      </v-col>

      <!-- Right column: Results -->
      <v-col cols="12" md="6">
        <!-- Preview results -->
        <v-card v-if="activeTab === 'preview'" class="pa-4">
          <h3 class="text-h6 mb-4">
            Preview
            <span v-if="preview" class="text-body-2 text-grey ml-2">
              ({{ preview.totalChunks }} chunks, avg {{ preview.avgChunkSize?.toFixed(0) ?? '0' }} chars)
            </span>
          </h3>

          <div v-if="preview?.chunks?.length">
            <v-chip
              v-for="chunk in preview.chunks"
              :key="chunk.index"
              :color="getChunkColor(chunk.index)"
              class="ma-1"
              variant="outlined"
            >
              #{{ chunk.index + 1 }} ({{ chunk.size }} chars)
            </v-chip>

            <v-divider class="my-4"></v-divider>

            <v-expansion-panels>
              <v-expansion-panel v-for="chunk in preview.chunks" :key="chunk.index">
                <v-expansion-panel-title>
                  <v-chip size="small" :color="getChunkColor(chunk.index)" class="mr-2">
                    #{{ chunk.index + 1 }}
                  </v-chip>
                  {{ chunk.content.substring(0, 60) }}...
                </v-expansion-panel-title>
                <v-expansion-panel-text>
                  <pre style="white-space: pre-wrap; font-size: 0.875rem;">{{ chunk.content }}</pre>
                  <div class="text-caption text-grey mt-2">
                    Characters {{ chunk.startOffset }}-{{ chunk.endOffset }} ({{ chunk.size }} total)
                  </div>
                </v-expansion-panel-text>
              </v-expansion-panel>
            </v-expansion-panels>
          </div>

          <div v-else class="text-center pa-8 text-grey">
            <v-icon size="64">mdi-scissors-cutting</v-icon>
            <p class="mt-4">Enter text and click "Preview Chunks" to see results</p>
          </div>
        </v-card>

        <!-- Compare results -->
        <template v-else>
          <!-- Recommendation card -->
          <v-card v-if="comparison?.recommendation" class="mb-4 pa-4" color="success" variant="tonal">
            <div class="d-flex align-center">
              <v-icon class="mr-3">mdi-lightbulb</v-icon>
              <div>
                <div class="text-subtitle-1 font-weight-bold">
                  Recommended: {{ comparison.recommendation.strategy }}
                </div>
                <div class="text-body-2">{{ comparison.recommendation.reason }}</div>
              </div>
            </div>
          </v-card>

          <!-- Stats comparison table -->
          <v-card v-if="comparison?.results?.length" class="mb-4">
            <v-card-title>Comparison Results</v-card-title>
            <v-table density="comfortable">
              <thead>
                <tr>
                  <th>Strategy</th>
                  <th class="text-right">Chunks</th>
                  <th class="text-right">Avg Size</th>
                  <th class="text-right">Min</th>
                  <th class="text-right">Max</th>
                  <th class="text-right">Est. Tokens</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="result in comparison.results" :key="result.strategy">
                  <td>
                    <v-chip size="small" :color="getStrategyColor(result.strategy)">
                      {{ result.strategy }}
                    </v-chip>
                  </td>
                  <td class="text-right">{{ result.stats.chunkCount }}</td>
                  <td class="text-right">{{ result.stats.avgChunkSize?.toFixed(0) ?? '0' }}</td>
                  <td class="text-right">{{ result.stats.minChunkSize }}</td>
                  <td class="text-right">{{ result.stats.maxChunkSize }}</td>
                  <td class="text-right">{{ result.stats.estimatedTokens }}</td>
                </tr>
              </tbody>
            </v-table>
          </v-card>

          <!-- Warnings -->
          <v-card v-if="comparison?.results?.some(r => r.warnings?.length)" class="mb-4">
            <v-card-title>Warnings</v-card-title>
            <v-card-text>
              <template v-for="result in comparison.results" :key="result.strategy">
                <v-alert
                  v-for="(warning, idx) in result.warnings"
                  :key="`${result.strategy}-${idx}`"
                  :type="getWarningColor(warning.level)"
                  density="compact"
                  class="mb-2"
                >
                  <strong>{{ result.strategy }}:</strong> {{ warning.message }}
                  <span v-if="warning.suggestion" class="text-caption d-block mt-1">
                    Suggestion: {{ warning.suggestion }}
                  </span>
                </v-alert>
              </template>
            </v-card-text>
          </v-card>

          <!-- Detailed chunks per strategy -->
          <v-card v-if="comparison?.results?.length">
            <v-card-title>Chunk Details</v-card-title>
            <v-card-text>
              <v-expansion-panels>
                <v-expansion-panel v-for="result in comparison.results" :key="result.strategy">
                  <v-expansion-panel-title>
                    <v-chip size="small" :color="getStrategyColor(result.strategy)" class="mr-2">
                      {{ result.strategy }}
                    </v-chip>
                    {{ result.stats.chunkCount }} chunks
                  </v-expansion-panel-title>
                  <v-expansion-panel-text>
                    <v-expansion-panels variant="accordion">
                      <v-expansion-panel v-for="chunk in result.chunks" :key="chunk.index">
                        <v-expansion-panel-title>
                          <v-chip size="x-small" class="mr-2">#{{ chunk.index + 1 }}</v-chip>
                          {{ chunk.content.substring(0, 50) }}...
                          <span class="text-caption text-grey ml-2">({{ chunk.length }} chars)</span>
                        </v-expansion-panel-title>
                        <v-expansion-panel-text>
                          <pre style="white-space: pre-wrap; font-size: 0.875rem;">{{ chunk.content }}</pre>
                        </v-expansion-panel-text>
                      </v-expansion-panel>
                    </v-expansion-panels>
                  </v-expansion-panel-text>
                </v-expansion-panel>
              </v-expansion-panels>
            </v-card-text>
          </v-card>

          <!-- Empty state -->
          <v-card v-if="!comparison" class="pa-4">
            <div class="text-center pa-8 text-grey">
              <v-icon size="64">mdi-compare</v-icon>
              <p class="mt-4">Select strategies and click "Compare Strategies" to see results</p>
            </div>
          </v-card>
        </template>
      </v-col>
    </v-row>
  </div>
</template>
