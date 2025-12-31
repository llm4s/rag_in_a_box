import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Document, Chunk } from '@/types/api'
import * as documentsApi from '@/api/documents'
import { type AppError, getErrorMessage, isAppError } from '@/composables/useApiError'

export const useDocumentsStore = defineStore('documents', () => {
  const documents = ref<Document[]>([])
  const currentDocument = ref<Document | null>(null)
  const currentChunks = ref<Chunk[]>([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(20)
  const loading = ref(false)
  const error = ref<AppError | null>(null)

  // Track last fetch params for retry
  let lastFetchParams: { collection?: string; search?: string } | undefined

  const hasDocuments = computed(() => documents.value.length > 0)
  const totalPages = computed(() => Math.ceil(total.value / pageSize.value))

  function clearError() {
    error.value = null
  }

  async function fetchDocuments(params?: { collection?: string; search?: string }) {
    loading.value = true
    error.value = null
    lastFetchParams = params
    try {
      const response = await documentsApi.getDocuments({
        page: page.value,
        pageSize: pageSize.value,
        ...params
      })
      documents.value = response.documents
      total.value = response.total
    } catch (e) {
      error.value = isAppError(e) ? e : {
        code: 'UNKNOWN_ERROR',
        message: getErrorMessage(e),
        retryable: true
      }
    } finally {
      loading.value = false
    }
  }

  async function retryFetchDocuments() {
    return fetchDocuments(lastFetchParams)
  }

  async function fetchDocument(id: string) {
    loading.value = true
    error.value = null
    try {
      currentDocument.value = await documentsApi.getDocument(id)
      currentChunks.value = await documentsApi.getDocumentChunks(id)
    } catch (e) {
      error.value = isAppError(e) ? e : {
        code: 'UNKNOWN_ERROR',
        message: getErrorMessage(e),
        retryable: true
      }
    } finally {
      loading.value = false
    }
  }

  async function createDocument(data: { content: string; filename?: string; collection?: string; metadata?: Record<string, string> }) {
    loading.value = true
    error.value = null
    try {
      const doc = await documentsApi.createDocument(data)
      documents.value.unshift(doc)
      total.value++
      return doc
    } catch (e) {
      error.value = isAppError(e) ? e : {
        code: 'UNKNOWN_ERROR',
        message: getErrorMessage(e),
        retryable: false
      }
      throw e
    } finally {
      loading.value = false
    }
  }

  async function deleteDocument(id: string) {
    loading.value = true
    error.value = null
    try {
      await documentsApi.deleteDocument(id)
      documents.value = documents.value.filter(d => d.id !== id)
      total.value--
      if (currentDocument.value?.id === id) {
        currentDocument.value = null
        currentChunks.value = []
      }
    } catch (e) {
      error.value = isAppError(e) ? e : {
        code: 'UNKNOWN_ERROR',
        message: getErrorMessage(e),
        retryable: false
      }
      throw e
    } finally {
      loading.value = false
    }
  }

  function setPage(newPage: number) {
    page.value = newPage
  }

  function clearCurrent() {
    currentDocument.value = null
    currentChunks.value = []
  }

  return {
    documents,
    currentDocument,
    currentChunks,
    total,
    page,
    pageSize,
    loading,
    error,
    hasDocuments,
    totalPages,
    fetchDocuments,
    retryFetchDocuments,
    fetchDocument,
    createDocument,
    deleteDocument,
    setPage,
    clearCurrent,
    clearError
  }
})
