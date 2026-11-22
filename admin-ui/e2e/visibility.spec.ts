import { test, expect } from '@playwright/test'
import { createDocument } from './support/api'

test.describe('Visibility', () => {
  test('shows stats, collections and the chunk browser', async ({ page, request }) => {
    await createDocument(request)
    await page.goto('visibility')
    await expect(page.getByRole('heading', { name: 'System Visibility' })).toBeVisible()

    for (const label of ['Documents', 'Chunks', 'Collections', 'Avg Chunks/Doc']) {
      await expect(page.locator('.text-caption').getByText(label, { exact: true })).toBeVisible()
    }

    await expect(page.getByRole('heading', { name: 'Collections' })).toBeVisible()
    await expect(page.getByText(/\d+ documents, \d+ chunks/).first()).toBeVisible()

    await expect(page.getByRole('heading', { name: 'Chunk Browser' })).toBeVisible()
    await expect(page.getByText('No chunks found')).toHaveCount(0)
    await page.locator('.v-expansion-panel-title').first().click()
    await expect(page.getByText(/Document: [0-9a-f]{8}\.\.\./).first()).toBeVisible()
  })
})
