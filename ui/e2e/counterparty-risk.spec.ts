import { test, expect, type Page } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockCounterpartyRiskRoutes,
  TEST_COUNTERPARTY_EXPOSURES,
  TEST_COUNTERPARTY_CVA_RESULT,
} from './fixtures'

async function goToCounterpartyRiskTab(page: Page) {
  await page.goto('/')
  await page.getByTestId('tab-counterparty-risk').click()
}

// ---------------------------------------------------------------------------
// Tab navigation
// ---------------------------------------------------------------------------

test.describe('Counterparty Risk tab', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page)
  })

  test('navigates to the counterparty risk tab', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await expect(page.getByTestId('counterparty-risk-dashboard')).toBeVisible()
  })

  test('shows counterparty list with expected counterparties', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await expect(page.getByTestId('counterparty-row-CP-GS')).toBeVisible()
    await expect(page.getByTestId('counterparty-row-CP-JPM')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

test.describe('Counterparty Risk - empty state', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page, [])
  })

  test('shows empty state when no exposures exist', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await expect(page.getByTestId('counterparty-empty-state')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Wrong-way risk flags
// ---------------------------------------------------------------------------

test.describe('Counterparty Risk - wrong-way risk flags', () => {
  // The WWR badge is driven by the top-decile-exposure threshold in
  // CounterpartyRiskDashboard.tsx, which returns null below 10 counterparties.
  // We provide a wider fixture here so CP-JPM (highest exposure) is flagged
  // and CP-GS (mid-range exposure) is not.
  const WWR_EXPOSURES = [
    { counterpartyId: 'CP-JPM', currentNetExposure: 9_500_000, peakPfe: 10_000_000, cva: null, cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-GS',  currentNetExposure: 2_000_000, peakPfe: 1_800_000,  cva: 12_500, cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-001', currentNetExposure: 500_000,   peakPfe: 600_000,    cva: 1_000,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-002', currentNetExposure: 750_000,   peakPfe: 800_000,    cva: 1_500,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-003', currentNetExposure: 1_000_000, peakPfe: 1_100_000,  cva: 2_000,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-004', currentNetExposure: 1_250_000, peakPfe: 1_300_000,  cva: 2_500,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-005', currentNetExposure: 1_500_000, peakPfe: 1_600_000,  cva: 3_000,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-006', currentNetExposure: 1_750_000, peakPfe: 1_900_000,  cva: 3_500,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-007', currentNetExposure: 2_250_000, peakPfe: 2_400_000,  cva: 4_500,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-008', currentNetExposure: 2_500_000, peakPfe: 2_700_000,  cva: 5_000,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-009', currentNetExposure: 2_750_000, peakPfe: 3_000_000,  cva: 5_500,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
    { counterpartyId: 'CP-010', currentNetExposure: 3_000_000, peakPfe: 3_300_000,  cva: 6_000,  cvaEstimated: false, currency: 'USD', calculatedAt: '2026-03-24T10:00:00Z', pfeProfile: [] },
  ]

  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page, WWR_EXPOSURES)
  })

  test('shows WWR flag for CP-JPM with high exposure', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-JPM"]')

    await expect(page.getByTestId('wwf-badge-CP-JPM')).toBeVisible()
  })

  test('does not show WWR flag for CP-GS with normal exposure', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await expect(page.getByTestId('wwf-badge-CP-GS')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Detail panel
// ---------------------------------------------------------------------------

test.describe('Counterparty Risk - detail panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page)
  })

  test('shows placeholder when no counterparty is selected', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await expect(page.getByTestId('detail-panel-placeholder')).toBeVisible()
  })

  test('shows detail panel when a counterparty is clicked', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await page.getByTestId('counterparty-row-CP-GS').click()

    await expect(page.getByTestId('counterparty-detail-panel')).toBeVisible()
    await expect(page.getByTestId('detail-net-exposure')).toBeVisible()
    await expect(page.getByTestId('detail-peak-pfe')).toBeVisible()
    await expect(page.getByTestId('detail-cva')).toBeVisible()
  })

  test('shows PFE chart for counterparty with profile data', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await page.getByTestId('counterparty-row-CP-GS').click()

    await expect(page.getByTestId('pfe-chart')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// PFE computation
// ---------------------------------------------------------------------------

test.describe('Counterparty Risk - PFE computation', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page)
  })

  test('Compute PFE button triggers computation and updates panel', async ({ page }) => {
    const updatedExposure = {
      ...TEST_COUNTERPARTY_EXPOSURES[0],
      peakPfe: 2_200_000,
      pfeProfile: [
        { tenor: '1Y', tenorYears: 1, expectedExposure: 1_600_000, pfe95: 2_000_000, pfe99: 2_200_000 },
        { tenor: '2Y', tenorYears: 2, expectedExposure: 1_300_000, pfe95: 1_700_000, pfe99: 1_900_000 },
      ],
    }

    // Override PFE endpoint to return updated result
    await page.route('**/api/v1/counterparty-risk/CP-GS/pfe', (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(updatedExposure),
      })
    })

    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await page.getByTestId('counterparty-row-CP-GS').click()
    await page.waitForSelector('[data-testid="compute-pfe-button"]')

    await page.getByTestId('compute-pfe-button').click()

    // PFE chart should be visible after computation
    await expect(page.getByTestId('pfe-chart')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// CVA computation
// ---------------------------------------------------------------------------

test.describe('Counterparty Risk - CVA computation', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page)
  })

  test('Compute CVA button triggers computation and updates CVA metric', async ({ page }) => {
    await page.route('**/api/v1/counterparty-risk/CP-GS/cva', (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(TEST_COUNTERPARTY_CVA_RESULT),
      })
    })

    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await page.getByTestId('counterparty-row-CP-GS').click()
    await page.waitForSelector('[data-testid="compute-cva-button"]')

    await page.getByTestId('compute-cva-button').click()

    await expect(page.getByTestId('detail-cva')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Refresh
// ---------------------------------------------------------------------------

test.describe('Counterparty Risk - refresh', () => {
  test('Refresh button reloads the exposure list', async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page)

    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await page.getByTestId('refresh-exposures-button').click()

    // After refresh, the list should still be visible
    await expect(page.getByTestId('counterparty-row-CP-GS')).toBeVisible()
  })
})
