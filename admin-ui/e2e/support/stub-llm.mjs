#!/usr/bin/env node
/**
 * Deterministic stand-in for an OpenAI-compatible API, used by the e2e suite so the
 * backend runs against a real database without calling (or paying for) a real provider.
 *
 * The backend is pointed at it with LLM_BASE_URL / EMBEDDING_BASE_URL (see
 * playwright.config.ts). It implements:
 *
 *   POST /v1/embeddings        hashed bag-of-words vectors: texts sharing words are similar
 *   POST /v1/chat/completions  a fixed answer (STUB_ANSWER), plain or streamed (SSE)
 *   GET  /v1/models            a minimal model list
 *   GET  /health               liveness for Playwright's webServer check
 *
 * Paths are also accepted without the /v1 prefix. Listens on STUB_LLM_PORT (default 18081).
 */
/* global process, Buffer, console */
/* eslint-disable no-console */
import { createServer } from 'node:http'

const STUB_ANSWER = 'E2E stub answer: RAG in a Box found the answer in your documents.'
const DEFAULT_DIMENSIONS = 1536
const port = Number(process.env.STUB_LLM_PORT ?? 18081)
const verbose = process.env.STUB_LLM_VERBOSE === 'true'

/** FNV-1a, so the vector for a word is the same on every run and platform. */
function hash(word) {
  let h = 0x811c9dc5
  for (let i = 0; i < word.length; i++) {
    h ^= word.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

function embed(text, dimensions) {
  const vector = new Array(dimensions).fill(0)
  const words = String(text).toLowerCase().match(/[a-z0-9]+/g) ?? []
  for (const word of words) {
    const h = hash(word)
    vector[h % dimensions] += (h & 0x10000) ? 1 : -1
  }
  // A constant component keeps empty / stop-word-only texts from being the zero vector
  vector[0] += 0.01
  const norm = Math.sqrt(vector.reduce((sum, v) => sum + v * v, 0))
  return vector.map((v) => v / norm)
}

function toBase64(vector) {
  const buffer = Buffer.alloc(vector.length * 4)
  vector.forEach((v, i) => buffer.writeFloatLE(v, i * 4))
  return buffer.toString('base64')
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8')
      try {
        resolve(raw ? JSON.parse(raw) : {})
      } catch (e) {
        reject(e)
      }
    })
    req.on('error', reject)
  })
}

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

function embeddings(body, res) {
  const inputs = Array.isArray(body.input) ? body.input : [body.input ?? '']
  const dimensions = Number(body.dimensions) || DEFAULT_DIMENSIONS
  const base64 = body.encoding_format === 'base64'
  send(res, 200, {
    object: 'list',
    model: body.model ?? 'stub-embedding',
    data: inputs.map((input, index) => {
      // Token arrays are possible in the OpenAI API; embed them as their joined ids
      const text = Array.isArray(input) ? input.join(' ') : input
      const vector = embed(text, dimensions)
      return { object: 'embedding', index, embedding: base64 ? toBase64(vector) : vector }
    }),
    usage: { prompt_tokens: inputs.length, total_tokens: inputs.length }
  })
}

function chatCompletions(body, res) {
  const id = 'chatcmpl-e2e'
  const created = Math.floor(Date.now() / 1000)
  const model = body.model ?? 'stub-llm'
  const usage = { prompt_tokens: 10, completion_tokens: 12, total_tokens: 22 }

  if (!body.stream) {
    send(res, 200, {
      id,
      object: 'chat.completion',
      created,
      model,
      choices: [{ index: 0, message: { role: 'assistant', content: STUB_ANSWER }, finish_reason: 'stop' }],
      usage
    })
    return
  }

  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
  const chunk = (delta, finish = null, extra = {}) =>
    res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', created, model, choices: [{ index: 0, delta, finish_reason: finish }], ...extra })}\n\n`)
  chunk({ role: 'assistant', content: '' })
  for (const word of STUB_ANSWER.split(/(?<= )/)) chunk({ content: word })
  chunk({}, 'stop', { usage })
  res.write('data: [DONE]\n\n')
  res.end()
}

const server = createServer(async (req, res) => {
  const path = (req.url ?? '/').split('?')[0].replace(/^\/v1(?=\/)/, '')
  try {
    if (req.method === 'GET' && path === '/health') return send(res, 200, { status: 'ok' })
    if (req.method === 'GET' && path === '/models') {
      return send(res, 200, { object: 'list', data: [{ id: 'gpt-4o', object: 'model' }, { id: 'text-embedding-3-small', object: 'model' }] })
    }
    if (req.method === 'POST' && (path === '/embeddings' || path === '/chat/completions')) {
      const body = await readJson(req)
      if (verbose) console.log(`[stub-llm] ${req.method} ${req.url}`)
      return path === '/embeddings' ? embeddings(body, res) : chatCompletions(body, res)
    }
    console.warn(`[stub-llm] unhandled ${req.method} ${req.url}`)
    send(res, 404, { error: { message: `stub-llm: no route for ${req.method} ${path}` } })
  } catch (e) {
    console.error('[stub-llm] error', e)
    send(res, 400, { error: { message: String(e) } })
  }
})

server.listen(port, '127.0.0.1', () => console.log(`[stub-llm] listening on http://127.0.0.1:${port}`))
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)))
