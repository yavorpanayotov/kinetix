import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

const STORAGE_KEY = 'kinetix:column-visibility'
const WORKSPACE_KEY = 'kinetix:workspace'

test.describe('Column Visibility - Persistence and Layout', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('hidden columns remain hidden after page reload', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="column-settings-button"]')

    // Hide the "Asset Class" column
    await page.getByTestId('column-settings-button').click()
    await page.getByTestId('column-toggle-assetClass').click()

    // Close the dropdown
    await page.locator('header').click()

    // Verify the column is hidden in the table
    const headerCells = page.locator('thead th')
    await expect(headerCells).not.toContainText(['Asset Class'])

    // Verify localStorage was updated
    const stored = await page.evaluate(
      (key) => localStorage.getItem(key),
      STORAGE_KEY,
    )
    expect(stored).toBeTruthy()
    const parsed = JSON.parse(stored!)
    expect(parsed.assetClass).toBe(false)

    // Reload the page
    await page.reload()
    await page.waitForSelector('[data-testid="column-settings-button"]')

    // The "Asset Class" column should still be hidden
    const headerTexts = await page.locator('thead tr').last().locator('th').allTextContents()
    expect(headerTexts).not.toContain('Asset Class')
    // Other columns should still be present
    expect(headerTexts).toContain('Instrument')
  })

  test('table layout reflows without horizontal overflow after hiding columns', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="column-settings-button"]')

    // Hide a customisable column to trigger a reflow (detail columns are
    // hidden by default under the risk-first layout).
    await page.getByTestId('column-settings-button').click()
    await page.getByTestId('column-toggle-assetClass').click()
    await page.getByTestId('column-toggle-realizedPnl').click()

    // Close the dropdown
    await page.locator('header').click()

    // The table's scrollable container should not have horizontal overflow
    const overflowInfo = await page.locator('table').evaluate((table) => {
      const container = table.parentElement!
      return {
        scrollWidth: container.scrollWidth,
        clientWidth: container.clientWidth,
      }
    })
    expect(overflowInfo.scrollWidth).toBeLessThanOrEqual(
      overflowInfo.clientWidth + 1, // +1 for rounding tolerance
    )
  })

  test('settings dropdown shows correct checked/unchecked state after reload', async ({
    page,
  }) => {
    // Pre-set column visibility in localStorage: hide "Asset Class" and
    // "Realized P&L". (Detail columns — Quantity / Avg Cost / Market Price —
    // are controlled by the separate Details toggle, not this dropdown.)
    await page.addInitScript(
      ({ key, workspaceKey }) => {
        localStorage.setItem(
          key,
          JSON.stringify({ assetClass: false, realizedPnl: false }),
        )
        // Reveal Details columns so we can assert against the full column set.
        localStorage.setItem(
          workspaceKey,
          JSON.stringify({ showPositionDetails: true }),
        )
      },
      { key: STORAGE_KEY, workspaceKey: WORKSPACE_KEY },
    )

    await page.goto('/')
    await page.waitForSelector('[data-testid="column-settings-button"]')

    // Open settings dropdown
    await page.getByTestId('column-settings-button').click()
    await page.waitForSelector('[data-testid="column-settings-dropdown"]')

    // "Asset Class" and "Realized P&L" should be unchecked
    await expect(page.getByTestId('column-toggle-assetClass')).not.toBeChecked()
    await expect(page.getByTestId('column-toggle-realizedPnl')).not.toBeChecked()

    // Other customisable columns should be checked
    await expect(page.getByTestId('column-toggle-instrument')).toBeChecked()
    await expect(page.getByTestId('column-toggle-marketValue')).toBeChecked()
    await expect(page.getByTestId('column-toggle-unrealizedPnl')).toBeChecked()

    // Detail columns are NOT in this dropdown (Details toggle owns them)
    await expect(page.getByTestId('column-toggle-quantity')).toHaveCount(0)
    await expect(page.getByTestId('column-toggle-avgCost')).toHaveCount(0)
    await expect(page.getByTestId('column-toggle-marketPrice')).toHaveCount(0)
  })
})
