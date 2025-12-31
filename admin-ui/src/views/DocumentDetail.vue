<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDocumentsStore } from '@/stores/documents'

const route = useRoute()
const router = useRouter()
const documentsStore = useDocumentsStore()

const document = computed(() => documentsStore.currentDocument)
const chunks = computed(() => documentsStore.currentChunks)
const loading = computed(() => documentsStore.loading)

onMounted(() => {
  const id = route.params.id as string
  documentsStore.fetchDocument(id)
})

function goBack() {
  router.push('/documents')
}
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <v-btn icon variant="text" @click="goBack">
        <v-icon>mdi-arrow-left</v-icon>
      </v-btn>
      <h1 class="text-h4 ml-2">Document Detail</h1>
    </div>

    <v-progress-linear v-if="loading" indeterminate></v-progress-linear>

    <template v-if="document">
      <!-- Document Info -->
      <v-card class="mb-4">
        <v-card-title>Document Information</v-card-title>
        <v-card-text>
          <v-row>
            <v-col cols="12" sm="6">
              <div class="text-caption text-grey">ID</div>
              <code>{{ document.id }}</code>
            </v-col>
            <v-col cols="12" sm="6">
              <div class="text-caption text-grey">Filename</div>
              <div>{{ document.filename || '(untitled)' }}</div>
            </v-col>
            <v-col cols="12" sm="6">
              <div class="text-caption text-grey">Collection</div>
              <v-chip size="small" color="primary">{{ document.collection || 'default' }}</v-chip>
            </v-col>
            <v-col cols="12" sm="6">
              <div class="text-caption text-grey">Chunks</div>
              <div>{{ chunks?.length ?? 0 }}</div>
            </v-col>
          </v-row>

          <div v-if="document.metadata && Object.keys(document.metadata).length" class="mt-4">
            <div class="text-caption text-grey mb-2">Metadata</div>
            <v-chip
              v-for="(value, key) in document.metadata"
              :key="key"
              size="small"
              class="mr-2 mb-2"
            >
              {{ key }}: {{ value }}
            </v-chip>
          </div>
        </v-card-text>
      </v-card>

      <!-- Chunks -->
      <v-card>
        <v-card-title>Chunks ({{ chunks?.length ?? 0 }})</v-card-title>
        <v-card-text>
          <v-expansion-panels>
            <v-expansion-panel v-for="chunk in chunks" :key="chunk.id">
              <v-expansion-panel-title>
                <div class="d-flex align-center">
                  <v-chip size="x-small" class="mr-2">#{{ chunk.chunkIndex }}</v-chip>
                  <span class="text-truncate">{{ chunk.content.substring(0, 100) }}...</span>
                </div>
              </v-expansion-panel-title>
              <v-expansion-panel-text>
                <pre class="text-body-2" style="white-space: pre-wrap;">{{ chunk.content }}</pre>
                <div class="mt-2 text-caption text-grey">
                  Size: {{ chunk.content.length }} characters
                </div>
              </v-expansion-panel-text>
            </v-expansion-panel>
          </v-expansion-panels>

          <div v-if="!chunks?.length" class="text-center pa-8 text-grey">
            No chunks available
          </div>
        </v-card-text>
      </v-card>
    </template>
  </div>
</template>
