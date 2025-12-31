import { test, expect } from '@playwright/test'

test.describe('Dashboard', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
  })

  test('should display stats cards', async ({ page }) => {
    // Wait for skeleton to disappear or content to appear
    await expect(page.getByText('Documents')).toBeVisible({ timeout: 10000 })
    await expect(page.getByText('Chunks')).toBeVisible()
    await expect(page.getByText('Collections')).toBeVisible()
    await expect(page.getByText('Avg Chunks/Doc')).toBeVisible()
  })

  test('should display quick actions', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Quick Actions' })).toBeVisible()
    await expect(page.getByRole('button', { name: /Upload Document/i })).toBeVisible()
    await expect(page.getByRole('button', { name: /Configure/i })).toBeVisible()
    await expect(page.getByRole('button', { name: /Chunking Preview/i })).toBeVisible()
    await expect(page.getByRole('button', { name: /Run Ingestion/i })).toBeVisible()
  })

  test('should navigate to upload when clicking upload button', async ({ page }) => {
    await page.getByRole('button', { name: /Upload Document/i }).click()
    await expect(page).toHaveURL(/\/documents\/upload/)
  })

  test('should navigate to config when clicking configure button', async ({ page }) => {
    await page.getByRole('button', { name: /Configure/i }).click()
    await expect(page).toHaveURL(/\/config\/runtime/)
  })

  test('should display chunk size distribution section', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Chunk Size Distribution' })).toBeVisible()
  })
})
