import { test, expect } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_HISTORICAL_VAR_RESULT,
  TEST_POSITION_RISK_FULL,
  TEST_HISTORICAL_POSITION_RISK,
  TEST_JOB_HISTORY,
  TEST_JOB_DETAIL,
  TEST_ALERTS,
  TEST_VAR_LIMIT_RULE,
  TEST_PNL_ATTRIBUTION,
} from './fixtures'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Navigate to the Risk tab. */
async function goToRiskTab(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
}

// ---------------------------------------------------------------------------
// VaR Dashboard - Data Display
// ---------------------------------------------------------------------------

test.describe('VaR Dashboard - Data Display', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('displays VaR value, Expected Shortfall, and ES/VaR ratio', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    await expect(page.getByTestId('var-value')).toContainText('$125K')
    await expect(page.getByTestId('es-value')).toContainText('$187.5K')
    await expect(page.getByTestId('es-var-ratio')).toContainText('1.50x')
  })

  test('displays Greeks sensitivities heatmap with per-asset-class rows and totals', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="greeks-heatmap"]')

    // EQUITY row
    const equityRow = page.getByTestId('greeks-row-EQUITY')
    await expect(equityRow).toContainText('1,500.00')
    await expect(equityRow).toContainText('25.00')
    await expect(equityRow).toContainText('800.00')

    // FX row
    const fxRow = page.getByTestId('greeks-row-FX')
    await expect(fxRow).toContainText('500.00')
    await expect(fxRow).toContainText('10.00')
    await expect(fxRow).toContainText('200.00')

    // TOTAL row (delta, gamma, vega only — theta/rho are in summary boxes)
    const totalRow = page.getByTestId('greeks-row-TOTAL')
    await expect(totalRow).toContainText('2,000.00') // delta
    await expect(totalRow).toContainText('35.00') // gamma
    await expect(totalRow).toContainText('1,000.00') // vega

    // Theta and Rho in summary boxes
    await expect(page.getByTestId('greek-summary-theta')).toContainText('-350.00')
    await expect(page.getByTestId('greek-summary-rho')).toContainText('120.00')

    // PV display - 5000000 -> "$5M"
    await expect(page.getByTestId('pv-display')).toContainText('$5M')
  })

  test('displays component breakdown with asset class contributions and diversification benefit', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // EQUITY breakdown: 64%, $80,000.30
    const equityBreakdown = page.getByTestId('breakdown-EQUITY')
    await expect(equityBreakdown).toContainText('64%')
    await expect(equityBreakdown).toContainText('$80,000.30')

    // FX breakdown: 44%, $55,000.20
    const fxBreakdown = page.getByTestId('breakdown-FX')
    await expect(fxBreakdown).toContainText('44%')
    await expect(fxBreakdown).toContainText('$55,000.20')

    // Diversification benefit: sum 135000.50 vs portfolio 125000.50 -> benefit $10,000.00
    await expect(page.getByTestId('diversification-benefit')).toBeVisible()
    await expect(page.getByTestId('diversification-amount')).toContainText('$10,000.00')
  })

  test('displays calculation type label with info tooltip', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="calc-type-label"]')

    await expect(page.getByTestId('calc-type-label')).toContainText('PARAMETRIC')

    // Open tooltip
    await page.getByTestId('calc-type-info').click()
    await expect(page.getByTestId('calc-type-tooltip')).toBeVisible()
    await expect(page.getByTestId('calc-type-tooltip')).toContainText('Variance-covariance')

    // Close via close button
    await page.getByTestId('calc-type-tooltip-close').click()
    await expect(page.getByTestId('calc-type-tooltip')).not.toBeVisible()
  })

  test('displays VaR limit utilisation bar when a VAR_BREACH rule exists', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      rules: TEST_VAR_LIMIT_RULE,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-limit"]')

    // 125000.50 / 200000 = 0.625 -> 63%
    await expect(page.getByTestId('var-limit')).toContainText('63%')
    await expect(page.getByTestId('var-limit-bar-fill')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// VaR Dashboard - Empty and Error States
// ---------------------------------------------------------------------------

test.describe('VaR Dashboard - Empty and Error States', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows empty state when no VaR results exist', async ({ page }) => {
    await mockRiskTabRoutes(page, { varResult: null })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-empty"]')

    await expect(page.getByTestId('var-empty')).toContainText('No VaR results yet')
    await expect(page.getByTestId('var-dashboard')).not.toBeVisible()
  })

  test('shows error state when VaR fetch fails', async ({ page }) => {
    // Override only the VaR endpoint to return 500
    await mockRiskTabRoutes(page, {})
    await page.unroute('**/api/v1/risk/var/*')
    await page.route('**/api/v1/risk/var/*', (route) => {
      route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ message: 'Server error' }) })
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-error"]')

    await expect(page.getByTestId('var-error')).toBeVisible()
  })

  test('shows loading spinner while VaR data is fetched', async ({ page }) => {
    // Override VaR to delay 2 seconds
    await mockRiskTabRoutes(page, {})
    await page.unroute('**/api/v1/risk/var/*')
    await page.route('**/api/v1/risk/var/*', (route) => {
      setTimeout(
        () => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TEST_VAR_RESULT) }),
        2000,
      )
    })

    await goToRiskTab(page)

    // Loading spinner should be visible initially
    await expect(page.getByTestId('var-loading')).toBeVisible()

    // After the response, dashboard appears
    await page.waitForSelector('[data-testid="var-dashboard"]', { timeout: 5000 })
    await expect(page.getByTestId('var-dashboard')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// VaR Dashboard - Confidence Level
// ---------------------------------------------------------------------------

test.describe('VaR Dashboard - Confidence Level', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('95% toggle is active by default', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="confidence-toggle-95"]')

    await expect(page.getByTestId('confidence-toggle-95')).toHaveClass(/bg-primary-100/)
    await expect(page.getByTestId('confidence-toggle-99')).not.toHaveClass(/bg-primary-100/)
  })

  test('switching to 99% activates the 99% toggle', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="confidence-toggle-99"]')

    await page.getByTestId('confidence-toggle-99').click()

    await expect(page.getByTestId('confidence-toggle-99')).toHaveClass(/bg-primary-100/)
    await expect(page.getByTestId('confidence-toggle-95')).not.toHaveClass(/bg-primary-100/)
  })

  test('confidence toggles are disabled during refresh', async ({ page }) => {
    await page.unroute('**/api/v1/risk/var/*')
    await page.route('**/api/v1/risk/var/*', (route) => {
      const method = route.request().method()
      if (method === 'POST') {
        // Delay POST to keep refreshing state active
        setTimeout(
          () => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TEST_VAR_RESULT) }),
          2000,
        )
      } else {
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TEST_VAR_RESULT) })
      }
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-recalculate"]')

    // Click refresh
    await page.getByTestId('var-recalculate').click()

    // Both toggles should be disabled during refresh
    await expect(page.getByTestId('confidence-toggle-95')).toBeDisabled()
    await expect(page.getByTestId('confidence-toggle-99')).toBeDisabled()
  })
})

// ---------------------------------------------------------------------------
// VaR Dashboard - Info Popovers
// ---------------------------------------------------------------------------

test.describe('VaR Dashboard - Info Popovers', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('VaR info popover opens on click and closes on Escape', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-info"]')

    await page.getByTestId('var-info').click()
    await expect(page.getByTestId('var-popover')).toBeVisible()
    await expect(page.getByTestId('var-popover')).toContainText('Value at Risk')

    await page.keyboard.press('Escape')
    await expect(page.getByTestId('var-popover')).not.toBeVisible()
  })

  test('ES info popover opens on click and closes via close button', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="es-info"]')

    await page.getByTestId('es-info').click()
    await expect(page.getByTestId('es-popover')).toBeVisible()
    await expect(page.getByTestId('es-popover')).toContainText('Expected Shortfall')

    await page.getByTestId('es-popover-close').click()
    await expect(page.getByTestId('es-popover')).not.toBeVisible()
  })

  test('Greek info popover opens and displays description', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="greek-info-delta"]')

    await page.getByTestId('greek-info-delta').click()
    await expect(page.getByTestId('greek-popover-delta')).toBeVisible()
    await expect(page.getByTestId('greek-popover-delta')).toContainText('the underlying')

    await page.getByTestId('greek-popover-delta-close').click()
    await expect(page.getByTestId('greek-popover-delta')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// VaR Dashboard - Chart and Actions
// ---------------------------------------------------------------------------

test.describe('VaR Dashboard - Chart and Actions', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('toggles between VaR/ES and Greeks chart views', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="chart-toggle-var"]')

    // Initially VaR is active
    await expect(page.getByTestId('chart-toggle-var')).toHaveClass(/bg-primary-100/)

    // Switch to Greeks
    await page.getByTestId('chart-toggle-greeks').click()
    await expect(page.getByTestId('chart-toggle-greeks')).toHaveClass(/bg-primary-100/)
    await expect(page.getByTestId('chart-toggle-var')).not.toHaveClass(/bg-primary-100/)

    // Switch back to VaR
    await page.getByTestId('chart-toggle-var').click()
    await expect(page.getByTestId('chart-toggle-var')).toHaveClass(/bg-primary-100/)
  })

  test('clicking Refresh triggers POST and updates values', async ({ page }) => {
    const refreshedResult = {
      ...TEST_VAR_RESULT,
      varValue: '130000.00',
      calculatedAt: new Date().toISOString(),
    }

    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      postVarResult: refreshedResult,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-recalculate"]')

    // Click refresh
    await page.getByTestId('var-recalculate').click()

    // After response, VaR value should update
    await expect(page.getByTestId('var-value')).toContainText('$130K')
  })

  test('What-If button is visible and clickable', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-whatif-button"]')

    await expect(page.getByTestId('var-whatif-button')).toContainText('What-If')
    await page.getByTestId('var-whatif-button').click()

    // What-If panel should open
    await expect(page.getByTestId('whatif-panel')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Position Risk Table - Data Display
// ---------------------------------------------------------------------------

test.describe('Position Risk Table - Data Display', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('displays position rows with Greeks, VaR/ES contributions, and % of total', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // AAPL row
    const aaplRow = page.getByTestId('position-risk-row-AAPL')
    await expect(aaplRow).toContainText('AAPL')
    await expect(aaplRow).toContainText('155.00') // delta
    await expect(aaplRow).toContainText('5,000.00') // VaR

    // EUR_USD row
    const fxRow = page.getByTestId('position-risk-row-EUR_USD')
    await expect(fxRow).toContainText('EUR_USD')
  })

  test('null Greeks display as em-dash', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // EUR_USD has null gamma, vega, theta
    const fxRow = page.getByTestId('position-risk-row-EUR_USD')
    const cells = fxRow.locator('td')

    // gamma, vega, theta columns (indices 4, 5, 6 in the row) should show em-dash
    // The table structure: Instrument(0), Asset Class(1), Market Value(2), Delta(3), Gamma(4), Vega(5), Theta(6), Rho(7), VaR(8), ES(9), %(10)
    await expect(cells.nth(4)).toContainText('\u2014')
    await expect(cells.nth(5)).toContainText('\u2014')
    await expect(cells.nth(6)).toContainText('\u2014')
  })

  test('% of Total color coding: red >30%, amber >15%', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // AAPL = 35% -> red
    await expect(page.getByTestId('pct-total-AAPL')).toHaveClass(/text-red-600/)

    // EUR_USD = 21% -> amber
    await expect(page.getByTestId('pct-total-EUR_USD')).toHaveClass(/text-amber-600/)

    // GOOGL = 14% -> no special color
    await expect(page.getByTestId('pct-total-GOOGL')).not.toHaveClass(/text-red-600/)
    await expect(page.getByTestId('pct-total-GOOGL')).not.toHaveClass(/text-amber-600/)
  })

  test('clicking a row expands the detail panel, clicking again collapses', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // Click AAPL row to expand
    await page.getByTestId('position-risk-row-AAPL').click()
    await expect(page.getByTestId('position-risk-detail-AAPL')).toBeVisible()

    // Should show Market Value, VaR Contribution, etc.
    await expect(page.getByTestId('position-risk-detail-AAPL')).toContainText('Market Value')
    await expect(page.getByTestId('position-risk-detail-AAPL')).toContainText('VaR Contribution')

    // Click again to collapse
    await page.getByTestId('position-risk-row-AAPL').click()
    await expect(page.getByTestId('position-risk-detail-AAPL')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Position Risk Table - Sorting and Export
// ---------------------------------------------------------------------------

test.describe('Position Risk Table - Sorting and Export', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('default sort by VaR Contribution descending (absolute)', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // Default sort: VaR desc -> AAPL(5000), EUR_USD(3000), GOOGL(2000)
    const rows = page.locator('[data-testid^="position-risk-row-"]')
    await expect(rows.nth(0)).toContainText('AAPL')
    await expect(rows.nth(1)).toContainText('EUR_USD')
    await expect(rows.nth(2)).toContainText('GOOGL')
  })

  test('clicking sort header toggles ascending/descending', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // Click VaR Contribution to toggle to ascending.
    // VaR values: AAPL=5000, EUR_USD=3000, GOOGL=2000, UST_10Y=1500.
    // Ascending: UST_10Y, GOOGL, EUR_USD, AAPL.
    await page.getByTestId('sort-varContribution').click()

    const rows = page.locator('[data-testid^="position-risk-row-"]')
    await expect(rows.nth(0)).toContainText('UST_10Y')
    await expect(rows.nth(1)).toContainText('GOOGL')
    await expect(rows.nth(2)).toContainText('EUR_USD')
    await expect(rows.nth(3)).toContainText('AAPL')

    // Click again to toggle back to descending
    await page.getByTestId('sort-varContribution').click()
    await expect(rows.nth(0)).toContainText('AAPL')
  })

  test('sorting by a different column resets to descending', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // Click delta header to sort by delta descending (signed, not absolute).
    await page.getByTestId('sort-delta').click()

    // Delta values: AAPL=155, EUR_USD=108.50, GOOGL=1425, UST_10Y=425
    // Descending signed: GOOGL(1425), UST_10Y(425), AAPL(155), EUR_USD(108.50)
    const rows = page.locator('[data-testid^="position-risk-row-"]')
    await expect(rows.nth(0)).toContainText('GOOGL')
    await expect(rows.nth(1)).toContainText('UST_10Y')
    await expect(rows.nth(2)).toContainText('AAPL')
    await expect(rows.nth(3)).toContainText('EUR_USD')
  })

  test('CSV export downloads position-risk.csv with correct headers and data', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="risk-csv-export"]')

    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('risk-csv-export').click()
    const download = await downloadPromise

    expect(download.suggestedFilename()).toBe('position-risk.csv')

    const filePath = await download.path()
    expect(filePath).toBeTruthy()

    const { readFile } = await import('fs/promises')
    const content = await readFile(filePath!, 'utf-8')
    const lines = content.trim().split('\n')

    // Header row should contain all column labels
    const header = lines[0]
    expect(header).toContain('Instrument')
    expect(header).toContain('Asset Class')
    expect(header).toContain('VaR Contrib')

    // 4 data rows + 1 header = 5 lines (AAPL, EUR_USD, GOOGL, UST_10Y).
    expect(lines).toHaveLength(5)

    // Data rows contain raw values
    const dataContent = lines.slice(1).join('\n')
    expect(dataContent).toContain('AAPL')
    expect(dataContent).toContain('EUR_USD')
    expect(dataContent).toContain('GOOGL')
    expect(dataContent).toContain('UST_10Y')
    // DV01 column populated for the rates row.
    expect(dataContent).toContain('4250.75')
  })
})

// ---------------------------------------------------------------------------
// Position Risk Table - DV01 column (rates instruments, kx-dmw)
// ---------------------------------------------------------------------------

test.describe('Position Risk Table - DV01 column (rates instruments)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('DV01 column header is visible on the Risk tab position table', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    const header = page.getByTestId('sort-dv01')
    await expect(header).toBeVisible()
    await expect(header).toContainText('DV01')
  })

  test('DV01 cell shows a non-zero USD value for a FIXED_INCOME (UST) row', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    const cell = page.getByTestId('dv01-UST_10Y')
    await expect(cell).toBeVisible()
    // Formatted as USD ($4,250.75 from fixture dv01='4250.75').
    await expect(cell).toContainText('$4,250.75')
  })

  test('DV01 cell renders em-dash for equity rows', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    const cell = page.getByTestId('dv01-AAPL')
    await expect(cell).toBeVisible()
    await expect(cell).toContainText('—')
  })
})

// ---------------------------------------------------------------------------
// Position Risk Table - Empty and Error States
// ---------------------------------------------------------------------------

test.describe('Position Risk Table - Empty and Error States', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows empty state when no position risk data', async ({ page }) => {
    await mockRiskTabRoutes(page, { positionRisk: [] })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-empty"]')

    await expect(page.getByTestId('position-risk-empty')).toBeVisible()
    await expect(page.getByTestId('risk-csv-export')).not.toBeVisible()
  })

  test('shows error state when position risk fetch fails', async ({ page }) => {
    await mockRiskTabRoutes(page, {})
    await page.unroute('**/api/v1/risk/positions/*')
    await page.route('**/api/v1/risk/positions/*', (route) => {
      route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ message: 'Server error' }) })
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-error"]')

    await expect(page.getByTestId('position-risk-error')).toContainText('Unable to load position risk')
  })

  test('collapsing and expanding the section toggles table visibility', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // Collapse
    await page.getByTestId('position-risk-toggle').click()
    await expect(page.getByTestId('position-risk-table')).not.toBeVisible()

    // Expand
    await page.getByTestId('position-risk-toggle').click()
    await expect(page.getByTestId('position-risk-table')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// P&L Summary Card
// ---------------------------------------------------------------------------

test.describe('P&L Summary Card', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows no-baseline message when SOD does not exist', async ({ page }) => {
    await mockRiskTabRoutes(page, { sodStatus: null, pnlAttribution: null })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="pnl-summary-card"]')

    await expect(page.getByTestId('pnl-no-baseline')).toBeVisible()
    await expect(page.getByTestId('pnl-no-baseline')).toContainText('No SOD baseline')
  })

  test('shows compute prompt when baseline exists but no P&L computed', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      sodStatus: { exists: true, baselineDate: '2025-01-15', snapshotType: 'MANUAL', createdAt: '2025-01-15T08:00:00Z', sourceJobId: null, calculationType: null },
      pnlAttribution: null,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="pnl-summary-card"]')

    await expect(page.getByTestId('pnl-compute-prompt')).toBeVisible()
    await expect(page.getByTestId('pnl-compute-prompt')).toContainText('SOD baseline active')
  })

  test('displays P&L attribution with color coding', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      sodStatus: { exists: true, baselineDate: '2025-01-15', snapshotType: 'MANUAL', createdAt: '2025-01-15T08:00:00Z', sourceJobId: null, calculationType: null },
      pnlAttribution: TEST_PNL_ATTRIBUTION,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="pnl-summary-data"]')

    // Total P&L positive -> green
    await expect(page.getByTestId('pnl-total-value')).toContainText('15,250.00')
    await expect(page.getByTestId('pnl-total-value')).toHaveClass(/text-green-600/)

    // Delta factor
    await expect(page.getByTestId('pnl-factor-delta')).toContainText('8,500.00')

    // Theta factor (negative) -> red
    await expect(page.getByTestId('pnl-factor-theta')).toContainText('-1,500.00')
    await expect(page.getByTestId('pnl-factor-theta')).toHaveClass(/text-red-600/)

    // View full attribution link
    await expect(page.getByTestId('pnl-view-full-attribution')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Stress Summary Card
// ---------------------------------------------------------------------------

test.describe('Stress Summary Card', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows empty message with Run button when no stress results exist', async ({ page }) => {
    await mockRiskTabRoutes(page, {})

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="stress-summary-card"]')

    await expect(page.getByTestId('stress-summary-card')).toContainText('No stress test results yet')
    await expect(page.getByTestId('stress-summary-run-btn')).toContainText('Run Stress Tests')
  })

  test('running a stress test populates the summary table', async ({ page }) => {
    const stressResult = {
      scenarioName: 'EQUITY_CRASH',
      baseVar: '125000',
      stressedVar: '375000',
      pnlImpact: '-250000',
      assetClassImpacts: [],
      calculatedAt: '2025-01-15T12:05:00Z',
      positionImpacts: [],
      limitBreaches: [],
    }

    await mockRiskTabRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      stressResult,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="stress-summary-run-btn"]')

    // Click run
    await page.getByTestId('stress-summary-run-btn').click()

    // Wait for table to appear
    await page.waitForSelector('[data-testid="stress-summary-table"]')

    // Summary row should show the scenario
    await expect(page.getByTestId('stress-summary-row')).toContainText('EQUITY CRASH')

    // P&L impact should be red (negative)
    await expect(page.getByTestId('stress-summary-pnl-impact')).toHaveClass(/text-red-600/)
  })

  test('View Details link navigates to Scenarios tab', async ({ page }) => {
    const stressResult = {
      scenarioName: 'EQUITY_CRASH',
      baseVar: '125000',
      stressedVar: '375000',
      pnlImpact: '-250000',
      assetClassImpacts: [],
      calculatedAt: '2025-01-15T12:05:00Z',
      positionImpacts: [],
      limitBreaches: [],
    }

    await mockRiskTabRoutes(page, {
      stressScenarios: ['EQUITY_CRASH'],
      stressResult,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="stress-summary-run-btn"]')

    // Run stress first to show results with View Details
    await page.getByTestId('stress-summary-run-btn').click()
    await page.waitForSelector('[data-testid="stress-summary-view-details"]')

    // Click View Details
    await page.getByTestId('stress-summary-view-details').click()

    // Should navigate to Scenarios tab
    await expect(page.getByTestId('tab-scenarios')).toHaveAttribute('aria-selected', 'true')
  })
})

// ---------------------------------------------------------------------------
// Alert Banner
// ---------------------------------------------------------------------------

test.describe('Alert Banner', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('displays CRITICAL and WARNING alerts with severity styling', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      alerts: TEST_ALERTS,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="risk-alert-banner"]')

    // CRITICAL alerts also surface in the global BreachBanner (§3.4 — breach
    // banner follows the user). Scope to the Risk-tab dashboard banner so we
    // assert on the in-tab instance, not the global one above it.
    const riskTabBanner = page.getByRole('tabpanel').getByTestId('risk-alert-banner')

    // CRITICAL alert has role="alert"
    await expect(riskTabBanner.getByTestId('alert-item-alert-1')).toHaveAttribute('role', 'alert')

    // WARNING alert does NOT have role="alert"
    const warningRole = await riskTabBanner.getByTestId('alert-item-alert-2').getAttribute('role')
    expect(warningRole).toBeNull()
  })

  test('dismissing an alert removes it from the banner', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      alerts: TEST_ALERTS,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="alert-item-alert-1"]')

    // CRITICAL alerts also surface in the global BreachBanner. Scope the
    // dismiss interaction to the Risk-tab dashboard banner so we exercise the
    // in-tab instance specifically.
    const riskTabBanner = page.getByRole('tabpanel').getByTestId('risk-alert-banner')

    // Dismiss first alert
    await riskTabBanner.getByTestId('alert-dismiss-alert-1').click()

    // First alert should disappear from the in-tab banner
    await expect(riskTabBanner.getByTestId('alert-item-alert-1')).not.toBeVisible()

    // Second alert (WARNING) should remain in the in-tab banner
    await expect(riskTabBanner.getByTestId('alert-item-alert-2')).toBeVisible()
  })

  test('no banner when no alerts exist', async ({ page }) => {
    await mockRiskTabRoutes(page, { alerts: [] })

    await goToRiskTab(page)
    // Wait for the risk tab content to load
    await page.waitForSelector('[data-testid="pnl-summary-card"]')

    // Check the in-tab banner specifically — the global BreachBanner is also
    // absent here because there's no breach and no CRITICAL alerts.
    await expect(page.getByRole('tabpanel').getByTestId('risk-alert-banner')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Job History
// ---------------------------------------------------------------------------

test.describe('Job History', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows summary when collapsed', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      jobHistory: TEST_JOB_HISTORY,
    })

    // Force collapsed state (default is now expanded)
    await page.goto('/')
    await page.evaluate(() => localStorage.setItem('kinetix:job-history-expanded', 'false'))
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="job-history"]')

    // Collapsed -> shows summary
    await expect(page.getByTestId('job-history-summary')).toBeVisible()
    await expect(page.getByTestId('job-history-summary')).toContainText('Last calc:')
    await expect(page.getByTestId('job-history-summary')).toContainText('1 job')
  })

  test('shows job table when expanded by default', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      jobHistory: TEST_JOB_HISTORY,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="job-history-table"]')

    // Job table is visible by default (expanded)
    await expect(page.getByTestId('job-row-job-1')).toBeVisible()
  })

  test('job history pagination with multiple pages', async ({ page }) => {
    // Build 25 jobs so pagination returns real data on each page
    const jobs = Array.from({ length: 25 }, (_, i) => ({
      jobId: `job-${i + 1}`,
      bookId: 'port-1',
      triggerType: 'ON_DEMAND',
      status: 'COMPLETED',
      startedAt: new Date(Date.now() - (10 - i) * 60_000).toISOString(),
      completedAt: new Date(Date.now() - (10 - i) * 60_000 + 5000).toISOString(),
      durationMs: 5000,
      calculationType: 'PARAMETRIC',
      confidenceLevel: 'CL_95',
      varValue: 125000 + i * 1000,
      expectedShortfall: 187500,
      pvValue: 5000000,
      delta: null,
      gamma: null,
      vega: null,
      theta: null,
      rho: null,
    }))

    // Use mockRiskTabRoutes with a custom jobHistory handler by passing
    // initial job history, then override the jobs route specifically.
    await mockRiskTabRoutes(page, {})

    // Override job history: unroute existing, then re-register with pagination logic.
    await page.unroute('**/api/v1/risk/jobs/*')
    await page.route('**/api/v1/risk/jobs/*', (route) => {
      const url = route.request().url()
      const urlObj = new URL(url)
      const limit = parseInt(urlObj.searchParams.get('limit') ?? '10', 10)
      const offset = parseInt(urlObj.searchParams.get('offset') ?? '0', 10)
      // For chart requests (limit=10000), return all items
      if (limit >= 1000) {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: jobs, totalCount: 25 }),
        })
        return
      }
      const pageItems = jobs.slice(offset, Math.min(offset + limit, jobs.length))
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: pageItems, totalCount: 25 }),
      })
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="job-history-table"]')

    // Total count shows 25
    await expect(page.getByTestId('total-count')).toContainText('Total: 25')

    // Click next page
    await page.getByTestId('pagination-next').click()

    // Page input updates to "2"
    await expect(page.getByTestId('pagination-page-input')).toHaveValue('2')

    // Previous button becomes enabled
    await expect(page.getByTestId('pagination-prev')).toBeEnabled()
  })

  test('clicking a job row expands it and shows the job timeline', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      jobHistory: TEST_JOB_HISTORY,
      jobDetail: TEST_JOB_DETAIL,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="job-history-table"]')

    // Click the job row to expand it
    await page.getByTestId('job-row-job-1').click()

    // Wait for the detail panel to appear
    await expect(page.getByTestId('job-detail-row-job-1')).toBeVisible()
    await expect(page.getByTestId('job-detail-panel')).toBeVisible()

    // Verify the job timeline shows the steps from the detail response
    await expect(page.getByTestId('job-detail-panel')).toContainText('Fetch Positions')
    await expect(page.getByTestId('job-detail-panel')).toContainText('Fetch Market Data')
    await expect(page.getByTestId('job-detail-panel')).toContainText('Valuation')

    // Click close to collapse
    await page.getByTestId('close-detail-job-1').click()
    await expect(page.getByTestId('job-detail-row-job-1')).not.toBeVisible()
  })

  test('full Risk tab renders all major sections', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
      alerts: TEST_ALERTS,
      sodStatus: { exists: true, baselineDate: '2025-01-15', snapshotType: 'MANUAL', createdAt: '2025-01-15T08:00:00Z', sourceJobId: null, calculationType: null },
      pnlAttribution: TEST_PNL_ATTRIBUTION,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    await expect(page.getByTestId('var-dashboard')).toBeVisible()
    await expect(page.getByTestId('position-risk-section')).toBeVisible()
    await expect(page.getByTestId('pnl-summary-card')).toBeVisible()
    await expect(page.getByTestId('stress-summary-card')).toBeVisible()
    await expect(page.getByTestId('job-history')).toBeVisible()
    // CRITICAL alerts also surface in the global BreachBanner above the tab;
    // scope this assertion to the in-tab Risk Dashboard banner.
    await expect(page.getByRole('tabpanel').getByTestId('risk-alert-banner')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Valuation Date Picker
// ---------------------------------------------------------------------------

test.describe('Valuation Date Picker', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('selecting Yesterday loads historical VaR and position risk data', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      historicalVarResult: TEST_HISTORICAL_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      historicalPositionRisk: TEST_HISTORICAL_POSITION_RISK,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // Live mode: shows current VaR
    await expect(page.getByTestId('var-dashboard')).toContainText('$125K')

    // Click Yesterday
    await page.getByTestId('vdate-yesterday').click()

    // Should show historical badge
    await expect(page.getByTestId('historical-badge')).toBeVisible()
    await expect(page.getByTestId('historical-badge')).toContainText('Historical')

    // Should show historical VaR value
    await expect(page.getByTestId('var-dashboard')).toContainText('$98K')

    // Should show the valuation date label
    await expect(page.getByTestId('valuation-date-label')).toContainText('2025-01-14')
  })

  test('selecting Yesterday shows historical position risk data', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      historicalVarResult: TEST_HISTORICAL_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      historicalPositionRisk: TEST_HISTORICAL_POSITION_RISK,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-section"]')

    // Live mode: 3 positions
    await expect(page.getByTestId('position-risk-section')).toContainText('GOOGL')

    // Click Yesterday
    await page.getByTestId('vdate-yesterday').click()

    // Wait for historical data — GOOGL should disappear (only 2 positions in historical)
    await expect(page.getByTestId('position-risk-section')).not.toContainText('GOOGL')
    await expect(page.getByTestId('position-risk-section')).toContainText('AAPL')
    await expect(page.getByTestId('position-risk-section')).toContainText('EUR_USD')
  })

  test('switching back to Today restores live data', async ({ page }) => {
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      historicalVarResult: TEST_HISTORICAL_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      historicalPositionRisk: TEST_HISTORICAL_POSITION_RISK,
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // Switch to Yesterday
    await page.getByTestId('vdate-yesterday').click()
    await expect(page.getByTestId('historical-badge')).toBeVisible()
    await expect(page.getByTestId('var-dashboard')).toContainText('$98K')

    // Switch back to Today
    await page.getByTestId('vdate-today').click()

    // Historical badge should disappear
    await expect(page.getByTestId('historical-badge')).not.toBeVisible()

    // Live VaR should be restored
    await expect(page.getByTestId('var-dashboard')).toContainText('$125K')
  })

  test('valuationDate query parameter is sent to the API', async ({ page }) => {
    const varRequests: string[] = []

    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      historicalVarResult: TEST_HISTORICAL_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      historicalPositionRisk: TEST_HISTORICAL_POSITION_RISK,
    })

    // Intercept VaR GET requests to capture URLs
    await page.unroute('**/api/v1/risk/var/*')
    await page.route('**/api/v1/risk/var/*', (route) => {
      const url = route.request().url()
      if (route.request().method() === 'GET') {
        varRequests.push(url)
      }
      const isHistorical = url.includes('valuationDate=')
      const result = isHistorical ? TEST_HISTORICAL_VAR_RESULT : TEST_VAR_RESULT
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(result) })
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // Click Yesterday
    await page.getByTestId('vdate-yesterday').click()
    await expect(page.getByTestId('historical-badge')).toBeVisible()

    // Verify at least one request included valuationDate
    const historicalRequest = varRequests.find((u) => u.includes('valuationDate='))
    expect(historicalRequest).toBeTruthy()
  })
})
