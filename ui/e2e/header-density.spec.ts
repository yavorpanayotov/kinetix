import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// Verifies the header indicator cluster fits at 1280×800 with every badge
// rendered. Pins the Phase 3 header-density refactor: the tape-replay long
// label is suppressed until 2xl, so at 1280px the compact "Replay" label is
// what we see and the cluster does not overflow.
async function mockHeaderIndicators(page: Page): Promise<void> {
  await page.route('**/api/v1/demo/scenario', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ scenario: 'options-book' }),
    })
  })
  await page.route('**/api/v1/demo/replay-status', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'ACTIVE' }),
    })
  })
}

test.describe('Header density at 1280px', () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await mockAllApiRoutes(page)
    await mockHeaderIndicators(page)
  })

  test('all indicator badges fit within the header — no horizontal overflow', async ({ page }) => {
    await page.goto('/')

    await expect(page.getByTestId('hierarchy-selector')).toBeVisible()
    await expect(page.getByTestId('scenario-indicator')).toBeVisible()
    await expect(page.getByTestId('tape-replay-indicator')).toBeVisible()
    await expect(page.getByTestId('regime-indicator')).toBeVisible()
    await expect(page.getByTestId('data-quality-indicator')).toBeVisible()

    const overflow = await page.evaluate(() => {
      const header = document.querySelector('header')
      if (!header) return null
      return {
        scrollWidth: header.scrollWidth,
        clientWidth: header.clientWidth,
        bodyScroll: document.documentElement.scrollWidth,
        bodyClient: document.documentElement.clientWidth,
      }
    })
    expect(overflow).not.toBeNull()
    expect(overflow!.scrollWidth).toBeLessThanOrEqual(overflow!.clientWidth)
    expect(overflow!.bodyScroll).toBeLessThanOrEqual(overflow!.bodyClient)
  })

  test('tape-replay shows the compact label at 1280px (long label suppressed until 2xl)', async ({ page }) => {
    await page.goto('/')

    const indicator = page.getByTestId('tape-replay-indicator')
    await expect(indicator).toBeVisible()

    // Both spans live in the DOM; only the compact one is rendered at 1280px.
    const visibleText = await indicator.evaluate((el) => {
      const spans = Array.from(el.querySelectorAll('span'))
      return spans
        .filter((s) => s.offsetWidth > 0 && s.offsetHeight > 0)
        .map((s) => s.textContent?.trim())
        .filter(Boolean)
    })
    expect(visibleText).toContain('Replay')
    expect(visibleText).not.toContain('Tape Replay Active')
  })

  test('right-cluster reaches the header right edge without exceeding it', async ({ page }) => {
    await page.goto('/')

    const box = await page.getByTestId('header-right-cluster').boundingBox()
    const headerBox = await page.locator('header').boundingBox()
    expect(box).not.toBeNull()
    expect(headerBox).not.toBeNull()
    const clusterRight = box!.x + box!.width
    const headerRight = headerBox!.x + headerBox!.width
    expect(clusterRight).toBeLessThanOrEqual(headerRight)
  })
})
