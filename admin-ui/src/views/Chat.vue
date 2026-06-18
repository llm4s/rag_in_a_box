<script setup lang="ts">
import { ref, onMounted, nextTick, watch, computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import ChatMessage from '@/components/ChatMessage.vue'
import type { ChatSession } from '@/types/api'

const chatStore = useChatStore()
const inputText = ref('')
const messagesContainer = ref<HTMLElement | null>(null)
const sidebarOpen = ref(true)

// Session management
const editingTitle = ref<string | null>(null)
const editTitleText = ref('')
const deleteDialog = ref(false)
const sessionToDelete = ref<ChatSession | null>(null)

onMounted(async () => {
  await Promise.all([
    chatStore.fetchCollections(),
    chatStore.fetchSessions()
  ])
})

// Scroll to bottom when new messages are added
watch(
  () => chatStore.messages.length,
  async () => {
    await nextTick()
    scrollToBottom()
  }
)

// Watch for streaming content updates
watch(
  () => chatStore.messages.map(m => m.content).join(''),
  async () => {
    await nextTick()
    scrollToBottom()
  }
)

const currentTitle = computed(() => {
  if (chatStore.currentSession) {
    return chatStore.currentSession.title || 'Untitled Chat'
  }
  return 'New Chat'
})

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

function handleNewChat() {
  chatStore.newChat()
}

async function handleSelectSession(session: ChatSession) {
  if (session.id !== chatStore.currentSession?.id) {
    await chatStore.selectSession(session.id)
  }
}

function startEditTitle(session: ChatSession) {
  editingTitle.value = session.id
  editTitleText.value = session.title || ''
}

async function saveTitle(sessionId: string) {
  if (editTitleText.value.trim()) {
    const wasCurrentSession = chatStore.currentSession?.id === sessionId
    if (wasCurrentSession) {
      await chatStore.updateSession({ title: editTitleText.value.trim() })
    } else {
      // Update session in list directly
      const session = chatStore.sessions.find(s => s.id === sessionId)
      if (session) {
        session.title = editTitleText.value.trim()
      }
    }
  }
  editingTitle.value = null
  editTitleText.value = ''
}

function cancelEditTitle() {
  editingTitle.value = null
  editTitleText.value = ''
}

function confirmDelete(session: ChatSession) {
  sessionToDelete.value = session
  deleteDialog.value = true
}

async function handleDelete() {
  if (sessionToDelete.value) {
    await chatStore.deleteSession(sessionToDelete.value.id)
  }
  deleteDialog.value = false
  sessionToDelete.value = null
}

function formatSessionDate(dateString: string): string {
  const date = new Date(dateString)
  const now = new Date()
  const diffDays = Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24))

  if (diffDays === 0) return 'Today'
  if (diffDays === 1) return 'Yesterday'
  if (diffDays < 7) return `${diffDays} days ago`

  return date.toLocaleDateString()
}

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}
</script>

<template>
  <div class="chat-layout d-flex" style="height: calc(100vh - 100px);">
    <!-- Session Sidebar -->
    <v-navigation-drawer
      v-model="sidebarOpen"
      :width="280"
      permanent
      :rail="!sidebarOpen"
      :rail-width="56"
    >
      <div class="sidebar-header d-flex align-center pa-3">
        <v-btn
          v-if="sidebarOpen"
          color="primary"
          variant="flat"
          block
          @click="handleNewChat"
        >
          <v-icon start>mdi-plus</v-icon>
          New Chat
        </v-btn>
        <v-btn
          v-else
          icon
          color="primary"
          @click="handleNewChat"
        >
          <v-icon>mdi-plus</v-icon>
          <v-tooltip activator="parent" location="right">New Chat</v-tooltip>
        </v-btn>
      </div>

      <v-divider></v-divider>

      <!-- Sessions List -->
      <v-list v-if="sidebarOpen" density="compact" nav class="sessions-list">
        <div v-if="chatStore.sessionsLoading" class="pa-4 text-center">
          <v-progress-circular indeterminate size="24"></v-progress-circular>
        </div>

        <div v-else-if="chatStore.sessions.length === 0" class="pa-4 text-center text-grey">
          No conversations yet
        </div>

        <template v-else>
          <v-list-item
            v-for="session in chatStore.sessions"
            :key="session.id"
            :active="chatStore.currentSession?.id === session.id"
            @click="handleSelectSession(session)"
            class="session-item"
          >
            <template v-slot:prepend>
              <v-icon size="small">mdi-chat-outline</v-icon>
            </template>

            <v-list-item-title v-if="editingTitle !== session.id">
              {{ session.title || 'Untitled Chat' }}
            </v-list-item-title>

            <v-text-field
              v-else
              v-model="editTitleText"
              density="compact"
              variant="underlined"
              hide-details
              autofocus
              @keydown.enter="saveTitle(session.id)"
              @keydown.esc="cancelEditTitle"
              @blur="saveTitle(session.id)"
              @click.stop
            ></v-text-field>

            <v-list-item-subtitle>
              {{ session.messageCount }} messages &middot; {{ formatSessionDate(session.updatedAt) }}
            </v-list-item-subtitle>

            <template v-slot:append v-if="editingTitle !== session.id">
              <v-menu>
                <template v-slot:activator="{ props }">
                  <v-btn
                    v-bind="props"
                    icon
                    variant="text"
                    size="x-small"
                    @click.stop
                  >
                    <v-icon size="small">mdi-dots-vertical</v-icon>
                  </v-btn>
                </template>
                <v-list density="compact">
                  <v-list-item @click="startEditTitle(session)">
                    <template v-slot:prepend>
                      <v-icon size="small">mdi-pencil</v-icon>
                    </template>
                    <v-list-item-title>Rename</v-list-item-title>
                  </v-list-item>
                  <v-list-item @click="confirmDelete(session)" class="text-error">
                    <template v-slot:prepend>
                      <v-icon size="small">mdi-delete</v-icon>
                    </template>
                    <v-list-item-title>Delete</v-list-item-title>
                  </v-list-item>
                </v-list>
              </v-menu>
            </template>
          </v-list-item>
        </template>
      </v-list>

      <!-- Collapsed state icons -->
      <v-list v-else density="compact" nav>
        <v-list-item
          v-for="session in chatStore.sessions.slice(0, 5)"
          :key="session.id"
          :active="chatStore.currentSession?.id === session.id"
          @click="handleSelectSession(session)"
        >
          <v-icon size="small">mdi-chat-outline</v-icon>
          <v-tooltip activator="parent" location="right">
            {{ session.title || 'Untitled Chat' }}
          </v-tooltip>
        </v-list-item>
      </v-list>
    </v-navigation-drawer>

    <!-- Main Chat Area -->
    <div class="chat-container d-flex flex-column flex-grow-1">
      <!-- Header -->
      <div class="chat-header d-flex align-center pa-4">
        <v-btn
          icon
          variant="text"
          size="small"
          @click="toggleSidebar"
          class="mr-2"
        >
          <v-icon>{{ sidebarOpen ? 'mdi-menu-open' : 'mdi-menu' }}</v-icon>
          <v-tooltip activator="parent" location="bottom">
            {{ sidebarOpen ? 'Collapse Sidebar' : 'Expand Sidebar' }}
          </v-tooltip>
        </v-btn>

        <h1 class="text-h6">{{ currentTitle }}</h1>

        <v-chip
          v-if="chatStore.currentSession"
          size="x-small"
          class="ml-2"
          color="primary"
          variant="outlined"
        >
          {{ chatStore.currentSession.collectionPattern === '*' ? 'All' : chatStore.currentSession.collectionPattern }}
        </v-chip>

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
          style="max-width: 200px;"
          class="mr-2"
          :loading="chatStore.collectionsLoading"
        ></v-select>

        <v-btn
          icon
          variant="text"
          @click="handleNewChat"
          :disabled="!chatStore.hasMessages && !chatStore.currentSession"
        >
          <v-icon>mdi-chat-plus</v-icon>
          <v-tooltip activator="parent" location="bottom">New Chat</v-tooltip>
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

        <!-- Loading Indicator (only shown when not streaming) -->
        <div v-if="chatStore.loading && !chatStore.streaming" class="d-flex justify-start mb-4">
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
              v-if="chatStore.streaming"
              icon
              color="error"
              @click="chatStore.cancelStream"
            >
              <v-icon>mdi-stop</v-icon>
              <v-tooltip activator="parent" location="top">Stop</v-tooltip>
            </v-btn>
            <v-btn
              v-else
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

    <!-- Delete Confirmation Dialog -->
    <v-dialog v-model="deleteDialog" max-width="400">
      <v-card>
        <v-card-title>Delete Conversation?</v-card-title>
        <v-card-text>
          Are you sure you want to delete "{{ sessionToDelete?.title || 'Untitled Chat' }}"?
          This action cannot be undone.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteDialog = false">Cancel</v-btn>
          <v-btn color="error" variant="flat" @click="handleDelete">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.chat-layout {
  background-color: rgb(var(--v-theme-background));
}

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

.sessions-list {
  overflow-y: auto;
  max-height: calc(100vh - 200px);
}

.session-item {
  cursor: pointer;
}

.session-item:hover {
  background-color: rgba(var(--v-theme-primary), 0.08);
}
</style>
