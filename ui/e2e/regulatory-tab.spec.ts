import { test, expect, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

const FRTB_RESULT = {
  bookId: 'port-1',
  sbmCharges: [
    { riskClass: 'GIRR', deltaCharge: '19.86', vegaCharge: '180532.51', curvatureCharge: '2030.99', totalCharge: '182583.36' },
    { riskClass: 'CSR_NON_SEC', deltaCharge: '16247.93', vegaCharge: '7221.30', curvatureCharge: '243.72', totalCharge: '23712.95' },
    { riskClass: 'CSR_SEC_CTP', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
    { riskClass: 'CSR_SEC_NON_CTP', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
    { riskClass: 'EQUITY', deltaCharge: '227231.14', vegaCharge: '127817.52', curvatureCharge: '22723.11', totalCharge: '377771.78' },
    { riskClass: 'COMMODITY', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
    { riskClass: 'FX', deltaCharge: '41869.98', vegaCharge: '26796.79', curvatureCharge: '2093.50', totalCharge: '70760.27' },
  ],
  totalSbmCharge: '635674.38',
  grossJtd: '162486.91',
  hedgeBenefit: '3.83',
  netDrc: '162483.09',
  exoticNotional: '850.20',
  otherNotional: '27919327.83',
  totalRrao: '27927.83',
  totalCapitalCharge: '826085.29',
  calculatedAt: '2026-05-19T13:22:48Z',
}

const REPORT_CSV = {
  bookId: 'port-1',
  format: 'CSV',
  content: 'risk_class,delta,vega,curvature,total\nEQUITY,227231.14,127817.52,22723.11,377771.78\n',
  generatedAt: '2026-05-19T13:22:48Z',
}

test.describe('Regulatory tab — FRTB result rendering', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)

    await page.route('**/api/v1/regulatory/frtb/**', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(FRTB_RESULT),
      })
    })

    await page.route('**/api/v1/regulatory/report/**', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(REPORT_CSV),
      })
    })

    await page.goto('/')
    await page.getByTestId('tab-regulatory').click()
  })

  test('renders the SBM table with formatted EQUITY delta and total after Calculate FRTB', async ({
    page,
  }) => {
    await expect(page.getByTestId('regulatory-dashboard')).toBeVisible()

    await page.getByTestId('frtb-calculate-btn').click()

    const table = page.getByTestId('frtb-sbm-table')
    await expect(table).toBeVisible()

    const equityRow = page.getByTestId('frtb-row-EQUITY')
    await expect(equityRow).toBeVisible()
    // Delta and total formatted with thousands separators.
    await expect(equityRow).toContainText('$227,231.14')
    await expect(equityRow).toContainText('$377,771.78')
  })

  test('Download CSV triggers a file download', async ({ page }) => {
    await page.getByTestId('frtb-calculate-btn').click()
    // Wait for the result to render so the Download CSV button is enabled.
    await expect(page.getByTestId('frtb-sbm-table')).toBeVisible()

    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('download-csv-btn').click()
    const download = await downloadPromise

    expect(download.suggestedFilename()).toMatch(/\.csv$/)
  })
})
