import { test, expect } from '@playwright/test'

test.describe('Analytics', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/analytics')
  })

  test('should display analytics page with header', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Analytics' })).toBeVisible()
  })

  test('should display time range selector', async ({ page }) => {
    // Look for time range filter options
    await expect(page.getByText(/24 hours|7 days|30 days/i).first()).toBeVisible({ timeout: 10000 })
  })

  test('should display query stats section', async ({ page }) => {
    await expect(page.getByText(/queries|total/i).first()).toBeVisible({ timeout: 10000 })
  })

  test('should display latency metrics', async ({ page }) => {
    await expect(page.getByText(/latency|response time/i).first()).toBeVisible({ timeout: 10000 })
  })
})
