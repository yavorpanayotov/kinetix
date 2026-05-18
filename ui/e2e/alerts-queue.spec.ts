import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * E2E coverage for the Alerts queue view (UI overhaul §3.2 — alerts queue,
 * not a list). Verifies:
 *   - default queue order: CRITICAL > WARNING > INFO, then newest first.
 *   - RESOLVED alerts are hidden by default.
 *   - clicking the RESOLVED chip surfaces recent resolved alerts AND folds
 *     >24h-old resolved alerts into a single collapsible row.
 *   - expanding the collapsible reveals the old resolved alerts.
 */

const CRITICAL_OLDEST = {
  id: 'queue-crit-oldest',
  ruleId: 'rule-1',
  ruleName: 'VaR Critical',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'Critical VaR breach (oldest)',
  currentValue: 300000,
  threshold: 100000,
  bookId: 'book-1',
  // 8 hours ago, but CRITICAL severity puts it on top.
  triggeredAt: new Date(Date.now() - 8 * 60 * 60 * 1000).toISOString(),
  status: 'TRIGGERED',
}

const WARNING_RECENT = {
  id: 'queue-warn-recent',
  ruleId: 'rule-2',
  ruleName: 'P&L Warning',
  type: 'PNL_THRESHOLD',
  severity: 'WARNING',
  message: 'P&L threshold breached',
  currentValue: 200000,
  threshold: 150000,
  bookId: 'book-1',
  // 30 minutes ago — much more recent than the CRITICAL, but lower severity.
  triggeredAt: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
  status: 'TRIGGERED',
}

const INFO_NEWEST = {
  id: 'queue-info-newest',
  ruleId: 'rule-3',
  ruleName: 'Margin notice',
  type: 'CONCENTRATION',
  severity: 'INFO',
  message: 'Concentration shift noted',
  currentValue: 0.42,
  threshold: 0.4,
  bookId: 'book-2',
  // 5 minutes ago — newest of all, but lowest severity.
  triggeredAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
  status: 'TRIGGERED',
}

const RESOLVED_RECENT = {
  id: 'queue-resolved-recent',
  ruleId: 'rule-4',
  ruleName: 'Delta breach',
  type: 'DELTA_BREACH',
  severity: 'WARNING',
  message: 'Delta breach resolved earlier today',
  currentValue: 90,
  threshold: 75,
  bookId: 'book-1',
  triggeredAt: new Date(Date.now() - 4 * 60 * 60 * 1000).toISOString(),
  status: 'RESOLVED',
  // resolved 2 hours ago — well inside the 24h auto-collapse window.
  resolvedAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
}

const RESOLVED_OLD = {
  id: 'queue-resolved-old',
  ruleId: 'rule-5',
  ruleName: 'Vega breach',
  type: 'VEGA_BREACH',
  severity: 'CRITICAL',
  message: 'Vega breach from two days ago',
  currentValue: 500,
  threshold: 400,
  bookId: 'book-1',
  triggeredAt: new Date(Date.now() - 50 * 60 * 60 * 1000).toISOString(),
  status: 'RESOLVED',
  // resolved 36 hours ago — falls outside the 24h auto-collapse window.
  resolvedAt: new Date(Date.now() - 36 * 60 * 60 * 1000).toISOString(),
}

async function mockQueueAlerts(page: Page): Promise<void> {
  const alerts = [
    CRITICAL_OLDEST,
    WARNING_RECENT,
    INFO_NEWEST,
    RESOLVED_RECENT,
    RESOLVED_OLD,
  ]
  await page.unroute('**/api/v1/notifications/alerts*')
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(alerts),
    })
  })
}

test.describe('Alerts queue (severity sort + status chips + collapse)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockQueueAlerts(page)
  })

  test('default queue orders by severity then recency, hides RESOLVED', async ({
    page,
  }) => {
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    // RESOLVED is hidden by default.
    await expect(
      page.getByTestId(`status-badge-${RESOLVED_RECENT.id}`),
    ).toHaveCount(0)
    await expect(
      page.getByTestId(`status-badge-${RESOLVED_OLD.id}`),
    ).toHaveCount(0)

    // Severity-first ordering: the only CRITICAL TRIGGERED alert sits above
    // WARNING which sits above INFO, regardless of triggeredAt.
    const badges = page
      .getByTestId('alerts-list')
      .locator('[data-testid^="status-badge-"]')
    await expect(badges).toHaveCount(3)
    await expect(badges.nth(0)).toHaveAttribute(
      'data-testid',
      `status-badge-${CRITICAL_OLDEST.id}`,
    )
    await expect(badges.nth(1)).toHaveAttribute(
      'data-testid',
      `status-badge-${WARNING_RECENT.id}`,
    )
    await expect(badges.nth(2)).toHaveAttribute(
      'data-testid',
      `status-badge-${INFO_NEWEST.id}`,
    )
  })

  test('RESOLVED chip surfaces recent resolved and collapses old resolved into a summary row', async ({
    page,
  }) => {
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    // Chip count reflects the two RESOLVED alerts in the dataset.
    await expect(page.getByTestId('status-filter-resolved')).toContainText('2')

    // Click the RESOLVED chip.
    await page.getByTestId('status-filter-resolved').click()

    // Recent RESOLVED is visible as a normal row.
    await expect(
      page.getByTestId(`status-badge-${RESOLVED_RECENT.id}`),
    ).toBeVisible()
    // Old RESOLVED (>24h) is hidden behind the summary row.
    await expect(
      page.getByTestId(`status-badge-${RESOLVED_OLD.id}`),
    ).toHaveCount(0)
    const summary = page.getByTestId('older-resolved-summary')
    await expect(summary).toBeVisible()
    await expect(page.getByTestId('older-resolved-count')).toHaveText('1')

    // Expanding the summary row reveals the old RESOLVED alert.
    await page.getByTestId('older-resolved-toggle').click()
    await expect(
      page.getByTestId(`status-badge-${RESOLVED_OLD.id}`),
    ).toBeVisible()
  })
})
