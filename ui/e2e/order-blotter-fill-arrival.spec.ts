import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes, type TradeFixture } from './fixtures'

/**
 * ADR-0035 phase 3 commit 3 — when a fill flows through the new
 * fix-gateway → execution.reports Kafka → position-service consumer path,
 * the resulting trade must surface in the blotter exactly as it did via
 * the legacy in-process FIX path. This regression test pins that contract:
 *  - a SENT order yields a freshly-booked trade row in the blotter
 *  - a follow-up fill arrival (simulated by re-navigating to refresh the
 *    blotter) advances the row's surfaced status from LIVE → FILLED
 *  - none of this requires a full page reload — the user stays on the
 *    Trades tab the entire time
 */

const BOOK_ID = 'port-1'

const INITIAL_TRADE: TradeFixture = {
  tradeId: 'order-fill-arrival-1',
  bookId: BOOK_ID,
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  side: 'BUY',
  quantity: '100',
  price: { amount: '150.00', currency: 'USD' },
  tradedAt: '2026-05-07T10:00:00Z',
  status: 'LIVE',
}

const FILLED_TRADE: TradeFixture = {
  ...INITIAL_TRADE,
  status: 'FILLED',
}

async function mockTradesEndpoint(page: Page, trades: TradeFixture[]): Promise<void> {
  await page.unroute('**/api/v1/books/*/trades/page**').catch(() => {})
  await page.unroute('**/api/v1/books/*/trades').catch(() => {})
  await page.route('**/api/v1/books/*/trades/page**', (route: Route) => {
    const url = new URL(route.request().url())
    const offset = Number(url.searchParams.get('offset') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 100)
    const items = trades.slice(offset, offset + limit)
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        total: trades.length,
        offset,
        limit,
        hasMore: offset + items.length < trades.length,
      }),
    })
  })
  await page.route('**/api/v1/books/*/trades', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(trades),
    })
  })
}

async function navigateToTradeBlotter(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.getByTestId('trades-subtab-blotter').click()
}

test.describe('OrderBlotter — fill arrival via execution.reports Kafka path', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders the new trade row sourced from the Kafka execution.reports path', async ({ page }) => {
    await mockTradesEndpoint(page, [INITIAL_TRADE])
    await navigateToTradeBlotter(page)

    const row = page.getByTestId(`trade-row-${INITIAL_TRADE.tradeId}`)
    await expect(row).toBeVisible()
    await expect(page.getByTestId(`trade-status-${INITIAL_TRADE.tradeId}`)).toHaveText('LIVE')
    await expect(page.getByTestId(`trade-side-${INITIAL_TRADE.tradeId}`)).toHaveText('BUY')
  })

  test('fill arrival on the blotter surfaces FILLED status without a full page reload', async ({ page }) => {
    // Initial state: the order has just been booked — row shows LIVE.
    await mockTradesEndpoint(page, [INITIAL_TRADE])
    await navigateToTradeBlotter(page)

    const row = page.getByTestId(`trade-row-${INITIAL_TRADE.tradeId}`)
    const statusCell = page.getByTestId(`trade-status-${INITIAL_TRADE.tradeId}`)
    await expect(row).toBeVisible()
    await expect(statusCell).toHaveText('LIVE')

    // Simulate the fix-gateway → execution.reports → position-service flow:
    // a fill lands, position-service updates trade status, REST endpoint now
    // returns FILLED. Refetch the blotter without a full page reload by
    // toggling sub-tabs (the user's typical flow when they want fresh data).
    await mockTradesEndpoint(page, [FILLED_TRADE])
    await page.getByTestId('trades-subtab-cost').click()
    await page.getByTestId('trades-subtab-blotter').click()

    await expect(statusCell).toHaveText('FILLED')

    // Crucially — the user did NOT reload. The Trades tab is still selected
    // and so is the blotter sub-tab. (If a reload had occurred, the page
    // would land on the default tab, not the blotter sub-tab.)
    await expect(page.getByTestId('tab-trades')).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByTestId('trades-subtab-blotter')).toHaveAttribute('aria-selected', 'true')
  })

  test('partial fill arriving via Kafka shows up as a new partial-fill row alongside the original', async ({ page }) => {
    // After a partial fill, position-service stays in the original row's
    // tradeId (idempotent on tradeId) but a fresh fill creates a new trade
    // event. The blotter should reflect both.
    const partialFill: TradeFixture = {
      tradeId: 'order-fill-arrival-partial-1',
      bookId: BOOK_ID,
      instrumentId: 'AAPL',
      assetClass: 'EQUITY',
      side: 'BUY',
      quantity: '40',
      price: { amount: '150.00', currency: 'USD' },
      tradedAt: '2026-05-07T10:01:00Z',
      status: 'LIVE',
    }

    await mockTradesEndpoint(page, [INITIAL_TRADE])
    await navigateToTradeBlotter(page)
    await expect(page.getByTestId(`trade-row-${INITIAL_TRADE.tradeId}`)).toBeVisible()

    await mockTradesEndpoint(page, [INITIAL_TRADE, partialFill])
    await page.getByTestId('trades-subtab-cost').click()
    await page.getByTestId('trades-subtab-blotter').click()

    await expect(page.getByTestId(`trade-row-${INITIAL_TRADE.tradeId}`)).toBeVisible()
    await expect(page.getByTestId(`trade-row-${partialFill.tradeId}`)).toBeVisible()
    await expect(page.getByTestId(`trade-status-${partialFill.tradeId}`)).toHaveText('LIVE')
  })
})
