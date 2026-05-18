import { test, expect } from '@playwright/test'
import { mockAllApiRoutes, mockPositionRisk, TEST_POSITION_RISK } from './fixtures'

// Plan ui-overhaul.md §8.5 — risk-first PositionGrid default columns.
//
// Default view exposes Instrument · Market Value · Unrealized P&L · Δ · Γ ·
// Vega · VaR%; Quantity · Avg Cost · Market Price live behind a Details
// toggle. The toggle state persists via the workspace preference.
test.describe('PositionGrid - risk-first columns (§8.5)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockPositionRisk(page, TEST_POSITION_RISK)
  })

  test('default Positions tab hides Detail columns and shows risk numbers', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    // Risk-first columns are visible
    await expect(page.locator('th', { hasText: 'Instrument' })).toBeVisible()
    await expect(page.locator('th', { hasText: 'Market Value' })).toBeVisible()
    await expect(page.locator('th', { hasText: 'Unrealized P&L' })).toBeVisible()
    await expect(page.getByTestId('sort-delta')).toBeVisible()
    await expect(page.getByTestId('sort-gamma')).toBeVisible()
    await expect(page.getByTestId('sort-vega')).toBeVisible()
    await expect(page.getByTestId('sort-var-pct')).toBeVisible()

    // Detail columns are hidden by default
    await expect(page.locator('thead').getByText('Quantity', { exact: true })).toHaveCount(0)
    await expect(page.locator('thead').getByText('Avg Cost', { exact: true })).toHaveCount(0)
    await expect(page.locator('thead').getByText('Market Price', { exact: true })).toHaveCount(0)
  })

  test('Details toggle reveals Quantity, Avg Cost, and Market Price', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="position-details-toggle"]')

    const toggle = page.getByTestId('position-details-toggle')
    await expect(toggle).toHaveAttribute('aria-pressed', 'false')

    await toggle.click()

    await expect(toggle).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('th', { hasText: 'Quantity' })).toBeVisible()
    await expect(page.locator('th', { hasText: 'Avg Cost' })).toBeVisible()
    await expect(page.locator('th', { hasText: 'Market Price' })).toBeVisible()
  })

  test('Details toggle is reversible and persists across reload', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="position-details-toggle"]')

    // Reveal
    await page.getByTestId('position-details-toggle').click()
    await expect(page.locator('th', { hasText: 'Quantity' })).toBeVisible()

    // Reload — preference should persist
    await page.reload()
    await page.waitForSelector('[data-testid="position-details-toggle"]')
    await expect(page.getByTestId('position-details-toggle')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('th', { hasText: 'Quantity' })).toBeVisible()

    // Hide again
    await page.getByTestId('position-details-toggle').click()
    await expect(page.locator('thead').getByText('Quantity', { exact: true })).toHaveCount(0)
  })
})
