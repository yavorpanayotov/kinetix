import { test, expect, type Page, type Route } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_POSITION_RISK_FULL,
  TEST_JOB_HISTORY,
} from './fixtures'

/**
 * E2E coverage for the Counterparty Exposure tile (kx-i72).
 *
 * The tile lives inside the Risk tab's "Position & Factor Risk" section and
 * aggregates trades by counterpartyId so a credit-risk reviewer can answer
 * "where is my top exposure" without leaving the Risk view.
 *
 * We override the default trades-page handler with a fixture that has 4
 * distinct counterparties so the top-10 list is non-trivial.
 */

const TRADES_WITH_COUNTERPARTIES = [
  { tradeId: 't-gs-1', bookId: 'port-1', instrumentId: 'AAPL', assetClass: 'EQUITY', side: 'BUY',  quantity: '100', price: { amount: '150', currency: 'USD' }, tradedAt: '2026-05-26T10:00:00Z', counterpartyId: 'CP-GS' },
  { tradeId: 't-gs-2', bookId: 'port-1', instrumentId: 'AAPL', assetClass: 'EQUITY', side: 'BUY',  quantity: '200', price: { amount: '150', currency: 'USD' }, tradedAt: '2026-05-26T10:01:00Z', counterpartyId: 'CP-GS' },
  { tradeId: 't-jpm-1', bookId: 'port-1', instrumentId: 'MSFT', assetClass: 'EQUITY', side: 'BUY', quantity: '50', price: { amount: '300', currency: 'USD' }, tradedAt: '2026-05-26T10:02:00Z', counterpartyId: 'CP-JPM' },
  { tradeId: 't-barc-1', bookId: 'port-1', instrumentId: 'GOOGL', assetClass: 'EQUITY', side: 'SELL', quantity: '10', price: { amount: '150', currency: 'USD' }, tradedAt: '2026-05-26T10:03:00Z', counterpartyId: 'CP-BARC' },
  { tradeId: 't-citi-1', bookId: 'port-1', instrumentId: 'AMZN', assetClass: 'EQUITY', side: 'BUY', quantity: '5', price: { amount: '180', currency: 'USD' }, tradedAt: '2026-05-26T10:04:00Z', counterpartyId: 'CP-CITI' },
]

async function mockTradesPageWithCounterparties(page: Page) {
  await page.unroute('**/api/v1/books/*/trades/page**')
  await page.route('**/api/v1/books/*/trades/page**', (route: Route) => {
    const url = new URL(route.request().url())
    const offset = Number(url.searchParams.get('offset') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 100)
    const items = TRADES_WITH_COUNTERPARTIES.slice(offset, offset + limit)
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        total: TRADES_WITH_COUNTERPARTIES.length,
        offset,
        limit,
        hasMore: false,
      }),
    })
  })
}

test.describe('Counterparty Exposure tile on Risk view', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })
    await mockTradesPageWithCounterparties(page)
  })

  test('tile is visible on the Risk tab and lists the seeded counterparties', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="counterparty-exposure-tile"]')

    await expect(page.getByTestId('counterparty-exposure-tile')).toBeVisible()
    await expect(page.getByTestId('counterparty-exposure-row-CP-GS')).toBeVisible()
    await expect(page.getByTestId('counterparty-exposure-row-CP-JPM')).toBeVisible()
    await expect(page.getByTestId('counterparty-exposure-row-CP-BARC')).toBeVisible()
    await expect(page.getByTestId('counterparty-exposure-row-CP-CITI')).toBeVisible()
  })

  test('top row by absolute net notional is CP-GS (45,000) and the bar is non-zero', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="counterparty-exposure-tile"]')

    // CP-GS = 100*150 + 200*150 = 45,000 — the largest absolute notional.
    const top = page.locator('[data-testid^="counterparty-exposure-row-"]').first()
    await expect(top).toHaveAttribute('data-testid', 'counterparty-exposure-row-CP-GS')

    // Bar width is rendered inline; just ensure the element exists with a width style.
    const bar = page.getByTestId('counterparty-exposure-bar-CP-GS')
    await expect(bar).toBeVisible()
  })
})

test.describe('Counterparty Exposure tile — empty state', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })
    // Override the default trades route with an empty page so the tile
    // renders the explanatory empty state instead of a list.
    await page.unroute('**/api/v1/books/*/trades/page**')
    await page.route('**/api/v1/books/*/trades/page**', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [], total: 0, offset: 0, limit: 100, hasMore: false }),
      })
    })
  })

  test('shows the empty-state copy when no trades carry a counterparty', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="counterparty-exposure-tile"]')

    await expect(page.getByTestId('counterparty-exposure-empty')).toBeVisible()
  })
})
