import { test, expect, type Page } from '@playwright/test'
import { mockAllApiRoutes, mockCounterpartyRiskRoutes } from './fixtures'

// Trader-review P2 #26: the Peak PFE tile must declare its methodology,
// confidence level and horizon so the number is interpretable. A "Peak PFE
// $7.2M" with no qualifier is meaningless to a credit-risk officer — it could
// be 95% or 99%, 1Y or 5Y, parametric or Monte Carlo. The PFE in Kinetix is
// computed by a Cholesky-based Monte Carlo engine at the 95th percentile
// (see risk-engine/src/kinetix_risk/credit_exposure.py), so the tile renders
// a methodology label (e.g. "MC_95_1Y" / "Monte Carlo · 95% · 1Y").

async function goToCounterpartyRiskTab(page: Page) {
  await page.goto('/')
  await page.getByTestId('tab-counterparty-risk').click()
}

const EXPOSURE_WITH_PROFILE = [
  {
    counterpartyId: 'CP-GS',
    calculatedAt: '2026-03-24T10:00:00Z',
    currentNetExposure: 2_000_000,
    peakPfe: 1_800_000,
    cva: 12_500,
    cvaEstimated: false,
    currency: 'USD',
    pfeProfile: [
      { tenor: '1Y', tenorYears: 1, expectedExposure: 1_500_000, pfe95: 1_800_000, pfe99: 2_000_000 },
      { tenor: '2Y', tenorYears: 2, expectedExposure: 1_200_000, pfe95: 1_500_000, pfe99: 1_700_000 },
    ],
  },
]

test.describe('Counterparty Risk - Peak PFE methodology label', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page, EXPOSURE_WITH_PROFILE)
  })

  test('the Peak PFE tile shows a methodology / confidence / horizon label', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await page.getByTestId('counterparty-row-CP-GS').click()
    await expect(page.getByTestId('detail-peak-pfe')).toBeVisible()

    const methodology = page.getByTestId('pfe-methodology')
    await expect(methodology).toBeVisible()

    // Must declare the method (Monte Carlo), the confidence level (95%) and a
    // horizon. The compact machine token "MC_95_1Y" is acceptable, as is the
    // human-readable "Monte Carlo · 95% · 1Y" — assert the load-bearing parts.
    const text = (await methodology.textContent()) ?? ''
    expect(text).toMatch(/MC_95_1Y|Monte Carlo/)
    expect(text).toMatch(/95/)
    expect(text).toMatch(/1Y/)
  })
})
