<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useConfigStore } from '@/stores/config'
import { useNotification } from '@/composables/useNotification'
import { type AppError, toAppError } from '@/composables/useApiError'
import ErrorAlert from '@/components/ErrorAlert.vue'
import type { PipelineConfigResponse, RuntimeConfig, RuntimeConfigUpdate, SettingValidation } from '@/types/api'
import { getPipelineConfig } from '@/api/config'

const configStore = useConfigStore()
const notification = useNotification()

const editing = ref(false)
const formData = ref<RuntimeConfig | null>(null)
const saving = ref(false)
const saveError = ref<AppError | null>(null)
const invalidSettings = ref<SettingValidation[]>([])
const warnings = ref<string[]>([])

const config = computed(() => configStore.runtimeConfig)
const strategyNames = computed(() => configStore.chunkingStrategies.map(s => s.name))

type ConfigKey = keyof RuntimeConfig
const configKeys = computed(() => (config.value ? (Object.keys(config.value) as ConfigKey[]) : []))

// How the pipeline searches, and which settings do not take effect (e.g. fusion in permission mode)
const pipeline = ref<PipelineConfigResponse | null>(null)

onMounted(() => {
  getPipelineConfig()
    .then(p => { pipeline.value = p })
    .catch(() => { pipeline.value = null })
  configStore.fetchRuntimeConfig()
  configStore.fetchConfigMeta()
  configStore.fetchConfigHistory()
  configStore.fetchChunkingStrategies()
})

function startEdit() {
  if (!config.value) return
  formData.value = { ...config.value }
  invalidSettings.value = []
  warnings.value = []
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  saveError.value = null
  invalidSettings.value = []
  warnings.value = []
}

/** Only send fields that actually changed, so untouched settings aren't re-written. */
function changedFields(): RuntimeConfigUpdate {
  const update: Record<string, unknown> = {}
  if (!config.value || !formData.value) return update
  for (const key of configKeys.value) {
    if (formData.value[key] !== config.value[key]) {
      update[key] = formData.value[key]
    }
  }
  return update as RuntimeConfigUpdate
}

async function saveConfig() {
  const update = changedFields()
  if (Object.keys(update).length === 0) {
    notification.info('No changes to save')
    editing.value = false
    return
  }

  saving.value = true
  saveError.value = null
  invalidSettings.value = []
  warnings.value = []

  try {
    const validation = await configStore.validateConfig(update)
    warnings.value = [
      ...validation.warnings,
      ...validation.validations.flatMap(v => (v.warning ? [`${v.field}: ${v.warning}`] : []))
    ]
    if (!validation.valid) {
      invalidSettings.value = validation.validations.filter(v => !v.valid)
      return
    }

    const result = await configStore.updateRuntimeConfig(update)
    notification.success(result.message || 'Configuration saved')
    editing.value = false
  } catch (e) {
    saveError.value = toAppError(e)
  } finally {
    saving.value = false
  }
}

function getConfigType(key: string): string {
  if (configStore.hotSettings.includes(key)) return 'hot'
  if (configStore.warmSettings.includes(key)) return 'warm'
  return configStore.configMeta.find(m => m.key === key)?.type || 'unknown'
}

function getDescription(key: string): string | undefined {
  return configStore.configMeta.find(m => m.key === key)?.description
}

function getTypeColor(type: string): string {
  switch (type) {
    case 'hot': return 'success'
    case 'warm': return 'warning'
    case 'cold': return 'error'
    default: return 'grey'
  }
}
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">Runtime Configuration</h1>
      <v-spacer></v-spacer>
      <v-btn v-if="!editing" color="primary" :disabled="!config" @click="startEdit">
        <v-icon start>mdi-pencil</v-icon>
        Edit
      </v-btn>
    </div>

    <p class="text-body-2 text-medium-emphasis mb-4">
      <v-chip size="x-small" color="success" class="mr-1">hot</v-chip> settings apply immediately;
      <v-chip size="x-small" color="warning" class="mx-1">warm</v-chip> settings apply to newly ingested documents.
      <span v-if="configStore.lastModified">
        Last modified {{ new Date(configStore.lastModified).toLocaleString() }}.
      </span>
    </p>

    <v-alert
      v-if="pipeline"
      :type="pipeline.rag.fusionApplies ? 'info' : 'warning'"
      variant="tonal"
      class="mb-4"
      data-testid="pipeline-limitations"
    >
      <div class="font-weight-medium mb-1">
        Search mode: {{ pipeline.rag.searchMode }}
        <span v-if="!pipeline.rag.fusionApplies">(fusionStrategy and rrfK have no effect)</span>
        &middot; reranking: {{ pipeline.rag.reranking }} &middot; query transform: {{ pipeline.rag.queryTransform }}
      </div>
      <ul class="ml-4">
        <li v-for="note in pipeline.rag.limitations" :key="note">{{ note }}</li>
      </ul>
    </v-alert>

    <ErrorAlert
      :error="saveError || configStore.error"
      :on-retry="saveError ? saveConfig : configStore.fetchRuntimeConfig"
      dismissible
      @dismiss="saveError = null; configStore.error = null"
    />

    <v-alert v-if="invalidSettings.length" type="error" variant="tonal" class="mb-4">
      <div class="font-weight-medium mb-1">Validation failed</div>
      <ul class="ml-4">
        <li v-for="v in invalidSettings" :key="v.field">
          <code>{{ v.field }}</code>: {{ v.error || 'invalid value' }}
        </li>
      </ul>
    </v-alert>

    <v-alert v-if="warnings.length" type="warning" variant="tonal" class="mb-4">
      <ul class="ml-4">
        <li v-for="w in warnings" :key="w">{{ w }}</li>
      </ul>
    </v-alert>

    <v-progress-linear v-if="configStore.loading" indeterminate class="mb-4"></v-progress-linear>

    <v-card v-if="config">
      <v-card-text>
        <v-row>
          <v-col cols="12" md="6" v-for="key in configKeys" :key="key">
            <div class="d-flex align-center mb-1">
              <span class="text-caption text-grey">{{ key }}</span>
              <v-chip size="x-small" :color="getTypeColor(getConfigType(key))" class="ml-2">
                {{ getConfigType(key) }}
              </v-chip>
            </div>
            <template v-if="editing && formData">
              <v-select
                v-if="key === 'chunkingStrategy' && strategyNames.length"
                v-model="formData.chunkingStrategy"
                :items="strategyNames"
                :hint="getDescription(key)"
                density="compact"
              ></v-select>
              <v-textarea
                v-else-if="key === 'systemPrompt'"
                v-model="formData.systemPrompt"
                :hint="getDescription(key)"
                rows="3"
                auto-grow
                density="compact"
              ></v-textarea>
              <v-text-field
                v-else-if="typeof config[key] === 'number'"
                v-model.number="(formData as Record<ConfigKey, string | number>)[key]"
                :hint="getDescription(key)"
                type="number"
                density="compact"
              ></v-text-field>
              <v-text-field
                v-else
                v-model="(formData as Record<ConfigKey, string | number>)[key]"
                :hint="getDescription(key)"
                density="compact"
              ></v-text-field>
            </template>
            <div v-else class="text-body-1" style="white-space: pre-wrap;">{{ config[key] }}</div>
          </v-col>
        </v-row>
      </v-card-text>

      <v-card-actions v-if="editing">
        <v-spacer></v-spacer>
        <v-btn @click="cancelEdit">Cancel</v-btn>
        <v-btn color="primary" @click="saveConfig" :loading="saving">Save</v-btn>
      </v-card-actions>
    </v-card>

    <!-- Config History -->
    <div class="mt-8" v-if="configStore.configHistory.length">
      <h2 class="text-h5 mb-4">Change History</h2>
      <v-card>
        <v-list>
          <v-list-item v-for="item in configStore.configHistory.slice(0, 10)" :key="item.id">
            <v-list-item-title>
              <code>{{ item.field }}</code>: {{ item.oldValue }} &rarr; {{ item.newValue }}
            </v-list-item-title>
            <v-list-item-subtitle>
              {{ new Date(item.changedAt).toLocaleString() }}
              <span v-if="item.changedBy"> by {{ item.changedBy }}</span>
            </v-list-item-subtitle>
          </v-list-item>
        </v-list>
      </v-card>
    </div>
  </div>
</template>
