import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Trader-review finding P1 #16 (plans/ui-trader-review.md):
 *
 *   "Activity tab uses 2/21/2026, 2:00:01 PM (US date) while every other tab
 *    uses ISO (2026-05-27). Pick one."
 *
 * The canonical format across the app is the {@link formatTimestamp} helper
 * in `ui/src/utils/format.ts` which renders `YYYY-MM-DD HH:MM:SS` — used by
 * PnlTab, JobPickerDialog, RegulatoryDashboard, SodBaselineIndicator,
 * TradeBlotter, and others. This spec pins the Activity tab to the same
 * shape so a future regression that re-introduces `toLocaleString()` (which
 * emits the locale-specific `M/D/YYYY, h:mm:ss A` on en-US runners) fails
 * before reaching code review.
 *
 * The test seeds two audit events with deliberately distinct `receivedAt`
 * instants so we can be sure we're scanning rendered cells rather than a
 * shared placeholder, then walks every `audit-event-row` and asserts the
 * visible string matches the canonical ISO regex.
 */

interface AuditEventFixture {
  id: number
  tradeId: string | null
  bookId: string | null
  instrumentId: string | null
  assetClass: string | null
  side: string | null
  quantity: string | null
  priceAmount: string | null
  priceCurrency: string | null
  tradedAt: string | null
  receivedAt: string
  previousHash: string | null
  recordHash: string
  userId: string | null
  userRole: string | null
  eventType: string
  modelName: string | null
  scenarioId: string | null
  limitId: string | null
  submissionId: string | null
  details: string | null
  sequenceNumber: number | null
}

const baseEvent: Omit<AuditEventFixture, 'id' | 'tradeId' | 'receivedAt' | 'recordHash' | 'sequenceNumber'> = {
  bookId: 'port-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  side: 'BUY',
  quantity: '100',
  priceAmount: '150.00',
  priceCurrency: 'USD',
  tradedAt: '2026-02-21T14:00:00Z',
  previousHash: null,
  userId: 'trader-a',
  userRole: 'TRADER',
  eventType: 'TRADE_BOOKED',
  modelName: null,
  scenarioId: null,
  limitId: null,
  submissionId: null,
  details: null,
}

const AUDIT_EVENTS: AuditEventFixture[] = [
  {
    ...baseEvent,
    id: 301,
    tradeId: 'trade-301',
    // Picked to match the trader-review screenshot ("2/21/2026, 2:00:01 PM")
    // so a regression to en-US locale rendering would visibly reproduce.
    receivedAt: '2026-02-21T14:00:01Z',
    recordHash: 'hash-301',
    sequenceNumber: 1,
  },
  {
    ...baseEvent,
    id: 302,
    tradeId: 'trade-302',
    // A late-evening instant so the hour component crosses noon (12-hour
    // locales would inject "PM" — the ISO regex must reject that).
    receivedAt: '2026-05-27T23:45:09Z',
    recordHash: 'hash-302',
    sequenceNumber: 2,
  },
]

/** Mocks `GET /api/v1/audit/events` with the seeded events. */
async function mockAuditEvents(page: Page, events: AuditEventFixture[]): Promise<void> {
  await page.route('**/api/v1/audit/events**', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(events),
    })
  })
}

/** Mocks `GET /api/v1/audit/verify` with a passing chain result. */
async function mockAuditVerify(
  page: Page,
  result: { valid: boolean; eventCount: number },
): Promise<void> {
  await page.route('**/api/v1/audit/verify', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(result),
    })
  })
}

/**
 * The canonical project pattern produced by `formatTimestamp`:
 *   2025-01-15 10:05:30
 *
 * The regex also tolerates a date-only form (`2026-05-27`) so cells that
 * intentionally omit the time component (rare in the audit table, common
 * elsewhere) still pass. The 12-hour `AM/PM` suffix used by the buggy
 * `toLocaleString('en-US')` output is rejected.
 */
const ISO_TIMESTAMP_RE = /^\d{4}-\d{2}-\d{2}( \d{2}:\d{2}(:\d{2})?)?( UTC)?$/

test.describe('Date format consistency — Activity tab', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockAuditEvents(page, AUDIT_EVENTS)
    await mockAuditVerify(page, { valid: true, eventCount: AUDIT_EVENTS.length })
  })

  test('renders every visible audit-event timestamp in canonical ISO shape', async ({
    page,
  }) => {
    await page.goto('/')
    await page.getByTestId('tab-activity').click()
    await expect(page.getByTestId('audit-log-panel')).toBeVisible()

    // Wait for the rows to render — the panel mounts behind an
    // auth-readiness gate (see activity-tab.spec.ts) so we cannot assert
    // immediately after click.
    await page.waitForSelector('[data-testid="audit-event-row"]', {
      state: 'visible',
    })

    const rows = page.getByTestId('audit-event-row')
    const count = await rows.count()
    expect(count).toBeGreaterThan(0)

    for (let i = 0; i < count; i++) {
      const text = (await rows.nth(i).innerText()).trim()
      expect(
        ISO_TIMESTAMP_RE.test(text),
        `Audit row ${i} timestamp "${text}" is not in canonical ISO format ` +
          `(expected /${ISO_TIMESTAMP_RE.source}/). The Activity tab must use ` +
          `the same YYYY-MM-DD HH:MM:SS shape as every other tab — see ` +
          `formatTimestamp() in ui/src/utils/format.ts.`,
      ).toBe(true)
    }
  })
})
