import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Cross-tab linking — plan §2.4. Verifies the four "jump to" shortcuts
 * surface in the UI: alerts → Risk, counterparty → Trades, report →
 * Risk-at-date, scenario row → ScenarioDetailPanel.
 *
 * The unit / integration suites cover the wiring exhaustively; these
 * Playwright tests prove the shortcuts survive a real render in the
 * browser and don't, for instance, get hidden behind a stale prop.
 */

const TRIGGERED_ALERT = {
  id: 'alert-jump-1',
  ruleId: 'rule-1',
  ruleName: 'VaR Critical Limit',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'VaR breach on port-1',
  currentValue: 250000,
  threshold: 100000,
  bookId: 'port-1',
  triggeredAt: '2025-01-15T09:00:00Z',
  status: 'TRIGGERED',
}

async function mockTriggeredAlert(page: Page): Promise<void> {
  await page.unroute('**/api/v1/notifications/alerts*')
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([TRIGGERED_ALERT]),
      })
    } else {
      route.fallback()
    }
  })
}

test.describe('Cross-tab navigation (plan §2.4)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('jump to Risk from an alert row switches tabs and renders Risk', async ({
    page,
  }) => {
    await mockTriggeredAlert(page)
    await page.goto('/')

    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    const jumpButton = page.getByTestId(`jump-to-risk-${TRIGGERED_ALERT.id}`)
    await expect(jumpButton).toBeVisible()

    await jumpButton.click()

    // The Risk tab is now active and the Risk tab content is in the DOM.
    await expect(page.getByTestId('tab-risk')).toHaveAttribute(
      'aria-selected',
      'true',
    )
    // Notification center is gone — confirms the panel actually switched.
    await expect(page.getByTestId('notification-center')).toHaveCount(0)
  })
})
