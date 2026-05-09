import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'
import type { Page, Route } from '@playwright/test'

// ---------------------------------------------------------------------------
// Test data
// ---------------------------------------------------------------------------

const POSITIONS_MULTI_TYPE = [
  {
    bookId: 'port-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    instrumentType: 'CASH_EQUITY',
    displayName: 'Apple Inc.',
    quantity: '100',
    averageCost: { amount: '150.00', currency: 'USD' },
    marketPrice: { amount: '155.00', currency: 'USD' },
    marketValue: { amount: '15500.00', currency: 'USD' },
    unrealizedPnl: { amount: '500.00', currency: 'USD' },
  },
  {
    bookId: 'port-1',
    instrumentId: 'AAPL-OPT',
    assetClass: 'EQUITY',
    instrumentType: 'EQUITY_OPTION',
    displayName: 'AAPL Call 200 Jun26',
    quantity: '10',
    averageCost: { amount: '5.00', currency: 'USD' },
    marketPrice: { amount: '6.50', currency: 'USD' },
    marketValue: { amount: '65.00', currency: 'USD' },
    unrealizedPnl: { amount: '15.00', currency: 'USD' },
  },
  {
    bookId: 'port-1',
    instrumentId: 'US10Y',
    assetClass: 'FIXED_INCOME',
    instrumentType: 'GOVERNMENT_BOND',
    displayName: 'US 10Y Treasury',
    quantity: '500',
    averageCost: { amount: '980.00', currency: 'USD' },
    marketPrice: { amount: '985.00', currency: 'USD' },
    marketValue: { amount: '492500.00', currency: 'USD' },
    unrealizedPnl: { amount: '2500.00', currency: 'USD' },
  },
]

const TRADES_MULTI_TYPE = [
  {
    tradeId: 'trade-eq-1',
    bookId: 'port-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    instrumentType: 'CASH_EQUITY',
    side: 'BUY',
    quantity: '100',
    price: { amount: '150.00', currency: 'USD' },
    tradedAt: '2025-01-15T10:00:00Z',
  },
  {
    tradeId: 'trade-opt-1',
    bookId: 'port-1',
    instrumentId: 'AAPL-OPT',
    assetClass: 'EQUITY',
    instrumentType: 'EQUITY_OPTION',
    side: 'BUY',
    quantity: '10',
    price: { amount: '5.00', currency: 'USD' },
    tradedAt: '2025-01-15T11:00:00Z',
  },
  {
    tradeId: 'trade-bond-1',
    bookId: 'port-1',
    instrumentId: 'US10Y',
    assetClass: 'FIXED_INCOME',
    instrumentType: 'GOVERNMENT_BOND',
    side: 'SELL',
    quantity: '100',
    price: { amount: '980.00', currency: 'USD' },
    tradedAt: '2025-01-15T12:00:00Z',
  },
]

async function setupMultiTypeData(page: Page) {
  await mockAllApiRoutes(page)

  await page.unroute('**/api/v1/books/*/positions')
  await page.route('**/api/v1/books/*/positions', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(POSITIONS_MULTI_TYPE),
    })
  })

  await page.unroute('**/api/v1/books/*/trades/page**')
  await page.unroute('**/api/v1/books/*/trades')
  await page.route('**/api/v1/books/*/trades/page**', (route: Route) => {
    const url = new URL(route.request().url())
    const offset = Number(url.searchParams.get('offset') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 100)
    const items = TRADES_MULTI_TYPE.slice(offset, offset + limit)
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        total: TRADES_MULTI_TYPE.length,
        offset,
        limit,
        hasMore: offset + items.length < TRADES_MULTI_TYPE.length,
      }),
    })
  })
  await page.route('**/api/v1/books/*/trades', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TRADES_MULTI_TYPE),
    })
  })
}

// ---------------------------------------------------------------------------
// 5.1 — PositionGrid instrument type filter
// ---------------------------------------------------------------------------

test.describe('PositionGrid - Instrument Type Filter', () => {
  test.beforeEach(async ({ page }) => {
    await setupMultiTypeData(page)
    await page.goto('/')
    await expect(page.getByTestId('position-row-AAPL')).toBeVisible()
  })

  test('renders instrument type filter dropdown above the grid', async ({ page }) => {
    await expect(page.getByTestId('filter-instrument-type')).toBeVisible()
  })

  test('defaults to showing all positions', async ({ page }) => {
    const rows = page.locator('[data-testid^="position-row-"]')
    await expect(rows).toHaveCount(3)
  })

  test('filters to EQUITY_OPTION shows only option position', async ({ page }) => {
    await page.getByTestId('filter-instrument-type').selectOption('EQUITY_OPTION')

    await expect(page.getByTestId('position-row-AAPL-OPT')).toBeVisible()
    await expect(page.locator('[data-testid^="position-row-"]')).toHaveCount(1)
  })

  test('filters to GOVERNMENT_BOND shows only bond position', async ({ page }) => {
    await page.getByTestId('filter-instrument-type').selectOption('GOVERNMENT_BOND')

    await expect(page.getByTestId('position-row-US10Y')).toBeVisible()
    await expect(page.locator('[data-testid^="position-row-"]')).toHaveCount(1)
  })

  test('resetting filter to All Types restores all positions', async ({ page }) => {
    await page.getByTestId('filter-instrument-type').selectOption('EQUITY_OPTION')
    await expect(page.locator('[data-testid^="position-row-"]')).toHaveCount(1)

    await page.getByTestId('filter-instrument-type').selectOption('')
    await expect(page.locator('[data-testid^="position-row-"]')).toHaveCount(3)
  })

  test('only shows instrument types present in the data', async ({ page }) => {
    const select = page.getByTestId('filter-instrument-type')
    const options = select.locator('option')
    const values = await options.evaluateAll((els) => els.map((el) => (el as HTMLOptionElement).value))
    expect(values).toEqual(['', 'CASH_EQUITY', 'EQUITY_OPTION', 'GOVERNMENT_BOND'])
  })

  test('displays counts next to each filter option', async ({ page }) => {
    const select = page.getByTestId('filter-instrument-type')
    const options = select.locator('option')
    const texts = await options.evaluateAll((els) => els.map((el) => el.textContent))
    expect(texts).toContain('Cash Equity (1)')
    expect(texts).toContain('Equity Option (1)')
    expect(texts).toContain('Government Bond (1)')
  })

  test('renders colored badges in the Type column', async ({ page }) => {
    const aaplRow = page.getByTestId('position-row-AAPL')
    await expect(aaplRow).toContainText('Cash Equity')

    const optRow = page.getByTestId('position-row-AAPL-OPT')
    await expect(optRow).toContainText('Equity Option')

    const bondRow = page.getByTestId('position-row-US10Y')
    await expect(bondRow).toContainText('Government Bond')
  })
})

// ---------------------------------------------------------------------------
// 5.2 — TradeBlotter instrument type filter
// ---------------------------------------------------------------------------

test.describe('TradeBlotter - Instrument Type Filter', () => {
  test.beforeEach(async ({ page }) => {
    await setupMultiTypeData(page)
    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await expect(page.getByTestId('trade-row-trade-eq-1')).toBeVisible()
  })

  test('renders instrument type filter dropdown in blotter toolbar', async ({ page }) => {
    await expect(page.getByTestId('filter-instrument-type')).toBeVisible()
  })

  test('defaults to showing all trades', async ({ page }) => {
    const rows = page.locator('[data-testid^="trade-row-"]')
    await expect(rows).toHaveCount(3)
  })

  test('filters to EQUITY_OPTION shows only option trades', async ({ page }) => {
    await page.getByTestId('filter-instrument-type').selectOption('EQUITY_OPTION')

    await expect(page.getByTestId('trade-row-trade-opt-1')).toBeVisible()
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(1)
  })

  test('combines instrument type filter with side filter', async ({ page }) => {
    await page.getByTestId('filter-instrument-type').selectOption('GOVERNMENT_BOND')
    await page.getByTestId('filter-side').selectOption('SELL')

    await expect(page.getByTestId('trade-row-trade-bond-1')).toBeVisible()
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(1)
  })

  test('resetting instrument type filter restores all trades', async ({ page }) => {
    await page.getByTestId('filter-instrument-type').selectOption('CASH_EQUITY')
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(1)

    await page.getByTestId('filter-instrument-type').selectOption('')
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(3)
  })

  test('renders colored badges in the Type column of trade blotter', async ({ page }) => {
    await expect(page.getByTestId('trade-row-trade-eq-1').getByText('Cash Equity')).toBeVisible()
    await expect(page.getByTestId('trade-row-trade-opt-1').getByText('Equity Option')).toBeVisible()
    await expect(page.getByTestId('trade-row-trade-bond-1').getByText('Government Bond')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// 5.5 — InstrumentTypeBadge colors
// ---------------------------------------------------------------------------

test.describe('InstrumentTypeBadge - Visual Colors', () => {
  test.beforeEach(async ({ page }) => {
    await setupMultiTypeData(page)
    await page.goto('/')
    await expect(page.getByTestId('position-row-AAPL')).toBeVisible()
  })

  test('CASH_EQUITY badge has blue styling', async ({ page }) => {
    const aaplRow = page.getByTestId('position-row-AAPL')
    const badge = aaplRow.locator('span.bg-blue-100')
    await expect(badge).toBeVisible()
    await expect(badge).toHaveText('Cash Equity')
  })

  test('EQUITY_OPTION badge has amber styling', async ({ page }) => {
    const optRow = page.getByTestId('position-row-AAPL-OPT')
    const badge = optRow.locator('span.bg-amber-100')
    await expect(badge).toBeVisible()
    await expect(badge).toHaveText('Equity Option')
  })

  test('GOVERNMENT_BOND badge has green styling', async ({ page }) => {
    const bondRow = page.getByTestId('position-row-US10Y')
    const badge = bondRow.locator('span.bg-green-100')
    await expect(badge).toBeVisible()
    await expect(badge).toHaveText('Government Bond')
  })
})
