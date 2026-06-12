import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes, type TradeFixture } from './fixtures'

/**
 * Plan 2.16 — order-detail-ghost-fills.spec.ts.
 *
 * When a 35=8 fill arrives for an order that has already reached terminal
 * state (EXPIRED / CANCELLED / REJECTED), `FIXExecutionReportProcessor`
 * persists the fill to `orders.ghost_fills` rather than mutating Position.
 * The trade blotter surfaces these via a row-expand affordance: clicking the
 * chevron on a terminal-status row reveals the [OrderGhostFills] panel which
 * renders a CRITICAL banner ("Fill received after cancel — contact ops")
 * and a table of the offending fills below the row.
 */

const BOOK_ID = 'port-1'

const EXPIRED_TRADE: TradeFixture = {
  tradeId: 'trd-ghost-1',
  bookId: BOOK_ID,
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  side: 'BUY',
  quantity: '100',
  price: { amount: '150.00', currency: 'USD' },
  tradedAt: '2026-05-08T10:00:00Z',
  status: 'EXPIRED',
}

const FILLED_TRADE: TradeFixture = {
  tradeId: 'trd-filled-1',
  bookId: BOOK_ID,
  instrumentId: 'GOOGL',
  assetClass: 'EQUITY',
  side: 'SELL',
  quantity: '10',
  price: { amount: '2800.00', currency: 'USD' },
  tradedAt: '2026-05-08T10:05:00Z',
  status: 'FILLED',
}

async function mockTradesEndpoint(page: Page, trades: TradeFixture[]): Promise<void> {
  await page.unroute('**/api/v1/books/*/trades/page**').catch(() => {})
  await page.unroute('**/api/v1/books/*/trades').catch(() => {})
  await page.route('**/api/v1/books/*/trades/page**', (route: Route) => {
    const url = new URL(route.request().url())
    const offset = Number(url.searchParams.get('offset') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 100)
    const counterpartyId = url.searchParams.get('counterpartyId')
    const filtered = counterpartyId
      ? trades.filter((t) => t.counterpartyId === counterpartyId)
      : trades
    const items = filtered.slice(offset, offset + limit)
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        total: filtered.length,
        offset,
        limit,
        hasMore: offset + items.length < filtered.length,
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

async function mockGhostFills(
  page: Page,
  orderId: string,
  fills: Array<{
    orderId: string
    priorStatus: string
    venue: string
    fixExecId: string
    fillQty: string
    fillPrice: string
    cumulativeQty: string
    detectedAt: string
  }>,
): Promise<void> {
  await page.route(`**/api/v1/orders/${orderId}/ghost-fills`, (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(fills),
    })
  })
}

async function navigateToTradeBlotter(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.getByTestId('trades-subtab-blotter').click()
}

test.describe('TradeBlotter — order detail ghost fills', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('expanding an EXPIRED order with ghost fills shows CRITICAL banner and fill rows', async ({
    page,
  }) => {
    await mockTradesEndpoint(page, [EXPIRED_TRADE])
    await mockGhostFills(page, EXPIRED_TRADE.tradeId, [
      {
        orderId: EXPIRED_TRADE.tradeId,
        priorStatus: 'EXPIRED',
        venue: 'NYSE',
        fixExecId: 'exec-ghost-1',
        fillQty: '100',
        fillPrice: '150.00',
        cumulativeQty: '100',
        detectedAt: '2026-05-08T10:01:23Z',
      },
    ])

    await navigateToTradeBlotter(page)

    const expandButton = page.getByTestId(`expand-trade-row-${EXPIRED_TRADE.tradeId}`)
    await expect(expandButton).toBeVisible()
    await expect(expandButton).toHaveAttribute('aria-expanded', 'false')

    await expandButton.click()
    await expect(expandButton).toHaveAttribute('aria-expanded', 'true')

    const detailRow = page.getByTestId(`trade-row-detail-${EXPIRED_TRADE.tradeId}`)
    await expect(detailRow).toBeVisible()

    const banner = detailRow.getByTestId('ghost-fill-banner')
    await expect(banner).toBeVisible()
    await expect(banner).toHaveAttribute('role', 'alert')
    await expect(banner).toContainText('Fill received after cancel — contact ops')

    const fillRows = detailRow.getByTestId('ghost-fill-row')
    await expect(fillRows).toHaveCount(1)
    await expect(fillRows.first()).toContainText('exec-ghost-1')
    await expect(fillRows.first()).toContainText('NYSE')
    await expect(fillRows.first()).toContainText('EXPIRED')

    await expandButton.click()
    await expect(expandButton).toHaveAttribute('aria-expanded', 'false')
    await expect(detailRow).toHaveCount(0)
  })

  test('FILLED orders expand to the order detail panel without a ghost-fills section (kx-ia4z)', async ({
    page,
  }) => {
    await mockTradesEndpoint(page, [FILLED_TRADE])
    await navigateToTradeBlotter(page)

    await expect(page.getByTestId(`trade-row-${FILLED_TRADE.tradeId}`)).toBeVisible()
    await page.getByTestId(`expand-trade-row-${FILLED_TRADE.tradeId}`).click()

    const detailRow = page.getByTestId(`trade-row-detail-${FILLED_TRADE.tradeId}`)
    await expect(detailRow.getByTestId('trade-detail-panel')).toBeVisible()
    await expect(detailRow.getByTestId('ghost-fill-banner')).toHaveCount(0)
    await expect(detailRow.getByTestId('ghost-fill-row')).toHaveCount(0)
  })

  test('terminal order without ghost fills expands to the detail panel (no banner, no rows)', async ({
    page,
  }) => {
    await mockTradesEndpoint(page, [EXPIRED_TRADE])
    await mockGhostFills(page, EXPIRED_TRADE.tradeId, [])

    await navigateToTradeBlotter(page)

    await page.getByTestId(`expand-trade-row-${EXPIRED_TRADE.tradeId}`).click()

    const detailRow = page.getByTestId(`trade-row-detail-${EXPIRED_TRADE.tradeId}`)
    await expect(detailRow).toBeVisible()
    await expect(detailRow.getByTestId('trade-detail-panel')).toBeVisible()
    await expect(detailRow.getByTestId('ghost-fill-banner')).toHaveCount(0)
    await expect(detailRow.getByTestId('ghost-fill-row')).toHaveCount(0)
  })
})
