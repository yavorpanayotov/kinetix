import { test, expect } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_POSITION_RISK_FULL,
  TEST_JOB_HISTORY,
} from './fixtures'

/** Plan §2.2 — Risk-tab Dashboard collapsible sections. */
test.describe('Risk tab Dashboard - collapsible sections', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('renders the four section headings on the Dashboard sub-tab', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="section-block-market-risk"]')

    await expect(page.getByTestId('section-block-market-risk')).toBeVisible()
    await expect(page.getByTestId('section-block-position-factor')).toBeVisible()
    await expect(page.getByTestId('section-block-pnl-stress-liquidity')).toBeVisible()
    await expect(page.getByTestId('section-block-limits-jobs')).toBeVisible()
  })

  test('clicking the Market Risk heading collapses the VaR dashboard', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // VaR dashboard visible initially
    await expect(page.getByTestId('var-dashboard')).toBeVisible()

    // Click the Market Risk header button to collapse
    await page.getByRole('button', { name: /market risk/i }).click()

    // VaR dashboard should no longer be in the DOM (SectionBlock unmounts children)
    await expect(page.getByTestId('var-dashboard')).toHaveCount(0)
  })

  test('collapsed section state persists across reload via workspace prefs', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="section-block-limits-jobs"]')

    // Collapse Limits & Jobs section
    await page.getByRole('button', { name: /limits & jobs/i }).click()
    await expect(page.getByTestId('job-history')).toHaveCount(0)

    // Verify the workspace pref was saved to localStorage
    const stored = await page.evaluate(() => localStorage.getItem('kinetix:workspace'))
    expect(stored).toBeTruthy()
    const parsed = JSON.parse(stored!)
    expect(parsed.riskDashboardSections.limitsJobs).toBe(false)

    // Reload the page — collapsed state should persist
    await page.reload()
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="section-block-limits-jobs"]')

    // Other sections still expanded
    await expect(page.getByTestId('section-block-market-risk')).toBeVisible()
    // Limits & Jobs section header rendered but content (job-history) hidden
    await expect(page.getByRole('button', { name: /limits & jobs/i })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    await expect(page.getByTestId('job-history')).toHaveCount(0)
  })
})
