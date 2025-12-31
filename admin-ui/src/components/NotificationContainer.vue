<script setup lang="ts">
import { useNotification } from '@/composables/useNotification'

const { notifications, dismiss } = useNotification()

function getColor(type: string): string {
  switch (type) {
    case 'success': return 'success'
    case 'error': return 'error'
    case 'warning': return 'warning'
    case 'info': return 'info'
    default: return 'primary'
  }
}

function getIcon(type: string): string {
  switch (type) {
    case 'success': return 'mdi-check-circle'
    case 'error': return 'mdi-alert-circle'
    case 'warning': return 'mdi-alert'
    case 'info': return 'mdi-information'
    default: return 'mdi-information'
  }
}
</script>

<template>
  <div class="notification-container">
    <v-snackbar
      v-for="notification in notifications"
      :key="notification.id"
      :model-value="true"
      :color="getColor(notification.type)"
      :timeout="-1"
      location="top right"
      multi-line
      class="notification-snackbar"
    >
      <div class="d-flex align-center">
        <v-icon :icon="getIcon(notification.type)" class="mr-3" />
        <span>{{ notification.message }}</span>
      </div>
      <template #actions>
        <v-btn
          variant="text"
          icon="mdi-close"
          size="small"
          @click="dismiss(notification.id)"
        />
      </template>
    </v-snackbar>
  </div>
</template>

<style scoped>
.notification-container {
  position: fixed;
  top: 16px;
  right: 16px;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.notification-snackbar {
  position: relative !important;
}
</style>
