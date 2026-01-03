<script setup lang="ts">
import { ref, onMounted, nextTick, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import ChatMessage from '@/components/ChatMessage.vue'

const chatStore = useChatStore()
const inputText = ref('')
const messagesContainer = ref<HTMLElement | null>(null)

onMounted(() => {
  chatStore.fetchCollections()
})

// Scroll to bottom when new messages are added
watch(
  () => chatStore.messages.length,
  async () => {
    await nextTick()
    scrollToBottom()
  }
)

function scrollToBottom() {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

async function handleSend() {
  if (!inputText.value.trim() || chatStore.loading) return

  const question = inputText.value
  inputText.value = ''
  await chatStore.sendMessage(question)
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}

async function handleRate(messageId: string, rating: number) {
  await chatStore.rateMessage(messageId, rating)
}

function handleClear() {
  chatStore.clearMessages()
}
</script>

<template>
  <div class="chat-container d-flex flex-column" style="height: calc(100vh - 100px);">
    <!-- Header -->
    <div class="chat-header d-flex align-center pa-4">
      <h1 class="text-h5">Chat</h1>
      <v-spacer></v-spacer>

      <!-- Collection Selector -->
      <v-select
        :model-value="chatStore.selectedCollection"
        @update:model-value="chatStore.setCollection"
        :items="[
          { title: 'All Collections', value: '*' },
          ...chatStore.collections.map(c => ({ title: c.name, value: c.name }))
        ]"
        item-title="title"
        item-value="value"
        label="Collection"
        variant="outlined"
        density="compact"
        hide-details
        style="max-width: 250px;"
        class="mr-2"
        :loading="chatStore.collectionsLoading"
      ></v-select>

      <v-btn
        icon
        variant="text"
        @click="handleClear"
        :disabled="!chatStore.hasMessages"
      >
        <v-icon>mdi-delete-sweep</v-icon>
        <v-tooltip activator="parent" location="bottom">Clear Chat</v-tooltip>
      </v-btn>
    </div>

    <v-divider></v-divider>

    <!-- Messages Area -->
    <div
      ref="messagesContainer"
      class="messages-area flex-grow-1 pa-4"
      style="overflow-y: auto;"
    >
      <!-- Empty State -->
      <div
        v-if="!chatStore.hasMessages"
        class="empty-state d-flex flex-column align-center justify-center"
        style="height: 100%;"
      >
        <v-icon size="80" color="grey-lighten-1" class="mb-4">mdi-chat-question</v-icon>
        <h2 class="text-h6 text-grey mb-2">Ask a Question</h2>
        <p class="text-body-2 text-grey text-center" style="max-width: 400px;">
          Query your documents using natural language. The system will find relevant information
          and generate an answer based on your document collection.
        </p>
        <div class="mt-4">
          <v-chip
            v-for="example in [
              'What is the main topic of my documents?',
              'Summarize the key points',
              'How does this system work?'
            ]"
            :key="example"
            class="ma-1"
            variant="outlined"
            size="small"
            @click="inputText = example"
          >
            {{ example }}
          </v-chip>
        </div>
      </div>

      <!-- Messages -->
      <ChatMessage
        v-for="message in chatStore.messages"
        :key="message.id"
        :message="message"
        @rate="handleRate"
      />

      <!-- Loading Indicator -->
      <div v-if="chatStore.loading" class="d-flex justify-start mb-4">
        <div class="assistant-message pa-3" style="border-radius: 12px;">
          <div class="d-flex align-center">
            <v-progress-circular
              indeterminate
              size="20"
              width="2"
              class="mr-2"
            ></v-progress-circular>
            <span class="text-body-2 text-grey">Thinking...</span>
          </div>
        </div>
      </div>
    </div>

    <v-divider></v-divider>

    <!-- Input Area -->
    <div class="input-area pa-4">
      <v-textarea
        v-model="inputText"
        :disabled="chatStore.loading"
        placeholder="Type your question..."
        variant="outlined"
        rows="2"
        auto-grow
        max-rows="4"
        hide-details
        @keydown="handleKeydown"
      >
        <template v-slot:append-inner>
          <v-btn
            icon
            color="primary"
            :loading="chatStore.loading"
            :disabled="!inputText.trim()"
            @click="handleSend"
          >
            <v-icon>mdi-send</v-icon>
          </v-btn>
        </template>
      </v-textarea>
      <div class="text-caption text-grey mt-1">
        Press Enter to send, Shift+Enter for new line
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-container {
  background-color: rgb(var(--v-theme-background));
}

.messages-area {
  background-color: rgb(var(--v-theme-surface));
}

.assistant-message {
  background-color: rgb(var(--v-theme-surface-variant));
}

.empty-state {
  opacity: 0.8;
}
</style>
