<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useConfigStore } from '@/stores/config'

const configStore = useConfigStore()

const editing = ref(false)
const formData = ref<Record<string, any>>({})
const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)

const config = computed(() => configStore.runtimeConfig)

onMounted(() => {
  configStore.fetchRuntimeConfig()
  configStore.fetchConfigMeta()
  configStore.fetchConfigHistory()
})

function startEdit() {
  formData.value = { ...config.value }
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  saveError.value = null
}

async function saveConfig() {
  saving.value = true
  saveError.value = null

  try {
    await configStore.updateRuntimeConfig(formData.value)
    saveSuccess.value = true
    editing.value = false
    setTimeout(() => { saveSuccess.value = false }, 3000)
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : 'Failed to save configuration'
  } finally {
    saving.value = false
  }
}

function getConfigType(key: string): string {
  const meta = configStore.configMeta.find(m => m.key === key)
  return meta?.type || 'unknown'
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
      <v-btn v-if="!editing" color="primary" @click="startEdit">
        <v-icon start>mdi-pencil</v-icon>
        Edit
      </v-btn>
    </div>

    <v-alert v-if="saveSuccess" type="success" class="mb-4" closable>
      Configuration saved successfully!
    </v-alert>

    <v-alert v-if="saveError" type="error" class="mb-4" closable @click:close="saveError = null">
      {{ saveError }}
    </v-alert>

    <v-progress-linear v-if="configStore.loading" indeterminate class="mb-4"></v-progress-linear>

    <v-card v-if="config">
      <v-card-text>
        <v-row>
          <v-col cols="12" md="6" v-for="(value, key) in config" :key="key">
            <div class="d-flex align-center mb-1">
              <span class="text-caption text-grey">{{ key }}</span>
              <v-chip size="x-small" :color="getTypeColor(getConfigType(String(key)))" class="ml-2">
                {{ getConfigType(String(key)) }}
              </v-chip>
            </div>
            <template v-if="editing">
              <v-text-field
                v-if="typeof value === 'number'"
                v-model.number="formData[key]"
                type="number"
                density="compact"
              ></v-text-field>
              <v-text-field
                v-else
                v-model="formData[key]"
                density="compact"
              ></v-text-field>
            </template>
            <div v-else class="text-body-1">
              {{ Array.isArray(value) ? value.join(', ') : value }}
            </div>
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
    <div class="mt-8" v-if="configStore.configHistory?.length">
      <h2 class="text-h5 mb-4">Change History</h2>
      <v-card>
        <v-list>
          <v-list-item v-for="item in configStore.configHistory.slice(0, 10)" :key="item.id">
            <v-list-item-title>
              <code>{{ item.key }}</code>: {{ item.oldValue }} -> {{ item.newValue }}
            </v-list-item-title>
            <v-list-item-subtitle>
              {{ new Date(item.changedAt).toLocaleString() }}
            </v-list-item-subtitle>
          </v-list-item>
        </v-list>
      </v-card>
    </div>
  </div>
</template>
