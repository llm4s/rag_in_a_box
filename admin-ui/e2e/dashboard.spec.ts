import { test, expect } from '@playwright/test'
import { createDocument } from './support/api'

test.describe('Dashboard', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('./')
  })

  test('shows the stats cards with live counts', async ({ page, request }) => {
    await createDocument(request)
    await page.reload()
    const documentsCard = page.locator('.v-card').filter({ hasText: 'Documents' }).first()
    await expect(documentsCard).toBeVisible()
    // At least the document seeded above
    await expect(documentsCard.locator('.text-h4')).not.toHaveText('0')
    await expect(page.getByText('Chunks', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('Collections', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('Avg Chunks/Doc')).toBeVisible()
  })

  test('shows the quick actions', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Quick Actions' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Upload Document' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Configure' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Chunking Preview' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Run Ingestion' })).toBeVisible()
  })

  test('Upload Document goes to the upload page', async ({ page }) => {
    await page.getByRole('button', { name: 'Upload Document' }).click()
    await expect(page).toHaveURL(/\/documents\/upload$/)
  })

  test('Configure goes to the runtime configuration', async ({ page }) => {
    await page.getByRole('button', { name: 'Configure' }).click()
    await expect(page).toHaveURL(/\/config\/runtime$/)
  })

  test('shows the analytics and chunk size sections', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Documents & Chunks by Collection' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Chunk Size Distribution' })).toBeVisible()
  })
})
