import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * E2E coverage for the per-instrument price columns (kx-m2v).
 *
 * The trade blotter must surface a Price column populated with the price each
 * trade was booked at, and the position grid must surface a Last Price column
 * populated with the current mark for each instrument. Both columns format
 * with asset-class-aware precision (FX 4dp, equities 2dp, bonds 2-3dp).
 *
 * The default `TEST_POSITIONS` and `TEST_TRADES` fixtures already include a
 * mix of asset classes; we mock with them and assert the price cell text is
 * non-empty and reflects realistic precision.
 */

test.describe('Per-instrument price columns', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('trade blotter shows a Price column with non-zero, asset-class-aware prices', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid^="trade-row-"]')

    // Header is visible.
    await expect(page.getByRole('columnheader', { name: 'Price' })).toBeVisible()

    // AAPL equity trade booked at $150.00 → 2 decimals.
    const aaplPrice = page.getByTestId('trade-price-trade-1')
    await expect(aaplPrice).toBeVisible()
    await expect(aaplPrice).toContainText('$150.00')

    // GOOGL equity trade booked at $2,800.00 → 2 decimals.
    const googlPrice = page.getByTestId('trade-price-trade-2')
    await expect(googlPrice).toBeVisible()
    await expect(googlPrice).toContainText('$2,800.00')

    // Price cells must never render as the em-dash placeholder.
    const allPriceCells = page.locator('[data-testid^="trade-price-"]')
    const count = await allPriceCells.count()
    expect(count).toBeGreaterThan(0)
    for (let i = 0; i < count; i++) {
      await expect(allPriceCells.nth(i)).not.toHaveText('—')
    }
  })

  test('position grid shows a Last Price column with FX precision for EUR_USD', async ({ page }) => {
    await page.goto('/')
    // Positions is the default tab — ensure it's selected.
    await page.getByTestId('tab-positions').click()
    await page.waitForSelector('[data-testid^="position-row-"]')

    // Header is visible by default (no Details toggle required).
    await expect(page.getByRole('columnheader', { name: 'Last Price' })).toBeVisible()

    // AAPL equity → 2dp.
    await expect(page.getByTestId('last-price-AAPL')).toContainText('$155.00')

    // EUR_USD FX → 4dp (TEST_POSITIONS has marketPrice 1.0850).
    await expect(page.getByTestId('last-price-EUR_USD')).toContainText('$1.0850')

    // No Last Price cell renders as em-dash on a healthy book.
    const lastPriceCells = page.locator('[data-testid^="last-price-"]')
    const count = await lastPriceCells.count()
    expect(count).toBeGreaterThan(0)
    for (let i = 0; i < count; i++) {
      await expect(lastPriceCells.nth(i)).not.toHaveText('—')
    }
  })
})
