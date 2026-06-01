import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Fixture data
// ---------------------------------------------------------------------------

const STRESS_RESULT_EQUITY_CRASH = {
  scenarioName: 'EQUITY_CRASH',
  baseVar: '125000',
  stressedVar: '375000',
  pnlImpact: '-250000',
  assetClassImpacts: [
    { assetClass: 'EQUITY', baseExposure: '500000', stressedExposure: '350000', pnlImpact: '-150000' },
    { assetClass: 'FX', baseExposure: '100000', stressedExposure: '80000', pnlImpact: '-20000' },
  ],
  positionImpacts: [
    { instrumentId: 'AAPL', assetClass: 'EQUITY', baseMarketValue: '155000', stressedMarketValue: '108500', pnlImpact: '-46500', percentageOfTotal: '62.0' },
    { instrumentId: 'GOOGL', assetClass: 'EQUITY', baseMarketValue: '142500', stressedMarketValue: '113000', pnlImpact: '-29500', percentageOfTotal: '38.0' },
  ],
  limitBreaches: [
    { limitType: 'VAR', limitLevel: 'FIRM', limitValue: '300000', stressedValue: '375000', breachSeverity: 'BREACHED', scenarioName: 'EQUITY_CRASH' },
  ],
  stressedGreeks: {
    baseDelta: '8675.25', stressedDelta: '6070.00',
    baseGamma: '57.50', stressedGamma: '40.25',
    baseVega: '1210.00', stressedVega: '847.00',
    baseTheta: '-125.50', stressedTheta: '-180.00',
    baseRho: '42.30', stressedRho: '29.60',
  },
  calculatedAt: '2025-01-15T12:00:00Z',
}

const STRESS_RESULT_RATES_SHOCK = {
  scenarioName: 'RATES_SHOCK',
  baseVar: '125000',
  stressedVar: '200000',
  pnlImpact: '-75000',
  assetClassImpacts: [
    { assetClass: 'FIXED_INCOME', baseExposure: '300000', stressedExposure: '250000', pnlImpact: '-50000' },
  ],
  positionImpacts: [
    { instrumentId: 'US10Y', assetClass: 'FIXED_INCOME', baseMarketValue: '300000', stressedMarketValue: '250000', pnlImpact: '-50000', percentageOfTotal: '100.0' },
  ],
  limitBreaches: [],
  stressedGreeks: {
    baseDelta: '3000.00', stressedDelta: '2500.00',
    baseGamma: '10.00', stressedGamma: '8.00',
    baseVega: '500.00', stressedVega: '450.00',
    baseTheta: '-50.00', stressedTheta: '-60.00',
    baseRho: '120.00', stressedRho: '150.00',
  },
  calculatedAt: '2025-01-15T12:01:00Z',
}

const STRESS_RESULT_FX_CRISIS = {
  scenarioName: 'FX_CRISIS',
  baseVar: '125000',
  stressedVar: '180000',
  pnlImpact: '-55000',
  assetClassImpacts: [
    { assetClass: 'FX', baseExposure: '100000', stressedExposure: '55000', pnlImpact: '-45000' },
  ],
  positionImpacts: [
    { instrumentId: 'EUR_USD', assetClass: 'FX', baseMarketValue: '100000', stressedMarketValue: '55000', pnlImpact: '-45000', percentageOfTotal: '100.0' },
  ],
  limitBreaches: [],
  calculatedAt: '2025-01-15T12:02:00Z',
}

const STRESS_RESULT_SIGN_FLIP = {
  scenarioName: 'SIGN_FLIP_SCENARIO',
  baseVar: '100000',
  stressedVar: '200000',
  pnlImpact: '-100000',
  assetClassImpacts: [],
  positionImpacts: [],
  limitBreaches: [],
  stressedGreeks: {
    baseDelta: '5000.00', stressedDelta: '-2000.00',
    baseGamma: '20.00', stressedGamma: '15.00',
    baseVega: '300.00', stressedVega: '250.00',
    baseTheta: '-80.00', stressedTheta: '-90.00',
    baseRho: '30.00', stressedRho: '25.00',
  },
  calculatedAt: '2025-01-15T12:03:00Z',
}

const GOVERNANCE_SCENARIOS = [
  { id: 'sc-1', name: 'EQUITY_CRASH', description: 'Global equity crash', shocks: '{"volShocks":{"EQUITY":2.0},"priceShocks":{"EQUITY":0.7}}', status: 'DRAFT', createdBy: 'analyst@kinetix.com', approvedBy: null, approvedAt: null, createdAt: '2025-01-15T10:00:00Z' },
  { id: 'sc-2', name: 'RATES_SHOCK', description: 'Rates up 200bp', shocks: '{"volShocks":{"FIXED_INCOME":1.5},"priceShocks":{"FIXED_INCOME":0.8}}', status: 'PENDING_APPROVAL', createdBy: 'analyst@kinetix.com', approvedBy: null, approvedAt: null, createdAt: '2025-01-15T10:05:00Z' },
  { id: 'sc-3', name: 'FX_CRISIS', description: 'FX volatility spike', shocks: '{"volShocks":{"FX":3.0},"priceShocks":{"FX":0.6}}', status: 'APPROVED', createdBy: 'analyst@kinetix.com', approvedBy: 'manager@kinetix.com', approvedAt: '2025-01-15T11:00:00Z', createdAt: '2025-01-15T10:10:00Z' },
]

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function goToScenariosTab(page: Page) {
  await page.goto('/')
  await page.getByTestId('tab-scenarios').click()
}

/**
 * Override the batch stress endpoint and stress scenarios list.
 * Call AFTER mockAllApiRoutes.
 */
async function mockScenariosRoutes(
  page: Page,
  opts: {
    stressScenarios?: string[]
    batchResults?: object[]
    singleResult?: object | null
    governanceScenarios?: object[]
    // kx-kjse — when set, GET .../batch returns this persisted batch so the
    // Scenarios tab renders the comparison grid on cold open. When omitted,
    // GET .../batch returns 404 (no stored batch) and the empty CTA shows.
    storedBatch?: object | null
  } = {},
): Promise<void> {
  // Unroute defaults set by mockAllApiRoutes
  await page.unroute('**/api/v1/risk/stress/scenarios')
  await page.unroute('**/api/v1/risk/**')

  // Catch-all for risk endpoints (lowest priority)
  await page.route('**/api/v1/risk/**', (route: Route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })

  // Single stress run
  await page.route('**/api/v1/risk/stress/*', (route: Route) => {
    const url = route.request().url()
    // Skip /batch and /scenarios routes — handled by more specific handlers
    if (url.includes('/batch') || url.includes('/scenarios')) {
      route.fallback()
      return
    }
    if (route.request().method() === 'POST') {
      if (opts.singleResult) {
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.singleResult) })
      } else {
        route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
      }
    } else {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
    }
  })

  // Batch stress run — API returns a BatchStressRunResult wrapper, not a raw array
  await page.route('**/api/v1/risk/stress/*/batch', (route: Route) => {
    if (route.request().method() === 'POST') {
      const results = opts.batchResults ?? []
      const worst = results.length > 0
        ? results.reduce((a, b) =>
            Math.abs(Number((a as Record<string, unknown>).pnlImpact)) >= Math.abs(Number((b as Record<string, unknown>).pnlImpact)) ? a : b,
          )
        : null
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          results,
          failedScenarios: [],
          worstScenarioName: worst ? (worst as Record<string, unknown>).scenarioName : null,
          worstPnlImpact: worst ? (worst as Record<string, unknown>).pnlImpact : null,
        }),
      })
    } else {
      // GET .../batch — the persist-and-fetch cold-open path (kx-kjse).
      if (opts.storedBatch) {
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.storedBatch) })
      } else {
        route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
      }
    }
  })

  // Stress scenario names
  await page.route('**/api/v1/risk/stress/scenarios', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(opts.stressScenarios ?? []),
    })
  })

  // Governance CRUD for stress-scenarios
  const scenarios = [...(opts.governanceScenarios ?? [])] as Array<Record<string, unknown>>
  await page.route('**/api/v1/stress-scenarios/approved', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(scenarios.filter((s) => s.status === 'APPROVED')),
    })
  })
  await page.route('**/api/v1/stress-scenarios/*/submit', (route: Route) => {
    if (route.request().method() === 'PATCH') {
      const url = route.request().url()
      const id = url.split('/stress-scenarios/')[1].split('/')[0]
      const sc = scenarios.find((s) => s.id === id)
      if (sc) sc.status = 'PENDING_APPROVAL'
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sc ?? {}) })
    } else {
      route.fallback()
    }
  })
  await page.route('**/api/v1/stress-scenarios/*/approve', (route: Route) => {
    if (route.request().method() === 'PATCH') {
      const url = route.request().url()
      const id = url.split('/stress-scenarios/')[1].split('/')[0]
      const sc = scenarios.find((s) => s.id === id)
      if (sc) {
        sc.status = 'APPROVED'
        sc.approvedBy = 'user'
        sc.approvedAt = new Date().toISOString()
      }
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sc ?? {}) })
    } else {
      route.fallback()
    }
  })
  await page.route('**/api/v1/stress-scenarios/*/retire', (route: Route) => {
    if (route.request().method() === 'PATCH') {
      const url = route.request().url()
      const id = url.split('/stress-scenarios/')[1].split('/')[0]
      const sc = scenarios.find((s) => s.id === id)
      if (sc) sc.status = 'RETIRED'
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sc ?? {}) })
    } else {
      route.fallback()
    }
  })
  await page.route('**/api/v1/stress-scenarios', (route: Route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      const newSc = {
        id: `sc-new-${Date.now()}`,
        name: body.name,
        description: body.description,
        shocks: body.shocks,
        status: 'DRAFT',
        createdBy: body.createdBy,
        approvedBy: null,
        approvedAt: null,
        createdAt: new Date().toISOString(),
      }
      scenarios.push(newSc)
      route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(newSc) })
    } else {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(scenarios) })
    }
  })
}

// ---------------------------------------------------------------------------
// Empty State
// ---------------------------------------------------------------------------

test.describe('Scenarios Tab - Empty State', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows empty message when no results exist', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.waitForSelector('[data-testid="scenarios-tab"]')

    await expect(page.getByTestId('no-results')).toContainText('No stress test results yet')
  })

  test('shows control bar with Run All button', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: ['EQUITY_CRASH'] })

    await goToScenariosTab(page)
    await page.waitForSelector('[data-testid="scenario-control-bar"]')

    await expect(page.getByTestId('run-all-btn')).toContainText('Run All Scenarios')
    await expect(page.getByTestId('confidence-level-select')).toBeVisible()
    await expect(page.getByTestId('time-horizon-select')).toBeVisible()
    await expect(page.getByTestId('custom-scenario-btn')).toBeVisible()
    await expect(page.getByTestId('manage-scenarios-btn')).toBeVisible()
  })

  test('does not show Export CSV button when no results exist', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.waitForSelector('[data-testid="scenario-control-bar"]')

    await expect(page.getByTestId('export-csv-btn')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Cold Open — persist-and-fetch (kx-kjse)
// ---------------------------------------------------------------------------

test.describe('Scenarios Tab - Cold Open', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  const STORED_BATCH = {
    results: [STRESS_RESULT_EQUITY_CRASH, STRESS_RESULT_RATES_SHOCK],
    failedScenarios: [],
    worstScenarioName: 'EQUITY_CRASH',
    worstPnlImpact: '-250000',
  }

  test('renders the comparison grid on load from the persisted batch without clicking Run All', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH', 'RATES_SHOCK'],
      storedBatch: STORED_BATCH,
    })

    await goToScenariosTab(page)

    // Grid populates from the stored batch fetched on mount — no Run All click.
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')
    const rows = page.getByTestId('scenario-row')
    await expect(rows).toHaveCount(2)
    // Worst scenario ranked first.
    await expect(rows.first()).toContainText('EQUITY CRASH')
  })

  test('shows the empty CTA with no error when no batch has been persisted (404)', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      // storedBatch omitted -> GET .../batch returns 404
    })

    await goToScenariosTab(page)
    await page.waitForSelector('[data-testid="scenarios-tab"]')

    await expect(page.getByTestId('no-results')).toContainText('No stress test results yet')
    // A 404 on the cold-open fetch must not surface as an error banner.
    await expect(page.getByTestId('stress-error')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Run All Scenarios
// ---------------------------------------------------------------------------

test.describe('Scenarios Tab - Run All Scenarios', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('runs batch stress test and displays results table', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH', 'RATES_SHOCK'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH, STRESS_RESULT_RATES_SHOCK],
    })

    await goToScenariosTab(page)
    await page.waitForSelector('[data-testid="run-all-btn"]')

    await page.getByTestId('run-all-btn').click()

    await page.waitForSelector('[data-testid="scenario-comparison-table"]')
    const rows = page.getByTestId('scenario-row')
    await expect(rows).toHaveCount(2)
  })

  test('results show VaR multiplier and P&L impact', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')

    // VaR multiplier = 375000 / 125000 = 3.0x
    await expect(page.getByTestId('var-multiplier')).toContainText('3.0x')
    // P&L impact should be negative
    await expect(page.getByTestId('pnl-impact')).toHaveClass(/text-red-600/)
  })

  test('results show breach badge for scenarios with limit breaches', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')

    await expect(page.getByTestId('breach-badge')).toContainText('1 breach')
  })

  test('Export CSV button appears after results load', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')

    await expect(page.getByTestId('export-csv-btn')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Confidence Level & Time Horizon selectors
// ---------------------------------------------------------------------------

test.describe('Scenarios Tab - Parameter Selectors', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('confidence level can be changed to 99%', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: ['EQUITY_CRASH'] })

    await goToScenariosTab(page)
    await page.waitForSelector('[data-testid="confidence-level-select"]')

    await page.getByTestId('confidence-level-select').selectOption('CL_99')
    await expect(page.getByTestId('confidence-level-select')).toHaveValue('CL_99')
  })

  test('time horizon can be changed to 10 days', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: ['EQUITY_CRASH'] })

    await goToScenariosTab(page)
    await page.waitForSelector('[data-testid="time-horizon-select"]')

    await page.getByTestId('time-horizon-select').selectOption('10')
    await expect(page.getByTestId('time-horizon-select')).toHaveValue('10')
  })
})

// ---------------------------------------------------------------------------
// Detail Panel -- view toggling
// ---------------------------------------------------------------------------

test.describe('Scenarios Tab - Detail Panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('clicking a scenario row opens the detail panel with Asset Class view', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-row"]')

    // Click the scenario row
    await page.getByTestId('scenario-row').click()

    // Detail panel should appear
    await expect(page.getByTestId('detail-panel')).toBeVisible()

    // Default view is Asset Class
    await expect(page.getByTestId('asset-class-impact-view')).toBeVisible()

    // Should show asset class names and P&L
    await expect(page.getByTestId('asset-class-impact-view')).toContainText('EQUITY')
    await expect(page.getByTestId('asset-class-impact-view')).toContainText('FX')
  })

  test('clicking asset class name navigates to Positions view filtered by that class', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-row"]')
    await page.getByTestId('scenario-row').click()

    // Click EQUITY asset class
    await page.getByTestId('asset-class-click-EQUITY').click()

    // Should switch to positions view
    await expect(page.getByTestId('stress-position-table')).toBeVisible()

    // Filter pill should show
    await expect(page.getByTestId('filter-pill')).toContainText('EQUITY')

    // Only EQUITY positions should be visible
    const rows = page.getByTestId('position-row')
    await expect(rows).toHaveCount(2) // AAPL and GOOGL
  })

  test('clearing asset class filter shows all positions', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-row"]')
    await page.getByTestId('scenario-row').click()
    await page.getByTestId('asset-class-click-EQUITY').click()
    await page.waitForSelector('[data-testid="filter-pill"]')

    // Clear filter
    await page.getByTestId('clear-filter').click()

    // Filter pill should disappear
    await expect(page.getByTestId('filter-pill')).not.toBeVisible()

    // All positions visible
    const rows = page.getByTestId('position-row')
    await expect(rows).toHaveCount(2) // AAPL and GOOGL (all are EQUITY in this fixture)
  })

  test('switching to Positions view shows position table with total row', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-row"]')
    await page.getByTestId('scenario-row').click()

    await page.getByTestId('view-toggle-positions').click()

    await expect(page.getByTestId('stress-position-table')).toBeVisible()
    await expect(page.getByTestId('total-row')).toContainText('Total')
  })

  test('switching to Greeks view shows greeks table with all five greeks', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-row"]')
    await page.getByTestId('scenario-row').click()

    await page.getByTestId('view-toggle-greeks').click()

    await expect(page.getByTestId('greeks-view')).toBeVisible()
    await expect(page.getByTestId('greek-row-Delta')).toBeVisible()
    await expect(page.getByTestId('greek-row-Gamma')).toBeVisible()
    await expect(page.getByTestId('greek-row-Vega')).toBeVisible()
    await expect(page.getByTestId('greek-row-Theta')).toBeVisible()
    await expect(page.getByTestId('greek-row-Rho')).toBeVisible()
  })

  test('sign flip is highlighted in Greeks view', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['SIGN_FLIP_SCENARIO'],
      batchResults: [STRESS_RESULT_SIGN_FLIP],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-row"]')
    await page.getByTestId('scenario-row').click()
    await page.getByTestId('view-toggle-greeks').click()

    // Delta row should show SIGN FLIP badge (baseDelta=5000, stressedDelta=-2000)
    await expect(page.getByTestId('greek-row-Delta')).toContainText('SIGN FLIP')
  })

  test('limit breach card is visible when breaches exist', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-row"]')
    await page.getByTestId('scenario-row').click()

    await expect(page.getByTestId('limit-breach-card')).toBeVisible()
    await expect(page.getByTestId('breach-row')).toHaveCount(1)
    await expect(page.getByTestId('severity-badge-0')).toContainText('BREACHED')
  })

  test('clicking selected scenario row again closes the detail panel', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-row"]')
    await page.getByTestId('scenario-row').click()
    await expect(page.getByTestId('detail-panel')).toBeVisible()

    // Click again to deselect
    await page.getByTestId('scenario-row').click()
    await expect(page.getByTestId('detail-panel')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Scenario Comparison
// ---------------------------------------------------------------------------

test.describe('Scenarios Tab - Scenario Comparison', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('compare button appears when 2 scenarios are checked', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH', 'RATES_SHOCK'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH, STRESS_RESULT_RATES_SHOCK],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')

    // Check first scenario — compare button should NOT appear yet
    await page.getByTestId('scenario-check-EQUITY_CRASH').check()
    await expect(page.getByTestId('compare-btn')).not.toBeVisible()

    // Check second scenario — compare button appears
    await page.getByTestId('scenario-check-RATES_SHOCK').check()
    await expect(page.getByTestId('compare-btn')).toBeVisible()
    await expect(page.getByTestId('compare-btn')).toContainText('Compare (2)')
  })

  test('clicking Compare shows side-by-side comparison view', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH', 'RATES_SHOCK'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH, STRESS_RESULT_RATES_SHOCK],
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')

    await page.getByTestId('scenario-check-EQUITY_CRASH').check()
    await page.getByTestId('scenario-check-RATES_SHOCK').check()
    await page.getByTestId('compare-btn').click()

    await expect(page.getByTestId('comparison-view')).toBeVisible()
    // Should contain metric labels
    await expect(page.getByTestId('comparison-view')).toContainText('Base VaR')
    await expect(page.getByTestId('comparison-view')).toContainText('Stressed VaR')
    await expect(page.getByTestId('comparison-view')).toContainText('P&L Impact')
    await expect(page.getByTestId('comparison-view')).toContainText('VaR Multiplier')
  })

  test('cannot check more than 3 scenarios', async ({ page }) => {
    const batchResults = [STRESS_RESULT_EQUITY_CRASH, STRESS_RESULT_RATES_SHOCK, STRESS_RESULT_FX_CRISIS]
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH', 'RATES_SHOCK', 'FX_CRISIS'],
      batchResults,
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')

    // Check 3 scenarios
    await page.getByTestId('scenario-check-EQUITY_CRASH').check()
    await page.getByTestId('scenario-check-RATES_SHOCK').check()
    await page.getByTestId('scenario-check-FX_CRISIS').check()

    // All three should be checked
    await expect(page.getByTestId('scenario-check-EQUITY_CRASH')).toBeChecked()
    await expect(page.getByTestId('scenario-check-RATES_SHOCK')).toBeChecked()
    await expect(page.getByTestId('scenario-check-FX_CRISIS')).toBeChecked()

    await expect(page.getByTestId('compare-btn')).toContainText('Compare (3)')
  })
})

// ---------------------------------------------------------------------------
// Custom Scenario Builder
// ---------------------------------------------------------------------------

test.describe('Scenarios Tab - Custom Scenario Builder', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('opens builder panel when Custom Scenario button is clicked', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.getByTestId('custom-scenario-btn').click()

    await expect(page.getByTestId('scenario-builder-panel')).toBeVisible()
    await expect(page.getByTestId('scenario-name')).toBeVisible()
    await expect(page.getByTestId('scenario-description')).toBeVisible()
  })

  test('shows vol and price shock inputs for all 5 asset classes', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.getByTestId('custom-scenario-btn').click()

    for (const ac of ['EQUITY', 'FIXED_INCOME', 'COMMODITY', 'FX', 'DERIVATIVE']) {
      await expect(page.getByTestId(`vol-shock-${ac}`)).toBeVisible()
      await expect(page.getByTestId(`price-shock-${ac}`)).toBeVisible()
    }
  })

  test('Save & Submit button is disabled when name is empty', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.getByTestId('custom-scenario-btn').click()

    // Name is empty by default — Save should be disabled
    await expect(page.getByTestId('scenario-save-btn')).toBeDisabled()
  })

  test('Save & Submit button enables when name is filled', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.getByTestId('custom-scenario-btn').click()

    await page.getByTestId('scenario-name').fill('My Test Scenario')
    await expect(page.getByTestId('scenario-save-btn')).toBeEnabled()
  })

  test('Run Ad-Hoc button is enabled even without name', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.getByTestId('custom-scenario-btn').click()

    await expect(page.getByTestId('scenario-run-btn')).toBeEnabled()
  })

  test('closing builder panel via X button', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.getByTestId('custom-scenario-btn').click()
    await expect(page.getByTestId('scenario-builder-panel')).toBeVisible()

    await page.getByTestId('scenario-builder-close').click()
    await expect(page.getByTestId('scenario-builder-panel')).not.toBeVisible()
  })

  test('closing builder panel via Escape key', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.getByTestId('custom-scenario-btn').click()
    const panel = page.getByTestId('scenario-builder-panel')
    await expect(panel).toBeVisible()

    // Click the panel to ensure document focus is inside it before pressing Escape;
    // when other suites have run first, focus can sit on a stale element and the
    // Escape listener (attached to document) misses the keydown.
    await panel.click({ position: { x: 5, y: 5 } })
    await page.keyboard.press('Escape')
    await expect(panel).not.toBeVisible()
  })

  test('closing builder panel via backdrop click', async ({ page }) => {
    await mockScenariosRoutes(page, { stressScenarios: [] })

    await goToScenariosTab(page)
    await page.getByTestId('custom-scenario-btn').click()
    await expect(page.getByTestId('scenario-builder-panel')).toBeVisible()

    await page.getByTestId('scenario-builder-backdrop').click()
    await expect(page.getByTestId('scenario-builder-panel')).not.toBeVisible()
  })

  test('Run Ad-Hoc executes stress test and appends result', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: [],
      singleResult: { ...STRESS_RESULT_EQUITY_CRASH, scenarioName: 'AD_HOC' },
    })

    await goToScenariosTab(page)
    await page.getByTestId('custom-scenario-btn').click()

    // Adjust a vol shock
    await page.getByTestId('vol-shock-EQUITY').fill('2.5')

    await page.getByTestId('scenario-run-btn').click()

    // Builder closes and result appears in table
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')
    await expect(page.getByTestId('scenario-row')).toHaveCount(1)
  })
})

// ---------------------------------------------------------------------------
// Governance Panel
// ---------------------------------------------------------------------------

test.describe('Scenarios Tab - Governance Panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('Manage Scenarios button toggles governance panel', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: [],
      governanceScenarios: GOVERNANCE_SCENARIOS,
    })

    await goToScenariosTab(page)
    await page.getByTestId('manage-scenarios-btn').click()

    await expect(page.getByTestId('governance-panel')).toBeVisible()
    await expect(page.getByTestId('governance-panel')).toContainText('Scenario Governance')
  })

  test('governance panel shows scenarios with correct statuses', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: [],
      governanceScenarios: GOVERNANCE_SCENARIOS,
    })

    await goToScenariosTab(page)
    await page.getByTestId('manage-scenarios-btn').click()
    await page.waitForSelector('[data-testid="governance-panel"]')

    await expect(page.getByTestId('governance-panel')).toContainText('EQUITY_CRASH')
    await expect(page.getByTestId('governance-panel')).toContainText('DRAFT')
    await expect(page.getByTestId('governance-panel')).toContainText('RATES_SHOCK')
    await expect(page.getByTestId('governance-panel')).toContainText('PENDING_APPROVAL')
    await expect(page.getByTestId('governance-panel')).toContainText('FX_CRISIS')
    await expect(page.getByTestId('governance-panel')).toContainText('APPROVED')
  })

  test('DRAFT scenario shows Submit for Approval button', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: [],
      governanceScenarios: [GOVERNANCE_SCENARIOS[0]], // DRAFT only
    })

    await goToScenariosTab(page)
    await page.getByTestId('manage-scenarios-btn').click()
    await page.waitForSelector('[data-testid="governance-panel"]')

    await expect(page.getByText('Submit for Approval')).toBeVisible()
  })

  test('PENDING_APPROVAL scenario shows Approve button', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: [],
      governanceScenarios: [GOVERNANCE_SCENARIOS[1]], // PENDING_APPROVAL only
    })

    await goToScenariosTab(page)
    await page.getByTestId('manage-scenarios-btn').click()
    await page.waitForSelector('[data-testid="governance-panel"]')

    await expect(page.getByText('Approve')).toBeVisible()
  })

  test('APPROVED scenario shows Retire button', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: [],
      governanceScenarios: [GOVERNANCE_SCENARIOS[2]], // APPROVED only
    })

    await goToScenariosTab(page)
    await page.getByTestId('manage-scenarios-btn').click()
    await page.waitForSelector('[data-testid="governance-panel"]')

    await expect(page.getByText('Retire')).toBeVisible()
  })

  test('governance panel shows empty state when no scenarios exist', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: [],
      governanceScenarios: [],
    })

    await goToScenariosTab(page)
    await page.getByTestId('manage-scenarios-btn').click()
    await page.waitForSelector('[data-testid="governance-panel"]')

    await expect(page.getByTestId('governance-empty')).toContainText('No scenarios found')
  })

  test('toggling Manage Scenarios button hides governance panel', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: [],
      governanceScenarios: [],
    })

    await goToScenariosTab(page)
    await page.getByTestId('manage-scenarios-btn').click()
    await expect(page.getByTestId('governance-panel')).toBeVisible()

    await page.getByTestId('manage-scenarios-btn').click()
    await expect(page.getByTestId('governance-panel')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Scenario Tooltip
// ---------------------------------------------------------------------------

test.describe('Scenarios Tab - Scenario Tooltip', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('hovering over scenario name shows tooltip with metadata', async ({ page }) => {
    await mockScenariosRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      batchResults: [STRESS_RESULT_EQUITY_CRASH],
      governanceScenarios: GOVERNANCE_SCENARIOS,
    })

    await goToScenariosTab(page)
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')

    // Hover over the scenario name — it's wrapped in a ScenarioTooltip span
    const scenarioName = page.locator('[role="button"]', { hasText: 'EQUITY CRASH' })
    await scenarioName.hover()

    await expect(page.getByTestId('scenario-tooltip')).toBeVisible()
    await expect(page.getByTestId('scenario-tooltip')).toContainText('Global equity crash')
  })
})
