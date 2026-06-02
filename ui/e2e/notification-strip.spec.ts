import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * E2E coverage for the global {@link NotificationStrip} (plan §9).
 *
 * The strip sits between <SystemStatusBanner> and <RiskTickerStrip> and is
 * rendered on every tab — it is NOT scoped to the Alerts tab. PR 9 wired its
 * `items` prop to the same `useNotifications` alert feed the <NotificationCenter>
 * consumes. This spec proves that a live limit-breach alert surfaces in the
 * strip on the DEFAULT tab without the operator ever opening the Alerts tab.
 *
 * The alert feed is the gateway-proxied `GET /api/v1/notifications/alerts`
 * endpoint that `fetchAlerts` (src/api/notifications.ts) calls; routes are
 * mocked per `e2e/fixtures.ts`.
 *
 * Plan ref: docs/plans/audit-v2.md PR 9 §9.2.
 */

interface AlertEventFixture {
  id: string
  ruleId: string
  ruleName: string
  type: string
  severity: string
  message: string
  currentValue: number
  threshold: number
  bookId: string
  triggeredAt: string
  status: string
}

/** A CRITICAL limit-breach alert, triggered five minutes ago. */
const LIMIT_BREACH_ALERT: AlertEventFixture = {
  id: 'alert-limit-breach-1',
  ruleId: 'rule-position-limit',
  ruleName: 'Position Limit Breach',
  type: 'LIMIT_BREACH',
  severity: 'CRITICAL',
  message: 'Position limit exceeded on AAPL: 12,000 vs limit 10,000',
  currentValue: 12000,
  threshold: 10000,
  bookId: 'port-1',
  triggeredAt: new Date(Date.now() - 5 * 60_000).toISOString(),
  status: 'TRIGGERED',
}

/**
 * Overrides `GET /api/v1/notifications/alerts` (the endpoint `fetchAlerts`
 * calls) to return the given alert events. The default `mockAllApiRoutes`
 * handler returns an empty list, so this must be registered afterwards —
 * Playwright uses the last-registered matching handler.
 */
async function mockAlerts(
  page: Page,
  alerts: AlertEventFixture[],
): Promise<void> {
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(alerts),
    })
  })
}

test.describe('NotificationStrip — live limit-breach surfacing', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('surfaces a limit-breach alert in the strip on the default tab without visiting Alerts', async ({
    page,
  }) => {
    await mockAlerts(page, [LIMIT_BREACH_ALERT])

    await page.goto('/')

    // Land on the default (Positions) tab — never navigate to Alerts.
    await expect(page.getByTestId('tab-positions')).toHaveAttribute(
      'aria-selected',
      'true',
    )

    // The collapsed strip reflects the breach: a CRITICAL severity chip and a
    // non-zero unread count, both visible globally above the tab content.
    const strip = page.getByTestId('notification-strip')
    await expect(strip).toBeVisible()
    await expect(strip.getByTestId('notification-chip-critical')).toContainText(
      '1 critical',
    )
    await expect(strip.getByTestId('notification-unread-count')).toHaveText(
      '1 unread',
    )

    // Expand the strip — the breach's title and message render in the inbox.
    await strip.getByTestId('notification-strip-toggle').click()
    const row = strip.getByTestId(`notification-item-${LIMIT_BREACH_ALERT.id}`)
    await expect(row).toBeVisible()
    await expect(row).toContainText('Position Limit Breach')
    await expect(row).toContainText(
      'Position limit exceeded on AAPL: 12,000 vs limit 10,000',
    )

    // The breach was proven visible without ever opening the Alerts tab.
    await expect(page.getByTestId('tab-alerts')).toHaveAttribute(
      'aria-selected',
      'false',
    )
  })

  test('shows the empty strip when there are no alerts', async ({ page }) => {
    await mockAlerts(page, [])

    await page.goto('/')

    await expect(page.getByTestId('tab-positions')).toHaveAttribute(
      'aria-selected',
      'true',
    )

    const strip = page.getByTestId('notification-strip')
    await expect(strip).toBeVisible()
    await expect(strip.getByTestId('notification-strip-empty')).toContainText(
      'No notifications',
    )
    await expect(
      strip.getByTestId('notification-chip-critical'),
    ).toHaveCount(0)
  })
})
