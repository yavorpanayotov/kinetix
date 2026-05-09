import { test, expect, type Page } from '@playwright/test'
import { mockKeycloakAuth } from './fixtures'

const TEST_BOOKS = [
  { bookId: 'book-1' },
  { bookId: 'book-2' },
  { bookId: 'book-3' },
]

const TEST_DIVISIONS = [
  { id: 'div-1', name: 'Equities', description: 'Equities division', deskCount: 2 },
  { id: 'div-2', name: 'Fixed Income', description: 'Fixed Income division', deskCount: 1 },
]

const TEST_DESKS = [
  { id: 'desk-1', name: 'EU Equities', divisionId: 'div-1', deskHead: 'Alice', bookCount: 2 },
  { id: 'desk-2', name: 'US Equities', divisionId: 'div-1', deskHead: 'Bob', bookCount: 1 },
]

const SUMMARY = {
  bookId: 'firm',
  baseCurrency: 'USD',
  totalNav: { amount: '5000000.00', currency: 'USD' },
  totalUnrealizedPnl: { amount: '250000.00', currency: 'USD' },
  currencyBreakdown: [],
}

const TEST_POSITIONS = [
  {
    bookId: 'book-1',
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
  {
    bookId: 'book-2',
    instrumentId: 'GOOGL',
    displayName: 'Alphabet Inc',
    instrumentType: 'STOCK',
    assetClass: 'EQUITY',
    quantity: '50',
    averageCost: { amount: '2800.00', currency: 'USD' },
    marketPrice: { amount: '2850.00', currency: 'USD' },
    marketValue: { amount: '142500.00', currency: 'USD' },
    unrealizedPnl: { amount: '2500.00', currency: 'USD' },
  },
]

async function mockHierarchyRoutes(page: Page): Promise<void> {
  // Books
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
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })

  await page.route('**/api/v1/books/*/summary*', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...SUMMARY, bookId: 'book-1' }),
    })
  })

  // Hierarchy
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
      body: JSON.stringify({ ...SUMMARY, bookId: 'div-1' }),
    })
  })

  await page.route('**/api/v1/desks/*/summary*', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...SUMMARY, bookId: 'desk-1' }),
    })
  })

  await page.route('**/api/v1/firm/summary*', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(SUMMARY),
    })
  })

  // Other standard routes
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

  // Desks for division desks endpoint
  await page.route('**/api/v1/desks/*/books', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_BOOKS),
    })
  })
}

test.describe('Hierarchy Navigation', () => {
  test.beforeEach(async ({ page }) => {
    await mockKeycloakAuth(page)
    await mockHierarchyRoutes(page)
  })

  test('shows hierarchy selector toggle in header', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="hierarchy-selector"]')

    await expect(page.getByTestId('hierarchy-selector-toggle')).toBeVisible()
  })

  test('breadcrumb shows "Firm" at initial firm level', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="hierarchy-selector"]')

    const toggle = page.getByTestId('hierarchy-selector-toggle')
    await expect(toggle).toContainText('Firm')
  })

  test('clicking toggle opens panel showing divisions', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="hierarchy-selector"]')

    await page.getByTestId('hierarchy-selector-toggle').click()

    await expect(page.getByTestId('hierarchy-panel')).toBeVisible()
    await expect(page.getByTestId('hierarchy-division-div-1')).toBeVisible()
    await expect(page.getByTestId('hierarchy-division-div-2')).toBeVisible()
  })

  test('navigate from Firm to Division shows breadcrumb update', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="hierarchy-selector"]')

    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.getByTestId('hierarchy-division-div-1').click()

    // Toggle button should now show division breadcrumb
    await expect(page.getByTestId('hierarchy-selector-toggle')).toContainText('Equities')
  })

  test('navigate from Division to Desk shows desks in panel', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="hierarchy-selector"]')

    // Select division — panel stays open after clicking
    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.getByTestId('hierarchy-division-div-1').click()

    // Desks load asynchronously; wait for them inside the still-open panel
    await page.waitForSelector('[data-testid="hierarchy-desk-desk-1"]')

    await expect(page.getByTestId('hierarchy-desk-desk-1')).toBeVisible()
    await expect(page.getByTestId('hierarchy-desk-desk-2')).toBeVisible()
  })

  test('navigate from Desk to Book shows books in panel', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="hierarchy-selector"]')

    // Navigate to division — panel stays open
    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.getByTestId('hierarchy-division-div-1').click()

    // Wait for desks to load, then navigate to desk — panel stays open
    await page.waitForSelector('[data-testid="hierarchy-desk-desk-1"]')
    await page.getByTestId('hierarchy-desk-desk-1').click()

    // Books appear inside the still-open panel
    await expect(page.getByTestId('hierarchy-book-book-1')).toBeVisible()
  })

  test('breadcrumb shows correct trail at desk level', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="hierarchy-selector"]')

    // Navigate to division — panel stays open
    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.getByTestId('hierarchy-division-div-1').click()

    // Wait for desks, then navigate to desk — panel stays open
    await page.waitForSelector('[data-testid="hierarchy-desk-desk-1"]')
    await page.getByTestId('hierarchy-desk-desk-1').click()

    // Panel is still open; breadcrumb should be visible
    await expect(page.getByTestId('hierarchy-panel')).toBeVisible()

    // Breadcrumb inside panel shows full trail
    await expect(page.getByTestId('breadcrumb-firm')).toBeVisible()
    await expect(page.getByTestId('breadcrumb-division')).toHaveText('Equities')
    await expect(page.getByTestId('breadcrumb-desk')).toHaveText('EU Equities')
  })

  test('clicking Firm breadcrumb navigates back to firm level', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="hierarchy-selector"]')

    // Navigate to division — panel stays open
    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.getByTestId('hierarchy-division-div-1').click()

    // Breadcrumb is visible in the still-open panel; click Firm to go back
    await page.waitForSelector('[data-testid="breadcrumb-firm"]')
    await page.getByTestId('breadcrumb-firm').click()

    // Toggle should now show just "Firm"
    await expect(page.getByTestId('hierarchy-selector-toggle')).toContainText('Firm')
    // Open panel to verify divisions are shown at firm level
    const panelVisible = await page.getByTestId('hierarchy-panel').isVisible().catch(() => false)
    if (!panelVisible) {
      await page.getByTestId('hierarchy-selector-toggle').click()
    }
    await expect(page.getByTestId('hierarchy-division-div-1')).toBeVisible()
  })

  test('summary card shows "Firm Summary" at firm level', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="book-summary-card"]')

    await expect(page.getByTestId('book-summary-card')).toContainText('Firm Summary')
  })

  test('position grid shows Book column at desk level', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="hierarchy-selector"]')

    // Navigate to division — panel stays open
    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.getByTestId('hierarchy-division-div-1').click()

    // Wait for desks to load, then click desk
    await page.waitForSelector('[data-testid="hierarchy-desk-desk-1"]')
    await page.getByTestId('hierarchy-desk-desk-1').click()

    // Wait for positions to load
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    // Book column header should be visible
    await expect(page.locator('th', { hasText: 'Book' })).toBeVisible()
  })
})
