import { test, expect } from '@playwright/test'

test.describe('Configuration', () => {
  test.describe('Runtime Config', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/config/runtime')
    })

    test('should display runtime config page', async ({ page }) => {
      await expect(page.getByRole('heading', { name: /runtime|configuration/i })).toBeVisible()
    })

    test('should display embedding settings section', async ({ page }) => {
      await expect(page.getByText(/embedding/i).first()).toBeVisible({ timeout: 10000 })
    })

    test('should display LLM settings section', async ({ page }) => {
      await expect(page.getByText(/llm|model/i).first()).toBeVisible({ timeout: 10000 })
    })

    test('should display RAG settings section', async ({ page }) => {
      await expect(page.getByText(/rag|chunking/i).first()).toBeVisible({ timeout: 10000 })
    })
  })

  test.describe('Collection Config', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/config/collections')
    })

    test('should display collection config page', async ({ page }) => {
      await expect(page.getByRole('heading', { name: /collection/i })).toBeVisible()
    })

    test('should have add collection button', async ({ page }) => {
      await expect(page.getByRole('button', { name: /add|create|new/i })).toBeVisible()
    })
  })
})
