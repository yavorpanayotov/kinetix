import { test, expect } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_POSITION_RISK_FULL,
  TEST_JOB_HISTORY,
} from './fixtures'

// ---------------------------------------------------------------------------
// Trader-review P0 #2: per-instrument DV01 / Theta / Rho rendering.
//
// On the live demo at kinetixrisk.ai every row of `Risk → Position Risk
// Breakdown` rendered `—` for DV01, Theta, and Rho — even bonds, even
// options. The gateway and orchestrator now surface those values
// per-instrument; this spec pins the UI rendering so the regression cannot
// silently come back.
//
// The fixture (TEST_POSITION_RISK_FULL) already carries:
//   - UST_10Y (FIXED_INCOME, dv01: '4250.75', theta: null, rho: '4250.00')
//   - AAPL    (EQUITY,       theta: '-12.50',  rho: '8.00')
//   - GOOGL   (EQUITY,       theta: '-42.00',  rho: '95.00')
//   - EUR_USD (FX,           theta: null,      rho: '15.00')
//
// We assert that:
//   1. The DV01 column header is present.
//   2. The DV01 cell renders a non-zero formatted USD number for a Treasury.
//   3. The DV01 cell renders em-dash for cash-equity rows (rates-only column).
//   4. The Theta and Rho cells render the numeric value for equity rows.
//   5. The Theta cell renders em-dash for an FX row whose theta is null.
// ---------------------------------------------------------------------------

async function goToRiskTab(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
}

test.describe('Position Risk Breakdown — per-instrument DV01 / Theta / Rho', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('DV01 column header is visible on the position-risk table', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    const header = page.getByTestId('sort-dv01')
    await expect(header).toBeVisible()
    await expect(header).toContainText('DV01')
  })

  test('DV01 renders a non-zero USD number for the Treasury row', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // Treasury row carries dv01: '4250.75' in the fixture.
    const cell = page.getByTestId('dv01-UST_10Y')
    await expect(cell).toBeVisible()
    await expect(cell).toContainText('$4,250.75')
    // Sanity-check: the cell does NOT show the em-dash regression marker.
    await expect(cell).not.toHaveText('—')
  })

  test('DV01 cell renders em-dash for a cash-equity row (rates-only column)', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // AAPL is EQUITY — DV01 does not apply; the table must render em-dash
    // rather than "$0.00" so a trader does not misread it as a real
    // sensitivity. This guards the column-formatter as much as the data
    // path.
    const cell = page.getByTestId('dv01-AAPL')
    await expect(cell).toBeVisible()
    await expect(cell).toContainText('—')
  })

  test('Theta and Rho columns render numeric values for equity rows', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // The Risk-tab table renders Theta / Rho as the 7th and 8th data cells.
    // Column order in COLUMNS array:
    //   Instrument(0), Asset Class(1), Mkt Value(2), Delta(3), Gamma(4),
    //   Vega(5), Theta(6), Rho(7), DV01(8), VaR(9), ES(10), %(11), explain(12)
    //
    // AAPL fixture: theta '-12.50', rho '8.00'.
    const aaplRow = page.getByTestId('position-risk-row-AAPL')
    const aaplCells = aaplRow.locator('td')
    await expect(aaplCells.nth(6)).toContainText('-12.50')
    await expect(aaplCells.nth(7)).toContainText('8.00')

    // GOOGL fixture: theta '-42.00', rho '95.00'.
    const googlRow = page.getByTestId('position-risk-row-GOOGL')
    const googlCells = googlRow.locator('td')
    await expect(googlCells.nth(6)).toContainText('-42.00')
    await expect(googlCells.nth(7)).toContainText('95.00')
  })

  test('Theta renders em-dash on the FX row where it is structurally null', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // EUR_USD fixture: theta null — em-dash is the correct rendering for
    // the missing-data case (vs the explicit-zero case for cash equity,
    // which lands in the orchestrator's analytical path).
    const fxRow = page.getByTestId('position-risk-row-EUR_USD')
    const fxCells = fxRow.locator('td')
    await expect(fxCells.nth(6)).toContainText('—')
  })

  test('DV01 detail panel shows the formatted USD value when the Treasury row is expanded', async ({ page }) => {
    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="position-risk-table"]')

    // Click the Treasury row to expand the detail panel.
    await page.getByTestId('position-risk-row-UST_10Y').click()
    await expect(page.getByTestId('position-risk-detail-UST_10Y')).toBeVisible()

    const dv01Detail = page.getByTestId('dv01-detail-UST_10Y')
    await expect(dv01Detail).toBeVisible()
    await expect(dv01Detail).toContainText('$4,250.75')
  })
})
