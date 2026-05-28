import { test, expect, type Page } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * One counterparty in the list with a fully-populated pfeProfile (so the list
 * endpoint returns valid data). The per-CP detail fetch returns a version with
 * one malformed tenor row — missing expectedExposure — to exercise the guard.
 */
const CP_ID = 'CP-DETAIL-TEST'

const LIST_EXPOSURE = {
  counterpartyId: CP_ID,
  calculatedAt: '2026-05-28T10:00:00Z',
  currentNetExposure: 3_000_000,
  peakPfe: 3_500_000,
  cva: 25_000,
  cvaEstimated: false,
  currency: 'USD',
  pfeProfile: [
    { tenor: '1Y', tenorYears: 1, expectedExposure: 2_000_000, pfe95: 2_500_000, pfe99: 2_800_000 },
  ],
}

/**
 * The per-CP detail response has one valid row and one row missing
 * expectedExposure. This is the data that flows into PfeChart when the user
 * clicks the row. Before the null-guard fix this produced NaN polyline
 * coordinates and crashed real SVG renderers.
 */
const DETAIL_EXPOSURE = {
  ...LIST_EXPOSURE,
  pfeProfile: [
    { tenor: '1Y', tenorYears: 1, expectedExposure: 2_000_000, pfe95: 2_500_000, pfe99: 2_800_000 },
    // Malformed row — expectedExposure absent from the API response
    { tenor: '2Y', tenorYears: 2, pfe95: 2_100_000, pfe99: 2_400_000 },
  ],
}

async function setupCounterpartyDetailRoutes(page: Page): Promise<void> {
  // List endpoint — returns one CP so the table renders
  await page.route('**/api/v1/counterparty-risk', (route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([LIST_EXPOSURE]),
      })
    } else {
      route.continue()
    }
  })

  // History endpoint — needed by selectCounterparty
  await page.route('**/api/v1/counterparty-risk/*/history*', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([LIST_EXPOSURE]),
    })
  })

  // Per-CP detail endpoint — returns the malformed pfeProfile
  await page.route(`**/api/v1/counterparty-risk/${CP_ID}`, (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(DETAIL_EXPOSURE),
    })
  })

  // SA-CCR endpoint — referenced by the dashboard after row click
  await page.route('**/api/v1/counterparty/*/sa-ccr*', (route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Counterparty detail panel — malformed pfeProfile', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await setupCounterpartyDetailRoutes(page)
  })

  test('clicking a counterparty row shows the detail panel and does not trigger the global error boundary', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-counterparty-risk').click()
    await page.waitForSelector(`[data-testid="counterparty-row-${CP_ID}"]`)

    await page.getByTestId(`counterparty-row-${CP_ID}`).click()

    // Detail panel must be visible — the row click succeeded
    await expect(page.getByTestId('counterparty-detail-panel')).toBeVisible()

    // The global ErrorBoundary replaces the entire app with "Something went wrong"
    // text. If the PfeChart crash were still present, this would appear instead
    // of the detail panel.
    await expect(page.getByText('Something went wrong')).not.toBeVisible()
  })
})
