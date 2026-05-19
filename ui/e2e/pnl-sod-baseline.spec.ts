import { test, expect } from '@playwright/test'
import { mockAllApiRoutes, mockRiskTabRoutes } from './fixtures'

// Plan §8.2 — once demo-orchestrator's SOD-baseline capture job (commit
// 288328a1) runs at day-open, the live P&L tab should NOT show the
// "No SOD baseline for today" callout, and the P&L Waterfall should show
// non-zero values across at least three of (Delta, Gamma, Vega, Theta,
// Rho). This spec mocks the post-8.1 backend state and pins the UI's
// rendering of it.

const POPULATED_SOD = {
  exists: true,
  baselineDate: '2026-05-19',
  snapshotType: 'AUTO_CLOSE',
  createdAt: '2026-05-19T09:00:00Z',
  sourceJobId: 'sod-job-1',
  calculationType: 'PARAMETRIC',
}

const POPULATED_WATERFALL = {
  bookId: 'port-1',
  date: '2026-05-19',
  totalPnl: '85432.10',
  deltaPnl: '17704.06',
  gammaPnl: '12300.00',
  vegaPnl: '8400.50',
  thetaPnl: '-2100.00',
  rhoPnl: '450.25',
  unexplainedPnl: '0.00',
}

test.describe('P&L tab — SOD baseline populated (post-PR 8.1 backend state)', () => {
  test('hides the "No SOD baseline" callout when baseline exists', async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      sodStatus: POPULATED_SOD,
      pnlAttribution: POPULATED_WATERFALL,
    })

    await page.goto('/')
    await page.getByTestId('tab-pnl').click()

    // The yellow callout that flagged the broken state must NOT render.
    await expect(page.getByTestId('sod-baseline-warning')).toHaveCount(0)
    // The active-baseline indicator should render in its place.
    await expect(page.getByTestId('sod-baseline-active')).toBeVisible()
  })

  test('waterfall renders non-zero values for at least three Greek factors', async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      sodStatus: POPULATED_SOD,
      pnlAttribution: POPULATED_WATERFALL,
    })

    await page.goto('/')
    await page.getByTestId('tab-pnl').click()

    await expect(page.getByTestId('waterfall-chart')).toBeVisible()

    const factors = ['delta', 'gamma', 'vega', 'theta', 'rho']
    let nonZeroCount = 0
    for (const factor of factors) {
      const cell = page.getByTestId(`waterfall-value-${factor}`)
      const txt = (await cell.textContent())?.replace(/[\s,$]/g, '') ?? ''
      // Strip leading "+"/"−"/"-" / trailing currency tokens / etc.
      // Non-zero == numeric string whose absolute value is > 0.
      const numeric = parseFloat(txt.replace(/[^0-9.\-+]/g, ''))
      if (!Number.isNaN(numeric) && Math.abs(numeric) > 0) {
        nonZeroCount += 1
      }
    }
    expect(nonZeroCount).toBeGreaterThanOrEqual(3)
  })
})
