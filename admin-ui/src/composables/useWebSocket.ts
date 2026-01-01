import { ref, onMounted, onUnmounted } from 'vue'

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
  message?: string
}

export function useWebSocket() {
  const connected = ref(false)
  const lastMessage = ref<WebSocketMessage | null>(null)
  const error = ref<string | null>(null)

  let ws: WebSocket | null = null
  let reconnectAttempts = 0
  const maxReconnectAttempts = 5
  const reconnectDelay = 3000

  const messageHandlers = new Map<string, Set<(payload: unknown) => void>>()

  function connect() {
    // Construct WebSocket URL from current location
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const wsUrl = `${protocol}//${window.location.host}/ws`

    try {
      ws = new WebSocket(wsUrl)

      ws.onopen = () => {
        connected.value = true
        error.value = null
        reconnectAttempts = 0
        console.log('[WebSocket] Connected')
      }

      ws.onmessage = (event) => {
        try {
          const message: WebSocketMessage = JSON.parse(event.data)
          lastMessage.value = message

          // Dispatch to registered handlers
          const handlers = messageHandlers.get(message.type)
          if (handlers) {
            handlers.forEach(handler => handler(message.payload))
          }
        } catch (e) {
          console.error('[WebSocket] Failed to parse message:', e)
        }
      }

      ws.onclose = () => {
        connected.value = false
        console.log('[WebSocket] Disconnected')

        // Attempt reconnection
        if (reconnectAttempts < maxReconnectAttempts) {
          reconnectAttempts++
          console.log(`[WebSocket] Reconnecting in ${reconnectDelay}ms (attempt ${reconnectAttempts})`)
          setTimeout(connect, reconnectDelay)
        } else {
          error.value = 'Failed to connect after multiple attempts'
        }
      }

      ws.onerror = (e) => {
        console.error('[WebSocket] Error:', e)
        error.value = 'WebSocket connection error'
      }
    } catch (e) {
      console.error('[WebSocket] Failed to create connection:', e)
      error.value = 'Failed to create WebSocket connection'
    }
  }

  function disconnect() {
    if (ws) {
      ws.close()
      ws = null
    }
  }

  function subscribe<T>(type: WebSocketMessage['type'], handler: (payload: T) => void) {
    if (!messageHandlers.has(type)) {
      messageHandlers.set(type, new Set())
    }
    messageHandlers.get(type)!.add(handler as (payload: unknown) => void)

    // Return unsubscribe function
    return () => {
      const handlers = messageHandlers.get(type)
      if (handlers) {
        handlers.delete(handler as (payload: unknown) => void)
      }
    }
  }

  function send(message: object) {
    if (ws && connected.value) {
      ws.send(JSON.stringify(message))
    }
  }

  onMounted(() => {
    connect()
  })

  onUnmounted(() => {
    disconnect()
  })

  return {
    connected,
    lastMessage,
    error,
    subscribe,
    send,
    connect,
    disconnect,
  }
}
