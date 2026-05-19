import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_JOB_HISTORY,
} from './fixtures'

// ---------------------------------------------------------------------------
// Test data
// ---------------------------------------------------------------------------

const TEST_CROSS_BOOK_VAR_RESULT = {
  portfolioGroupId: 'firm',
  bookIds: ['port-1', 'port-2'],
  calculationType: 'PARAMETRIC',
  confidenceLevel: 'CL_95',
  varValue: '200000.00',
  expectedShortfall: '300000.00',
  componentBreakdown: [
    { assetClass: 'EQUITY', varContribution: '120000.00', percentageOfTotal: '60.00' },
    { assetClass: 'FIXED_INCOME', varContribution: '80000.00', percentageOfTotal: '40.00' },
  ],
  bookContributions: [
    { bookId: 'port-1', varContribution: '130000.00', percentageOfTotal: '65.00', standaloneVar: '150000.00', diversificationBenefit: '20000.00', marginalVar: '0.866667', incrementalVar: '140000.00' },
    { bookId: 'port-2', varContribution: '70000.00', percentageOfTotal: '35.00', standaloneVar: '100000.00', diversificationBenefit: '30000.00', marginalVar: '0.700000', incrementalVar: '80000.00' },
  ],
  totalStandaloneVar: '250000.00',
  diversificationBenefit: '50000.00',
  calculatedAt: '2025-01-15T12:00:00Z',
}

const TEST_BOOKS_MULTI = [
  { bookId: 'port-1' },
  { bookId: 'port-2' },
]

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Overrides the books endpoint to return multiple books so that
 * effectiveBookIds has more than one entry at firm level.
 * Must be called AFTER mockAllApiRoutes.
 */
async function mockMultipleBooks(page: Page): Promise<void> {
  await page.unroute('**/api/v1/books')
  await page.route('**/api/v1/books', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_BOOKS_MULTI),
    })
  })
}

/**
 * Mocks the cross-book VaR GET and POST endpoints.
 * Must be called AFTER mockRiskTabRoutes (which registers the risk catch-all).
 */
async function mockCrossBookVaR(
  page: Page,
  result: object | null,
  status = 200,
): Promise<void> {
  // POST endpoint: /api/v1/risk/var/cross-book
  await page.route('**/api/v1/risk/var/cross-book', (route: Route) => {
    if (route.request().method() === 'POST') {
      route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(result),
      })
    } else {
      route.fallback()
    }
  })

  // GET endpoint: /api/v1/risk/var/cross-book/{groupId}
  await page.route('**/api/v1/risk/var/cross-book/*', (route: Route) => {
    route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(result),
    })
  })
}

/** Navigate to the Risk tab. The app starts at firm level which is aggregatedView=true. */
async function goToRiskTab(page: Page) {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
}

// ---------------------------------------------------------------------------
// Cross-Book VaR Tests
// ---------------------------------------------------------------------------

test.describe('Cross-Book VaR', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockMultipleBooks(page)
  })

  test('displays diversification benefit when cross-book result is available', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
    await mockCrossBookVaR(page, TEST_CROSS_BOOK_VAR_RESULT)

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // Diversification summary should be visible
    await expect(page.getByTestId('diversification-summary')).toBeVisible()

    // Sum of books: $250,000.00 (rendered compact as $250K with full value in title)
    await expect(page.getByTestId('sum-of-books-var')).toHaveAttribute('title', '$250,000.00')

    // Diversification benefit: -$50,000.00 (20.0%) — rendered compact as -$50K with full value in title
    await expect(page.getByTestId('diversification-benefit-value')).toHaveAttribute('title', '$50,000.00')
    await expect(page.getByTestId('diversification-benefit-value')).toContainText('20.0%')
  })

  test('displays book contribution table with per-book rows', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
    await mockCrossBookVaR(page, TEST_CROSS_BOOK_VAR_RESULT)

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="book-contribution-table"]')

    // Table should be visible
    await expect(page.getByTestId('book-contribution-table')).toBeVisible()

    // port-1 row
    const port1Row = page.getByTestId('book-row-port-1')
    await expect(port1Row).toBeVisible()
    await expect(port1Row).toContainText('port-1')
    await expect(port1Row).toContainText('$130,000.00')
    await expect(port1Row).toContainText('65.0%')
    await expect(port1Row).toContainText('$150,000.00')
    await expect(port1Row).toContainText('$20,000.00')

    // port-2 row
    const port2Row = page.getByTestId('book-row-port-2')
    await expect(port2Row).toBeVisible()
    await expect(port2Row).toContainText('port-2')
    await expect(port2Row).toContainText('$70,000.00')
    await expect(port2Row).toContainText('35.0%')
    await expect(port2Row).toContainText('$100,000.00')
    await expect(port2Row).toContainText('$30,000.00')
  })

  test('displays correlation heatmap in aggregated view', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
    await mockCrossBookVaR(page, TEST_CROSS_BOOK_VAR_RESULT)

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    await expect(page.getByTestId('correlation-heatmap')).toBeVisible()
  })

  test('hides warning banner when cross-book result is available', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
    await mockCrossBookVaR(page, TEST_CROSS_BOOK_VAR_RESULT)

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // The aggregated-var-note banner should NOT be visible when cross-book data is present
    await expect(page.getByTestId('aggregated-var-note')).not.toBeVisible()
  })

  test('shows warning banner when no cross-book result', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
    // Return 404 for cross-book endpoints (no data available)
    await mockCrossBookVaR(page, null, 404)

    await goToRiskTab(page)

    // Wait for the amber banner to appear
    await page.waitForSelector('[data-testid="aggregated-var-note"]')
    await expect(page.getByTestId('aggregated-var-note')).toBeVisible()
    await expect(page.getByTestId('aggregated-var-note')).toContainText('Recalculate All')
  })

  test('refresh triggers cross-book VaR calculation', async ({ page }) => {
    const postRequests: { url: string; body: string }[] = []

    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
    await mockCrossBookVaR(page, TEST_CROSS_BOOK_VAR_RESULT)

    // Override the POST endpoint to capture the request body
    await page.unroute('**/api/v1/risk/var/cross-book')
    await page.route('**/api/v1/risk/var/cross-book', (route: Route) => {
      if (route.request().method() === 'POST') {
        postRequests.push({
          url: route.request().url(),
          body: route.request().postData() ?? '',
        })
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(TEST_CROSS_BOOK_VAR_RESULT),
        })
      } else {
        route.fallback()
      }
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // Click refresh button
    await page.getByTestId('var-recalculate').click()

    // Wait for the POST request to be captured
    await expect(async () => {
      expect(postRequests.length).toBeGreaterThan(0)
    }).toPass({ timeout: 5000 })

    // Verify the POST body contains bookIds
    const body = JSON.parse(postRequests[0].body)
    expect(body.bookIds).toContain('port-1')
    expect(body.bookIds).toContain('port-2')
    expect(body.portfolioGroupId).toBe('firm')
  })
})
