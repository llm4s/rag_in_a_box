<script setup lang="ts">
import { ref, watch } from 'vue'
import * as documentsApi from '@/api/documents'
import type { Document, Chunk } from '@/types/api'
import ErrorAlert from './ErrorAlert.vue'
import { isAppError, getErrorMessage, type AppError } from '@/composables/useApiError'

const props = defineProps<{
  modelValue: boolean
  documentId: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const loading = ref(false)
const error = ref<AppError | null>(null)
const document = ref<Document | null>(null)
const chunks = ref<Chunk[]>([])
const activeTab = ref('info')

watch(() => props.documentId, async (id) => {
  if (id && props.modelValue) {
    await loadDocument(id)
  }
})

watch(() => props.modelValue, async (isOpen) => {
  if (isOpen && props.documentId) {
    await loadDocument(props.documentId)
  } else if (!isOpen) {
    // Reset state when closed
    document.value = null
    chunks.value = []
    error.value = null
    activeTab.value = 'info'
  }
})

async function loadDocument(id: string) {
  loading.value = true
  error.value = null

  try {
    document.value = await documentsApi.getDocument(id)
    chunks.value = await documentsApi.getDocumentChunks(id) || []
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

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <v-dialog
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    max-width="800"
    scrollable
  >
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon class="mr-2">mdi-file-document</v-icon>
        <span v-if="document">{{ document.filename || '(untitled)' }}</span>
        <span v-else>Document Preview</span>
        <v-spacer />
        <v-btn icon variant="text" @click="close">
          <v-icon>mdi-close</v-icon>
        </v-btn>
      </v-card-title>

      <v-divider />

      <!-- Loading -->
      <div v-if="loading" class="pa-8 text-center">
        <v-progress-circular indeterminate size="48" />
        <p class="mt-4 text-grey">Loading document...</p>
      </div>

      <!-- Error -->
      <div v-else-if="error" class="pa-4">
        <ErrorAlert
          :error="error"
          :on-retry="() => documentId && loadDocument(documentId)"
        />
      </div>

      <!-- Content -->
      <template v-else-if="document">
        <v-tabs v-model="activeTab" bg-color="grey-lighten-4">
          <v-tab value="info">Info</v-tab>
          <v-tab value="chunks">Chunks ({{ chunks.length }})</v-tab>
        </v-tabs>

        <v-tabs-window v-model="activeTab">
          <!-- Info Tab -->
          <v-tabs-window-item value="info">
            <v-card-text>
              <v-list density="compact">
                <v-list-item>
                  <template v-slot:prepend>
                    <v-icon>mdi-identifier</v-icon>
                  </template>
                  <v-list-item-title>ID</v-list-item-title>
                  <v-list-item-subtitle>
                    <code>{{ document.id }}</code>
                  </v-list-item-subtitle>
                </v-list-item>

                <v-list-item>
                  <template v-slot:prepend>
                    <v-icon>mdi-folder</v-icon>
                  </template>
                  <v-list-item-title>Collection</v-list-item-title>
                  <v-list-item-subtitle>
                    <v-chip size="small" color="primary" variant="outlined">
                      {{ document.collection || 'default' }}
                    </v-chip>
                  </v-list-item-subtitle>
                </v-list-item>

                <v-list-item>
                  <template v-slot:prepend>
                    <v-icon>mdi-puzzle</v-icon>
                  </template>
                  <v-list-item-title>Chunks</v-list-item-title>
                  <v-list-item-subtitle>{{ document.chunkCount }} chunks</v-list-item-subtitle>
                </v-list-item>

                <v-list-item v-if="document.metadata && Object.keys(document.metadata).length > 0">
                  <template v-slot:prepend>
                    <v-icon>mdi-tag-multiple</v-icon>
                  </template>
                  <v-list-item-title>Metadata</v-list-item-title>
                  <v-list-item-subtitle>
                    <pre class="text-caption mt-2">{{ JSON.stringify(document.metadata, null, 2) }}</pre>
                  </v-list-item-subtitle>
                </v-list-item>
              </v-list>
            </v-card-text>
          </v-tabs-window-item>

          <!-- Chunks Tab -->
          <v-tabs-window-item value="chunks">
            <v-card-text class="pa-0">
              <v-expansion-panels v-if="chunks.length > 0">
                <v-expansion-panel v-for="chunk in chunks" :key="chunk.id">
                  <v-expansion-panel-title>
                    <v-chip size="x-small" class="mr-2">{{ chunk.chunkIndex }}</v-chip>
                    <span class="text-truncate">{{ chunk.content.substring(0, 60) }}...</span>
                    <v-spacer />
                    <v-chip size="x-small" variant="outlined" class="mr-4">
                      {{ chunk.content.length }} chars
                    </v-chip>
                  </v-expansion-panel-title>
                  <v-expansion-panel-text>
                    <pre style="white-space: pre-wrap; font-size: 0.875rem; background: rgba(0,0,0,0.03); padding: 12px; border-radius: 4px;">{{ chunk.content }}</pre>
                  </v-expansion-panel-text>
                </v-expansion-panel>
              </v-expansion-panels>
              <div v-else class="pa-8 text-center text-grey">
                No chunks available
              </div>
            </v-card-text>
          </v-tabs-window-item>
        </v-tabs-window>
      </template>

      <v-divider />

      <v-card-actions>
        <v-spacer />
        <v-btn @click="close">Close</v-btn>
        <v-btn
          v-if="document"
          color="primary"
          :to="`/documents/${document.id}`"
          @click="close"
        >
          View Full Details
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
