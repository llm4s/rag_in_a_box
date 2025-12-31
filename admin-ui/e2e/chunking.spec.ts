import { test, expect } from '@playwright/test'

test.describe('Chunking Preview Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/chunking')
  })

  test('should display chunking preview heading', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Chunking Preview' })).toBeVisible()
  })

  test('should display mode tabs', async ({ page }) => {
    await expect(page.getByRole('tab', { name: /Preview/i })).toBeVisible()
    await expect(page.getByRole('tab', { name: /Compare/i })).toBeVisible()
  })

  test('should display text input area', async ({ page }) => {
    await expect(page.getByLabel(/Enter text/i)).toBeVisible()
  })

  test('should display strategy selector', async ({ page }) => {
    await expect(page.getByLabel(/Strategy/i)).toBeVisible()
  })

  test('should display chunk size and overlap inputs', async ({ page }) => {
    await expect(page.getByLabel(/Chunk Size/i)).toBeVisible()
    await expect(page.getByLabel(/Overlap/i)).toBeVisible()
  })

  test('should have preview button', async ({ page }) => {
    await expect(page.getByRole('button', { name: /Preview/i })).toBeVisible()
  })

  test('should switch to compare mode', async ({ page }) => {
    await page.getByRole('tab', { name: /Compare/i }).click()
    await expect(page.getByRole('button', { name: /Compare Strategies/i })).toBeVisible()
  })
})
