import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes, type TradeFixture } from './fixtures'

/**
 * kx-ia4z — every blotter row expands to an inline order detail panel.
 *
 * Trader review (Marcus): per-trade questions — counterparty, venue, order
 * ID, book, true notional — were previously answered by global column
 * toggles or not at all; the row-expand affordance existed only for
 * terminal-status orders (ghost fills). The detail panel surfaces the
 * identifiers and economics blocks for any trade, FILLED included.
 */

const BOOK_ID = 'port-1'

const FILLED_TRADE: TradeFixture = {
  tradeId: 'trd-detail-1',
  bookId: BOOK_ID,
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  side: 'BUY',
  quantity: '100',
  price: { amount: '150.25', currency: 'USD' },
  tradedAt: '2026-05-08T10:00:00Z',
  status: 'LIVE',
  venue: 'NYSE',
  venueOrderId: 'VO-9001',
  counterpartyId: 'cp-goldman',
}

const BARE_TRADE: TradeFixture = {
  tradeId: 'trd-detail-2',
  bookId: BOOK_ID,
  instrumentId: 'GOOGL',
  assetClass: 'EQUITY',
  side: 'SELL',
  quantity: '10',
  price: { amount: '2800.00', currency: 'USD' },
  tradedAt: '2026-05-08T10:05:00Z',
  status: 'LIVE',
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

test.describe('TradeBlotter — expandable order detail panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockTradesEndpoint(page, [FILLED_TRADE, BARE_TRADE])
  })

  test('expanding a FILLED trade shows identifiers and economics', async ({ page }) => {
    await navigateToTradeBlotter(page)

    const expandButton = page.getByTestId(`expand-trade-row-${FILLED_TRADE.tradeId}`)
    await expect(expandButton).toHaveAttribute('aria-expanded', 'false')
    await expandButton.click()
    await expect(expandButton).toHaveAttribute('aria-expanded', 'true')

    const detail = page.getByTestId(`trade-row-detail-${FILLED_TRADE.tradeId}`)
    await expect(detail.getByTestId('trade-detail-panel')).toBeVisible()

    await expect(detail.getByTestId('detail-trade-id')).toContainText('trd-detail-1')
    await expect(detail.getByTestId('detail-venue-order-id')).toContainText('VO-9001')
    await expect(detail.getByTestId('detail-venue')).toContainText('NYSE')
    await expect(detail.getByTestId('detail-book')).toContainText(BOOK_ID)
    await expect(detail.getByTestId('detail-counterparty')).toContainText('cp-goldman')

    await expect(detail.getByTestId('detail-quantity')).toContainText('100')
    await expect(detail.getByTestId('detail-price')).toContainText('$150.25')
    await expect(detail.getByTestId('detail-notional')).toContainText('$15,025.00')
  })

  test('absent venue, venue order ID, and counterparty fall back to em dashes', async ({
    page,
  }) => {
    await navigateToTradeBlotter(page)

    await page.getByTestId(`expand-trade-row-${BARE_TRADE.tradeId}`).click()

    const detail = page.getByTestId(`trade-row-detail-${BARE_TRADE.tradeId}`)
    await expect(detail.getByTestId('detail-venue')).toContainText('—')
    await expect(detail.getByTestId('detail-venue-order-id')).toContainText('—')
    await expect(detail.getByTestId('detail-counterparty')).toContainText('—')
  })

  test('copy buttons put the trade ID and venue order ID on the clipboard', async ({
    page,
    context,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await navigateToTradeBlotter(page)

    await page.getByTestId(`expand-trade-row-${FILLED_TRADE.tradeId}`).click()
    const detail = page.getByTestId(`trade-row-detail-${FILLED_TRADE.tradeId}`)

    await detail.getByTestId('copy-trade-id').click()
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('trd-detail-1')

    await detail.getByTestId('detail-copy-venue-order-id').click()
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('VO-9001')
  })

  test('expanding a second trade collapses the first', async ({ page }) => {
    await navigateToTradeBlotter(page)

    await page.getByTestId(`expand-trade-row-${FILLED_TRADE.tradeId}`).click()
    await expect(
      page.getByTestId(`trade-row-detail-${FILLED_TRADE.tradeId}`),
    ).toBeVisible()

    await page.getByTestId(`expand-trade-row-${BARE_TRADE.tradeId}`).click()
    await expect(page.getByTestId(`trade-row-detail-${FILLED_TRADE.tradeId}`)).toHaveCount(0)
    await expect(page.getByTestId(`trade-row-detail-${BARE_TRADE.tradeId}`)).toBeVisible()
  })
})
