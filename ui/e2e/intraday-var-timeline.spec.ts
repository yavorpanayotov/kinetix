import { test, expect, type Route } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockIntradayVaRTimelineRoutes,
  TEST_INTRADAY_VAR_POINTS,
  TEST_INTRADAY_TRADE_ANNOTATIONS,
} from './fixtures'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function goToIntradayVaRTab(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
  await page.waitForSelector('[data-testid="risk-subtab-intraday"]')
  await page.getByTestId('risk-subtab-intraday').click()
  await page.waitForSelector('[data-testid="intraday-var-panel"]')
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Intraday VaR timeline tab', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('Intraday sub-tab is visible in the Risk tab', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-risk').click()

    await expect(page.getByTestId('risk-subtab-intraday')).toBeVisible()
  })

  test('clicking Intraday sub-tab shows the intraday panel', async ({ page }) => {
    await goToIntradayVaRTab(page)

    await expect(page.getByTestId('intraday-var-panel')).toBeVisible()
  })

  test('shows empty state when no VaR points are available', async ({ page }) => {
    // Default mockAllApiRoutes returns empty varPoints
    await goToIntradayVaRTab(page)

    await expect(page.getByTestId('intraday-var-chart')).toBeVisible()
    await expect(page.getByTestId('intraday-var-chart-empty')).toBeVisible()
    await expect(page.getByTestId('intraday-var-chart-empty')).toContainText('No intraday VaR data')
  })

  test('renders SVG chart when VaR points are loaded', async ({ page }) => {
    await mockIntradayVaRTimelineRoutes(page, 'port-1', TEST_INTRADAY_VAR_POINTS)

    await goToIntradayVaRTab(page)

    const chart = page.getByTestId('intraday-var-chart')
    await expect(chart.locator('svg')).toBeVisible()
  })

  test('displays latest VaR value in chart header', async ({ page }) => {
    await mockIntradayVaRTimelineRoutes(page, 'port-1', TEST_INTRADAY_VAR_POINTS)

    await goToIntradayVaRTab(page)

    await expect(page.getByTestId('intraday-var-latest')).toBeVisible()
    // Latest varValue is 12500.0, formatted as $12,500.00
    await expect(page.getByTestId('intraday-var-latest')).toContainText('12,500')
  })

  test('renders trade annotation markers when annotations are present', async ({ page }) => {
    await mockIntradayVaRTimelineRoutes(
      page,
      'port-1',
      TEST_INTRADAY_VAR_POINTS,
      TEST_INTRADAY_TRADE_ANNOTATIONS,
    )

    await goToIntradayVaRTab(page)

    const chart = page.getByTestId('intraday-var-chart')
    // Each annotation renders a polygon with data-testid="trade-marker"
    const markers = chart.locator('[data-testid="trade-marker"]')
    await expect(markers).toHaveCount(1)
  })

  test('switching away from and back to Intraday sub-tab preserves state', async ({ page }) => {
    await mockIntradayVaRTimelineRoutes(page, 'port-1', TEST_INTRADAY_VAR_POINTS)

    await goToIntradayVaRTab(page)

    // Switch to Dashboard
    await page.getByTestId('risk-subtab-dashboard').click()
    await expect(page.getByTestId('intraday-var-panel')).not.toBeVisible()

    // Switch back to Intraday
    await page.getByTestId('risk-subtab-intraday').click()
    await expect(page.getByTestId('intraday-var-panel')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Fallback tests: when today has no VaR points, the chart shows the most
// recent day's data and displays a "Last session" indicator.
// ---------------------------------------------------------------------------

const PAST_VAR_SESSION_DATE = '2026-03-22'
const PAST_VAR_SESSION_POINTS = [
  {
    timestamp: `${PAST_VAR_SESSION_DATE}T09:00:00Z`,
    varValue: 9500.0,
    expectedShortfall: 12000.0,
    delta: 0.55,
    gamma: null,
    vega: null,
  },
  {
    timestamp: `${PAST_VAR_SESSION_DATE}T10:00:00Z`,
    varValue: 11000.0,
    expectedShortfall: 13500.0,
    delta: 0.60,
    gamma: null,
    vega: null,
  },
]

test.describe('Intraday VaR chart — last-session fallback', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders past session data and shows "Last session" indicator when today has no VaR points', async ({
    page,
  }) => {
    // Route the intraday VaR endpoint so:
    //   - today's single-day window returns empty
    //   - the 7-day lookback window returns past session data
    await page.unroute('**/api/v1/risk/var/*/intraday*')
    await page.route('**/api/v1/risk/var/*/intraday*', (route: Route) => {
      const url = new URL(route.request().url())
      const from = url.searchParams.get('from') ?? ''
      const to = url.searchParams.get('to') ?? ''
      const fromDate = from.slice(0, 10)
      const toDate = to.slice(0, 10)
      const isSingleDay = fromDate === toDate
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          bookId: 'port-1',
          varPoints: isSingleDay ? [] : PAST_VAR_SESSION_POINTS,
          tradeAnnotations: [],
        }),
      })
    })

    await goToIntradayVaRTab(page)

    // Chart should render (SVG present) because past session data was loaded.
    const chart = page.getByTestId('intraday-var-chart')
    await expect(chart.locator('svg')).toBeVisible({ timeout: 5000 })

    // "Last session" indicator must be visible with the past session date.
    const indicator = page.getByTestId('intraday-var-last-session')
    await expect(indicator).toBeVisible()
    await expect(indicator).toContainText(PAST_VAR_SESSION_DATE)
  })

  test('does NOT show "Last session" indicator when today has VaR points', async ({
    page,
  }) => {
    // Today has data → no fallback should trigger.
    await mockIntradayVaRTimelineRoutes(page, 'port-1', TEST_INTRADAY_VAR_POINTS)

    await goToIntradayVaRTab(page)

    const chart = page.getByTestId('intraday-var-chart')
    await expect(chart.locator('svg')).toBeVisible({ timeout: 5000 })

    await expect(page.getByTestId('intraday-var-last-session')).toHaveCount(0)
  })
})
