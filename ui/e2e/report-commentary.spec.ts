import { test, expect } from '@playwright/test'
import { insightsMock, mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// AI Insight Panel — Report Commentary (docs/plans/ai-v1.md §3)
// ---------------------------------------------------------------------------
//
// The Reports tab renders an AIInsightPanel below the generated report
// output. Once the user clicks Generate:
//   1. POST /api/v1/reports/generate returns a ReportOutput.
//   2. POST /api/v1/insights/explain/report returns an InsightResponse.
//   3. The card (`ai-commentary-card`) wraps an AIInsightPanel that shows
//      the narrative, bullets, and a "Demo mode" badge whenever the
//      response came from the canned (offline) client.
//
// Both endpoints are mocked via the shared helpers in fixtures.ts — the
// default canned `TEST_INSIGHT_RESPONSE` already has the narrative and
// bullets the assertions below check for.

test.describe('Report Commentary', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await insightsMock(page)
  })

  test('Reports tab generates a report and renders the AI Commentary card with narrative, bullets, and Demo mode badge', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-reports').click()

    // Fill the required form fields. The Template select is populated by
    // the mocked /api/v1/reports/templates response (TEST_REPORT_TEMPLATES).
    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await page.getByTestId('report-book-input').fill('BOOK-1')

    await page.getByTestId('report-generate-button').click()

    // The generated report output panel should appear.
    await expect(page.getByTestId('report-output-panel')).toBeVisible()

    // The AI Commentary card wraps the standard AIInsightPanel.
    await expect(page.getByTestId('ai-commentary-card')).toBeVisible()
    await expect(page.getByTestId('ai-insight-panel')).toBeVisible()

    const content = page.getByTestId('ai-insight-content')
    await expect(content).toBeVisible()
    await expect(content).toContainText('VaR is driven by concentrated equity exposure.')
    await expect(content).toContainText('AAPL contributes 32% of total VaR.')

    await expect(page.getByTestId('ai-insight-demo-badge')).toHaveText('Demo mode')
  })
})
