<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import type { ChatMessage } from '@/types/api'
import SourceCitation from './SourceCitation.vue'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  rate: [messageId: string, rating: number]
}>()

const router = useRouter()
const showSources = ref(false)

const isUser = computed(() => props.message.role === 'user')
const hasContexts = computed(() => props.message.contexts && props.message.contexts.length > 0)

const formattedTime = computed(() => {
  return new Date(props.message.timestamp).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit'
  })
})

const tokenInfo = computed(() => {
  if (!props.message.usage) return null
  return `${props.message.usage.totalTokens} tokens`
})

function handleRate(rating: number) {
  emit('rate', props.message.id, rating)
}

function viewDocument(documentId: string) {
  router.push(`/documents/${documentId}`)
}
</script>

<template>
  <div
    class="chat-message d-flex"
    :class="{ 'justify-end': isUser, 'justify-start': !isUser }"
  >
    <div
      class="message-bubble pa-3"
      :class="{
        'user-message': isUser,
        'assistant-message': !isUser
      }"
    >
      <!-- Message Header -->
      <div class="d-flex align-center mb-1">
        <v-icon
          :icon="isUser ? 'mdi-account' : 'mdi-robot'"
          size="small"
          class="mr-2"
        ></v-icon>
        <span class="text-caption text-grey">{{ formattedTime }}</span>
        <v-spacer></v-spacer>
        <span v-if="tokenInfo && !isUser" class="text-caption text-grey">
          {{ tokenInfo }}
        </span>
      </div>

      <!-- Message Content -->
      <div class="message-content text-body-1">
        {{ message.content }}
      </div>

      <!-- Sources Toggle (for assistant messages with contexts) -->
      <div v-if="hasContexts && !isUser" class="mt-3">
        <v-btn
          size="small"
          variant="text"
          @click="showSources = !showSources"
          class="text-none"
        >
          <v-icon start size="small">
            {{ showSources ? 'mdi-chevron-up' : 'mdi-chevron-down' }}
          </v-icon>
          {{ showSources ? 'Hide' : 'Show' }} Sources ({{ message.contexts?.length }})
        </v-btn>

        <!-- Sources List -->
        <v-expand-transition>
          <div v-show="showSources" class="mt-2">
            <SourceCitation
              v-for="(context, idx) in message.contexts"
              :key="idx"
              :context="context"
              :index="idx"
              @view-document="viewDocument"
            />
          </div>
        </v-expand-transition>
      </div>

      <!-- Rating (for assistant messages) -->
      <div v-if="!isUser" class="mt-2 d-flex align-center">
        <span class="text-caption text-grey mr-2">Was this helpful?</span>
        <v-btn-group density="compact" variant="text">
          <v-btn
            size="x-small"
            :color="message.rating === 5 ? 'success' : undefined"
            @click="handleRate(5)"
            :disabled="message.rating !== undefined"
          >
            <v-icon size="small">mdi-thumb-up</v-icon>
          </v-btn>
          <v-btn
            size="x-small"
            :color="message.rating === 1 ? 'error' : undefined"
            @click="handleRate(1)"
            :disabled="message.rating !== undefined"
          >
            <v-icon size="small">mdi-thumb-down</v-icon>
          </v-btn>
        </v-btn-group>
        <span v-if="message.rating" class="text-caption text-grey ml-2">
          Thanks for your feedback!
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-message {
  margin-bottom: 16px;
}

.message-bubble {
  max-width: 80%;
  border-radius: 12px;
}

.user-message {
  background-color: rgb(var(--v-theme-primary));
  color: white;
  border-bottom-right-radius: 4px;
}

.assistant-message {
  background-color: rgb(var(--v-theme-surface-variant));
  border-bottom-left-radius: 4px;
}

.message-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}
</style>
