import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { WebSocketMessage, StatsUpdate, IngestionProgress } from '@/composables/useWebSocket'

export const useWebSocketStore = defineStore('websocket', () => {
  const connected = ref(false)
  const error = ref<string | null>(null)
  const latestStats = ref<StatsUpdate | null>(null)
  const ingestionProgress = ref<Map<string, IngestionProgress>>(new Map())

  let ws: WebSocket | null = null
  let reconnectTimeout: ReturnType<typeof setTimeout> | null = null
  const maxReconnectAttempts = 5
  let reconnectAttempts = 0

  const isConnected = computed(() => connected.value)

  function connect() {
    if (ws?.readyState === WebSocket.OPEN) {
      return
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const wsUrl = `${protocol}//${window.location.host}/ws`

    try {
      ws = new WebSocket(wsUrl)

      ws.onopen = () => {
        connected.value = true
        error.value = null
        reconnectAttempts = 0
      }

      ws.onmessage = (event) => {
        try {
          const message: WebSocketMessage = JSON.parse(event.data)
          handleMessage(message)
        } catch (e) {
          console.error('[WebSocket] Parse error:', e)
        }
      }

      ws.onclose = () => {
        connected.value = false
        scheduleReconnect()
      }

      ws.onerror = () => {
        error.value = 'Connection error'
      }
    } catch {
      error.value = 'Failed to connect'
      scheduleReconnect()
    }
  }

  function disconnect() {
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout)
      reconnectTimeout = null
    }
    if (ws) {
      ws.close()
      ws = null
    }
    connected.value = false
  }

  function scheduleReconnect() {
    if (reconnectAttempts >= maxReconnectAttempts) {
      error.value = 'Max reconnection attempts reached'
      return
    }

    reconnectAttempts++
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000)

    reconnectTimeout = setTimeout(() => {
      connect()
    }, delay)
  }

  function handleMessage(message: WebSocketMessage) {
    switch (message.type) {
      case 'stats_update':
        latestStats.value = message.payload as StatsUpdate
        break

      case 'ingestion_progress': {
        const progress = message.payload as IngestionProgress
        ingestionProgress.value.set(progress.source, progress)
        break
      }

      case 'document_added':
      case 'document_deleted':
        // These could trigger store refreshes
        break

      case 'error':
        error.value = String(message.payload)
        break
    }
  }

  function clearIngestionProgress(source: string) {
    ingestionProgress.value.delete(source)
  }

  return {
    connected,
    isConnected,
    error,
    latestStats,
    ingestionProgress,
    connect,
    disconnect,
    clearIngestionProgress,
  }
})
