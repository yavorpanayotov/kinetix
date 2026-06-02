import { test, expect, type Page } from '@playwright/test'
import { mockKeycloakAuth } from './fixtures'

// ---------------------------------------------------------------------------
// Positions scope banner — `docs/plans/ui-trader-review.md` P1 §11/§12
// ---------------------------------------------------------------------------
//
// The trader review observed that the Positions tab shows "Firm Summary" as a
// section header even when the position rows are filtered to a single book —
// e.g. 25 rows all from `balanced-income` under a heading that implies firm
// scope. That mislabels the data and erodes trust.
//
// This spec drives the fix: when a book is selected (`hierarchy.selection
// .level === 'book'`), the Positions tab must surface an explicit scope
// indicator — either a "book = <bookId>" badge near the summary, or a
// relabelled heading like "Book Summary — balanced-income". When the user
// clears the filter back to the firm level, the indicator must drop the book
// reference and the heading must read "Firm Summary" again.

const TEST_BOOKS = [
  { bookId: 'balanced-income' },
  { bookId: 'macro-hedge' },
]

const TEST_DIVISIONS = [
  { id: 'div-1', name: 'Multi-Strategy', description: 'Multi-Strategy division', deskCount: 1 },
]

const TEST_DESKS = [
  { id: 'desk-1', name: 'Income', divisionId: 'div-1', deskHead: 'Alice', bookCount: 2 },
]

const FIRM_SUMMARY = {
  bookId: 'firm',
  baseCurrency: 'USD',
  totalNav: { amount: '5608745634.54', currency: 'USD' },
  totalUnrealizedPnl: { amount: '47524023.67', currency: 'USD' },
  currencyBreakdown: [],
}

const BOOK_SUMMARY_BALANCED_INCOME = {
  bookId: 'balanced-income',
  baseCurrency: 'USD',
  totalNav: { amount: '21100000.00', currency: 'USD' },
  totalUnrealizedPnl: { amount: '500000.00', currency: 'USD' },
  currencyBreakdown: [],
}

const TEST_POSITIONS = [
  {
    bookId: 'balanced-income',
    instrumentId: 'AAPL',
    displayName: 'Apple Inc',
    instrumentType: 'STOCK',
    assetClass: 'EQUITY',
    quantity: '100',
    averageCost: { amount: '150.00', currency: 'USD' },
    marketPrice: { amount: '155.00', currency: 'USD' },
    marketValue: { amount: '15500.00', currency: 'USD' },
    unrealizedPnl: { amount: '500.00', currency: 'USD' },
  },
]

async function mockBaseRoutes(page: Page): Promise<void> {
  await page.route('**/api/v1/divisions', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_DIVISIONS),
    })
  })

  await page.route('**/api/v1/divisions/*/desks', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_DESKS),
    })
  })

  await page.route('**/api/v1/divisions/*/summary*', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(FIRM_SUMMARY),
    })
  })

  await page.route('**/api/v1/desks/*/summary*', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(FIRM_SUMMARY),
    })
  })

  await page.route('**/api/v1/desks', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_DESKS),
    })
  })

  await page.route('**/api/v1/books', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_BOOKS),
    })
  })

  await page.route('**/api/v1/books/*/positions', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_POSITIONS),
    })
  })

  await page.route('**/api/v1/books/*/trades/page**', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], total: 0, offset: 0, limit: 100, hasMore: false }),
    })
  })

  await page.route('**/api/v1/books/*/trades', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    })
  })

  await page.route('**/api/v1/firm/summary*', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(FIRM_SUMMARY),
    })
  })

  await page.route('**/api/v1/books/balanced-income/summary*', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(BOOK_SUMMARY_BALANCED_INCOME),
    })
  })

  // Fallback book summary for any other bookId
  await page.route('**/api/v1/books/*/summary*', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(BOOK_SUMMARY_BALANCED_INCOME),
    })
  })

  await page.route('**/api/v1/data-quality/status', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ overall: 'OK', checks: [] }),
    })
  })

  await page.route('**/api/v1/notifications/rules', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })

  await page.route('**/api/v1/notifications/alerts*', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })

  await page.route('**/api/v1/system/health', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'HEALTHY', services: [] }),
    })
  })

  await page.route('**/api/v1/risk/**', (route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })

  await page.route('**/api/v1/positions/*/notes*', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })
}

test.describe('Positions scope banner', () => {
  test.beforeEach(async ({ page }) => {
    await mockKeycloakAuth(page)
    await mockBaseRoutes(page)
  })

  test('shows no book-scoped badge at firm level', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="book-summary-card"]')

    // The summary heading should read "Firm Summary" — the default scope.
    await expect(page.getByTestId('book-summary-card')).toContainText('Firm Summary')

    // The book-scoped badge must NOT be rendered when scope is firm-wide.
    await expect(page.getByTestId('positions-scope-banner')).toHaveCount(0)
  })

  test('shows an explicit book-scope badge after a book is selected', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="book-summary-card"]')

    // Drill Firm → Division → Desk → Book via the hierarchy selector.
    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.waitForSelector('[data-testid="hierarchy-division-div-1"]')
    await page.getByTestId('hierarchy-division-div-1').click()

    await page.waitForSelector('[data-testid="hierarchy-desk-desk-1"]')
    await page.getByTestId('hierarchy-desk-desk-1').click()

    await page.waitForSelector('[data-testid="hierarchy-book-balanced-income"]')
    await page.getByTestId('hierarchy-book-balanced-income').click()

    // The page now scopes to `balanced-income`. The scope banner must spell
    // that out so the trader knows the rows are filtered, not firm-wide.
    const banner = page.getByTestId('positions-scope-banner')
    await expect(banner).toBeVisible()
    await expect(banner).toContainText('book = balanced-income')
  })

  test('drops the book scope banner when navigating back to firm', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="book-summary-card"]')

    // First drill into a book so the banner appears.
    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.waitForSelector('[data-testid="hierarchy-division-div-1"]')
    await page.getByTestId('hierarchy-division-div-1').click()

    await page.waitForSelector('[data-testid="hierarchy-desk-desk-1"]')
    await page.getByTestId('hierarchy-desk-desk-1').click()

    await page.waitForSelector('[data-testid="hierarchy-book-balanced-income"]')
    await page.getByTestId('hierarchy-book-balanced-income').click()

    await expect(page.getByTestId('positions-scope-banner')).toBeVisible()

    // Navigate back to the firm scope.
    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.waitForSelector('[data-testid="hierarchy-firm-option"]')
    await page.getByTestId('hierarchy-firm-option').click()

    // Banner is gone, heading is back to "Firm Summary".
    await expect(page.getByTestId('positions-scope-banner')).toHaveCount(0)
    await expect(page.getByTestId('book-summary-card')).toContainText('Firm Summary')
  })
})
