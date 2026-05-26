import { test, expect, type Route } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_JOB_HISTORY,
} from './fixtures'

/**
 * E2E coverage for the canned stress-scenario tile (issue kx-wxy).
 *
 * The tile is seeded server-side by the demo orchestrator's
 * StressScenarioSeedJob and read by the UI via
 * `GET /api/v1/risk/stress/{bookId}/canned`. These tests mock that endpoint
 * and assert the tile renders the scenario name, delta-PV, and as-of stamp
 * on the Risk overview.
 */
test.describe('Canned stress scenario tile on Risk overview', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('renders the canned scenario name and delta-PV when seeded', async ({ page }) => {
    await page.route('**/api/v1/risk/stress/*/canned', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          bookId: 'port-1',
          scenario: '+100BPS_PARALLEL',
          deltaPv: '-12345.67',
          asOf: '2026-05-26T09:00:00Z',
        }),
      })
    })

    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="stress-scenario-tile"]')

    const tile = page.getByTestId('stress-scenario-tile')
    await expect(tile).toBeVisible()
    await expect(tile.getByTestId('stress-scenario-name')).toContainText('+100BPS_PARALLEL')
    await expect(tile.getByTestId('stress-scenario-delta-pv')).toContainText(/-\$?12,?345/)
    await expect(tile.getByTestId('stress-scenario-as-of')).toBeVisible()
  })

  test('renders the empty state when no canned scenario has been seeded', async ({ page }) => {
    await page.route('**/api/v1/risk/stress/*/canned', (route: Route) => {
      route.fulfill({ status: 404, contentType: 'application/json', body: 'null' })
    })

    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="stress-scenario-tile"]')

    const tile = page.getByTestId('stress-scenario-tile')
    await expect(tile).toBeVisible()
    await expect(tile).toContainText(/no canned stress scenario/i)
  })
})
