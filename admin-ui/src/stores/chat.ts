import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ChatMessage, QueryResponse, CollectionStats } from '@/types/api'
import * as queryApi from '@/api/query'
import * as visibilityApi from '@/api/visibility'
import * as analyticsApi from '@/api/analytics'

function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const selectedCollection = ref<string>('*')
  const collections = ref<CollectionStats[]>([])
  const collectionsLoading = ref(false)

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

    loading.value = true
    error.value = null

    try {
      const response: QueryResponse = await queryApi.executeQuery({
        question: question.trim(),
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

  async function rateMessage(messageId: string, rating: number): Promise<boolean> {
    const message = messages.value.find(m => m.id === messageId)
    if (!message || message.role !== 'assistant') return false

    try {
      // Find the corresponding user message to get the query text
      const messageIndex = messages.value.findIndex(m => m.id === messageId)
      if (messageIndex <= 0) return false

      // Submit feedback (the backend will track this)
      await analyticsApi.submitFeedback({
        queryId: messageId, // Use message ID as a proxy
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
    messages.value = []
    error.value = null
  }

  function setCollection(collection: string) {
    selectedCollection.value = collection
  }

  return {
    messages,
    loading,
    error,
    selectedCollection,
    collections,
    collectionsLoading,
    hasMessages,
    fetchCollections,
    sendMessage,
    rateMessage,
    clearMessages,
    setCollection
  }
})
