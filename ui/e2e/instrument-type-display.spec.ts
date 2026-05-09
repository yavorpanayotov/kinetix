import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'
import type { Page, Route } from '@playwright/test'

const POSITIONS_WITH_TYPES = [
  {
    bookId: 'port-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    quantity: '100',
    averageCost: { amount: '150.00', currency: 'USD' },
    marketPrice: { amount: '155.00', currency: 'USD' },
    marketValue: { amount: '15500.00', currency: 'USD' },
    unrealizedPnl: { amount: '500.00', currency: 'USD' },
    instrumentType: 'CASH_EQUITY',
    displayName: 'Apple Inc.',
  },
  {
    bookId: 'port-1',
    instrumentId: 'AAPL-C-200-20260620',
    assetClass: 'EQUITY',
    quantity: '10',
    averageCost: { amount: '5.50', currency: 'USD' },
    marketPrice: { amount: '6.20', currency: 'USD' },
    marketValue: { amount: '62.00', currency: 'USD' },
    unrealizedPnl: { amount: '7.00', currency: 'USD' },
    instrumentType: 'EQUITY_OPTION',
    displayName: 'AAPL Call 200 Jun2026',
  },
  {
    bookId: 'port-1',
    instrumentId: 'US10Y',
    assetClass: 'FIXED_INCOME',
    quantity: '500',
    averageCost: { amount: '980.00', currency: 'USD' },
    marketPrice: { amount: '985.00', currency: 'USD' },
    marketValue: { amount: '492500.00', currency: 'USD' },
    unrealizedPnl: { amount: '2500.00', currency: 'USD' },
    instrumentType: 'GOVERNMENT_BOND',
    displayName: 'US 10Y Treasury',
  },
]

const TRADES_WITH_TYPES = [
  {
    tradeId: 'trade-1',
    bookId: 'port-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '100',
    price: { amount: '150.00', currency: 'USD' },
    tradedAt: '2025-01-15T10:30:00Z',
    instrumentType: 'CASH_EQUITY',
  },
  {
    tradeId: 'trade-2',
    bookId: 'port-1',
    instrumentId: 'AAPL-C-200-20260620',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '10',
    price: { amount: '5.50', currency: 'USD' },
    tradedAt: '2025-01-15T11:00:00Z',
    instrumentType: 'EQUITY_OPTION',
  },
]

async function setupWithInstrumentTypes(page: Page) {
  await mockAllApiRoutes(page)

  // Override positions with instrument type data
  await page.unroute('**/api/v1/books/*/positions')
  await page.route('**/api/v1/books/*/positions', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(POSITIONS_WITH_TYPES),
    })
  })

  // Override trades with instrument type data
  await page.unroute('**/api/v1/books/*/trades/page**')
  await page.unroute('**/api/v1/books/*/trades')
  await page.route('**/api/v1/books/*/trades/page**', (route: Route) => {
    const url = new URL(route.request().url())
    const offset = Number(url.searchParams.get('offset') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 100)
    const items = TRADES_WITH_TYPES.slice(offset, offset + limit)
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        total: TRADES_WITH_TYPES.length,
        offset,
        limit,
        hasMore: offset + items.length < TRADES_WITH_TYPES.length,
      }),
    })
  })
  await page.route('**/api/v1/books/*/trades', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TRADES_WITH_TYPES),
    })
  })
}

test.describe('Position grid instrument type columns', () => {
  test('displays instrument type and display name columns', async ({ page }) => {
    await setupWithInstrumentTypes(page)
    await page.goto('/')

    // Wait for positions to load
    const firstRow = page.getByTestId('position-row-AAPL')
    await expect(firstRow).toBeVisible()

    // Verify instrument type is shown
    await expect(firstRow).toContainText('Cash Equity')
    await expect(firstRow).toContainText('Apple Inc.')
  })

  test('displays option type for equity option', async ({ page }) => {
    await setupWithInstrumentTypes(page)
    await page.goto('/')

    const optionRow = page.getByTestId('position-row-AAPL-C-200-20260620')
    await expect(optionRow).toBeVisible()
    await expect(optionRow).toContainText('Equity Option')
    await expect(optionRow).toContainText('AAPL Call 200 Jun2026')
  })

  test('displays bond type for government bond', async ({ page }) => {
    await setupWithInstrumentTypes(page)
    await page.goto('/')

    const bondRow = page.getByTestId('position-row-US10Y')
    await expect(bondRow).toBeVisible()
    await expect(bondRow).toContainText('Government Bond')
    await expect(bondRow).toContainText('US 10Y Treasury')
  })

  test('column visibility toggle works for new columns', async ({ page }) => {
    await setupWithInstrumentTypes(page)
    await page.goto('/')

    // Wait for data
    await expect(page.getByTestId('position-row-AAPL')).toBeVisible()

    // Open column settings
    await page.getByTestId('column-settings-button').click()

    // Find the Type checkbox and toggle it off
    const typeCheckbox = page.getByRole('checkbox', { name: 'Type' })
    await expect(typeCheckbox).toBeVisible()
    await typeCheckbox.click()

    // Verify Type column is hidden
    const aapl = page.getByTestId('position-row-AAPL')
    await expect(aapl).not.toContainText('Cash Equity')
    // But Name column should still be visible
    await expect(aapl).toContainText('Apple Inc.')
  })
})

test.describe('Trade blotter instrument type column', () => {
  test('displays instrument type in trade blotter', async ({ page }) => {
    await setupWithInstrumentTypes(page)
    await page.goto('/')

    // Click on the Trades tab
    const tradesTab = page.getByRole('tab', { name: /Trades/i })
    await tradesTab.click()

    // Wait for trades to load
    const firstTrade = page.getByTestId('trade-row-trade-1')
    await expect(firstTrade).toBeVisible()
    await expect(firstTrade).toContainText('Cash Equity')
  })

  test('shows option type for option trades', async ({ page }) => {
    await setupWithInstrumentTypes(page)
    await page.goto('/')

    const tradesTab = page.getByRole('tab', { name: /Trades/i })
    await tradesTab.click()

    const optionTrade = page.getByTestId('trade-row-trade-2')
    await expect(optionTrade).toBeVisible()
    await expect(optionTrade).toContainText('Equity Option')
  })
})
