<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useConfigStore } from '@/stores/config'
import type { CollectionConfig } from '@/types/api'

const configStore = useConfigStore()

const dialog = ref(false)
const editingCollection = ref<CollectionConfig | null>(null)
const formData = ref({
  name: '',
  chunkingStrategy: 'fixed-size',
  chunkSize: 1000,
  chunkOverlap: 200
})

const strategies = ['fixed-size', 'sentence', 'paragraph', 'semantic']

onMounted(() => {
  configStore.fetchCollectionConfigs()
})

function openNew() {
  editingCollection.value = null
  formData.value = {
    name: '',
    chunkingStrategy: 'fixed-size',
    chunkSize: 1000,
    chunkOverlap: 200
  }
  dialog.value = true
}

function openEdit(config: CollectionConfig) {
  editingCollection.value = config
  formData.value = {
    name: config.name,
    chunkingStrategy: config.chunkingStrategy,
    chunkSize: config.chunkSize,
    chunkOverlap: config.chunkOverlap
  }
  dialog.value = true
}

async function save() {
  await configStore.updateCollectionConfig(formData.value.name, {
    chunkingStrategy: formData.value.chunkingStrategy,
    chunkSize: formData.value.chunkSize,
    chunkOverlap: formData.value.chunkOverlap
  })
  dialog.value = false
}

async function deleteConfig(name: string) {
  if (confirm(`Delete configuration for collection "${name}"?`)) {
    await configStore.deleteCollectionConfig(name)
  }
}
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">Collection Configuration</h1>
      <v-spacer></v-spacer>
      <v-btn color="primary" @click="openNew">
        <v-icon start>mdi-plus</v-icon>
        Add Collection
      </v-btn>
    </div>

    <v-progress-linear v-if="configStore.loading" indeterminate class="mb-4"></v-progress-linear>

    <v-card>
      <v-list v-if="configStore.collectionConfigs?.length">
        <v-list-item
          v-for="config in configStore.collectionConfigs"
          :key="config.name"
        >
          <template v-slot:prepend>
            <v-icon>mdi-folder</v-icon>
          </template>

          <v-list-item-title>{{ config.name }}</v-list-item-title>
          <v-list-item-subtitle>
            Strategy: {{ config.chunkingStrategy }} |
            Size: {{ config.chunkSize }} |
            Overlap: {{ config.chunkOverlap }}
          </v-list-item-subtitle>

          <template v-slot:append>
            <v-btn icon size="small" variant="text" @click="openEdit(config)">
              <v-icon>mdi-pencil</v-icon>
            </v-btn>
            <v-btn icon size="small" variant="text" color="error" @click="deleteConfig(config.name)">
              <v-icon>mdi-delete</v-icon>
            </v-btn>
          </template>
        </v-list-item>
      </v-list>

      <div v-else class="text-center pa-8 text-grey">
        <v-icon size="64">mdi-folder-cog-outline</v-icon>
        <p class="mt-4">No collection-specific configurations. All collections use default settings.</p>
      </div>
    </v-card>

    <!-- Edit Dialog -->
    <v-dialog v-model="dialog" max-width="500">
      <v-card>
        <v-card-title>
          {{ editingCollection ? 'Edit Collection Config' : 'New Collection Config' }}
        </v-card-title>
        <v-card-text>
          <v-text-field
            v-model="formData.name"
            label="Collection Name"
            :disabled="!!editingCollection"
          ></v-text-field>

          <v-select
            v-model="formData.chunkingStrategy"
            :items="strategies"
            label="Chunking Strategy"
          ></v-select>

          <v-text-field
            v-model.number="formData.chunkSize"
            label="Chunk Size"
            type="number"
          ></v-text-field>

          <v-text-field
            v-model.number="formData.chunkOverlap"
            label="Chunk Overlap"
            type="number"
          ></v-text-field>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="dialog = false">Cancel</v-btn>
          <v-btn color="primary" @click="save" :loading="configStore.loading">Save</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
