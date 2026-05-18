import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const VAR_BREACH_RULE = {
  id: 'rule-var-breach',
  name: 'VaR Breach',
  type: 'VAR_BREACH',
  threshold: 100_000,
  operator: 'GREATER_THAN',
  severity: 'HIGH',
  channels: [],
  enabled: true,
}

const SAMPLE_VAR_BREACH = {
  bookId: 'port-1',
  varValue: '90000.00', // 90% of 100k = 90% > 80% threshold
  expectedShortfall: '95000.00',
  confidenceLevel: 'CL_95',
  calculationType: 'PARAMETRIC',
  componentBreakdown: [],
  calculatedAt: '2026-03-24T12:00:00Z',
  greeks: {
    bookId: 'port-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '1000', gamma: '5', vega: '200' },
    ],
    theta: '-30',
    rho: '12',
    calculatedAt: '2026-03-24T12:00:00Z',
  },
}

const SAMPLE_VAR_BELOW_LIMIT = {
  ...SAMPLE_VAR_BREACH,
  varValue: '40000.00', // 40% of 100k = 40% < 80%
}

const CRITICAL_ALERT = {
  id: 'alert-critical-1',
  ruleId: 'rule-var-breach',
  ruleName: 'VaR Breach',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'VaR breached limit',
  currentValue: 125_000,
  threshold: 100_000,
  bookId: 'port-1',
  triggeredAt: new Date(Date.now() - 5 * 60_000).toISOString(),
  status: 'TRIGGERED',
}

async function overrideVarRoute(page: Page, varResult: object) {
  await page.unroute('**/api/v1/risk/var/*')
  await page.route('**/api/v1/risk/var/*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(varResult),
    })
  })
}

async function overrideRulesRoute(page: Page, rules: object[]) {
  await page.unroute('**/api/v1/notifications/rules')
  await page.route('**/api/v1/notifications/rules', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(rules),
    })
  })
}

async function overrideAlertsRoute(page: Page, alerts: object[]) {
  await page.unroute('**/api/v1/notifications/alerts*')
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(alerts),
    })
  })
}

async function drillIntoBook(page: Page) {
  // The default firm-level selection leaves effectiveBookId null and the VaR
  // hook inert; selecting a specific book triggers the per-book fetch.
  const toggle = page.getByTestId('hierarchy-selector-toggle')
  if (await toggle.isVisible()) {
    await toggle.click()
    const bookOption = page.getByTestId(/^hierarchy-book-/).first()
    if (await bookOption.count() > 0) {
      await bookOption.click()
    }
  }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Global breach banner (alert banner)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows the breach banner on the default Positions tab when VaR utilisation exceeds 80% of limit', async ({ page }) => {
    await overrideRulesRoute(page, [VAR_BREACH_RULE])
    await overrideVarRoute(page, SAMPLE_VAR_BREACH)

    await page.goto('/')
    await drillIntoBook(page)

    await expect(page.getByTestId('breach-banner')).toBeVisible({ timeout: 5000 })
  })

  test('breach banner follows the user across Positions, Risk and P&L tabs', async ({ page }) => {
    await overrideRulesRoute(page, [VAR_BREACH_RULE])
    await overrideVarRoute(page, SAMPLE_VAR_BREACH)

    await page.goto('/')
    await drillIntoBook(page)

    await expect(page.getByTestId('breach-banner')).toBeVisible({ timeout: 5000 })

    await page.getByTestId('tab-risk').click()
    await expect(page.getByTestId('breach-banner')).toBeVisible()

    await page.getByTestId('tab-pnl').click()
    await expect(page.getByTestId('breach-banner')).toBeVisible()

    await page.getByTestId('tab-positions').click()
    await expect(page.getByTestId('breach-banner')).toBeVisible()
  })

  test('breach banner disappears when navigating to tabs outside the trading-day flow (Reports)', async ({ page }) => {
    await overrideRulesRoute(page, [VAR_BREACH_RULE])
    await overrideVarRoute(page, SAMPLE_VAR_BREACH)

    await page.goto('/')
    await drillIntoBook(page)

    await expect(page.getByTestId('breach-banner')).toBeVisible({ timeout: 5000 })

    await page.getByTestId('tab-reports').click()
    await expect(page.getByTestId('breach-banner')).toHaveCount(0)
  })

  test('does NOT show the breach banner when VaR is well within limit and no CRITICAL alerts exist', async ({ page }) => {
    await overrideRulesRoute(page, [VAR_BREACH_RULE])
    await overrideVarRoute(page, SAMPLE_VAR_BELOW_LIMIT)
    await overrideAlertsRoute(page, [])

    await page.goto('/')
    await drillIntoBook(page)

    // Give the page a moment to settle so we don't false-pass before VaR resolves.
    await expect(page.getByTestId('ticker-var')).toBeVisible()

    await expect(page.getByTestId('breach-banner')).toHaveCount(0)
  })

  test('shows the breach banner when a CRITICAL alert is active even if VaR is well within limit', async ({ page }) => {
    await overrideRulesRoute(page, [VAR_BREACH_RULE])
    await overrideVarRoute(page, SAMPLE_VAR_BELOW_LIMIT)
    await overrideAlertsRoute(page, [CRITICAL_ALERT])

    await page.goto('/')
    await drillIntoBook(page)

    await expect(page.getByTestId('breach-banner')).toBeVisible({ timeout: 5000 })
  })
})
