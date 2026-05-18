import { test, expect, Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

const RECENT_ISO = new Date(Date.now() - 5 * 60_000).toISOString()
const OLDER_ISO = new Date(Date.now() - 90 * 60_000).toISOString()

const BREACH_ALERT = {
  id: 'breach-1',
  ruleId: 'rule-var',
  ruleName: 'VaR Critical Limit',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'VaR breached limit',
  currentValue: 1_300_000,
  threshold: 1_000_000,
  bookId: 'port-1',
  triggeredAt: RECENT_ISO,
  status: 'TRIGGERED',
}

const NEAR_BREACH_ALERT = {
  id: 'near-1',
  ruleId: 'rule-pnl',
  ruleName: 'P&L Approaching Limit',
  type: 'PNL_THRESHOLD',
  severity: 'WARNING',
  message: 'P&L approaching threshold',
  currentValue: 850_000,
  threshold: 1_000_000,
  bookId: 'port-1',
  triggeredAt: RECENT_ISO,
  status: 'TRIGGERED',
}

const STALE_ALERT = {
  id: 'stale-1',
  ruleId: 'rule-old',
  ruleName: 'Old VaR Warning',
  type: 'VAR_BREACH',
  severity: 'WARNING',
  message: 'historical warning',
  currentValue: 900_000,
  threshold: 1_000_000,
  bookId: 'port-1',
  triggeredAt: OLDER_ISO,
  status: 'RESOLVED',
}

async function mockAlerts(page: Page, alerts: object[]): Promise<void> {
  await page.unroute('**/api/v1/notifications/alerts*')
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(alerts),
    })
  })
}

test.describe('Limits breach header (Risk Dashboard sub-tab)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders at the top of the Risk Dashboard with breach + near-breach + recent counts', async ({ page }) => {
    await mockAlerts(page, [BREACH_ALERT, NEAR_BREACH_ALERT, STALE_ALERT])

    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="limits-breach-header"]')

    const header = page.getByTestId('limits-breach-header')
    await expect(header).toBeVisible()
    await expect(header.getByTestId('breach-count')).toHaveText('1')
    await expect(header.getByTestId('near-breach-count')).toHaveText('1')
    // Recent count = both active alerts triggered within 30 min (stale one is RESOLVED + older than 30 min)
    await expect(header.getByTestId('recent-alert-count')).toHaveText('2')
  })

  test('shows "All clear" when there are no breaches, near-breaches, or recent alerts', async ({ page }) => {
    await mockAlerts(page, [])

    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="limits-breach-header"]')

    await expect(page.getByTestId('limits-breach-header-all-clear')).toBeVisible()
    await expect(page.getByTestId('breach-count')).toHaveText('0')
    await expect(page.getByTestId('near-breach-count')).toHaveText('0')
    await expect(page.getByTestId('recent-alert-count')).toHaveText('0')
  })

  test('is not rendered on the Intraday sub-tab', async ({ page }) => {
    await mockAlerts(page, [BREACH_ALERT])

    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="limits-breach-header"]')

    await page.getByTestId('risk-subtab-intraday').click()

    await expect(page.getByTestId('limits-breach-header')).toHaveCount(0)
  })
})
