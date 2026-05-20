import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * E2E coverage for the Activity tab's {@link AuditLogPanel}.
 *
 * Exercises the four user-visible states the panel can be in: empty result,
 * rendered data with a verified hash chain, trade-ID filtering, and the
 * API-error path. Routes are mocked per `e2e/fixtures.ts`; the audit endpoints
 * are the gateway-proxied paths the typed client in `src/api/audit.ts` calls.
 *
 * Plan ref: plans/audit-v2.md PR 8 §8.4.
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
  id: 101,
  tradeId: 'trade-1',
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
  recordHash: 'hash-101',
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

const LIMIT_BREACH: AuditEventFixture = {
  ...TRADE_BOOKED,
  id: 102,
  tradeId: 'trade-2',
  instrumentId: 'GOOGL',
  receivedAt: '2026-05-19T11:00:01Z',
  previousHash: 'hash-101',
  recordHash: 'hash-102',
  eventType: 'LIMIT_BREACH',
  sequenceNumber: 2,
}

const MODEL_APPROVED: AuditEventFixture = {
  ...TRADE_BOOKED,
  id: 103,
  tradeId: null,
  bookId: null,
  instrumentId: null,
  assetClass: null,
  side: null,
  quantity: null,
  priceAmount: null,
  priceCurrency: null,
  tradedAt: null,
  receivedAt: '2026-05-19T12:00:01Z',
  previousHash: 'hash-102',
  recordHash: 'hash-103',
  userId: 'risk-officer',
  userRole: 'RISK_MANAGER',
  eventType: 'MODEL_APPROVED',
  modelName: 'VaR-Parametric-v2',
  sequenceNumber: 3,
}

const ALL_EVENTS: AuditEventFixture[] = [TRADE_BOOKED, LIMIT_BREACH, MODEL_APPROVED]

/**
 * Mocks `GET /api/v1/audit/events`, filtering the returned set by the
 * `tradeId` query parameter when present.
 */
async function mockAuditEvents(
  page: Page,
  events: AuditEventFixture[],
): Promise<void> {
  await page.route('**/api/v1/audit/events**', (route: Route) => {
    const url = new URL(route.request().url())
    const tradeId = url.searchParams.get('tradeId')
    const body = tradeId ? events.filter((e) => e.tradeId === tradeId) : events
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    })
  })
}

/** Mocks `GET /api/v1/audit/verify` with the given chain verification result. */
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

test.describe('Activity tab — audit log', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows the empty state when no audit events match', async ({ page }) => {
    await mockAuditEvents(page, [])
    await mockAuditVerify(page, { valid: true, eventCount: 0 })

    await page.goto('/')
    await page.getByTestId('tab-activity').click()

    const panel = page.getByTestId('audit-log-panel')
    await expect(panel).toBeVisible()
    await expect(panel.getByText('No audit events match your filters.')).toBeVisible()
    await expect(page.getByTestId('audit-events-table')).toHaveCount(0)
  })

  test('renders audit event rows, event-type badges and a verified chain', async ({ page }) => {
    await mockAuditEvents(page, ALL_EVENTS)
    await mockAuditVerify(page, { valid: true, eventCount: ALL_EVENTS.length })

    await page.goto('/')
    await page.getByTestId('tab-activity').click()

    await expect(page.getByTestId('audit-log-panel')).toBeVisible()
    await expect(page.getByTestId('audit-events-table')).toBeVisible()

    // One row per event, with the event-type rendered as a badge.
    await expect(page.getByTestId('audit-row-101')).toBeVisible()
    await expect(page.getByTestId('audit-row-102')).toBeVisible()
    await expect(page.getByTestId('audit-row-103')).toBeVisible()
    await expect(page.getByTestId('audit-event-badge-101')).toHaveText('TRADE_BOOKED')
    await expect(page.getByTestId('audit-event-badge-102')).toHaveText('LIMIT_BREACH')
    await expect(page.getByTestId('audit-event-badge-103')).toHaveText('MODEL_APPROVED')

    // The trade-lifecycle row surfaces its trade id; the governance row its model.
    await expect(page.getByTestId('audit-row-101')).toContainText('trade-1')
    await expect(page.getByTestId('audit-row-103')).toContainText('VaR-Parametric-v2')

    // The chain-integrity indicator reports the verified state and event count.
    const chain = page.getByTestId('audit-chain-valid')
    await expect(chain).toBeVisible()
    await expect(chain).toContainText('Chain verified')
    await expect(chain).toContainText('3 events')
  })

  test('filters by trade ID and forwards tradeId as a query parameter', async ({ page }) => {
    await mockAuditEvents(page, ALL_EVENTS)
    await mockAuditVerify(page, { valid: true, eventCount: ALL_EVENTS.length })

    await page.goto('/')
    await page.getByTestId('tab-activity').click()

    // Initial unfiltered load renders all rows.
    await expect(page.getByTestId('audit-row-101')).toBeVisible()
    await expect(page.getByTestId('audit-row-102')).toBeVisible()

    // Wait for the request that carries the tradeId filter.
    const filteredRequest = page.waitForRequest(
      (req) => req.url().includes('/api/v1/audit/events') && req.url().includes('tradeId=trade-2'),
    )
    await page.getByTestId('audit-filter-trade').fill('trade-2')
    const request = await filteredRequest

    // The intercepted request URL carries the tradeId query parameter…
    expect(new URL(request.url()).searchParams.get('tradeId')).toBe('trade-2')

    // …and only the matching row remains.
    await expect(page.getByTestId('audit-row-102')).toBeVisible()
    await expect(page.getByTestId('audit-row-101')).toHaveCount(0)
    await expect(page.getByTestId('audit-row-103')).toHaveCount(0)
  })

  test('shows the error state when the audit events request fails', async ({ page }) => {
    await page.route('**/api/v1/audit/events**', (route: Route) => {
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'upstream failure' }),
      })
    })
    await mockAuditVerify(page, { valid: true, eventCount: 0 })

    await page.goto('/')
    await page.getByTestId('tab-activity').click()

    const errorCard = page.getByTestId('audit-error')
    await expect(errorCard).toBeVisible()
    await expect(errorCard).toContainText('Failed to fetch audit events: 500')
    await expect(page.getByTestId('audit-events-table')).toHaveCount(0)
  })
})
