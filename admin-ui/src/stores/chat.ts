import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ChatMessage, QueryResponse, CollectionStats, ContextItem, UsageInfo } from '@/types/api'
import * as queryApi from '@/api/query'
import * as visibilityApi from '@/api/visibility'
import * as analyticsApi from '@/api/analytics'

function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const loading = ref(false)
  const streaming = ref(false)
  const error = ref<string | null>(null)
  const selectedCollection = ref<string>('*')
  const collections = ref<CollectionStats[]>([])
  const collectionsLoading = ref(false)
  const streamController = ref<AbortController | null>(null)
  const useStreaming = ref(true) // Toggle between streaming and non-streaming mode

  const hasMessages = computed(() => messages.value.length > 0)

  async function fetchCollections() {
    collectionsLoading.value = true
    try {
      collections.value = await visibilityApi.getCollections()
    } catch (e) {
      console.error('Failed to fetch collections', e)
    } finally {
      collectionsLoading.value = false
    }
  }

  /**
   * Send a message with optional streaming support.
   */
  async function sendMessage(question: string): Promise<void> {
    if (!question.trim()) return

    // Add user message
    const userMessage: ChatMessage = {
      id: generateId(),
      role: 'user',
      content: question.trim(),
      timestamp: new Date()
    }
    messages.value.push(userMessage)

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
    messages.value.push(assistantMessage)

    // Get the message reference for updates
    const getMessage = () => messages.value.find(m => m.id === assistantMessageId)

    return new Promise((resolve) => {
      streamController.value = queryApi.executeQueryStream(
        {
          question,
          collection: selectedCollection.value !== '*' ? selectedCollection.value : undefined,
          includeMetadata: true
        },
        {
          onStart: (queryId) => {
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
          onChunk: (chunk: string) => {
            const msg = getMessage()
            if (msg) {
              msg.content += chunk
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
            loading.value = false
            streaming.value = false
            streamController.value = null
            resolve()
          },
          onError: (_errorCode: string, errorMessage: string) => {
            const msg = getMessage()
            if (msg) {
              msg.content = `Sorry, I encountered an error: ${errorMessage}`
              msg.isStreaming = false
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
    loading.value = true
    error.value = null

    try {
      const response: QueryResponse = await queryApi.executeQuery({
        question,
        collection: selectedCollection.value !== '*' ? selectedCollection.value : undefined,
        includeMetadata: true
      })

      // Add assistant message
      const assistantMessage: ChatMessage = {
        id: generateId(),
        role: 'assistant',
        content: response.answer,
        contexts: response.contexts,
        usage: response.usage,
        timestamp: new Date()
      }
      messages.value.push(assistantMessage)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to get response'
      // Add error message
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

  async function rateMessage(messageId: string, rating: number): Promise<boolean> {
    const message = messages.value.find(m => m.id === messageId)
    if (!message || message.role !== 'assistant') return false

    try {
      // Find the corresponding user message to get the query text
      const messageIndex = messages.value.findIndex(m => m.id === messageId)
      if (messageIndex <= 0) return false

      // Submit feedback (the backend will track this)
      await analyticsApi.submitFeedback({
        queryId: message.queryLogId || messageId, // Use queryLogId if available
        rating,
        comment: undefined
      })

      message.rating = rating
      return true
    } catch (e) {
      console.error('Failed to submit rating', e)
      return false
    }
  }

  function clearMessages() {
    cancelStream() // Cancel any ongoing stream
    messages.value = []
    error.value = null
  }

  function setCollection(collection: string) {
    selectedCollection.value = collection
  }

  function toggleStreaming() {
    useStreaming.value = !useStreaming.value
  }

  return {
    messages,
    loading,
    streaming,
    error,
    selectedCollection,
    collections,
    collectionsLoading,
    hasMessages,
    useStreaming,
    fetchCollections,
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
