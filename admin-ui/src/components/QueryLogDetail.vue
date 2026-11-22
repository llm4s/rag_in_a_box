<script setup lang="ts">
import { computed } from 'vue'
import type { QueryLogEntry } from '@/types/api'
import QueryConfigSnapshotView from './QueryConfigSnapshotView.vue'

/** One logged query: what was asked, how long it took, what it cost and the config it ran with. */
const props = defineProps<{
  entry: QueryLogEntry
}>()

function formatLatency(ms: number | null | undefined) {
  if (ms === null || ms === undefined) return '-'
  if (ms < 1000) return `${ms.toFixed(0)}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

const facts = computed(() => {
  const e = props.entry
  const rows: { label: string; value: string }[] = [
    { label: 'Collection', value: e.collectionPattern || '*' },
    { label: 'Time', value: new Date(e.createdAt).toLocaleString() },
    {
      label: 'Latency',
      value: `${formatLatency(e.totalLatencyMs)} (embedding ${formatLatency(e.embeddingLatencyMs)}, ` +
        `search ${formatLatency(e.searchLatencyMs)}, LLM ${formatLatency(e.llmLatencyMs)})`
    },
    { label: 'Chunks used / retrieved', value: `${e.chunksUsed}/${e.chunksRetrieved}` }
  ]
  if (e.userId) rows.push({ label: 'User', value: e.userId })
  const tokens = [
    e.promptTokens != null ? `prompt ${e.promptTokens}` : null,
    e.answerTokens != null ? `answer ${e.answerTokens}` : null,
    e.embeddingTokens != null ? `embedding ${e.embeddingTokens}` : null
  ].filter((t): t is string => t !== null)
  if (tokens.length) rows.push({ label: 'Tokens', value: tokens.join(', ') })
  if (e.costUsd != null) rows.push({ label: 'Cost', value: `$${e.costUsd.toFixed(4)}` })
  if (e.experimentId) {
    rows.push({ label: 'Experiment', value: e.variant ? `${e.experimentId} (${e.variant})` : e.experimentId })
  }
  return rows
})
</script>

<template>
  <div class="query-log-detail">
    <div class="text-body-1 mb-3" data-test="query-text">{{ entry.queryText }}</div>

    <v-alert v-if="entry.failed" type="error" variant="tonal" density="compact" class="mb-3" data-test="query-error">
      Failed{{ entry.errorMessage ? `: ${entry.errorMessage}` : '' }}
    </v-alert>

    <v-table density="compact" class="mb-4">
      <tbody>
        <tr v-for="fact in facts" :key="fact.label">
          <th class="text-no-wrap" style="width: 1%;">{{ fact.label }}</th>
          <td>{{ fact.value }}</td>
        </tr>
      </tbody>
    </v-table>

    <h4 class="text-subtitle-2 mb-2">Configuration</h4>
    <QueryConfigSnapshotView :snapshot="entry.configSnapshot" />
  </div>
</template>
