<script setup lang="ts">
import type { KeyboardShortcut } from '@/composables/useKeyboardShortcuts'

defineProps<{
  modelValue: boolean
  shortcuts: KeyboardShortcut[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

function formatKey(shortcut: KeyboardShortcut): string {
  const parts: string[] = []
  if (shortcut.ctrl || shortcut.meta) {
    // Show Cmd on Mac, Ctrl on others
    parts.push(navigator.platform.includes('Mac') ? '⌘' : 'Ctrl')
  }
  if (shortcut.shift) parts.push('Shift')
  if (shortcut.alt) parts.push('Alt')
  parts.push(shortcut.key.toUpperCase())
  return parts.join(' + ')
}
</script>

<template>
  <v-dialog
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    max-width="400"
  >
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon class="mr-2">mdi-keyboard</v-icon>
        Keyboard Shortcuts
      </v-card-title>
      <v-card-text>
        <v-list density="compact">
          <v-list-item v-for="shortcut in shortcuts" :key="shortcut.key">
            <template v-slot:prepend>
              <kbd class="shortcut-key">{{ formatKey(shortcut) }}</kbd>
            </template>
            <v-list-item-title>{{ shortcut.description }}</v-list-item-title>
          </v-list-item>
          <v-divider class="my-2" />
          <v-list-item>
            <template v-slot:prepend>
              <kbd class="shortcut-key">?</kbd>
            </template>
            <v-list-item-title>Show this help</v-list-item-title>
          </v-list-item>
          <v-list-item>
            <template v-slot:prepend>
              <kbd class="shortcut-key">Esc</kbd>
            </template>
            <v-list-item-title>Close dialog / Clear focus</v-list-item-title>
          </v-list-item>
        </v-list>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="emit('update:modelValue', false)">Close</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.shortcut-key {
  display: inline-block;
  min-width: 80px;
  padding: 4px 8px;
  font-family: monospace;
  font-size: 0.85rem;
  background-color: rgba(var(--v-theme-on-surface), 0.08);
  border-radius: 4px;
  text-align: center;
}
</style>
