import { test, expect, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// The most recent persisted FRTB calculation the Regulatory tab should load by
// default, so the tab does not start on the empty "Click Calculate FRTB" state.
const LAST_FRTB = {
  bookId: 'port-1',
  sbmCharges: [
    { riskClass: 'GIRR', deltaCharge: '19.86', vegaCharge: '180532.51', curvatureCharge: '2030.99', totalCharge: '182583.36' },
    { riskClass: 'EQUITY', deltaCharge: '227231.14', vegaCharge: '127817.52', curvatureCharge: '22723.11', totalCharge: '377771.78' },
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

test.describe('Regulatory tab — default shows last calculation', () => {
  test('loads the most recent FRTB calculation by default instead of the empty state', async ({
    page,
  }) => {
    await mockAllApiRoutes(page)

    // GET .../latest returns the most recent stored calculation.
    await page.route('**/api/v1/regulatory/frtb/*/latest', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(LAST_FRTB),
      })
    })

    await page.goto('/')
    await page.getByTestId('tab-regulatory').click()

    // The results render without the user clicking Calculate FRTB.
    await expect(page.getByTestId('regulatory-results')).toBeVisible()

    // The last-calculated date is shown.
    await expect(page.getByTestId('frtb-calculated-at')).toContainText('2026')

    // A key result value is rendered.
    const equityRow = page.getByTestId('frtb-row-EQUITY')
    await expect(equityRow).toContainText('$377,771.78')

    // The empty "Click Calculate FRTB" placeholder is NOT shown.
    await expect(page.getByTestId('frtb-empty-state')).toHaveCount(0)
  })

  test('falls back to the empty state when there is no prior calculation', async ({
    page,
  }) => {
    await mockAllApiRoutes(page)

    // GET .../latest returns 404 — no calculation exists for this book yet.
    await page.route('**/api/v1/regulatory/frtb/*/latest', (route: Route) => {
      route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/')
    await page.getByTestId('tab-regulatory').click()

    await expect(page.getByTestId('frtb-empty-state')).toBeVisible()
    await expect(page.getByTestId('regulatory-results')).toHaveCount(0)
  })
})
