import { test, expect } from '@playwright/test'
import { createDocument, runQuery } from './support/api'

test.describe('Analytics', () => {
  test('shows query analytics for recorded queries', async ({ page, request }) => {
    const doc = await createDocument(request)
    await runQuery(request, `What does ${doc.filename} say about the fox?`)

    await page.goto('analytics')
    await expect(page.getByRole('heading', { name: 'Query Analytics' })).toBeVisible()

    for (const range of ['Last 24 Hours', 'Last 7 Days', 'Last 30 Days']) {
      await expect(page.getByRole('button', { name: range })).toBeVisible()
    }

    const totalCard = page.locator('.v-card').filter({ hasText: 'Total Queries' }).first()
    await expect(totalCard).toBeVisible()
    await expect(totalCard.locator('.text-h4')).not.toHaveText('0')
    await expect(page.getByText('Avg Latency')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Latency Percentiles' })).toBeVisible()

    await page.getByRole('button', { name: 'Last 7 Days' }).click()
    await expect(totalCard).toBeVisible()
  })
})
