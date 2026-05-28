import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * E2E coverage for batch acknowledge (trader review §20 — "Alerts pile up
 * forever, no batch select / acknowledge").
 *
 * The Alerts queue must expose:
 *   - a `Select all visible` checkbox that ticks every currently-rendered
 *     TRIGGERED alert in one click;
 *   - per-row checkboxes for selective batch ack;
 *   - an `Acknowledge selected` action that submits ack requests for every
 *     selected alert and only enables once at least one row is selected;
 *   - a visible count of selected alerts so the operator knows what they're
 *     about to action.
 *
 * Each acknowledge still POSTs to the per-alert endpoint — the batch action
 * is implemented client-side as a fan-out of N independent POSTs against the
 * existing `/alerts/{id}/acknowledge` route. That keeps the backend contract
 * unchanged while still removing the 50-clicks-to-clear pain point.
 */

const ALERT_ONE = {
  id: 'batch-alert-1',
  ruleId: 'rule-1',
  ruleName: 'VaR Critical Limit',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'CRITICAL VaR breach (one)',
  currentValue: 1372142.47,
  threshold: 1000000,
  bookId: 'derivatives-book',
  triggeredAt: new Date(Date.now() - 6 * 60 * 1000).toISOString(),
  status: 'TRIGGERED',
}

const ALERT_TWO = {
  id: 'batch-alert-2',
  ruleId: 'rule-1',
  ruleName: 'VaR Critical Limit',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'CRITICAL VaR breach (two)',
  currentValue: 1500000.0,
  threshold: 1000000,
  bookId: 'derivatives-book',
  triggeredAt: new Date(Date.now() - 4 * 60 * 1000).toISOString(),
  status: 'TRIGGERED',
}

const ALERT_THREE = {
  id: 'batch-alert-3',
  ruleId: 'rule-2',
  ruleName: 'PnL Threshold Alert',
  type: 'PNL_THRESHOLD',
  severity: 'WARNING',
  message: 'PnL threshold breached',
  currentValue: 250000,
  threshold: 100000,
  bookId: 'macro-hedge',
  triggeredAt: new Date(Date.now() - 2 * 60 * 1000).toISOString(),
  status: 'TRIGGERED',
}

const ALERT_RESOLVED = {
  id: 'batch-alert-4',
  ruleId: 'rule-3',
  ruleName: 'Resolved Already',
  type: 'VAR_BREACH',
  severity: 'INFO',
  message: 'Old resolved alert (must not be selectable)',
  currentValue: 10000,
  threshold: 5000,
  bookId: 'macro-hedge',
  triggeredAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
  status: 'RESOLVED',
  resolvedAt: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
}

async function mockBatchAlerts(page: Page): Promise<void> {
  await page.unroute('**/api/v1/notifications/alerts*')
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([ALERT_ONE, ALERT_TWO, ALERT_THREE, ALERT_RESOLVED]),
      })
    } else {
      route.fallback()
    }
  })
}

async function captureAcknowledgePosts(page: Page): Promise<string[]> {
  const ackedIds: string[] = []
  await page.route(
    '**/api/v1/notifications/alerts/*/acknowledge',
    (route: Route) => {
      if (route.request().method() !== 'POST') {
        route.fallback()
        return
      }
      const url = route.request().url()
      const match = url.match(/\/alerts\/([^/]+)\/acknowledge/)
      if (match) ackedIds.push(decodeURIComponent(match[1]))
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ...ALERT_ONE,
          id: match ? decodeURIComponent(match[1]) : 'unknown',
          status: 'ACKNOWLEDGED',
        }),
      })
    },
  )
  return ackedIds
}

test.describe('Alerts batch acknowledge (trader review §20)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockBatchAlerts(page)
  })

  test('batch select-all action renders only for TRIGGERED rows and exposes the count', async ({
    page,
  }) => {
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    // `Select all visible` must be present and disabled until there are
    // selectable rows on screen (we have three TRIGGERED rows here).
    const selectAll = page.getByTestId('alerts-select-all-visible')
    await expect(selectAll).toBeVisible()

    // Per-row checkboxes exist for TRIGGERED alerts only.
    await expect(
      page.getByTestId('alerts-row-select-batch-alert-1'),
    ).toBeVisible()
    await expect(
      page.getByTestId('alerts-row-select-batch-alert-2'),
    ).toBeVisible()
    await expect(
      page.getByTestId('alerts-row-select-batch-alert-3'),
    ).toBeVisible()
    // RESOLVED row is excluded — no checkbox.
    await expect(
      page.getByTestId('alerts-row-select-batch-alert-4'),
    ).toHaveCount(0)

    // Acknowledge-selected button is visible but disabled with no selection.
    const ackSelected = page.getByTestId('alerts-acknowledge-selected')
    await expect(ackSelected).toBeVisible()
    await expect(ackSelected).toBeDisabled()
  })

  test('Select all visible selects every TRIGGERED row, Acknowledge selected fires one POST per selection', async ({
    page,
  }) => {
    const ackedIds = await captureAcknowledgePosts(page)
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.getByTestId('alerts-select-all-visible').click()

    // Three TRIGGERED rows are now selected — the count reflects that.
    await expect(page.getByTestId('alerts-selection-count')).toHaveText('3')

    // Acknowledge-selected becomes enabled and triggers three independent
    // POSTs against the per-alert acknowledge endpoint.
    const ackSelected = page.getByTestId('alerts-acknowledge-selected')
    await expect(ackSelected).toBeEnabled()
    await ackSelected.click()

    // All three TRIGGERED alerts must transition to ACKNOWLEDGED in the UI.
    await expect(page.getByTestId('status-badge-batch-alert-1')).toHaveText(
      'ACKNOWLEDGED',
    )
    await expect(page.getByTestId('status-badge-batch-alert-2')).toHaveText(
      'ACKNOWLEDGED',
    )
    await expect(page.getByTestId('status-badge-batch-alert-3')).toHaveText(
      'ACKNOWLEDGED',
    )

    // Backend received exactly three POSTs, one per selected row.
    expect(ackedIds.sort()).toEqual(
      ['batch-alert-1', 'batch-alert-2', 'batch-alert-3'].sort(),
    )

    // Selection clears after the batch fires so the user can't double-submit.
    await expect(page.getByTestId('alerts-selection-count')).toHaveText('0')
    await expect(ackSelected).toBeDisabled()
  })
})
