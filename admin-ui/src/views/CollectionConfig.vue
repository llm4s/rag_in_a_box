<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useConfigStore } from '@/stores/config'
import { useNotification } from '@/composables/useNotification'
import { type AppError, toAppError } from '@/composables/useApiError'
import * as configApi from '@/api/config'
import ErrorAlert from '@/components/ErrorAlert.vue'
import type { CollectionConfigResponse, EffectiveConfigPreviewResponse } from '@/types/api'

const configStore = useConfigStore()
const notification = useNotification()

const dialog = ref(false)
const editingCollection = ref<CollectionConfigResponse | null>(null)
const formData = ref({
  name: '',
  strategy: '',
  targetSize: 800,
  maxSize: 1600,
  overlap: 150
})
const actionError = ref<AppError | null>(null)
const saving = ref(false)

// Effective-config preview (POST /collections/{c}/config/preview)
const previewCollection = ref('')
const previewFilename = ref('')
const previewResult = ref<EffectiveConfigPreviewResponse | null>(null)
const previewLoading = ref(false)

const strategyItems = computed(() =>
  configStore.chunkingStrategies.map(s => ({ title: s.displayName || s.name, value: s.name }))
)

onMounted(() => {
  configStore.fetchCollectionConfigs()
  configStore.fetchChunkingStrategies()
})

function defaults() {
  const d = configStore.defaultCollectionConfig
  return {
    strategy: d?.strategy ?? configStore.chunkingStrategies[0]?.name ?? 'sentence',
    targetSize: d?.targetSize ?? 800,
    maxSize: d?.maxSize ?? 1600,
    overlap: d?.overlap ?? 150
  }
}

function openNew() {
  editingCollection.value = null
  actionError.value = null
  formData.value = { name: '', ...defaults() }
  dialog.value = true
}

function openEdit(config: CollectionConfigResponse) {
  editingCollection.value = config
  actionError.value = null
  const eff = config.effectiveConfig
  formData.value = {
    name: config.collection,
    strategy: eff.strategy,
    targetSize: eff.targetSize,
    maxSize: eff.maxSize,
    overlap: eff.overlap
  }
  dialog.value = true
}

async function save() {
  if (!formData.value.name.trim()) {
    actionError.value = { code: 'VALIDATION_ERROR', message: 'Collection name is required', retryable: false }
    return
  }
  saving.value = true
  actionError.value = null
  try {
    await configStore.updateCollectionConfig(formData.value.name.trim(), {
      strategy: formData.value.strategy,
      targetSize: formData.value.targetSize,
      maxSize: formData.value.maxSize,
      overlap: formData.value.overlap
    })
    notification.success(`Saved chunking config for "${formData.value.name}"`)
    dialog.value = false
  } catch (e) {
    actionError.value = toAppError(e, false)
  } finally {
    saving.value = false
  }
}

async function deleteConfig(name: string) {
  if (!confirm(`Remove custom configuration for collection "${name}"? It will fall back to defaults.`)) return
  actionError.value = null
  try {
    await configStore.deleteCollectionConfig(name)
    notification.success(`"${name}" now uses the default configuration`)
  } catch (e) {
    actionError.value = toAppError(e, false)
  }
}

async function runPreview() {
  if (!previewCollection.value.trim()) return
  previewLoading.value = true
  actionError.value = null
  try {
    previewResult.value = await configApi.previewEffectiveConfig(
      previewCollection.value.trim(),
      previewFilename.value.trim() || undefined
    )
  } catch (e) {
    actionError.value = toAppError(e)
  } finally {
    previewLoading.value = false
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

    <ErrorAlert
      :error="actionError || configStore.error"
      :on-retry="configStore.fetchCollectionConfigs"
      dismissible
      @dismiss="actionError = null; configStore.error = null"
    />

    <v-progress-linear v-if="configStore.loading" indeterminate class="mb-4"></v-progress-linear>

    <v-card>
      <v-list v-if="configStore.collectionConfigs.length">
        <v-list-item
          v-for="config in configStore.collectionConfigs"
          :key="config.collection"
        >
          <template v-slot:prepend>
            <v-icon>mdi-folder</v-icon>
          </template>

          <v-list-item-title>
            {{ config.collection }}
            <v-chip v-if="config.hasCustomConfig" size="x-small" color="primary" class="ml-2">custom</v-chip>
            <v-chip v-else size="x-small" class="ml-2">default</v-chip>
          </v-list-item-title>
          <v-list-item-subtitle>
            Strategy: {{ config.effectiveConfig.strategy }} |
            Target: {{ config.effectiveConfig.targetSize }} |
            Max: {{ config.effectiveConfig.maxSize }} |
            Overlap: {{ config.effectiveConfig.overlap }} |
            Documents: {{ config.documentCount }}
          </v-list-item-subtitle>

          <template v-slot:append>
            <v-btn icon size="small" variant="text" @click="openEdit(config)" title="Edit">
              <v-icon>mdi-pencil</v-icon>
            </v-btn>
            <v-btn
              v-if="config.hasCustomConfig"
              icon
              size="small"
              variant="text"
              color="error"
              title="Reset to defaults"
              @click="deleteConfig(config.collection)"
            >
              <v-icon>mdi-delete</v-icon>
            </v-btn>
          </template>
        </v-list-item>
      </v-list>

      <div v-else class="text-center pa-8 text-grey">
        <v-icon size="64">mdi-folder-cog-outline</v-icon>
        <p class="mt-4">No collections yet. All collections use default settings.</p>
      </div>
    </v-card>

    <p v-if="configStore.defaultCollectionConfig" class="text-caption text-grey mt-2">
      Defaults: {{ configStore.defaultCollectionConfig.strategy }},
      target {{ configStore.defaultCollectionConfig.targetSize }},
      max {{ configStore.defaultCollectionConfig.maxSize }},
      overlap {{ configStore.defaultCollectionConfig.overlap }}
    </p>

    <!-- Effective config preview -->
    <v-card class="mt-6">
      <v-card-title>Preview Effective Config</v-card-title>
      <v-card-text>
        <v-row>
          <v-col cols="12" sm="5">
            <v-text-field v-model="previewCollection" label="Collection" density="compact" hide-details />
          </v-col>
          <v-col cols="12" sm="5">
            <v-text-field
              v-model="previewFilename"
              label="Filename (optional, e.g. guide.md)"
              density="compact"
              hide-details
            />
          </v-col>
          <v-col cols="12" sm="2">
            <v-btn
              block
              color="primary"
              :loading="previewLoading"
              :disabled="!previewCollection.trim()"
              @click="runPreview"
            >
              Preview
            </v-btn>
          </v-col>
        </v-row>
        <div v-if="previewResult" class="mt-4">
          <div>
            <strong>{{ previewResult.effectiveConfig.strategy }}</strong>,
            target {{ previewResult.effectiveConfig.targetSize }},
            max {{ previewResult.effectiveConfig.maxSize }},
            overlap {{ previewResult.effectiveConfig.overlap }}
            <v-chip size="x-small" class="ml-2">source: {{ previewResult.effectiveConfig.source }}</v-chip>
          </div>
          <ol class="ml-6 mt-2 text-body-2">
            <li v-for="(step, i) in previewResult.configResolutionPath" :key="i">{{ step }}</li>
          </ol>
        </div>
      </v-card-text>
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
            v-model="formData.strategy"
            :items="strategyItems"
            label="Chunking Strategy"
          ></v-select>

          <v-text-field
            v-model.number="formData.targetSize"
            label="Target Size (characters)"
            type="number"
            hint="100 - 10000"
          ></v-text-field>

          <v-text-field
            v-model.number="formData.maxSize"
            label="Max Size (characters)"
            type="number"
            hint="100 - 20000"
          ></v-text-field>

          <v-text-field
            v-model.number="formData.overlap"
            label="Overlap (characters)"
            type="number"
            hint="Must be less than target size"
          ></v-text-field>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="dialog = false">Cancel</v-btn>
          <v-btn color="primary" @click="save" :loading="saving">Save</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
