<script setup lang="ts">
import { computed } from 'vue'
import type { ContextItem } from '@/types/api'

const props = defineProps<{
  context: ContextItem
  index: number
}>()

const emit = defineEmits<{
  viewDocument: [documentId: string]
}>()

const documentName = computed(() => {
  if (props.context.metadata?.filename) {
    return props.context.metadata.filename
  }
  if (props.context.documentId) {
    return props.context.documentId.slice(0, 20) + '...'
  }
  return `Source ${props.index + 1}`
})

const collection = computed(() => {
  return props.context.metadata?.collection || 'default'
})

const score = computed(() => {
  return (props.context.score * 100).toFixed(0)
})

const truncatedContent = computed(() => {
  const content = props.context.content
  if (content.length <= 200) return content
  return content.slice(0, 200) + '...'
})

function handleViewDocument() {
  if (props.context.documentId) {
    emit('viewDocument', props.context.documentId)
  }
}
</script>

<template>
  <v-card variant="outlined" class="source-citation mb-2">
    <v-card-text class="pa-3">
      <div class="d-flex align-center justify-space-between mb-2">
        <div class="d-flex align-center">
          <v-chip size="x-small" color="primary" class="mr-2">{{ index + 1 }}</v-chip>
          <span class="text-subtitle-2 font-weight-medium">{{ documentName }}</span>
        </div>
        <div class="d-flex align-center">
          <v-chip size="x-small" variant="outlined" class="mr-2">
            {{ collection }}
          </v-chip>
          <v-chip size="x-small" :color="Number(score) >= 70 ? 'success' : 'warning'">
            {{ score }}%
          </v-chip>
        </div>
      </div>

      <div class="text-body-2 text-grey-darken-1 source-content">
        {{ truncatedContent }}
      </div>

      <div v-if="context.documentId" class="mt-2">
        <v-btn
          size="x-small"
          variant="text"
          color="primary"
          @click="handleViewDocument"
        >
          <v-icon start size="small">mdi-file-document</v-icon>
          View Document
        </v-btn>
      </div>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.source-citation {
  border-left: 3px solid rgb(var(--v-theme-primary));
}

.source-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}
</style>
