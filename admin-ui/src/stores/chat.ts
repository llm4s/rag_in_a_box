import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  ChatMessage,
  CollectionStats,
  ContextItem,
  UsageInfo,
  ChatSession,
  ChatMessageRecord
} from '@/types/api'
import * as chatApi from '@/api/chat'
import * as visibilityApi from '@/api/visibility'

function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
}

/**
 * Convert a backend ChatMessageRecord to frontend ChatMessage format.
 */
function recordToMessage(record: ChatMessageRecord): ChatMessage {
  return {
    id: record.id,
    role: record.role as 'user' | 'assistant',
    content: record.content,
    contexts: record.contexts,
    usage: record.usage,
    timestamp: new Date(record.createdAt),
    rating: record.rating,
    queryLogId: record.queryLogId
  }
}

export const useChatStore = defineStore('chat', () => {
  // Session state
  const sessions = ref<ChatSession[]>([])
  const currentSession = ref<ChatSession | null>(null)
  const sessionsLoading = ref(false)

  // Message state
  const messages = ref<ChatMessage[]>([])
  const loading = ref(false)
  const streaming = ref(false)
  const error = ref<string | null>(null)

  // Collection state
  const selectedCollection = ref<string>('*')
  const collections = ref<CollectionStats[]>([])
  const collectionsLoading = ref(false)

  // Streaming state
  const streamController = ref<AbortController | null>(null)
  const useStreaming = ref(true)

  const hasMessages = computed(() => messages.value.length > 0)
  const hasSession = computed(() => currentSession.value !== null)

  // ============================================================
  // Session Operations
  // ============================================================

  /**
   * Fetch all chat sessions from the backend.
   */
  async function fetchSessions(includeArchived = false): Promise<void> {
    sessionsLoading.value = true
    try {
      const response = await chatApi.listSessions(includeArchived)
      sessions.value = response.sessions
    } catch (e) {
      console.error('Failed to fetch sessions', e)
    } finally {
      sessionsLoading.value = false
    }
  }

  /**
   * Create a new chat session.
   */
  async function createSession(title?: string): Promise<ChatSession | null> {
    try {
      const response = await chatApi.createSession({
        title,
        collectionPattern: selectedCollection.value
      })
      sessions.value.unshift(response.session)
      return response.session
    } catch (e) {
      console.error('Failed to create session', e)
      error.value = e instanceof Error ? e.message : 'Failed to create session'
      return null
    }
  }

  /**
   * Select and load a session with its messages.
   */
  async function selectSession(sessionId: string): Promise<void> {
    loading.value = true
    error.value = null
    cancelStream()

    try {
      const response = await chatApi.getSession(sessionId)
      currentSession.value = response.session
      selectedCollection.value = response.session.collectionPattern
      messages.value = response.messages.map(recordToMessage)
    } catch (e) {
      console.error('Failed to load session', e)
      error.value = e instanceof Error ? e.message : 'Failed to load session'
    } finally {
      loading.value = false
    }
  }

  /**
   * Update the current session.
   */
  async function updateSession(updates: { title?: string; isArchived?: boolean }): Promise<boolean> {
    if (!currentSession.value) return false

    try {
      const updated = await chatApi.updateSession(currentSession.value.id, updates)
      currentSession.value = updated

      // Update in sessions list
      const index = sessions.value.findIndex(s => s.id === updated.id)
      if (index >= 0) {
        sessions.value[index] = updated
      }
      return true
    } catch (e) {
      console.error('Failed to update session', e)
      return false
    }
  }

  /**
   * Delete a session.
   */
  async function deleteSession(sessionId: string): Promise<boolean> {
    try {
      await chatApi.deleteSession(sessionId)
      sessions.value = sessions.value.filter(s => s.id !== sessionId)

      if (currentSession.value?.id === sessionId) {
        currentSession.value = null
        messages.value = []
      }
      return true
    } catch (e) {
      console.error('Failed to delete session', e)
      return false
    }
  }

  /**
   * Start a new chat (clear current session and messages).
   */
  function newChat(): void {
    cancelStream()
    currentSession.value = null
    messages.value = []
    error.value = null
  }

  // ============================================================
  // Collection Operations
  // ============================================================

  async function fetchCollections(): Promise<void> {
    collectionsLoading.value = true
    try {
      collections.value = await visibilityApi.getCollections()
    } catch (e) {
      console.error('Failed to fetch collections', e)
    } finally {
      collectionsLoading.value = false
    }
  }

  // ============================================================
  // Message Operations
  // ============================================================

  /**
   * Send a message with optional streaming support.
   * Auto-creates a session if none exists.
   */
  async function sendMessage(question: string): Promise<void> {
    if (!question.trim()) return

    // Auto-create session if needed
    if (!currentSession.value) {
      const session = await createSession()
      if (!session) {
        error.value = 'Failed to create chat session'
        return
      }
      currentSession.value = session
    }

    if (useStreaming.value) {
      await sendMessageStreaming(question.trim())
    } else {
      await sendMessageNonStreaming(question.trim())
    }
  }

  /**
   * Send a message using SSE streaming for real-time updates.
   */
  async function sendMessageStreaming(question: string): Promise<void> {
    if (!currentSession.value) return

    loading.value = true
    streaming.value = true
    error.value = null

    // Create placeholder assistant message
    const assistantMessageId = generateId()
    const assistantMessage: ChatMessage = {
      id: assistantMessageId,
      role: 'assistant',
      content: '',
      contexts: [],
      timestamp: new Date(),
      isStreaming: true
    }

    // Get the message reference for updates
    const getMessage = () => messages.value.find(m => m.id === assistantMessageId)

    return new Promise((resolve) => {
      streamController.value = chatApi.sendMessageStream(
        currentSession.value!.id,
        { content: question },
        {
          onStart: (queryId, userMessageId) => {
            // Add user message when we get confirmation
            const userMsg: ChatMessage = {
              id: userMessageId,
              role: 'user',
              content: question,
              timestamp: new Date()
            }
            messages.value.push(userMsg)
            messages.value.push(assistantMessage)

            const msg = getMessage()
            if (msg) {
              msg.queryLogId = queryId
            }
          },
          onContext: (context: ContextItem, _index: number) => {
            const msg = getMessage()
            if (msg) {
              if (!msg.contexts) msg.contexts = []
              msg.contexts.push(context)
            }
          },
          onAnswer: (answer: string) => {
            const msg = getMessage()
            if (msg) {
              msg.content = answer
            }
          },
          onUsage: (usage: UsageInfo) => {
            const msg = getMessage()
            if (msg) {
              msg.usage = usage
            }
          },
          onComplete: (_queryId: string, _totalContexts: number) => {
            const msg = getMessage()
            if (msg) {
              msg.isStreaming = false
            }

            // Update session in list (message count changed)
            if (currentSession.value) {
              currentSession.value.messageCount += 2
              const idx = sessions.value.findIndex(s => s.id === currentSession.value?.id)
              if (idx >= 0) {
                sessions.value[idx] = { ...currentSession.value }
              }
            }

            loading.value = false
            streaming.value = false
            streamController.value = null
            resolve()
          },
          onError: (_errorCode: string, errorMessage: string) => {
            // If no messages added yet, add them now
            if (!messages.value.find(m => m.id === assistantMessageId)) {
              const userMsg: ChatMessage = {
                id: generateId(),
                role: 'user',
                content: question,
                timestamp: new Date()
              }
              messages.value.push(userMsg)
              assistantMessage.content = `Sorry, I encountered an error: ${errorMessage}`
              assistantMessage.isStreaming = false
              messages.value.push(assistantMessage)
            } else {
              const msg = getMessage()
              if (msg) {
                msg.content = `Sorry, I encountered an error: ${errorMessage}`
                msg.isStreaming = false
              }
            }
            error.value = errorMessage
            loading.value = false
            streaming.value = false
            streamController.value = null
            resolve()
          }
        }
      )
    })
  }

  /**
   * Send a message using traditional request/response (non-streaming).
   */
  async function sendMessageNonStreaming(question: string): Promise<void> {
    if (!currentSession.value) return

    loading.value = true
    error.value = null

    try {
      const response = await chatApi.sendMessage(
        currentSession.value.id,
        { content: question }
      )

      // Add messages from response
      messages.value.push(recordToMessage(response.userMessage))
      messages.value.push(recordToMessage(response.assistantMessage))

      if (response.error) {
        error.value = response.error
      }

      // Update session in list
      if (currentSession.value) {
        currentSession.value.messageCount += 2
        const idx = sessions.value.findIndex(s => s.id === currentSession.value?.id)
        if (idx >= 0) {
          sessions.value[idx] = { ...currentSession.value }
        }
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to get response'

      // Add local error message
      const userMsg: ChatMessage = {
        id: generateId(),
        role: 'user',
        content: question,
        timestamp: new Date()
      }
      messages.value.push(userMsg)

      const errorMessage: ChatMessage = {
        id: generateId(),
        role: 'assistant',
        content: `Sorry, I encountered an error: ${error.value}`,
        timestamp: new Date()
      }
      messages.value.push(errorMessage)
    } finally {
      loading.value = false
    }
  }

  /**
   * Cancel the current streaming request.
   */
  function cancelStream(): void {
    if (streamController.value) {
      streamController.value.abort()
      streamController.value = null
      streaming.value = false
      loading.value = false

      // Mark the last message as not streaming
      const lastMessage = messages.value[messages.value.length - 1]
      if (lastMessage && lastMessage.role === 'assistant' && lastMessage.isStreaming) {
        lastMessage.isStreaming = false
        if (!lastMessage.content) {
          lastMessage.content = '(Cancelled)'
        }
      }
    }
  }

  /**
   * Rate a message (updates both local state and backend).
   */
  async function rateMessage(messageId: string, rating: number): Promise<boolean> {
    const message = messages.value.find(m => m.id === messageId)
    if (!message || message.role !== 'assistant') return false

    try {
      await chatApi.rateMessage(messageId, { rating })
      message.rating = rating
      return true
    } catch (e) {
      console.error('Failed to submit rating', e)
      return false
    }
  }

  /**
   * Clear messages (deprecated - use newChat instead).
   */
  function clearMessages(): void {
    newChat()
  }

  function setCollection(collection: string): void {
    selectedCollection.value = collection

    // Update current session's collection pattern if we have one
    if (currentSession.value && currentSession.value.collectionPattern !== collection) {
      chatApi.updateSession(currentSession.value.id, { collectionPattern: collection })
        .then(updated => {
          if (currentSession.value) {
            currentSession.value = updated
          }
        })
        .catch(e => console.error('Failed to update session collection', e))
    }
  }

  function toggleStreaming(): void {
    useStreaming.value = !useStreaming.value
  }

  return {
    // Session state
    sessions,
    currentSession,
    sessionsLoading,
    hasSession,

    // Message state
    messages,
    loading,
    streaming,
    error,
    hasMessages,

    // Collection state
    selectedCollection,
    collections,
    collectionsLoading,

    // Streaming state
    useStreaming,

    // Session actions
    fetchSessions,
    createSession,
    selectSession,
    updateSession,
    deleteSession,
    newChat,

    // Collection actions
    fetchCollections,

    // Message actions
    sendMessage,
    sendMessageStreaming,
    sendMessageNonStreaming,
    cancelStream,
    rateMessage,
    clearMessages,
    setCollection,
    toggleStreaming
  }
})
