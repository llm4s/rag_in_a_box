import { test, expect } from '@playwright/test'
import { createDocument } from './support/api'

test.describe('Configuration', () => {
  test('overview page', async ({ page }) => {
    await page.goto('config')
    await expect(page.getByRole('heading', { name: 'Configuration', exact: true })).toBeVisible()
  })

  test.describe('Runtime configuration', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('config/runtime')
      await expect(page.getByRole('heading', { name: 'Runtime Configuration' })).toBeVisible()
    })

    test('shows the settings and the active pipeline', async ({ page }) => {
      await expect(page.getByText('chunkingStrategy', { exact: true })).toBeVisible()
      await expect(page.getByText('topK', { exact: true })).toBeVisible()
      await expect(page.getByTestId('pipeline-limitations')).toContainText('Search mode')
    })

    test('can enter and cancel edit mode', async ({ page }) => {
      await page.getByRole('button', { name: 'Edit' }).click()
      await expect(page.getByRole('button', { name: 'Save' })).toBeVisible()
      await page.getByRole('button', { name: 'Cancel' }).click()
      await expect(page.getByRole('button', { name: 'Edit' })).toBeVisible()
    })
  })

  test.describe('Collection configuration', () => {
    test('lists collections and opens the add dialog', async ({ page, request }) => {
      // The default collection exists once anything is ingested into it
      await createDocument(request)

      await page.goto('config/collections')
      await expect(page.getByRole('heading', { name: 'Collection Configuration' })).toBeVisible()
      await expect(page.locator('.v-list-item-title').filter({ hasText: /^\s*default\b/ }).first()).toBeVisible()

      await page.getByRole('button', { name: 'Add Collection' }).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog.getByLabel('Collection Name')).toBeVisible()
      await expect(dialog.getByLabel('Chunking Strategy')).toBeVisible()
      await dialog.getByRole('button', { name: 'Cancel' }).click()
      await expect(dialog).toBeHidden()
    })
  })
})
