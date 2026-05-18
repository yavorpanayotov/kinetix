import { test, expect } from '@playwright/test'
import { mockAllApiRoutes, TEST_POSITIONS, TEST_TRADES } from './fixtures'

test.describe('CSV Export - File Download and Content Validity', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test.describe('Position Grid CSV Export', () => {
    test('clicking Export CSV triggers a file download named positions.csv', async ({
      page,
    }) => {
      await page.goto('/')
      await page.waitForSelector('[data-testid="csv-export-button"]')

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('csv-export-button').click()
      const download = await downloadPromise

      expect(download.suggestedFilename()).toBe('positions.csv')
    })

    test('downloaded positions CSV contains correct headers and all position rows', async ({
      page,
    }) => {
      // Reveal Details columns so the export captures the full position view —
      // by default the risk-first PositionGrid hides Quantity / Avg Cost /
      // Market Price.
      await page.addInitScript(() => {
        localStorage.setItem(
          'kinetix:workspace',
          JSON.stringify({ showPositionDetails: true }),
        )
      })
      await page.goto('/')
      await page.waitForSelector('[data-testid="csv-export-button"]')

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('csv-export-button').click()
      const download = await downloadPromise

      const filePath = await download.path()
      expect(filePath).toBeTruthy()

      const { readFile } = await import('fs/promises')
      const content = await readFile(filePath!, 'utf-8')
      const lines = content.trim().split('\n')

      // Header row should contain all visible column labels (with Details on)
      const headerRow = lines[0]
      expect(headerRow).toContain('Instrument')
      expect(headerRow).toContain('Asset Class')
      expect(headerRow).toContain('Quantity')
      expect(headerRow).toContain('Avg Cost')
      expect(headerRow).toContain('Market Price')
      expect(headerRow).toContain('Market Value')
      expect(headerRow).toContain('Unrealized P&L')

      // Should have exactly 3 data rows (one per position) plus the header
      expect(lines).toHaveLength(TEST_POSITIONS.length + 1)

      // Verify each position appears in the CSV
      const dataContent = lines.slice(1).join('\n')
      for (const pos of TEST_POSITIONS) {
        expect(dataContent).toContain(pos.instrumentId)
      }
    })

    test('default risk-first CSV export omits Detail columns', async ({
      page,
    }) => {
      // With Details toggle OFF (the default), the CSV should NOT include
      // Quantity, Avg Cost, or Market Price.
      await page.goto('/')
      await page.waitForSelector('[data-testid="csv-export-button"]')

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('csv-export-button').click()
      const download = await downloadPromise

      const filePath = await download.path()
      const { readFile } = await import('fs/promises')
      const content = await readFile(filePath!, 'utf-8')
      const headerRow = content.trim().split('\n')[0]

      expect(headerRow).toContain('Instrument')
      expect(headerRow).toContain('Market Value')
      expect(headerRow).toContain('Unrealized P&L')
      expect(headerRow).not.toContain('Quantity')
      expect(headerRow).not.toContain('Avg Cost')
      expect(headerRow).not.toContain('Market Price')
    })

    test('position CSV contains raw numeric values without currency symbols', async ({
      page,
    }) => {
      await page.goto('/')
      await page.waitForSelector('[data-testid="csv-export-button"]')

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('csv-export-button').click()
      const download = await downloadPromise

      const filePath = await download.path()
      const { readFile } = await import('fs/promises')
      const content = await readFile(filePath!, 'utf-8')
      const lines = content.trim().split('\n')

      // Check the AAPL row contains raw amounts, not formatted currency
      const aaplRow = lines.find((line) => line.includes('AAPL'))
      expect(aaplRow).toBeTruthy()
      expect(aaplRow).toContain('500.00') // unrealized P&L amount
      expect(aaplRow).not.toContain('$') // should not contain currency symbols
    })

    test('CSV export respects column visibility settings', async ({
      page,
    }) => {
      // Reveal Details so we can verify Quantity is included by default once
      // toggled on, then hide Asset Class via the per-column settings dropdown.
      await page.addInitScript(() => {
        localStorage.setItem(
          'kinetix:workspace',
          JSON.stringify({ showPositionDetails: true }),
        )
      })
      await page.goto('/')
      await page.waitForSelector('[data-testid="csv-export-button"]')

      // Hide the "Asset Class" column
      await page.getByTestId('column-settings-button').click()
      await page.getByTestId('column-toggle-assetClass').click()

      // Click somewhere else to close the settings dropdown
      await page.locator('header').click()

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('csv-export-button').click()
      const download = await downloadPromise

      const filePath = await download.path()
      const { readFile } = await import('fs/promises')
      const content = await readFile(filePath!, 'utf-8')
      const headerRow = content.trim().split('\n')[0]

      // "Asset Class" should not appear in headers since we hid it
      expect(headerRow).not.toContain('Asset Class')
      // Other columns should still be present
      expect(headerRow).toContain('Instrument')
      expect(headerRow).toContain('Quantity')
    })
  })

  test.describe('Trade Blotter CSV Export', () => {
    test('clicking Export CSV on the trade blotter downloads trades.csv', async ({
      page,
    }) => {
      await page.goto('/')

      // Switch to the Trades tab
      await page.getByTestId('tab-trades').click()
      await page.waitForSelector('[data-testid="csv-export-button"]')

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('csv-export-button').click()
      const download = await downloadPromise

      expect(download.suggestedFilename()).toBe('trades.csv')
    })

    test('trade blotter CSV contains correct header and all trade rows', async ({
      page,
    }) => {
      await page.goto('/')

      await page.getByTestId('tab-trades').click()
      await page.waitForSelector('[data-testid="csv-export-button"]')

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('csv-export-button').click()
      const download = await downloadPromise

      const filePath = await download.path()
      const { readFile } = await import('fs/promises')
      const content = await readFile(filePath!, 'utf-8')
      const lines = content.trim().split('\n')

      // Header row
      const headerRow = lines[0]
      expect(headerRow).toContain('Time')
      expect(headerRow).toContain('Instrument')
      expect(headerRow).toContain('Side')
      expect(headerRow).toContain('Qty')
      expect(headerRow).toContain('Price')

      // Should have one row per trade plus header
      expect(lines).toHaveLength(TEST_TRADES.length + 1)
    })

    test('filtered trade blotter exports only the visible rows', async ({
      page,
    }) => {
      await page.goto('/')

      await page.getByTestId('tab-trades').click()
      await page.waitForSelector('[data-testid="filter-side"]')

      // Filter to show only SELL trades
      await page.getByTestId('filter-side').selectOption('SELL')

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('csv-export-button').click()
      const download = await downloadPromise

      const filePath = await download.path()
      const { readFile } = await import('fs/promises')
      const content = await readFile(filePath!, 'utf-8')
      const lines = content.trim().split('\n')

      // Only 1 SELL trade in fixtures, plus header = 2 lines
      const sellTrades = TEST_TRADES.filter((t) => t.side === 'SELL')
      expect(lines).toHaveLength(sellTrades.length + 1)

      // All data rows should contain SELL
      for (const line of lines.slice(1)) {
        expect(line).toContain('SELL')
      }
    })

    test('instrument filter narrows CSV export to matching trades only', async ({
      page,
    }) => {
      await page.goto('/')

      await page.getByTestId('tab-trades').click()
      await page.waitForSelector('[data-testid="filter-instrument"]')

      // Filter by instrument
      await page.getByTestId('filter-instrument').fill('AAPL')

      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId('csv-export-button').click()
      const download = await downloadPromise

      const filePath = await download.path()
      const { readFile } = await import('fs/promises')
      const content = await readFile(filePath!, 'utf-8')
      const lines = content.trim().split('\n')

      const aaplTrades = TEST_TRADES.filter((t) => t.instrumentId === 'AAPL')
      expect(lines).toHaveLength(aaplTrades.length + 1)

      // All data rows should contain AAPL
      for (const line of lines.slice(1)) {
        expect(line).toContain('AAPL')
      }
    })
  })
})
