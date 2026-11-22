import { test, expect } from '@playwright/test'
import { STUB_ANSWER, createDocument, uniqueName } from './support/api'

test.describe('Chat', () => {
  test('shows an empty conversation ready for a question', async ({ page }) => {
    await page.goto('chat')
    await expect(page.getByRole('heading', { name: 'New Chat' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Ask a Question' })).toBeVisible()
    await expect(page.getByLabel('Collection')).toBeVisible()
    const input = page.getByPlaceholder('Type your question...')
    await expect(input).toBeVisible()
    // Example chips fill the input
    await page.getByText('Summarize the key points').click()
    await expect(input).toHaveValue('Summarize the key points')
  })

  test('answers a question with citations from the indexed documents', async ({ page, request }) => {
    const topic = uniqueName('zephyrium')
    const doc = await createDocument(request, {
      filename: `${topic}.txt`,
      content: `${topic} is a rare blue mineral mined only in the northern ${topic} caves of Atlantis.`
    })

    await page.goto('chat')
    const input = page.getByPlaceholder('Type your question...')
    await input.fill(`Where is ${topic} mined?`)
    await input.press('Enter')

    // The question, then the stub LLM's deterministic answer
    await expect(page.getByText(`Where is ${topic} mined?`).first()).toBeVisible()
    await expect(page.getByText(STUB_ANSWER)).toBeVisible({ timeout: 20_000 })

    // Citations come from real retrieval over the seeded document
    const sources = page.getByRole('button', { name: /Show Sources \(\d+\)/ })
    await expect(sources).toBeVisible()
    await sources.click()
    await expect(page.getByText(doc.filename).first()).toBeVisible()

    // The conversation is saved as a session in the sidebar
    await expect(page.getByText('No conversations yet')).toHaveCount(0)
  })

  test('streams /query/stream as raw server-sent events', async ({ request }) => {
    const response = await request.post('/api/v1/query/stream', { data: { question: 'What does the fox do?' } })
    expect(response.ok()).toBeTruthy()
    expect(response.headers()['content-type']).toContain('text/event-stream')
    const body = await response.text()
    expect(body.startsWith('event: start\ndata: {')).toBeTruthy()
    expect(body).toMatch(/event: answer\ndata: \{"answer":"E2E stub answer/)
    expect(body).toContain('event: complete\n')
  })
})
