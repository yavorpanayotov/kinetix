import { test, expect, type Route } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_JOB_HISTORY,
} from './fixtures'

/**
 * Regression: the Stress Test Summary headline and the inline Stress Scenario
 * (canned) tile must agree on whether stress results exist for the active book.
 *
 * Before this fix, the headline read "No stress test results yet" while the
 * canned tile next to it rendered a real Δ PV for `+100BPS_PARALLEL` — two
 * components reading from different data sources and disagreeing about the
 * same conceptual state. See trader-review P0 #10 in
 * `docs/plans/ui-trader-review.md`.
 */
test.describe('Risk → Stress Test Summary / canned tile consistency', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('hides the "no stress test results yet" headline when the canned tile has a seeded result', async ({ page }) => {
    await page.route('**/api/v1/risk/stress/*/canned', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          bookId: 'port-1',
          scenario: '+100BPS_PARALLEL',
          deltaPv: '-1589994.06',
          asOf: '2026-05-28T10:25:00Z',
        }),
      })
    })

    await page.goto('/')
    await page.getByTestId('tab-risk').click()

    // Both widgets live inside the "P&L, Stress & Liquidity" section block.
    await page.waitForSelector('[data-testid="stress-summary-card"]')
    await page.waitForSelector('[data-testid="stress-scenario-tile"]')

    const summary = page.getByTestId('stress-summary-card')
    const tile = page.getByTestId('stress-scenario-tile')

    // Tile shows the seeded canned result.
    await expect(tile.getByTestId('stress-scenario-name')).toContainText('+100BPS_PARALLEL')
    await expect(tile.getByTestId('stress-scenario-delta-pv')).toBeVisible()

    // Summary headline must NOT contradict the tile by claiming there are no results.
    await expect(summary).not.toContainText(/no stress test results yet/i)
  })

  test('hides the inline stress scenario delta-PV widget when the summary says no results', async ({ page }) => {
    // Canned endpoint returns 404 — orchestrator has not seeded anything.
    await page.route('**/api/v1/risk/stress/*/canned', (route: Route) => {
      route.fulfill({ status: 404, contentType: 'application/json', body: 'null' })
    })

    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="stress-summary-card"]')

    const summary = page.getByTestId('stress-summary-card')

    // Summary correctly shows the empty state.
    await expect(summary).toContainText(/no stress test results yet/i)

    // The inline Δ PV widget must NOT be rendered with a real delta-PV value
    // — empty headline and an empty (or absent) tile are consistent.
    await expect(page.getByTestId('stress-scenario-delta-pv')).toHaveCount(0)
  })
})
