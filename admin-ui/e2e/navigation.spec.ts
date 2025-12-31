import { test, expect } from '@playwright/test'

test.describe('Navigation', () => {
  test('should load the dashboard page', async ({ page }) => {
    await page.goto('/')
    await expect(page).toHaveTitle(/RAG Box Admin/)
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
  })

  test('should navigate to documents page', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('link', { name: /Documents/i }).click()
    await expect(page.getByRole('heading', { name: 'Documents' })).toBeVisible()
  })

  test('should navigate to visibility page', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('link', { name: /Visibility/i }).click()
    await expect(page.getByRole('heading', { name: 'System Visibility' })).toBeVisible()
  })

  test('should navigate to chunking preview page', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('link', { name: /Chunking/i }).click()
    await expect(page.getByRole('heading', { name: 'Chunking Preview' })).toBeVisible()
  })

  test('should navigate to ingestion page', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('link', { name: /Ingestion/i }).click()
    await expect(page.getByRole('heading', { name: 'Ingestion' })).toBeVisible()
  })
})
