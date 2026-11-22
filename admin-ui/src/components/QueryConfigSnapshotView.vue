<script setup lang="ts">
import { computed } from 'vue'
import type { QueryConfigSnapshot } from '@/types/api'

/** The configuration a logged query ran with, as a list of the settings it recorded. */
const props = withDefaults(defineProps<{
  snapshot?: QueryConfigSnapshot | null
  /** Longest system prompt shown before it is cut with an ellipsis. */
  promptMaxLength?: number
}>(), {
  snapshot: null,
  promptMaxLength: 200
})

interface Row {
  key: string
  label: string
  value: string
  title?: string
}

const isSet = <T,>(value: T | null | undefined): value is T => value !== null && value !== undefined

const rows = computed<Row[]>(() => {
  const s = props.snapshot
  if (!s) return []
  const result: Row[] = [{ key: 'topK', label: 'Top K', value: String(s.topK) }]
  if (isSet(s.searchMode)) {
    result.push({
      key: 'searchMode',
      label: 'Search',
      value: s.searchMode === 'vector-only' ? 'vector-only (permission mode)' : s.searchMode
    })
  }
  if (isSet(s.fusionStrategy)) result.push({ key: 'fusionStrategy', label: 'Fusion', value: s.fusionStrategy })
  if (isSet(s.rrfK)) result.push({ key: 'rrfK', label: 'RRF k', value: String(s.rrfK) })
  if (isSet(s.reranking)) result.push({ key: 'reranking', label: 'Reranking', value: s.reranking })
  if (isSet(s.similarityThreshold)) {
    result.push({ key: 'similarityThreshold', label: 'Similarity threshold', value: String(s.similarityThreshold) })
  }
  if (isSet(s.llmTemperature)) {
    result.push({ key: 'llmTemperature', label: 'LLM temperature', value: String(s.llmTemperature) })
  }
  if (isSet(s.systemPrompt) && s.systemPrompt !== '') {
    const prompt = s.systemPrompt
    const truncated = prompt.length > props.promptMaxLength
    result.push({
      key: 'systemPrompt',
      label: 'System prompt',
      value: truncated ? `${prompt.slice(0, props.promptMaxLength).trimEnd()}…` : prompt,
      title: truncated ? prompt : undefined
    })
  }
  return result
})

/** Permission-mode search is vector-only, so the query ran without fusion or reranking. */
const vectorOnly = computed(() => props.snapshot?.searchMode === 'vector-only')
</script>

<template>
  <div class="query-config-snapshot">
    <v-table v-if="rows.length" density="compact">
      <tbody>
        <tr v-for="row in rows" :key="row.key" :data-test="`snapshot-${row.key}`">
          <th class="text-no-wrap" style="width: 1%;">{{ row.label }}</th>
          <td
            :title="row.title"
            :class="{ 'text-pre-wrap': row.key === 'systemPrompt' }"
            style="word-break: break-word;"
          >
            {{ row.value }}
          </td>
        </tr>
      </tbody>
    </v-table>
    <div v-if="vectorOnly" class="text-caption text-medium-emphasis mt-1" data-test="snapshot-vector-only-note">
      Fusion, RRF k and reranking did not apply: permission-mode search is vector-only
      (llm4s/llm4s#2716).
    </div>
    <div v-if="!rows.length" class="text-grey text-body-2" data-test="snapshot-none">
      No configuration was recorded for this query.
    </div>
  </div>
</template>
