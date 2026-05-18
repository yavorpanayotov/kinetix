import { test, expect } from '@playwright/test'
import {
  insightsMock,
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_JOB_HISTORY,
  TEST_VAR_RESULT,
} from './fixtures'

// ---------------------------------------------------------------------------
// AI Insight Panel — Explain VaR (plans/ai-v1.md §2)
// ---------------------------------------------------------------------------
//
// The VaR dashboard exposes an "Explain" button that POSTs the current VaR
// context to /api/v1/insights/explain/var and renders an AIInsightPanel with
// the model's narrative, bullets, and a "Demo mode" badge whenever the answer
// came from a canned (offline) response.

test.describe('VaR Explainer', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
    await insightsMock(page)
  })

  test('clicking Explain shows the AI insight panel with narrative, bullets, and Demo mode badge', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="var-dashboard"]')

    await expect(page.getByTestId('explain-var-button')).toBeVisible()

    await page.getByTestId('explain-var-button').click()

    await expect(page.getByTestId('ai-insight-panel')).toBeVisible()

    const content = page.getByTestId('ai-insight-content')
    await expect(content).toBeVisible()
    await expect(content).toContainText('VaR is driven by concentrated equity exposure.')
    await expect(content).toContainText('AAPL contributes 32% of total VaR.')

    await expect(page.getByTestId('ai-insight-demo-badge')).toHaveText('Demo mode')
  })
})
