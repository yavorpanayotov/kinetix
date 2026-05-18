import { test, expect } from '@playwright/test'
import type { Route } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_JOB_HISTORY,
} from './fixtures'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function goToRiskTab(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
}

// ---------------------------------------------------------------------------
// VaR endpoint error states
// ---------------------------------------------------------------------------

test.describe('Risk tab — VaR error state', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows error state with Retry button when VaR endpoint returns 503', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: null,
      jobHistory: TEST_JOB_HISTORY,
    })

    // Override VaR to return 503
    await page.route('**/api/v1/risk/var/*', (route: Route) => {
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Service Unavailable' }),
      })
    })

    await goToRiskTab(page)

    // Wait for the error card
    await expect(page.getByTestId('var-error')).toBeVisible({ timeout: 5000 })

    // The error card should contain a Retry button
    await expect(page.getByTestId('var-error-retry')).toBeVisible()
  })

  test('clicking the VaR Retry button re-triggers the calculation', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: null,
      jobHistory: TEST_JOB_HISTORY,
    })

    // Initial GETs (including React StrictMode's double-mount in dev) all fail
    // with 503 so the error card renders. The Retry button triggers a POST via
    // useVaR.refresh() / triggerVaRCalculation — that succeeds and the dashboard
    // renders. Pattern is scoped to the specific bookId so we don't accidentally
    // intercept /risk/var/cross-book — that endpoint returns a different DTO
    // (with bookContributions) and the broad pattern would crash
    // BookContributionTable on undefined.length.
    await page.route('**/api/v1/risk/var/port-1*', (route: Route) => {
      if (route.request().method() === 'GET') {
        route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Service Unavailable' }),
        })
      } else {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(TEST_VAR_RESULT),
        })
      }
    })

    await goToRiskTab(page)

    await expect(page.getByTestId('var-error')).toBeVisible({ timeout: 5000 })
    await page.getByTestId('var-error-retry').click()

    // After the retry succeeds, the dashboard should render
    await expect(page.getByTestId('var-dashboard')).toBeVisible({ timeout: 5000 })
    await expect(page.getByTestId('var-error')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Position risk endpoint error states
// ---------------------------------------------------------------------------

test.describe('Risk tab — Position risk error state', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows error state when position-risk endpoint returns 500', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: null,
      positionRiskStatus: 500,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)

    // The VaR dashboard should load (it uses a different endpoint)
    await expect(page.getByTestId('var-dashboard')).toBeVisible({ timeout: 5000 })

    // The position risk section should show the error state
    await expect(page.getByTestId('position-risk-error')).toBeVisible({ timeout: 5000 })
  })

  test('position risk error container has role=alert for accessibility', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: null,
      positionRiskStatus: 500,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await expect(page.getByTestId('position-risk-error')).toBeVisible({ timeout: 5000 })

    await expect(page.getByTestId('position-risk-error')).toHaveAttribute('role', 'alert')
  })

  test('shows Retry button in position risk error state', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: null,
      positionRiskStatus: 500,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await expect(page.getByTestId('position-risk-error')).toBeVisible({ timeout: 5000 })

    await expect(page.getByTestId('position-risk-retry')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Panel independence — VaR error does not block Greeks display
// ---------------------------------------------------------------------------

test.describe('Risk tab — panel independence', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('VaR error card is isolated — does not take down the whole page', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: null,
      jobHistory: TEST_JOB_HISTORY,
    })

    await page.route('**/api/v1/risk/var/*', (route: Route) => {
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Service Unavailable' }),
      })
    })

    await goToRiskTab(page)

    // VaR error is shown inside its card
    await expect(page.getByTestId('var-error')).toBeVisible({ timeout: 5000 })

    // The rest of the Risk tab is still present (tab bar, sub-tabs)
    await expect(page.getByTestId('risk-subtab-dashboard')).toBeVisible()
    await expect(page.getByTestId('risk-subtab-intraday')).toBeVisible()

    // Position risk section is still rendered (even if it may be empty or erroring)
    await expect(page.getByTestId('position-risk-section')).toBeVisible()
  })

  test('position risk 500 error does not affect VaR dashboard rendering', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: null,
      positionRiskStatus: 500,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)

    // VaR dashboard renders successfully
    await expect(page.getByTestId('var-dashboard')).toBeVisible({ timeout: 5000 })
    await expect(page.getByTestId('var-value')).toBeVisible()

    // Position risk error is shown independently
    await expect(page.getByTestId('position-risk-error')).toBeVisible()
  })
})
