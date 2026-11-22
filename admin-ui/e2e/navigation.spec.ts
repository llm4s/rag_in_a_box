import { test, expect } from '@playwright/test'

test.describe('Navigation', () => {
  test('opens the dashboard without a login in open auth mode', async ({ page }) => {
    await page.goto('./')
    await expect(page).toHaveURL(/\/admin\/$/)
    await expect(page).toHaveTitle('Dashboard - RAG in a Box')
    await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible()
  })

  test('sends the login page straight on when no login is required', async ({ page }) => {
    await page.goto('login?redirect=/documents')
    await expect(page).toHaveURL(/\/admin\/documents$/)
    await expect(page.getByRole('heading', { name: 'Documents', exact: true })).toBeVisible()
  })

  const pages = [
    { link: 'Documents', url: /\/documents$/, heading: 'Documents' },
    { link: 'Upload', url: /\/documents\/upload$/, heading: 'Upload Document' },
    { link: 'Analytics', url: /\/analytics$/, heading: 'Query Analytics' },
    { link: 'Configuration', url: /\/config$/, heading: 'Configuration' },
    { link: 'Runtime Config', url: /\/config\/runtime$/, heading: 'Runtime Configuration' },
    { link: 'Collections', url: /\/config\/collections$/, heading: 'Collection Configuration' },
    { link: 'Chunking Preview', url: /\/chunking$/, heading: 'Chunking Preview' },
    { link: 'Visibility', url: /\/visibility$/, heading: 'System Visibility' },
    { link: 'Ingestion', url: /\/ingestion$/, heading: 'Ingestion Dashboard' }
  ]

  for (const { link, url, heading } of pages) {
    test(`navigates to ${link} from the sidebar`, async ({ page }) => {
      await page.goto('./')
      await page.getByRole('link', { name: link, exact: true }).click()
      await expect(page).toHaveURL(url)
      await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    })
  }

  test('navigates to Chat from the sidebar', async ({ page }) => {
    await page.goto('./')
    await page.getByRole('link', { name: 'Chat', exact: true }).click()
    await expect(page).toHaveURL(/\/chat$/)
    await expect(page.getByPlaceholder('Type your question...')).toBeVisible()
  })
})
