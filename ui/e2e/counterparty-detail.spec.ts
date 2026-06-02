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

// ---------------------------------------------------------------------------
// SA-CCR panel — real per-netting-set summary contract (kx-eihp)
//
// The orchestrator's /sa-ccr endpoint (no nettingSetId) returns a
// SaCcrSummaryResponse: { counterpartyId, totalEad, nettingSets: [...] }, NOT a
// flat result. The UI previously expected a flat object and crashed with
// "Cannot read properties of undefined (reading 'toFixed')" on result.multiplier.
// Mock the real 200 shape and prove the panel renders.
// ---------------------------------------------------------------------------

const SA_CCR_SUMMARY = {
  counterpartyId: CP_ID,
  totalEad: 5_268_000,
  nettingSets: [
    {
      nettingSetId: 'ISDA-GS-2019',
      counterpartyId: CP_ID,
      replacementCost: 2_100_000,
      pfeAddon: 1_013_000,
      multiplier: 0.995,
      ead: 4_218_000,
      alpha: 1.4,
    },
    {
      nettingSetId: `${CP_ID}-UNASSIGNED`,
      counterpartyId: CP_ID,
      replacementCost: 500_000,
      pfeAddon: 250_000,
      multiplier: 1.0,
      ead: 1_050_000,
      alpha: 1.4,
    },
  ],
}

test.describe('Counterparty detail — SA-CCR per-netting-set summary', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await setupCounterpartyDetailRoutes(page)
    // Override the SA-CCR route with the real 200 summary shape.
    await page.route('**/api/v1/counterparty/*/sa-ccr*', (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(SA_CCR_SUMMARY),
      })
    })
  })

  test('renders total EAD and a row per netting set without crashing', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-counterparty-risk').click()
    await page.waitForSelector(`[data-testid="counterparty-row-${CP_ID}"]`)
    await page.getByTestId(`counterparty-row-${CP_ID}`).click()

    await expect(page.getByTestId('sa-ccr-panel')).toBeVisible()
    await expect(page.getByTestId('sa-ccr-total-ead')).toContainText('$5,268,000')
    await expect(page.getByTestId('sa-ccr-netting-set-ISDA-GS-2019')).toBeVisible()
    await expect(page.getByTestId(`sa-ccr-netting-set-${CP_ID}-UNASSIGNED`)).toBeVisible()
    await expect(page.getByTestId('sa-ccr-multiplier-ISDA-GS-2019')).toContainText('0.9950')

    // The crash this fixes: undefined.toFixed would trip the global ErrorBoundary.
    await expect(page.getByText('Something went wrong')).not.toBeVisible()
  })
})
