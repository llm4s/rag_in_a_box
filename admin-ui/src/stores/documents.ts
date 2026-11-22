import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Document, Chunk } from '@/types/api'
import * as documentsApi from '@/api/documents'
import { type AppError, toAppError } from '@/composables/useApiError'

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
      error.value = toAppError(e, true)
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
      const [doc, chunks] = await Promise.all([
        documentsApi.getDocument(id),
        // Chunks are a secondary view; don't fail the whole page if they can't load
        documentsApi.getDocumentChunks(id).catch((e: unknown) => {
          console.error('Failed to load chunks for', id, e)
          return []
        })
      ])
      currentDocument.value = doc
      currentChunks.value = chunks
    } catch (e) {
      currentDocument.value = null
      currentChunks.value = []
      error.value = toAppError(e)
    } finally {
      loading.value = false
    }
  }

  async function createDocument(data: { content: string; filename?: string; collection?: string; metadata?: Record<string, string> }) {
    loading.value = true
    error.value = null
    try {
      // The upload response only carries the id and chunk count; the list is
      // refreshed from the server the next time the Documents page loads.
      return await documentsApi.createDocument(data)
    } catch (e) {
      error.value = toAppError(e, false)
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
      error.value = toAppError(e, false)
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
