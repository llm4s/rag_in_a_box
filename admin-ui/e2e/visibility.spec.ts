import { test, expect } from '@playwright/test'

test.describe('Visibility', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/visibility')
  })

  test('should display visibility page with header', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'System Visibility' })).toBeVisible()
  })

  test('should display configuration section', async ({ page }) => {
    await expect(page.getByText(/configuration|settings/i).first()).toBeVisible({ timeout: 10000 })
  })

  test('should display chunks section', async ({ page }) => {
    await expect(page.getByText(/chunks/i).first()).toBeVisible({ timeout: 10000 })
  })

  test('should display system statistics', async ({ page }) => {
    await expect(page.getByText(/statistics|stats/i).first()).toBeVisible({ timeout: 10000 })
  })

  test('should have refresh button', async ({ page }) => {
    await expect(page.getByRole('button', { name: /refresh/i })).toBeVisible()
  })
})
