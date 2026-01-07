import { test, expect } from '@playwright/test'

test.describe('Chat', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/chat')
  })

  test('should display chat page with header', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Chat' })).toBeVisible()
  })

  test('should display collection selector', async ({ page }) => {
    await expect(page.getByLabel('Collection')).toBeVisible()
  })

  test('should display message input area', async ({ page }) => {
    await expect(page.getByPlaceholder(/ask a question/i)).toBeVisible()
  })

  test('should have send button', async ({ page }) => {
    const sendButton = page.getByRole('button', { name: /send/i })
    await expect(sendButton).toBeVisible()
  })

  test('should have clear chat button', async ({ page }) => {
    const clearButton = page.getByRole('button', { name: /clear/i })
    await expect(clearButton).toBeVisible()
  })

  test('should accept text input', async ({ page }) => {
    const input = page.getByPlaceholder(/ask a question/i)
    await input.fill('What is RAG?')
    await expect(input).toHaveValue('What is RAG?')
  })
})
