import { test, expect, type Page, type Route } from '@playwright/test'
import { chatMockCanned, mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Inline explainer — Alerts / breaches panel (plans/ai-v2.md §9.3)
// ---------------------------------------------------------------------------
//
// The NotificationCenter alerts/breaches panel exposes an <ExplainButton> on
// every alert row. Clicking it opens an inline <AIInsightPanel> wired to a
// <StreamingNarrative> consuming the v2 chat endpoint
// (POST /api/v1/insights/chat). The `page_context` carries the clicked
// alert's payload — alertId, type, currentValue, threshold and severity.
//
// Coverage per the checkbox: per-row explain open + streaming narrative
// renders, double-click protection, "only one panel open", panel close, and
// the page_context payload.

const TRIGGERED_ALERT = {
  id: 'alert-explain-1',
  ruleId: 'rule-1',
  ruleName: 'VaR Critical Limit',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'VaR breach on book-1',
  currentValue: 250000,
  threshold: 100000,
  bookId: 'book-1',
  triggeredAt: '2025-01-15T09:00:00Z',
  status: 'TRIGGERED',
}

const WARNING_ALERT = {
  id: 'alert-explain-2',
  ruleId: 'rule-2',
  ruleName: 'ES Warning Limit',
  type: 'PNL_THRESHOLD',
  severity: 'WARNING',
  message: 'Expected shortfall exceeded',
  currentValue: 250000,
  threshold: 200000,
  bookId: 'book-1',
  triggeredAt: '2025-01-15T09:05:00Z',
  status: 'TRIGGERED',
}

/** Routes the alerts list GET with the two canned alerts above. */
async function mockAlertsList(page: Page): Promise<void> {
  await page.unroute('**/api/v1/notifications/alerts*')
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([TRIGGERED_ALERT, WARNING_ALERT]),
      })
    } else {
      route.fallback()
    }
  })
}

/**
 * Mocks `/api/v1/insights/chat` with a deferred SSE body and counts how many
 * times the route was hit. The body resolves only after `releaseMs` so a
 * second (duplicate) click can be observed *before* the first stream
 * finishes — that is the window double-click protection must cover.
 */
async function chatMockCounting(
  page: Page,
  releaseMs = 250,
): Promise<{ count: () => number }> {
  let hits = 0
  const citation = {
    tool: 'get_book_var',
    params: { book_id: 'book-1' },
    result_field: 'total_var',
    result_value: 250000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-20T08:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 120,
    quality_flags: [],
  }
  const body = [
    'data: {"delta":"This alert fired ","done":false}\n\n',
    'data: {"delta":"because VaR breached the limit.","done":false}\n\n',
    'event: source\ndata: ' + JSON.stringify([citation]) + '\n\n',
    'data: ' +
      JSON.stringify({
        done: true,
        session_id: 'sess-alert',
        conversation_id: 'conv-alert',
        model: 'canned-chat',
        mode: 'canned',
        citations: [citation],
      }) +
      '\n\n',
  ].join('')

  await page.unroute('**/api/v1/insights/chat')
  await page.route('**/api/v1/insights/chat', async (route: Route) => {
    hits += 1
    await new Promise((resolve) => setTimeout(resolve, releaseMs))
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body,
    })
  })

  return { count: () => hits }
}

async function gotoAlertsPanel(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-alerts').click()
  await page.waitForSelector('[data-testid="alerts-list"]')
}

test.describe('Inline explainer — Alerts / breaches panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockAlertsList(page)
  })

  test('clicking an alert-row explain button opens the streaming insight panel', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoAlertsPanel(page)

    await expect(
      page.getByTestId('explain-alert-alert-explain-1'),
    ).toBeVisible()
    await page.getByTestId('explain-alert-alert-explain-1').click()

    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-1'),
    ).toBeVisible()
    await expect(page.getByTestId('ai-insight-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-streaming')).toBeVisible()

    // The streaming narrative renders the canned answer token by token.
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    // Panel title is scoped to the clicked alert.
    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-1'),
    ).toContainText('VaR breach on book-1')
  })

  test('a rapid double-click does not open a duplicate panel or fire a duplicate request', async ({
    page,
  }) => {
    const chatMock = await chatMockCounting(page, 400)
    await gotoAlertsPanel(page)

    const button = page.getByTestId('explain-alert-alert-explain-1')
    // Three clicks in quick succession while the first stream is still in
    // flight (the mock holds the response for 400ms).
    await button.click()
    await button.click()
    await button.click()

    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-1'),
    ).toBeVisible()

    // Exactly one panel and exactly one /chat request despite three clicks.
    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-1'),
    ).toHaveCount(1)
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'This alert fired because VaR breached the limit.',
    )
    expect(chatMock.count()).toBe(1)
  })

  test('opening a second alert explainer closes the first (only one panel open)', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoAlertsPanel(page)

    await page.getByTestId('explain-alert-alert-explain-1').click()
    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-1'),
    ).toBeVisible()

    // Open a different alert's explainer — the first panel is replaced.
    await page.getByTestId('explain-alert-alert-explain-2').click()

    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-1'),
    ).toHaveCount(0)
    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-2'),
    ).toHaveCount(1)
    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-2'),
    ).toContainText('Expected shortfall exceeded')
  })

  test('the panel close button dismisses the explainer', async ({ page }) => {
    await chatMockCanned(page)
    await gotoAlertsPanel(page)

    await page.getByTestId('explain-alert-alert-explain-1').click()
    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-1'),
    ).toBeVisible()

    await page.getByTestId('ai-insight-close').click()
    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-1'),
    ).toHaveCount(0)
  })

  test('the explain request carries the alert payload — alertId, type, currentValue, threshold, severity', async ({
    page,
  }) => {
    let captured: Record<string, unknown> | null = null
    await page.unroute('**/api/v1/insights/chat')
    await page.route('**/api/v1/insights/chat', async (route: Route) => {
      const payload = route.request().postDataJSON() as {
        page_context?: Record<string, unknown>
      }
      captured = payload.page_context ?? null
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body:
          'data: {"delta":"ok","done":false}\n\n' +
          'data: {"done":true,"session_id":"s","conversation_id":"c","model":"m","mode":"canned"}\n\n',
      })
    })

    await gotoAlertsPanel(page)
    await page.getByTestId('explain-alert-alert-explain-1').click()
    await expect(
      page.getByTestId('alert-explain-panel-alert-explain-1'),
    ).toBeVisible()

    expect(captured).not.toBeNull()
    const ctx = (captured ?? {}) as Record<string, unknown>
    expect(ctx.page).toBe('alerts')
    expect(ctx.alertId).toBe('alert-explain-1')
    expect(ctx.type).toBe('VAR_BREACH')
    expect(ctx.currentValue).toBe(250000)
    expect(ctx.threshold).toBe(100000)
    expect(ctx.severity).toBe('CRITICAL')
  })
})
