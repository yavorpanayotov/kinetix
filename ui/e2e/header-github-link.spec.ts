import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

test.describe('Header GitHub link', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('points at the repo, opens in a new tab, and has an accessible label', async ({
    page,
  }) => {
    await page.goto('/')

    const link = page.getByTestId('header-github-link')
    await expect(link).toBeVisible()
    await expect(link).toHaveAttribute(
      'href',
      'https://github.com/panayotovk/kinetix',
    )
    await expect(link).toHaveAttribute('target', '_blank')
    const rel = await link.getAttribute('rel')
    expect(rel ?? '').toContain('noreferrer')
    expect(rel ?? '').toContain('noopener')
    await expect(link).toHaveAttribute('aria-label', 'View source on GitHub')
  })

  test('sits next to the dark-mode toggle inside the header right cluster', async ({
    page,
  }) => {
    await page.goto('/')

    const cluster = page.getByTestId('header-right-cluster')
    await expect(cluster.getByTestId('header-github-link')).toBeVisible()
    await expect(cluster.getByTestId('dark-mode-toggle')).toBeVisible()
  })

  test('is visible when the app loads with dark mode pre-set', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem('kinetix:theme', 'dark')
    })
    await page.goto('/')

    await expect(page.locator('html')).toHaveClass(/dark/)
    await expect(page.getByTestId('header-github-link')).toBeVisible()
  })
})
