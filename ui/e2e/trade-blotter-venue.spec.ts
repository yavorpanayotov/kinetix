import { test, expect } from '@playwright/test'
import { mockAllApiRoutes, mockTradesOverride, type TradeFixture } from './fixtures'

/**
 * Trader-review P2 §22: the Trade Blotter exposes a "Show Venue" toggle but,
 * until now, enabling it did nothing — no Venue column appeared. This spec
 * asserts that:
 *   - the Venue column is hidden by default,
 *   - clicking "Show Venue" reveals a "Venue" column header,
 *   - each row renders its trading venue (e.g. NYSE / NASDAQ), falling back to
 *     an em dash when the upstream omits the venue.
 */

const BOOK_ID = 'port-1'

const TRADES_WITH_VENUE: TradeFixture[] = [
  {
    tradeId: 't-nyse',
    bookId: BOOK_ID,
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '1000',
    price: { amount: '150.00', currency: 'USD' },
    tradedAt: '2025-01-15T10:00:00Z',
    status: 'LIVE',
    venue: 'NYSE',
  },
  {
    tradeId: 't-nasdaq',
    bookId: BOOK_ID,
    instrumentId: 'MSFT',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '200',
    price: { amount: '300.00', currency: 'USD' },
    tradedAt: '2025-01-15T10:30:00Z',
    status: 'LIVE',
    venue: 'NASDAQ',
  },
  {
    tradeId: 't-novenue',
    bookId: BOOK_ID,
    instrumentId: 'GOOGL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '50',
    price: { amount: '2800.00', currency: 'USD' },
    tradedAt: '2025-01-15T11:00:00Z',
    status: 'LIVE',
    // No venue — should fall back to an em dash.
  },
]

async function goToBlotter(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.waitForSelector('[data-testid^="trade-row-"]')
}

test.describe('Trade Blotter — Venue column', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockTradesOverride(page, TRADES_WITH_VENUE)
  })

  test('hides the Venue column until the "Show Venue" toggle is enabled', async ({ page }) => {
    await goToBlotter(page)

    await expect(page.getByRole('columnheader', { name: 'Venue', exact: true })).toHaveCount(0)

    await page.getByTestId('toggle-venue-column').click()

    await expect(page.getByRole('columnheader', { name: 'Venue', exact: true })).toBeVisible()
  })

  test('renders the trading venue per row when the column is enabled', async ({ page }) => {
    await goToBlotter(page)

    await page.getByTestId('toggle-venue-column').click()

    await expect(page.getByTestId('trade-venue-t-nyse')).toHaveText('NYSE')
    await expect(page.getByTestId('trade-venue-t-nasdaq')).toHaveText('NASDAQ')
  })

  test('falls back to an em dash when a row carries no venue', async ({ page }) => {
    await goToBlotter(page)

    await page.getByTestId('toggle-venue-column').click()

    await expect(page.getByTestId('trade-venue-t-novenue')).toHaveText('—')
  })
})
