<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as documentsApi from '@/api/documents'
import type { Document } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const router = useRouter()
const searchQuery = ref('')
const searchResults = ref<Document[]>([])
const loading = ref(false)
const searchInput = ref<HTMLInputElement | null>(null)

// Debounced search
let searchTimeout: ReturnType<typeof setTimeout> | null = null

watch(searchQuery, (query) => {
  if (searchTimeout) {
    clearTimeout(searchTimeout)
  }

  if (!query || query.length < 2) {
    searchResults.value = []
    return
  }

  searchTimeout = setTimeout(async () => {
    loading.value = true
    try {
      const result = await documentsApi.getDocuments({ search: query, pageSize: 10 })
      searchResults.value = result.documents
    } catch {
      searchResults.value = []
    } finally {
      loading.value = false
    }
  }, 300)
})

// Focus input when dialog opens
watch(() => props.modelValue, (isOpen) => {
  if (isOpen) {
    setTimeout(() => {
      searchInput.value?.focus()
    }, 100)
  } else {
    searchQuery.value = ''
    searchResults.value = []
  }
})

function selectDocument(doc: Document) {
  emit('update:modelValue', false)
  router.push(`/documents/${doc.id}`)
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    emit('update:modelValue', false)
  }
}

// Quick navigation items
const quickNav = [
  { title: 'Dashboard', icon: 'mdi-view-dashboard', to: '/' },
  { title: 'Documents', icon: 'mdi-file-document-multiple', to: '/documents' },
  { title: 'Upload', icon: 'mdi-upload', to: '/documents/upload' },
  { title: 'Chunking Preview', icon: 'mdi-scissors-cutting', to: '/chunking' },
  { title: 'Configuration', icon: 'mdi-cog', to: '/config/runtime' },
  { title: 'Visibility', icon: 'mdi-eye', to: '/visibility' },
]

function navigateTo(path: string) {
  emit('update:modelValue', false)
  router.push(path)
}
</script>

<template>
  <v-dialog
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    max-width="600"
    @keydown="handleKeydown"
  >
    <v-card>
      <v-card-text class="pa-0">
        <v-text-field
          ref="searchInput"
          v-model="searchQuery"
          placeholder="Search documents or navigate..."
          prepend-inner-icon="mdi-magnify"
          variant="solo"
          hide-details
          autofocus
          class="search-input"
        >
          <template v-slot:append-inner>
            <kbd class="text-caption">ESC</kbd>
          </template>
        </v-text-field>

        <v-divider />

        <!-- Loading -->
        <v-progress-linear v-if="loading" indeterminate height="2" />

        <!-- Search Results -->
        <v-list v-if="searchResults.length > 0" density="compact" class="py-0">
          <v-list-subheader>Documents</v-list-subheader>
          <v-list-item
            v-for="doc in searchResults"
            :key="doc.id"
            @click="selectDocument(doc)"
          >
            <template v-slot:prepend>
              <v-icon>mdi-file-document</v-icon>
            </template>
            <v-list-item-title>{{ doc.filename || '(untitled)' }}</v-list-item-title>
            <v-list-item-subtitle>
              {{ doc.collection || 'default' }} · {{ doc.chunkCount }} chunks
            </v-list-item-subtitle>
          </v-list-item>
        </v-list>

        <!-- No Results -->
        <div v-else-if="searchQuery.length >= 2 && !loading" class="pa-4 text-center text-grey">
          No documents found for "{{ searchQuery }}"
        </div>

        <!-- Quick Navigation (shown when no search query) -->
        <v-list v-if="!searchQuery" density="compact" class="py-0">
          <v-list-subheader>Quick Navigation</v-list-subheader>
          <v-list-item
            v-for="item in quickNav"
            :key="item.to"
            @click="navigateTo(item.to)"
          >
            <template v-slot:prepend>
              <v-icon>{{ item.icon }}</v-icon>
            </template>
            <v-list-item-title>{{ item.title }}</v-list-item-title>
          </v-list-item>
        </v-list>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.search-input :deep(.v-field) {
  border-radius: 0;
}

kbd {
  padding: 2px 6px;
  background-color: rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 4px;
}
</style>
