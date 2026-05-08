import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes, type TradeFixture } from './fixtures'

/**
 * ADR-0035 phase 4 §4.12 — Venue Order ID column on the trade blotter.
 * Surfaces the venue's FIX tag 37 OrderID so traders can quote it when
 * calling the venue. Hidden by default; reveals via a column-visibility
 * toggle. Must be ARIA-labelled, monospaced, right-aligned, copyable, and
 * included in CSV export.
 */

interface PhaseTradeFixture extends TradeFixture {
  venueOrderId?: string
}

const TRADE_WITH_VENUE_ID: PhaseTradeFixture = {
  tradeId: 'venue-id-trade-1',
  bookId: 'port-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  side: 'BUY',
  quantity: '100',
  price: { amount: '150.00', currency: 'USD' },
  tradedAt: '2026-05-08T10:00:00Z',
  status: 'SENT',
  venueOrderId: 'NYSE-99887766',
}

const TRADE_WITHOUT_VENUE_ID: PhaseTradeFixture = {
  tradeId: 'venue-id-trade-2',
  bookId: 'port-1',
  instrumentId: 'MSFT',
  assetClass: 'EQUITY',
  side: 'SELL',
  quantity: '50',
  price: { amount: '300.00', currency: 'USD' },
  tradedAt: '2026-05-08T10:01:00Z',
  status: 'PENDING',
}

async function mockTrades(page: Page, trades: PhaseTradeFixture[]): Promise<void> {
  await page.unroute('**/api/v1/books/*/trades').catch(() => {})
  await page.route('**/api/v1/books/*/trades', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(trades),
    })
  })
}

async function navigateToBlotter(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.getByTestId('trades-subtab-blotter').click()
}

test.describe('TradeBlotter — Venue Order ID column', () => {
  test.beforeEach(async ({ page, context }) => {
    await mockAllApiRoutes(page)
    await context.grantPermissions(['clipboard-read', 'clipboard-write']).catch(() => {})
  })

  test('column is hidden by default and revealed via the visibility toggle', async ({ page }) => {
    await mockTrades(page, [TRADE_WITH_VENUE_ID, TRADE_WITHOUT_VENUE_ID])
    await navigateToBlotter(page)

    // Column header hidden by default — the toggle button itself contains
    // similar text ("Show Venue Order ID") so target the <th> by role+name.
    await expect(page.getByRole('columnheader', { name: 'Venue Order ID' })).toHaveCount(0)

    await page.getByTestId('toggle-venue-order-id-column').click()
    await expect(page.getByRole('columnheader', { name: 'Venue Order ID' })).toBeVisible()
  })

  test('renders monospaced + right-aligned venue order id with ARIA label', async ({ page }) => {
    await mockTrades(page, [TRADE_WITH_VENUE_ID])
    await navigateToBlotter(page)
    await page.getByTestId('toggle-venue-order-id-column').click()

    const cell = page.getByTestId('trade-venue-order-id-venue-id-trade-1')
    await expect(cell).toHaveText('NYSE-99887766')
    await expect(cell).toHaveAttribute('aria-label', 'Venue order ID')
    const className = await cell.getAttribute('class')
    expect(className).toMatch(/font-mono/)
    expect(className).toMatch(/text-right/)
  })

  test('renders an em-dash for trades without a venue order id', async ({ page }) => {
    await mockTrades(page, [TRADE_WITHOUT_VENUE_ID])
    await navigateToBlotter(page)
    await page.getByTestId('toggle-venue-order-id-column').click()

    await expect(page.getByTestId('trade-venue-order-id-venue-id-trade-2')).toHaveText('—')
    // No copy button for missing venue ids
    await expect(page.getByTestId('copy-venue-order-id-venue-id-trade-2')).toHaveCount(0)
  })

  test('clipboard-copy button copies the venue order id', async ({ page }) => {
    await mockTrades(page, [TRADE_WITH_VENUE_ID])
    await navigateToBlotter(page)
    await page.getByTestId('toggle-venue-order-id-column').click()

    const copyBtn = page.getByTestId('copy-venue-order-id-venue-id-trade-1')
    await expect(copyBtn).toHaveAttribute('aria-label', 'Copy venue order ID')
    await copyBtn.click()

    const clipboardText = await page.evaluate(async () => {
      try { return await navigator.clipboard.readText() } catch { return null }
    })
    if (clipboardText !== null) {
      expect(clipboardText).toBe('NYSE-99887766')
    }
  })

  test('CSV export includes the VenueOrderId column', async ({ page }) => {
    await mockTrades(page, [TRADE_WITH_VENUE_ID])
    await navigateToBlotter(page)

    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('csv-export-button').click()
    const download = await downloadPromise
    const path = await download.path()
    if (path) {
      const { readFile } = await import('fs/promises')
      const csv = await readFile(path, 'utf8')
      expect(csv).toContain('VenueOrderId')
      expect(csv).toContain('NYSE-99887766')
    }
  })
})
