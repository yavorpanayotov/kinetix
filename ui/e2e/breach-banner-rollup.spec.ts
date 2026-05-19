import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Fixtures — three near-identical VaR breaches on the same book/severity/type
// that should fold into a single rollup banner (plan §3.1, G3).
// ---------------------------------------------------------------------------

const BREACH_RULE = {
  id: 'rule-var-breach',
  name: 'VaR Breach',
  type: 'VAR_BREACH',
  threshold: 1_000_000,
  operator: 'GREATER_THAN',
  severity: 'CRITICAL',
  channels: [],
  enabled: true,
}

const SAMPLE_VAR_BELOW_LIMIT = {
  bookId: 'port-1',
  varValue: '40000.00',
  expectedShortfall: '50000.00',
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

// Three CRITICAL VaR breaches on derivatives-book, all triggered within
// minutes of each other — exact audit-screenshot scenario. Use a fixed
// reference time so the 24h rollup window can be reasoned about deterministically.
const REFERENCE_TIME = new Date('2026-02-28T11:55:00Z').getTime()
const ROLLUP_ALERTS = [
  {
    id: 'rollup-1',
    ruleId: 'rule-var-breach',
    ruleName: 'VaR Breach',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR breached limit',
    currentValue: 2_512_672,
    threshold: 1_000_000,
    bookId: 'derivatives-book',
    triggeredAt: new Date(REFERENCE_TIME - 30 * 60_000).toISOString(),
    status: 'TRIGGERED',
  },
  {
    id: 'rollup-2',
    ruleId: 'rule-var-breach',
    ruleName: 'VaR Breach',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR breached limit',
    currentValue: 2_512_658,
    threshold: 1_000_000,
    bookId: 'derivatives-book',
    triggeredAt: new Date(REFERENCE_TIME - 15 * 60_000).toISOString(),
    status: 'TRIGGERED',
  },
  {
    id: 'rollup-3',
    ruleId: 'rule-var-breach',
    ruleName: 'VaR Breach',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR breached limit',
    currentValue: 2_512_730,
    threshold: 1_000_000,
    bookId: 'derivatives-book',
    triggeredAt: new Date(REFERENCE_TIME).toISOString(),
    status: 'TRIGGERED',
  },
]

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

async function drillIntoBook(page: Page) {
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

test.describe('Breach banner rollup (plan §3.1, G3)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('folds three matching CRITICAL VaR breaches into a single rollup banner with a count badge', async ({ page }) => {
    await overrideRulesRoute(page, [BREACH_RULE])
    await overrideVarRoute(page, SAMPLE_VAR_BELOW_LIMIT)
    await overrideAlertsRoute(page, ROLLUP_ALERTS)

    await page.goto('/')
    await drillIntoBook(page)

    // The banner shell renders once.
    await expect(page.getByTestId('breach-banner')).toBeVisible({ timeout: 5000 })

    // Exactly one rollup row — not three individual alert items.
    const rollupRows = page.getByTestId('breach-banner-rollup')
    await expect(rollupRows).toHaveCount(1)

    // The original alert-item rows for the rolled-up IDs are NOT rendered.
    await expect(page.getByTestId('alert-item-rollup-1')).toHaveCount(0)
    await expect(page.getByTestId('alert-item-rollup-2')).toHaveCount(0)
    await expect(page.getByTestId('alert-item-rollup-3')).toHaveCount(0)

    // Count badge — a <span> with data-testid="breach-banner-count" and "3".
    const countBadge = page.getByTestId('breach-banner-count')
    await expect(countBadge).toBeVisible()
    await expect(countBadge).toHaveText('3')
    await expect(countBadge).toHaveJSProperty('tagName', 'SPAN')

    // Headline mirrors the latest alert's value and book.
    await expect(rollupRows).toContainText('3 VaR breaches in the last 24h')
    await expect(rollupRows).toContainText('$2,512,730')
    await expect(rollupRows).toContainText('derivatives-book')
  })
})
