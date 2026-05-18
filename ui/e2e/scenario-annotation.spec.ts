import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockActiveScenario,
  mockRegimeRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_REGIME_NORMAL,
  TEST_REGIME_CRISIS,
} from './fixtures'

async function goToRiskTab(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
}

test.describe('per-number scenario annotation on risk panels', () => {
  test('shows a scenario annotation on the VaR number when a demo scenario is active', async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, { varResult: TEST_VAR_RESULT })
    await mockRegimeRoutes(page, TEST_REGIME_NORMAL)
    await mockActiveScenario(page, 'stress')

    await goToRiskTab(page)

    // Wait for the VaR dashboard to render
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // At least one scenario-annotation badge should be present on the Risk tab
    const badges = page.locator('[data-testid="scenario-badge"]')
    await expect(badges.first()).toBeVisible()

    const first = badges.first()
    await expect(first).toHaveAttribute('aria-label', /scenario/i)
    await expect(first).toHaveAttribute('data-scenario', 'stress')
  })

  test('does not show scenario annotations when no demo scenario is active and regime is NORMAL', async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, { varResult: TEST_VAR_RESULT })
    await mockRegimeRoutes(page, TEST_REGIME_NORMAL)
    await mockActiveScenario(page, null)

    await goToRiskTab(page)

    await page.waitForSelector('[data-testid="var-dashboard"]')

    // Regime is NORMAL and no scenario is active — no per-number badges should
    // be rendered anywhere on the Risk tab.
    await expect(page.locator('[data-testid="scenario-badge"]')).toHaveCount(0)
  })

  test('shows a regime-adj annotation on the VaR number when regime is non-NORMAL', async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, { varResult: TEST_VAR_RESULT })
    await mockRegimeRoutes(page, TEST_REGIME_CRISIS)
    await mockActiveScenario(page, null)

    await goToRiskTab(page)

    await page.waitForSelector('[data-testid="var-dashboard"]')

    const badges = page.locator('[data-testid="scenario-badge"]')
    await expect(badges.first()).toBeVisible()
    await expect(badges.first()).toHaveAttribute('aria-label', /regime/i)
    await expect(badges.first()).toHaveAttribute('data-regime', 'CRISIS')
  })
})
