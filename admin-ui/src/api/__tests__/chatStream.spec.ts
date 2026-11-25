import { describe, it, expect, afterEach, vi } from 'vitest'
import { sendMessageStream } from '../chat'

/** A fetch Response whose body is `events`, split across network reads at `splitAt`. */
function sseResponse(events: string, splitAt: number): Response {
  const bytes = new TextEncoder().encode(events)
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes.slice(0, splitAt))
      controller.enqueue(bytes.slice(splitAt))
      controller.close()
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

const sse = (event: string, data: object) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`

describe('sendMessageStream', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('hands over the answer chunk by chunk as it is generated, then whole', async () => {
    const events =
      sse('start', { queryId: 'q1', userMessageId: 'm1' }) +
      sse('context', { context: { content: 'c', score: 0.9, metadata: {} }, index: 0 }) +
      sse('chunk', { chunk: 'Zebras' }) +
      sse('chunk', { chunk: ' are striped.' }) +
      sse('answer', { answer: 'Zebras are striped.' }) +
      sse('complete', { queryId: 'q1', totalContexts: 1 })
    // Split inside the first chunk event: an event may arrive over several reads
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(events, events.indexOf('Zebras') + 3)))

    const seen: string[] = []
    await new Promise<void>((resolve) => {
      sendMessageStream('s1', { content: 'stripes?' }, {
        onStart: (queryId, userMessageId) => seen.push(`start:${queryId}:${userMessageId}`),
        onContext: (_context, index) => seen.push(`context:${index}`),
        onChunk: (chunk) => seen.push(`chunk:${chunk}`),
        onAnswer: (answer) => seen.push(`answer:${answer}`),
        onComplete: () => resolve(),
      })
    })

    expect(seen).toEqual([
      'start:q1:m1',
      'context:0',
      'chunk:Zebras',
      'chunk: are striped.',
      'answer:Zebras are striped.',
    ])
  })
})
