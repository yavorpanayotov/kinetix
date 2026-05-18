import { test, expect } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockPositionRisk,
  mockPositions,
  generatePositions,
  generatePositionRisk,
  TEST_POSITION_RISK,
} from './fixtures'

test.describe('Risk Sorting', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockPositionRisk(page, TEST_POSITION_RISK)
  })

  test('clicking Delta header sorts descending by default', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="sort-delta"]')

    await page.getByTestId('sort-delta').click()

    const rows = page.locator('tbody tr')
    // Descending: GOOGL (7125) > AAPL (1550.25) > EUR_USD (null = -Infinity)
    await expect(rows.nth(0).getByTestId('delta-GOOGL')).toBeVisible()
    await expect(rows.nth(1).getByTestId('delta-AAPL')).toBeVisible()
    await expect(rows.nth(2).getByTestId('delta-EUR_USD')).toBeVisible()
  })

  test('clicking Delta twice toggles to ascending', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="sort-delta"]')

    await page.getByTestId('sort-delta').click()
    await page.getByTestId('sort-delta').click()

    const rows = page.locator('tbody tr')
    // Ascending: EUR_USD (null = -Infinity) < AAPL (1550.25) < GOOGL (7125)
    await expect(rows.nth(0).getByTestId('delta-EUR_USD')).toBeVisible()
    await expect(rows.nth(1).getByTestId('delta-AAPL')).toBeVisible()
    await expect(rows.nth(2).getByTestId('delta-GOOGL')).toBeVisible()
  })

  test('switching to a different column resets to descending', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="sort-delta"]')

    // Sort by delta first
    await page.getByTestId('sort-delta').click()

    // Switch to gamma -- should default to descending
    await page.getByTestId('sort-gamma').click()

    const rows = page.locator('tbody tr')
    // Descending by gamma: GOOGL (45) > AAPL (12.5) > EUR_USD (null)
    await expect(rows.nth(0).getByTestId('gamma-GOOGL')).toBeVisible()
    await expect(rows.nth(1).getByTestId('gamma-AAPL')).toBeVisible()
    await expect(rows.nth(2).getByTestId('gamma-EUR_USD')).toBeVisible()
  })

  test('sort indicator chevron shows only on active column', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="sort-delta"]')

    // Before any sort: chevrons are transparent (opacity-0 hint icons)
    await expect(page.getByTestId('sort-delta').locator('svg')).toHaveCSS('opacity', '0')
    await expect(page.getByTestId('sort-gamma').locator('svg')).toHaveCSS('opacity', '0')

    // Sort by delta
    await page.getByTestId('sort-delta').click()

    // Chevron on delta is fully visible, others are transparent hints
    await expect(page.getByTestId('sort-delta').locator('svg')).not.toHaveCSS('opacity', '0')
    await expect(page.getByTestId('sort-gamma').locator('svg')).toHaveCSS('opacity', '0')
    await expect(page.getByTestId('sort-vega').locator('svg')).toHaveCSS('opacity', '0')
    await expect(page.getByTestId('sort-var-pct').locator('svg')).toHaveCSS('opacity', '0')
  })

  test('sorting does not reset pagination', async ({ page }) => {
    // Set up 120 positions with risk data
    const positions = generatePositions(120)
    await mockPositions(page, positions)
    const risk = generatePositionRisk(positions)
    await mockPositionRisk(page, risk)

    await page.goto('/')
    await page.waitForSelector('[data-testid="pagination-controls"]')

    // Navigate to page 2
    await page.getByTestId('pagination-next').click()
    await expect(page.getByTestId('pagination-info')).toHaveText('Page 2 of 3')

    // Sort by delta
    await page.getByTestId('sort-delta').click()

    // Should still be on page 2
    await expect(page.getByTestId('pagination-info')).toHaveText('Page 2 of 3')
  })

  test('sort by VaR Contrib %', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="sort-var-pct"]')

    await page.getByTestId('sort-var-pct').click()

    const rows = page.locator('tbody tr')
    // Descending: GOOGL (45.6%) > AAPL (42.5%) > EUR_USD (11.9%)
    await expect(rows.nth(0).getByTestId('var-pct-GOOGL')).toBeVisible()
    await expect(rows.nth(1).getByTestId('var-pct-AAPL')).toBeVisible()
    await expect(rows.nth(2).getByTestId('var-pct-EUR_USD')).toBeVisible()
  })

  test('sort has no effect when risk data absent', async ({ page }) => {
    // Override to remove risk data (back to default 404)
    await page.unroute('**/api/v1/risk/positions/*')
    await page.route('**/api/v1/risk/positions/*', (route) => {
      route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    })

    await page.goto('/')
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    // Sort headers should not be visible
    await expect(page.getByTestId('sort-delta')).not.toBeVisible()

    // Rows should be in original order
    const rows = page.locator('tbody tr')
    await expect(rows.nth(0)).toContainText('AAPL')
    await expect(rows.nth(1)).toContainText('GOOGL')
    await expect(rows.nth(2)).toContainText('EUR_USD')
  })

  test('sort persists after column visibility toggle', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="sort-delta"]')

    // Sort by delta descending
    await page.getByTestId('sort-delta').click()

    const rows = page.locator('tbody tr')
    // Verify initial sort: GOOGL first
    await expect(rows.nth(0)).toContainText('GOOGL')

    // Toggle a column off and back on. `quantity` is a Detail column
    // controlled by the Details toggle (not the column-settings dropdown);
    // `assetClass` is a customisable column rendered inside the dropdown.
    await page.getByTestId('column-settings-button').click()
    await page.getByTestId('column-toggle-assetClass').click()
    await page.getByTestId('column-toggle-assetClass').click()
    // Close settings
    await page.getByTestId('column-settings-button').click()

    // Sort order should be unchanged
    await expect(rows.nth(0)).toContainText('GOOGL')
    await expect(rows.nth(1)).toContainText('AAPL')
    await expect(rows.nth(2)).toContainText('EUR_USD')
  })
})
