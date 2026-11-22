import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useWebSocketStore, reconnectDelay, buildWebSocketUrl, MAX_RECONNECT_ATTEMPTS } from '../websocket'
import { setApiKey, setAuthToken } from '@/api/credentials'

class FakeWebSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  static instances: FakeWebSocket[] = []

  readyState = FakeWebSocket.CONNECTING
  onopen: (() => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((e: { data: string }) => void) | null = null

  constructor(public url: string) {
    FakeWebSocket.instances.push(this)
  }

  open() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.()
  }

  serverClose() {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.()
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.()
  }
}

describe('reconnectDelay', () => {
  it('grows exponentially and is capped', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5) // zero jitter
    expect(reconnectDelay(1)).toBe(1000)
    expect(reconnectDelay(2)).toBe(2000)
    expect(reconnectDelay(5)).toBe(16000)
    expect(reconnectDelay(10)).toBe(30000)
    vi.restoreAllMocks()
  })
})

describe('websocket store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('connects to /ws and tracks connection state', () => {
    const store = useWebSocketStore()
    store.connect()
    expect(FakeWebSocket.instances).toHaveLength(1)
    expect(FakeWebSocket.instances[0].url).toMatch(/\/ws$/)
    FakeWebSocket.instances[0].open()
    expect(store.isConnected).toBe(true)
  })

  it('passes a stored JWT or API key as access_token (browsers cannot set ws headers)', () => {
    localStorage.clear()
    setApiKey('key/with+chars')
    expect(buildWebSocketUrl('ws:', 'host')).toBe('ws://host/ws?access_token=key%2Fwith%2Bchars')
    setAuthToken('jwt.token')
    expect(buildWebSocketUrl('wss:', 'host')).toBe('wss://host/ws?access_token=jwt.token')
    localStorage.clear()
    expect(buildWebSocketUrl('ws:', 'host')).toBe('ws://host/ws')
  })

  it('reconnects with backoff after an unexpected close', () => {
    const store = useWebSocketStore()
    store.connect()
    FakeWebSocket.instances[0].open()
    FakeWebSocket.instances[0].serverClose()
    expect(store.isConnected).toBe(false)
    expect(store.reconnectAttempts).toBe(1)

    vi.advanceTimersByTime(30000)
    expect(FakeWebSocket.instances).toHaveLength(2)
    FakeWebSocket.instances[1].open()
    expect(store.reconnectAttempts).toBe(0)
  })

  it('does not reconnect after a manual disconnect', () => {
    const store = useWebSocketStore()
    store.connect()
    FakeWebSocket.instances[0].open()
    store.disconnect()
    vi.advanceTimersByTime(120000)
    expect(FakeWebSocket.instances).toHaveLength(1)
  })

  it('gives up after max attempts and resumes via reconnect()', () => {
    const store = useWebSocketStore()
    store.connect()
    for (let i = 0; i < MAX_RECONNECT_ATTEMPTS + 1; i++) {
      FakeWebSocket.instances[FakeWebSocket.instances.length - 1].serverClose()
      vi.advanceTimersByTime(60000)
    }
    expect(store.gaveUp).toBe(true)
    const countWhenGaveUp = FakeWebSocket.instances.length
    vi.advanceTimersByTime(600000)
    expect(FakeWebSocket.instances).toHaveLength(countWhenGaveUp)

    store.reconnect()
    expect(FakeWebSocket.instances).toHaveLength(countWhenGaveUp + 1)
    FakeWebSocket.instances[countWhenGaveUp].open()
    expect(store.gaveUp).toBe(false)
    expect(store.isConnected).toBe(true)
  })

  it('stores ingestion progress messages', () => {
    const store = useWebSocketStore()
    store.connect()
    const socket = FakeWebSocket.instances[0]
    socket.open()
    socket.onmessage?.({
      data: JSON.stringify({
        type: 'ingestion_progress',
        payload: { source: 'docs', status: 'running', progress: 0.5, message: null },
        timestamp: '2024-01-01T00:00:00Z'
      })
    })
    expect(store.ingestionProgress.get('docs')?.progress).toBe(0.5)
  })
})
