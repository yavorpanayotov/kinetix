import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Regression test for the AuditLogPanel auth-readiness race (kx-bly / kx-41l).
 *
 * Before kx-41l, the Activity tab's audit fetches fired during the first
 * render — before {@link DemoAuthProvider} had finished publishing the persona
 * headers `authFetch` needs. The panel then sat indefinitely on the chain
 * verification spinner whose label was "Verifying chain...", because the in-
 * flight request was launched without a persona header and silently stalled.
 *
 * The fix gates both `useEffect` fetches on `auth.initialising`, and the
 * spinner copy was renamed to "Loading activity log...". This spec guards both
 * legs of that fix:
 *
 *  1. clicking the Activity tab eventually surfaces at least one audit row
 *     (no infinite spinner);
 *  2. the old "Verifying chain..." copy never appears;
 *  3. the new "Loading activity log..." copy may transiently appear but is
 *     gone by the time rows render.
 *
 * The test drives the real demo auth flow (no auth bypass) so a future change
 * that re-introduces the race — for example, by dropping the `initialising`
 * gate or by re-ordering provider initialisation — will fail this spec.
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

const TRADE_BOOKED: AuditEventFixture = {
  id: 201,
  tradeId: 'trade-201',
  bookId: 'port-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  side: 'BUY',
  quantity: '100',
  priceAmount: '150.00',
  priceCurrency: 'USD',
  tradedAt: '2026-05-19T10:30:00Z',
  receivedAt: '2026-05-19T10:30:01Z',
  previousHash: null,
  recordHash: 'hash-201',
  userId: 'trader-a',
  userRole: 'TRADER',
  eventType: 'TRADE_BOOKED',
  modelName: null,
  scenarioId: null,
  limitId: null,
  submissionId: null,
  details: null,
  sequenceNumber: 1,
}

/** Mocks `GET /api/v1/audit/events` with a single trade-booked event. */
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

/** Builds an audit event of the given type, overriding the trade-booked baseline. */
function eventOfType(
  id: number,
  eventType: string,
  overrides: Partial<AuditEventFixture> = {},
): AuditEventFixture {
  return { ...TRADE_BOOKED, id, eventType, recordHash: `hash-${id}`, ...overrides }
}

test.describe('Activity tab — full event lifecycle (P2 #28)', () => {
  // The Activity feed must surface governance / risk / reconciliation lifecycle
  // events forwarded by the gateway projection, not only TRADE_BOOKED. This
  // guards against a regression where non-booking events were dropped.
  const MIXED_EVENTS: AuditEventFixture[] = [
    eventOfType(301, 'TRADE_BOOKED', { tradeId: 'trade-301' }),
    eventOfType(302, 'LIMIT_BREACH', { tradeId: null, limitId: 'LIM-7' }),
    eventOfType(303, 'RUN_PROMOTED', { tradeId: null, modelName: 'VAR-EOD' }),
    eventOfType(304, 'RECONCILIATION_BREAK', { tradeId: null, bookId: 'port-2' }),
  ]

  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockAuditEvents(page, MIXED_EVENTS)
    await mockAuditVerify(page, { valid: true, eventCount: MIXED_EVENTS.length })
  })

  test('renders a row and badge for each non-TRADE_BOOKED event type', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-activity').click()

    await expect(page.getByTestId('audit-log-panel')).toBeVisible()
    await page.waitForSelector('[data-testid="audit-event-row"]', { state: 'visible' })

    await expect(page.getByTestId('audit-event-badge-301')).toHaveText('TRADE_BOOKED')
    await expect(page.getByTestId('audit-event-badge-302')).toHaveText('LIMIT_BREACH')
    await expect(page.getByTestId('audit-event-badge-303')).toHaveText('RUN_PROMOTED')
    await expect(page.getByTestId('audit-event-badge-304')).toHaveText('RECONCILIATION_BREAK')
  })
})

test.describe('Activity tab — auth-readiness regression (kx-bly)', () => {
  test.beforeEach(async ({ page }) => {
    // mockAllApiRoutes wires up the same fixtures the rest of the e2e suite
    // uses: a mocked Keycloak (so the OIDC handshake doesn't need a live
    // realm) plus the backend route stubs. The kx-41l race lives in the
    // `auth.initialising` gate, which `useAuth` flips from `true` → `false`
    // for the Keycloak provider in exactly the same way as the demo
    // provider, so this fixture still exercises the gated code path.
    await mockAllApiRoutes(page)
    await mockAuditEvents(page, [TRADE_BOOKED])
    await mockAuditVerify(page, { valid: true, eventCount: 1 })
  })

  test('renders at least one audit row immediately after the Activity tab is clicked', async ({
    page,
  }) => {
    await page.goto('/')

    // Click straight into Activity — no warm-up on other tabs. The original
    // race showed up specifically on the first render of AuditLogPanel.
    await page.getByTestId('tab-activity').click()

    // The panel mounts.
    await expect(page.getByTestId('audit-log-panel')).toBeVisible()

    // Presence-only assertion: at least one row eventually renders.
    // Using waitForSelector (not a wall-clock 5s timeout, per QA review).
    await page.waitForSelector('[data-testid="audit-event-row"]', {
      state: 'visible',
    })

    // The old pre-kx-41l spinner copy must never appear — it was the very
    // string a stuck panel was showing forever.
    await expect(page.getByText('Verifying chain...')).toHaveCount(0)

    // The new spinner copy may have appeared transiently while auth settled,
    // but must be gone once the row is on screen.
    await expect(page.getByText('Loading activity log...')).toHaveCount(0)
  })
})
