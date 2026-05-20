import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * E2E coverage for the alert delivery path under a connection-loss /
 * reconnect window (plan §11.3, plans/audit-v2.md).
 *
 * ----------------------------------------------------------------------------
 * KNOWN GAP — documented, not fixed by this spec (TEST-ONLY checkbox).
 * ----------------------------------------------------------------------------
 * The checkbox asked for a Playwright spec proving that "a limit breach
 * dispatched during a reconnect window is displayed once the connection
 * recovers". Investigating the running app empirically (Playwright
 * `page.on('websocket')`) shows the truth that drives this spec:
 *
 *   The app opens   /ws/prices, /ws/pnl, /ws/copilot
 *   The app NEVER opens  /ws/alerts
 *
 * A `useAlertStream` hook DOES exist (src/hooks/useAlertStream.ts) with full
 * reconnect/backoff logic and a `/ws/alerts` URL — but it is NOT wired into
 * any rendered component. App.tsx feeds both <NotificationStrip> and
 * <NotificationCenter> from `useNotifications`, which is REST-only: it calls
 * `fetchAlerts` (GET /api/v1/notifications/alerts) exactly once, on mount.
 *
 * Consequence — the gap this spec documents:
 *   There is no live alert WebSocket pushing breaches to the UI. An alert
 *   raised server-side while the page is already open is NOT delivered to the
 *   running view. It only becomes visible on the next REST fetch, which today
 *   happens solely on a fresh page load (a "reconnect" of the REST session).
 *   A breach dispatched during ANY down-window — and indeed during normal
 *   uptime — is silently absent from the live strip until the page reloads.
 *
 * Server-side alert-event replay/buffering AND wiring a live alert WebSocket
 * into the UI are explicitly OUT OF SCOPE for PR 11 — see the "Out of scope"
 * section of plans/audit-v2.md: "Alert-WebSocket server-side event
 * replay/buffering. PR 11 adds a *test* exposing the gap; building the buffer
 * is follow-up work." This spec is that test.
 *
 * The two tests below assert the ACTUAL behaviour:
 *   1. The app never opens a /ws/alerts WebSocket — proving alerts are not
 *      live-pushed, so the "reconnect window" for alerts is the REST session.
 *   2. A breach dispatched while the page is open is dropped from the live
 *      view; once the connection recovers (a reload re-runs `fetchAlerts`)
 *      the breach IS displayed. This documents both the gap (no live push)
 *      and the recovery path that does work (REST catch-up on reconnect).
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

/** A CRITICAL limit-breach alert, raised "during" the down-window. */
const LIMIT_BREACH_ALERT: AlertEventFixture = {
  id: 'alert-breach-during-reconnect',
  ruleId: 'rule-position-limit',
  ruleName: 'Position Limit Breach',
  type: 'LIMIT_BREACH',
  severity: 'CRITICAL',
  message: 'Position limit exceeded on AAPL: 12,000 vs limit 10,000',
  currentValue: 12000,
  threshold: 10000,
  bookId: 'port-1',
  triggeredAt: new Date(Date.now() - 60_000).toISOString(),
  status: 'TRIGGERED',
}

/**
 * Installs a mutable `GET /api/v1/notifications/alerts` handler. The handler
 * serves whatever `state.alerts` holds at request time, so a test can flip
 * the server-side alert set mid-session and observe whether the UI picks it
 * up. Returns the mutable `state` object.
 *
 * Registered after `mockAllApiRoutes` (whose default returns `[]`) so this
 * last-registered handler wins — Playwright uses the last matching route.
 */
async function mockMutableAlerts(
  page: Page,
): Promise<{ alerts: AlertEventFixture[] }> {
  const state: { alerts: AlertEventFixture[] } = { alerts: [] }
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(state.alerts),
    })
  })
  return state
}

test.describe('Alert WebSocket — breach during a reconnect window', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('the app never opens a live /ws/alerts WebSocket — alerts are not live-pushed', async ({
    page,
  }) => {
    // Record every WebSocket the app opens. A `routeWebSocket` mock for
    // /ws/alerts is also installed: if the app ever connected, this handler
    // would fire — it never does, which is itself the assertion.
    const openedWsUrls: string[] = []
    let alertsWsConnected = false

    await page.routeWebSocket('**/ws/alerts*', (ws) => {
      // If we get here, the app HAS opened the alert WebSocket — which would
      // mean the gap documented at the top of this file no longer holds.
      alertsWsConnected = true
      ws.onMessage(() => {})
    })

    page.on('websocket', (ws) => {
      openedWsUrls.push(ws.url())
    })

    await page.goto('/')

    // Land on the default tab and let all startup connections settle.
    await expect(page.getByTestId('tab-positions')).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await expect(page.getByTestId('notification-strip')).toBeVisible()
    // Give every startup WebSocket a generous window to be opened.
    await page.waitForTimeout(3000)

    // GAP, asserted: no /ws/alerts connection was ever attempted. The alert
    // WebSocket hook exists but is unwired, so breaches cannot be pushed
    // live — they depend entirely on the REST `fetchAlerts` call.
    expect(alertsWsConnected).toBe(false)
    expect(openedWsUrls.some((u) => u.includes('/ws/alerts'))).toBe(false)
  })

  test('a breach raised while the page is open is dropped from the live view and only appears after the connection recovers', async ({
    page,
  }) => {
    const alertState = await mockMutableAlerts(page)

    // --- Connection established: no alerts outstanding -----------------------
    // The session starts clean. The strip shows its empty state.
    alertState.alerts = []
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

    // --- Down-window: a limit breach is dispatched server-side --------------
    // The breach now exists on the server. In an app with a live alert
    // WebSocket this would arrive (or be replayed) the instant the socket
    // recovers. Here there is no alert socket, so flipping the server-side
    // state mid-session has no effect on the open page.
    alertState.alerts = [LIMIT_BREACH_ALERT]

    // GAP, asserted: the open page does NOT pick up the breach. The strip is
    // still empty because nothing re-fetches alerts within the live session
    // (`useNotifications` calls `fetchAlerts` only once, on mount, and no
    // alert WebSocket pushes the event).
    await page.waitForTimeout(2000)
    await expect(strip.getByTestId('notification-strip-empty')).toContainText(
      'No notifications',
    )
    await expect(strip.getByTestId('notification-chip-critical')).toHaveCount(0)

    // --- Connection recovers: REST session re-established ------------------
    // The only mechanism that re-runs `fetchAlerts` today is a fresh page
    // load — the REST equivalent of the connection recovering. On reload the
    // breach, which the server has been holding all along, is finally
    // delivered to the UI.
    await page.reload()

    await expect(page.getByTestId('tab-positions')).toHaveAttribute(
      'aria-selected',
      'true',
    )
    const stripAfter = page.getByTestId('notification-strip')
    await expect(stripAfter).toBeVisible()

    // RECOVERY, asserted: once the connection recovers the breach IS shown.
    // The collapsed bar reflects it — a CRITICAL chip and a non-zero count.
    await expect(
      stripAfter.getByTestId('notification-chip-critical'),
    ).toContainText('1 critical')
    await expect(
      stripAfter.getByTestId('notification-unread-count'),
    ).toHaveText('1 unread')

    // Expanding the strip shows the breach detail in the inbox.
    await stripAfter.getByTestId('notification-strip-toggle').click()
    const row = stripAfter.getByTestId(
      `notification-item-${LIMIT_BREACH_ALERT.id}`,
    )
    await expect(row).toBeVisible()
    await expect(row).toContainText('Position Limit Breach')
    await expect(row).toContainText(
      'Position limit exceeded on AAPL: 12,000 vs limit 10,000',
    )
  })
})
