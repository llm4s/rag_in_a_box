import client from './client'
import type {
  QueryRequest,
  QueryResponse,
  StreamingCallbacks,
  QueryStartEvent,
  QueryContextEvent,
  QueryChunkEvent,
  QueryAnswerEvent,
  QueryUsageEvent,
  QueryCompleteEvent,
  QueryErrorEvent
} from '@/types/api'

export async function executeQuery(request: QueryRequest): Promise<QueryResponse> {
  const response = await client.post<QueryResponse>('/query', request)
  return response.data
}

/**
 * Execute a query with Server-Sent Events (SSE) streaming.
 * Provides real-time updates as contexts and answers are generated.
 *
 * @param request The query request
 * @param callbacks Callbacks for handling streaming events
 * @returns AbortController to cancel the stream if needed
 */
export function executeQueryStream(
  request: QueryRequest,
  callbacks: StreamingCallbacks
): AbortController {
  const controller = new AbortController()

  // Build the full URL
  const baseUrl = client.defaults.baseURL || ''
  const url = `${baseUrl}/query/stream`

  // Use fetch with SSE
  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      // Include any auth headers from axios client
      ...getAuthHeaders()
    },
    body: JSON.stringify(request),
    signal: controller.signal
  })
    .then(async (response) => {
      if (!response.ok) {
        const errorText = await response.text()
        callbacks.onError?.('http_error', `HTTP ${response.status}: ${errorText}`)
        return
      }

      const reader = response.body?.getReader()
      if (!reader) {
        callbacks.onError?.('no_reader', 'Failed to get response reader')
        return
      }

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // Process complete SSE messages
        const lines = buffer.split('\n\n')
        buffer = lines.pop() || '' // Keep incomplete message in buffer

        for (const message of lines) {
          if (!message.trim()) continue
          processSSEMessage(message, callbacks)
        }
      }

      // Process any remaining message
      if (buffer.trim()) {
        processSSEMessage(buffer, callbacks)
      }
    })
    .catch((error) => {
      if (error.name === 'AbortError') {
        // Stream was intentionally cancelled
        return
      }
      callbacks.onError?.('fetch_error', error.message || 'Network error')
    })

  return controller
}

/**
 * Get authentication headers from the current session.
 */
function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}

  // Get JWT token from localStorage if available
  const token = localStorage.getItem('auth_token')
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  // Get API key if available
  const apiKey = localStorage.getItem('api_key')
  if (apiKey) {
    headers['X-API-Key'] = apiKey
  }

  return headers
}

/**
 * Process a single SSE message and invoke the appropriate callback.
 */
function processSSEMessage(message: string, callbacks: StreamingCallbacks): void {
  const lines = message.split('\n')
  let eventType = ''
  let data = ''

  for (const line of lines) {
    if (line.startsWith('event: ')) {
      eventType = line.slice(7).trim()
    } else if (line.startsWith('data: ')) {
      data = line.slice(6)
    }
  }

  if (!eventType || !data) return

  try {
    const parsed = JSON.parse(data)

    switch (eventType) {
      case 'start': {
        const event = parsed as QueryStartEvent
        callbacks.onStart?.(event.queryId)
        break
      }
      case 'context': {
        const event = parsed as QueryContextEvent
        callbacks.onContext?.(event.context, event.index)
        break
      }
      case 'chunk': {
        const event = parsed as QueryChunkEvent
        callbacks.onChunk?.(event.chunk)
        break
      }
      case 'answer': {
        const event = parsed as QueryAnswerEvent
        callbacks.onAnswer?.(event.answer)
        break
      }
      case 'usage': {
        const event = parsed as QueryUsageEvent
        callbacks.onUsage?.(event.usage)
        break
      }
      case 'complete': {
        const event = parsed as QueryCompleteEvent
        callbacks.onComplete?.(event.queryId, event.totalContexts)
        break
      }
      case 'error': {
        const event = parsed as QueryErrorEvent
        callbacks.onError?.(event.error, event.message)
        break
      }
    }
  } catch (e) {
    console.warn('Failed to parse SSE message:', e)
  }
}
