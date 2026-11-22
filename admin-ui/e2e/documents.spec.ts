import { test, expect } from '@playwright/test'
import { uniqueName } from './support/api'

test.describe('Documents', () => {
  test('lists documents with filters and an upload button', async ({ page }) => {
    await page.goto('documents')
    await expect(page.getByRole('heading', { name: 'Documents', exact: true })).toBeVisible()
    await expect(page.getByRole('textbox', { name: 'Search' })).toBeVisible()
    await expect(page.getByRole('textbox', { name: 'Collection' })).toBeVisible()
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page).toHaveURL(/\/documents\/upload$/)
  })

  test('paste text, find it in the list, open the detail, delete it', async ({ page }) => {
    const filename = `${uniqueName('e2e-doc')}.md`
    const content = `# Lighthouse keeping\n\nThe ${filename} lighthouse keeper polishes the lens every evening before sunset.`

    // Upload pasted text
    await page.goto('documents/upload')
    await expect(page.getByRole('heading', { name: 'Upload Document' })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'Text Content' })).toHaveAttribute('aria-selected', 'true')
    await page.getByLabel('Filename (optional)').fill(filename)
    await page.getByLabel('Content', { exact: true }).fill(content)
    await page.getByRole('button', { name: 'Create Document' }).click()
    await expect(page.getByText('Success! Redirecting...')).toBeVisible()

    // Redirected to the list; find it by name
    await expect(page).toHaveURL(/\/documents$/)
    await page.getByRole('textbox', { name: 'Search' }).fill(filename)
    const row = page.getByRole('row').filter({ hasText: filename })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('default')

    // Detail page shows its metadata and chunks
    await row.getByTitle('View Details').click()
    await expect(page).toHaveURL(/\/documents\/[0-9a-f-]{36}$/)
    await expect(page.getByRole('heading', { name: 'Document Detail' })).toBeVisible()
    await expect(page.getByText(filename, { exact: true })).toBeVisible()
    await expect(page.getByText(/Chunks \([1-9]\d*\)/)).toBeVisible()
    await page.locator('.v-expansion-panel-title').first().click()
    await expect(page.locator('pre').filter({ hasText: 'polishes the lens' })).toBeVisible()

    // Back to the list and delete it
    await page.goto('documents')
    await page.getByRole('textbox', { name: 'Search' }).fill(filename)
    await expect(row).toHaveCount(1)
    await row.getByTitle('Delete').click()
    const dialog = page.getByRole('dialog').filter({ hasText: 'Delete Document' })
    await dialog.getByRole('button', { name: 'Delete' }).click()
    await expect(row).toHaveCount(0)
    await expect(page.getByText('No documents found')).toBeVisible()
  })

  test('rejects an empty document', async ({ page }) => {
    await page.goto('documents/upload')
    await page.getByRole('button', { name: 'Create Document' }).click()
    await expect(page.getByText('Content is required').first()).toBeVisible()
    await expect(page).toHaveURL(/\/documents\/upload$/)
  })
})
