import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getApiKey, getAuthToken } from '@/api/credentials'

// Message shapes sent by the backend's WebSocketRoutes (`/ws`)
export interface WebSocketMessage {
  type: 'stats_update' | 'document_added' | 'document_deleted' | 'ingestion_progress' | 'error'
  payload: unknown
  timestamp: string
}

export interface StatsUpdate {
  documentCount: number
  chunkCount: number
  collectionCount: number
}

export interface IngestionProgress {
  source: string
  status: 'running' | 'completed' | 'failed'
  progress: number
  message?: string | null
}

/** Reconnect delay for the given (1-based) attempt: exponential with jitter, capped. */
export function reconnectDelay(attempt: number, baseMs = 1000, maxMs = 30000): number {
  const exp = Math.min(maxMs, baseMs * Math.pow(2, Math.max(0, attempt - 1)))
  // +/-20% jitter so many tabs don't reconnect in lockstep after a restart
  const jitter = exp * 0.2 * (Math.random() * 2 - 1)
  return Math.max(0, Math.min(maxMs, Math.round(exp + jitter)))
}

export const MAX_RECONNECT_ATTEMPTS = 10

/**
 * `/ws` URL. Browsers can't set headers on a WebSocket handshake, so a stored
 * JWT or API key is passed as `?access_token=`; SSO sessions use the cookie.
 */
export function buildWebSocketUrl(protocol: string, host: string): string {
  const base = `${protocol}//${host}/ws`
  const credential = getAuthToken() ?? getApiKey()
  return credential ? `${base}?access_token=${encodeURIComponent(credential)}` : base
}

export const useWebSocketStore = defineStore('websocket', () => {
  const connected = ref(false)
  const error = ref<string | null>(null)
  const latestStats = ref<StatsUpdate | null>(null)
  const ingestionProgress = ref<Map<string, IngestionProgress>>(new Map())
  const reconnectAttempts = ref(0)
  /** True once automatic retries are exhausted; cleared by `reconnect()` or a resume event. */
  const gaveUp = ref(false)

  let ws: WebSocket | null = null
  let reconnectTimeout: ReturnType<typeof setTimeout> | null = null
  // Set by disconnect() so onclose doesn't schedule a reconnect
  let manuallyClosed = false
  let resumeListenersInstalled = false

  const isConnected = computed(() => connected.value)

  function clearReconnectTimer() {
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout)
      reconnectTimeout = null
    }
  }

  function connect() {
    manuallyClosed = false
    installResumeListeners()
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return
    }
    clearReconnectTimer()

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const wsUrl = buildWebSocketUrl(protocol, window.location.host)

    try {
      const socket = new WebSocket(wsUrl)
      ws = socket

      socket.onopen = () => {
        connected.value = true
        error.value = null
        reconnectAttempts.value = 0
        gaveUp.value = false
      }

      socket.onmessage = (event) => {
        try {
          handleMessage(JSON.parse(event.data) as WebSocketMessage)
        } catch (e) {
          console.error('[WebSocket] Parse error:', e)
        }
      }

      socket.onclose = () => {
        // Ignore close events from a socket we've already replaced
        if (ws !== socket) return
        connected.value = false
        ws = null
        if (!manuallyClosed) scheduleReconnect()
      }

      socket.onerror = () => {
        error.value = 'Connection error'
      }
    } catch {
      error.value = 'Failed to connect'
      scheduleReconnect()
    }
  }

  function disconnect() {
    manuallyClosed = true
    clearReconnectTimer()
    if (ws) {
      const socket = ws
      ws = null
      socket.close()
    }
    connected.value = false
  }

  /** Manually retry now, resetting the backoff (e.g. from a "Reconnect" button). */
  function reconnect() {
    reconnectAttempts.value = 0
    gaveUp.value = false
    connect()
  }

  function scheduleReconnect() {
    if (manuallyClosed || reconnectTimeout) return
    if (reconnectAttempts.value >= MAX_RECONNECT_ATTEMPTS) {
      gaveUp.value = true
      error.value = 'Live updates disconnected. Will retry when the page regains focus or the network returns.'
      return
    }
    reconnectAttempts.value++
    reconnectTimeout = setTimeout(() => {
      reconnectTimeout = null
      connect()
    }, reconnectDelay(reconnectAttempts.value))
  }

  // After giving up, resume when the user comes back or the network returns
  function installResumeListeners() {
    if (resumeListenersInstalled || typeof window === 'undefined') return
    resumeListenersInstalled = true
    const resume = () => {
      if (!manuallyClosed && !connected.value && gaveUp.value) reconnect()
    }
    window.addEventListener('online', resume)
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') resume()
    })
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
    reconnectAttempts,
    gaveUp,
    connect,
    disconnect,
    reconnect,
    clearIngestionProgress,
  }
})
