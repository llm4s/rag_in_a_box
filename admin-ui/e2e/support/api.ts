import type { APIRequestContext } from '@playwright/test'
import { expect } from '@playwright/test'

/**
 * Helpers for seeding the backend through its REST API (proxied by the UI server, so
 * absolute /api paths work against the configured baseURL's origin). The database is
 * shared by all tests and kept between local runs, so every test works with uniquely
 * named data and never assumes an empty system.
 */

/** The fixed answer of the stub LLM (e2e/support/stub-llm.mjs). */
export const STUB_ANSWER = /E2E stub answer: RAG in a Box found the answer in your documents\./

/** A name no other test (or earlier run) uses. */
export function uniqueName(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

export interface SeededDocument {
  documentId: string
  filename: string
  content: string
}

/** Create a text document; resolves once it is indexed. */
export async function createDocument(
  request: APIRequestContext,
  opts: { filename?: string; content?: string; collection?: string } = {}
): Promise<SeededDocument> {
  const filename = opts.filename ?? `${uniqueName('seed')}.txt`
  const content =
    opts.content ?? `Seeded document ${filename}. The quick brown fox jumps over the lazy dog near the riverbank.`
  const response = await request.post('/api/v1/documents', {
    data: { content, filename, ...(opts.collection ? { collection: opts.collection } : {}) }
  })
  expect(response.ok(), `create document: ${response.status()} ${await response.text()}`).toBeTruthy()
  const body = (await response.json()) as { documentId: string }
  return { documentId: body.documentId, filename, content }
}

/** Run a single-shot query (recorded in query analytics). */
export async function runQuery(request: APIRequestContext, question: string): Promise<void> {
  const response = await request.post('/api/v1/query', { data: { question } })
  expect(response.ok(), `query: ${response.status()} ${await response.text()}`).toBeTruthy()
}
