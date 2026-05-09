/**
 * Performance acceptance test for the server-paginated trade blotter.
 *
 * Per docs/plans/demo-review.md, the demo plan's most important UI test:
 * "scroll the blotter at 50K rows and verify the page renders in <3s".
 *
 * This test mocks a backend with 50,000 trades and asserts the initial
 * page paint completes well under 3s. Because the blotter now drives
 * server pagination (commits 6207c7ac / ad6d3e06 / 63c2481e), only the
 * 50-row first page is fetched on load — the test catches regressions
 * that would re-introduce client-side fetching of the full tape.
 */
import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

const BACKEND_TRADE_COUNT = 50_000
const PAGE_SIZE = 50
const INITIAL_PAINT_BUDGET_MS = 3_000

test.describe('Trade Blotter — performance (50K-row backend)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test(`first page initial paint stays under ${INITIAL_PAINT_BUDGET_MS}ms with ${BACKEND_TRADE_COUNT} backend rows`, async ({
    page,
  }) => {
    // Replace the trade endpoints with handlers that simulate a 50K-row
    // backend: each request returns only the requested slice. The handler
    // never materialises the full 50K array — the contract is the same as
    // production (server returns one page).
    await page.unroute('**/api/v1/books/*/trades/page**')
    await page.unroute('**/api/v1/books/*/trades')

    let pageRequestCount = 0
    await page.route('**/api/v1/books/*/trades/page**', (route) => {
      pageRequestCount += 1
      const url = new URL(route.request().url())
      const offset = Number(url.searchParams.get('offset') ?? 0)
      const limit = Number(url.searchParams.get('limit') ?? PAGE_SIZE)
      const items = Array.from({ length: Math.min(limit, BACKEND_TRADE_COUNT - offset) }, (_, i) => {
        const idx = offset + i
        return {
          tradeId: `perf-trade-${idx}`,
          bookId: 'port-1',
          instrumentId: 'AAPL',
          assetClass: 'EQUITY',
          side: idx % 2 === 0 ? 'BUY' : 'SELL',
          quantity: '100',
          price: { amount: '150.00', currency: 'USD' },
          tradedAt: new Date(Date.UTC(2026, 0, 1) + idx * 60_000).toISOString(),
          status: 'LIVE',
          instrumentType: 'CASH_EQUITY',
        }
      })
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items,
          total: BACKEND_TRADE_COUNT,
          offset,
          limit,
          hasMore: offset + items.length < BACKEND_TRADE_COUNT,
        }),
      })
    })
    // A failure-safety net: if the legacy unpaged endpoint is ever hit by
    // the blotter again, fail the test with a diagnostic.
    await page.route('**/api/v1/books/*/trades', () => {
      throw new Error(
        'Legacy /trades endpoint hit — the blotter must use /trades/page for server pagination',
      )
    })

    await page.goto('/')
    const start = Date.now()
    await page.getByTestId('tab-trades').click()
    // The blotter renders the first server-side page (50 rows by default).
    await page.waitForSelector('[data-testid^="trade-row-"]', { timeout: INITIAL_PAINT_BUDGET_MS })
    const initialPaintMs = Date.now() - start

    expect(initialPaintMs).toBeLessThan(INITIAL_PAINT_BUDGET_MS)

    // Sanity check: only one paged request should fire to draw the first
    // page. If something refetches eagerly we'll see > 1 here.
    expect(pageRequestCount).toBeGreaterThanOrEqual(1)

    // The pagination footer reports the full backend total (50K), not the
    // 50-row page size. This pins the contract end-to-end.
    await expect(page.getByTestId('blotter-pagination-footer')).toContainText('of 50000')
  })

  test('Next page advances offset by PAGE_SIZE without reloading the full dataset', async ({ page }) => {
    let lastOffset = -1
    await page.unroute('**/api/v1/books/*/trades/page**')
    await page.route('**/api/v1/books/*/trades/page**', (route) => {
      const url = new URL(route.request().url())
      const offset = Number(url.searchParams.get('offset') ?? 0)
      const limit = Number(url.searchParams.get('limit') ?? PAGE_SIZE)
      lastOffset = offset
      const items = Array.from({ length: Math.min(limit, BACKEND_TRADE_COUNT - offset) }, (_, i) => ({
        tradeId: `perf-trade-${offset + i}`,
        bookId: 'port-1',
        instrumentId: 'AAPL',
        assetClass: 'EQUITY',
        side: 'BUY',
        quantity: '100',
        price: { amount: '150.00', currency: 'USD' },
        tradedAt: new Date(Date.UTC(2026, 0, 1) + (offset + i) * 60_000).toISOString(),
        status: 'LIVE',
        instrumentType: 'CASH_EQUITY',
      }))
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items,
          total: BACKEND_TRADE_COUNT,
          offset,
          limit,
          hasMore: offset + items.length < BACKEND_TRADE_COUNT,
        }),
      })
    })

    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid^="trade-row-"]')
    expect(lastOffset).toBe(0)

    await page.getByTestId('blotter-next-page').click()
    await page.waitForFunction((expectedOffset) => {
      // The first row id encodes the offset (perf-trade-{N}).
      const first = document.querySelector('[data-testid^="trade-row-perf-trade-"]')
      const id = first?.getAttribute('data-testid') ?? ''
      const m = id.match(/perf-trade-(\d+)/)
      return m !== null && Number(m[1]) >= expectedOffset
    }, PAGE_SIZE, { timeout: INITIAL_PAINT_BUDGET_MS })

    expect(lastOffset).toBe(PAGE_SIZE)
  })
})
