import { test, expect } from '@playwright/test'

const SAMPLE = Array.from(
  { length: 12 },
  (_, i) => `Sentence number ${i + 1} explains one more detail about how chunking splits long documents.`
).join(' ')

test.describe('Chunking Preview', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('chunking')
    await expect(page.getByRole('heading', { name: 'Chunking Preview' })).toBeVisible()
  })

  test('shows the input controls', async ({ page }) => {
    await expect(page.getByRole('tab', { name: 'Preview' })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'Compare Strategies' })).toBeVisible()
    await expect(page.getByLabel('Text to chunk')).toBeVisible()
    await expect(page.getByLabel('Strategy')).toBeVisible()
    await expect(page.getByLabel('Chunk Size')).toBeVisible()
    await expect(page.getByLabel('Overlap')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Preview Chunks' })).toBeDisabled()
  })

  test('previews how a text is chunked', async ({ page }) => {
    await page.getByLabel('Text to chunk').fill(SAMPLE)
    await page.getByLabel('Chunk Size').fill('200')
    await page.getByLabel('Overlap').fill('20')
    await page.getByRole('button', { name: 'Preview Chunks' }).click()
    // ~1100 characters in chunks of ~200: several chunks
    await expect(page.getByText(/\((?:[3-9]|\d{2,}) chunks, avg \d+ chars\)/)).toBeVisible()
  })

  test('compares chunking strategies', async ({ page }) => {
    await page.getByRole('tab', { name: 'Compare Strategies' }).click()
    await page.getByLabel('Text to chunk').fill(SAMPLE)
    const compare = page.getByRole('button', { name: 'Compare Strategies' })
    await expect(compare).toBeVisible()
    // Pick two strategies if none are preselected
    const chips = page.locator('.v-chip-group .v-chip')
    await expect(chips.first()).toBeVisible()
    if (await compare.isDisabled()) {
      await chips.nth(0).click()
      await chips.nth(1).click()
    }
    await compare.click()
    await expect(page.getByText('Comparison Results')).toBeVisible()
  })
})
