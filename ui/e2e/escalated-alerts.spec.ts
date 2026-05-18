import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

const ESCALATED_ALERT = {
  id: 'esc-1',
  ruleId: 'rule-1',
  ruleName: 'VaR Critical Limit',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'VaR breach not acknowledged within timeout',
  currentValue: 250000,
  threshold: 100000,
  bookId: 'book-1',
  triggeredAt: '2025-01-15T09:00:00Z',
  status: 'ESCALATED',
  escalatedAt: '2025-01-15T09:35:00Z',
  escalatedTo: 'risk-manager,cro',
}

const TRIGGERED_ALERT = {
  id: 'trig-1',
  ruleId: 'rule-2',
  ruleName: 'P&L Warning',
  type: 'PNL_THRESHOLD',
  severity: 'WARNING',
  message: 'P&L threshold exceeded',
  currentValue: 200000,
  threshold: 150000,
  bookId: 'book-2',
  triggeredAt: '2025-01-15T10:00:00Z',
  status: 'TRIGGERED',
}

async function mockAlertsWithEscalated(page: Page): Promise<void> {
  const alerts = [ESCALATED_ALERT, TRIGGERED_ALERT]
  await page.unroute('**/api/v1/notifications/alerts*')
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(alerts),
    })
  })
}

test.describe('Escalated Alerts UI', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockAlertsWithEscalated(page)
  })

  test('ESCALATED status filter button is present', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alert-status-filters"]')

    await expect(page.getByTestId('status-filter-escalated')).toBeVisible()
  })

  test('escalated alert shows orange ESCALATED badge', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    const badge = page.getByTestId('escalation-badge-esc-1')
    await expect(badge).toBeVisible()
    await expect(badge).toHaveText('ESCALATED')
    const className = await badge.getAttribute('class')
    expect(className).toContain('orange')
  })

  test('escalated alert shows escalatedTo recipient', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    const escalatedTo = page.getByTestId('escalated-to-esc-1')
    await expect(escalatedTo).toBeVisible()
    await expect(escalatedTo).toHaveText('risk-manager,cro')
  })

  test('escalated alert shows escalatedAt timestamp', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    const escalatedAt = page.getByTestId('escalated-at-esc-1')
    await expect(escalatedAt).toBeVisible()
  })

  test('ESCALATED filter isolates escalated alerts when others are toggled off', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    // Both alerts initially visible — TRIGGERED + ESCALATED are on by default
    // under the queue model (§3.2 of docs/plans/ui-overhaul.md).
    const alertsList = page.getByTestId('alerts-list')
    const initialCount = await alertsList.locator('> div').count()
    expect(initialCount).toBe(2)

    // Multi-select chips: drop TRIGGERED (and ACKNOWLEDGED, which has no
    // matching alerts here but is on by default) so only ESCALATED remains.
    await page.getByTestId('status-filter-triggered').click()
    await page.getByTestId('status-filter-acknowledged').click()

    const filteredCount = await alertsList.locator('> div').count()
    expect(filteredCount).toBe(1)
    await expect(page.getByTestId('escalation-badge-esc-1')).toBeVisible()
  })
})
