import { test, expect } from '@playwright/test'

test.describe('Documents Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/documents')
  })

  test('should display documents heading', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Documents' })).toBeVisible()
  })

  test('should have upload button', async ({ page }) => {
    await expect(page.getByRole('button', { name: /Upload/i })).toBeVisible()
  })

  test('should navigate to upload page when clicking upload', async ({ page }) => {
    await page.getByRole('button', { name: /Upload/i }).click()
    await expect(page).toHaveURL(/\/documents\/upload/)
  })

  test('should display filter inputs', async ({ page }) => {
    await expect(page.getByLabel('Search')).toBeVisible()
    await expect(page.getByLabel('Collection')).toBeVisible()
  })

  test('should show empty state or document list', async ({ page }) => {
    // Either shows documents or empty state message
    const hasDocuments = await page.getByText('No documents found').isVisible().catch(() => false)
    const hasTable = await page.locator('table').isVisible().catch(() => false)
    expect(hasDocuments || hasTable).toBeTruthy()
  })
})
