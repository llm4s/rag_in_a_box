import client from './client'
import type {
  ChatSession,
  ChatSessionListResponse,
  ChatSessionDetailResponse,
  ChatMessageRecord,
  CreateChatSessionRequest,
  CreateChatSessionResponse,
  UpdateChatSessionRequest,
  ChatOperationResponse,
  SendChatMessageRequest,
  RateChatMessageRequest,
  ChatStreamingCallbacks,
  ChatStartEvent,
  QueryContextEvent,
  QueryAnswerEvent,
  QueryUsageEvent,
  ChatCompleteEvent,
  QueryErrorEvent
} from '@/types/api'

/**
 * Create a new chat session.
 */
export async function createSession(request: CreateChatSessionRequest = {}): Promise<CreateChatSessionResponse> {
  const response = await client.post<CreateChatSessionResponse>('/chat/sessions', request)
  return response.data
}

/**
 * List all chat sessions.
 */
export async function listSessions(includeArchived = false): Promise<ChatSessionListResponse> {
  const params = includeArchived ? '?includeArchived=true' : ''
  const response = await client.get<ChatSessionListResponse>(`/chat/sessions${params}`)
  return response.data
}

/**
 * Get a session with all its messages.
 */
export async function getSession(id: string): Promise<ChatSessionDetailResponse> {
  const response = await client.get<ChatSessionDetailResponse>(`/chat/sessions/${id}`)
  return response.data
}

/**
 * Update a chat session (title, collection pattern, archive status).
 */
export async function updateSession(id: string, request: UpdateChatSessionRequest): Promise<ChatSession> {
  const response = await client.put<ChatSession>(`/chat/sessions/${id}`, request)
  return response.data
}

/**
 * Delete a chat session and all its messages.
 */
export async function deleteSession(id: string): Promise<ChatOperationResponse> {
  const response = await client.delete<ChatOperationResponse>(`/chat/sessions/${id}`)
  return response.data
}

/**
 * Send a message in a chat session (non-streaming).
 */
export async function sendMessage(sessionId: string, request: SendChatMessageRequest): Promise<{
  userMessage: ChatMessageRecord
  assistantMessage: ChatMessageRecord
  error?: string
}> {
  const response = await client.post<{
    userMessage: ChatMessageRecord
    assistantMessage: ChatMessageRecord
    error?: string
  }>(`/chat/sessions/${sessionId}/messages`, request)
  return response.data
}

/**
 * Send a message with SSE streaming response.
 */
export function sendMessageStream(
  sessionId: string,
  request: SendChatMessageRequest,
  callbacks: ChatStreamingCallbacks
): AbortController {
  const controller = new AbortController()

  const baseUrl = client.defaults.baseURL || ''
  const url = `${baseUrl}/chat/sessions/${sessionId}/messages/stream`

  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
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
        buffer = lines.pop() || ''

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
        return
      }
      callbacks.onError?.('fetch_error', error.message || 'Network error')
    })

  return controller
}

/**
 * Rate a chat message.
 */
export async function rateMessage(messageId: string, request: RateChatMessageRequest): Promise<ChatOperationResponse> {
  const response = await client.put<ChatOperationResponse>(`/chat/messages/${messageId}/rate`, request)
  return response.data
}

/**
 * Get authentication headers from the current session.
 */
function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}

  const token = localStorage.getItem('auth_token')
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  const apiKey = localStorage.getItem('api_key')
  if (apiKey) {
    headers['X-API-Key'] = apiKey
  }

  return headers
}

/**
 * Process a single SSE message for chat streaming.
 */
function processSSEMessage(message: string, callbacks: ChatStreamingCallbacks): void {
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
        const event = parsed as ChatStartEvent
        callbacks.onStart?.(event.queryId, event.userMessageId)
        break
      }
      case 'context': {
        const event = parsed as QueryContextEvent
        callbacks.onContext?.(event.context, event.index)
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
        const event = parsed as ChatCompleteEvent
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
