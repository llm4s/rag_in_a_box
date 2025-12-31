<script setup lang="ts">
import { computed } from 'vue'
import { isAppError, type AppError } from '@/composables/useApiError'

const props = defineProps<{
  error: AppError | Error | string | null
  onRetry?: () => void
  dismissible?: boolean
}>()

const emit = defineEmits<{
  dismiss: []
}>()

const errorMessage = computed(() => {
  if (!props.error) return ''
  if (isAppError(props.error)) return props.error.message
  if (props.error instanceof Error) return props.error.message
  return props.error
})

const isRetryable = computed(() => {
  if (!props.error || !props.onRetry) return false
  if (isAppError(props.error)) return props.error.retryable
  return true // Default to retryable for generic errors
})

const errorCode = computed(() => {
  if (!props.error) return null
  if (isAppError(props.error)) return props.error.code
  return null
})
</script>

<template>
  <v-alert
    v-if="error"
    type="error"
    variant="tonal"
    :closable="dismissible"
    class="mb-4"
    @click:close="emit('dismiss')"
  >
    <template #title v-if="errorCode">
      <span class="text-caption text-medium-emphasis">{{ errorCode }}</span>
    </template>

    <div class="d-flex align-center justify-space-between">
      <span>{{ errorMessage }}</span>
      <v-btn
        v-if="isRetryable"
        variant="outlined"
        size="small"
        color="error"
        class="ml-4"
        prepend-icon="mdi-refresh"
        @click="onRetry"
      >
        Retry
      </v-btn>
    </div>
  </v-alert>
</template>
