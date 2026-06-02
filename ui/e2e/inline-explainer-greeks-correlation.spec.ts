import { test, expect, type Page, type Route } from '@playwright/test'
import {
  chatMockCanned,
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_VAR_RESULT,
  TEST_JOB_HISTORY,
} from './fixtures'

// ---------------------------------------------------------------------------
// Inline explainer — Greeks panel + Correlation matrix (docs/plans/ai-v2.md §9.5)
// ---------------------------------------------------------------------------
//
// Two card/matrix-level <ExplainButton> surfaces land in this checkbox:
//
//  A) The aggregate Greeks card (<RiskSensitivities>, rendered inside the
//     VaR dashboard) exposes ONE card-level explain button in its header.
//     Clicking it opens an inline <AIInsightPanel> wired to a
//     <StreamingNarrative> consuming POST /api/v1/insights/chat. The
//     page_context carries the aggregate Greeks figures (delta / gamma /
//     vega summed across asset classes, plus book-level theta / rho) and
//     the book scope.
//
//  B) The correlation matrix (<CorrelationHeatmap>, rendered in the
//     aggregated risk view) exposes ONE matrix-level explain button next
//     to its title. The page_context is matrix-level and focused on
//     correlation breaks — the off-diagonal pairs whose absolute
//     correlation crosses the break threshold, derived from the matrix
//     data already on screen.
//
// Coverage per the checkbox ("Playwright covers both"): for EACH surface
// — explain open + streaming narrative renders, double-click protection,
// panel close, and the page_context payload.

const TEST_BOOKS_MULTI = [{ bookId: 'port-1' }, { bookId: 'port-2' }]

const TEST_CROSS_BOOK_VAR_RESULT = {
  portfolioGroupId: 'firm',
  bookIds: ['port-1', 'port-2'],
  calculationType: 'PARAMETRIC',
  confidenceLevel: 'CL_95',
  varValue: '200000.00',
  expectedShortfall: '300000.00',
  componentBreakdown: [
    { assetClass: 'EQUITY', varContribution: '120000.00', percentageOfTotal: '60.00' },
    { assetClass: 'DERIVATIVE', varContribution: '80000.00', percentageOfTotal: '40.00' },
  ],
  bookContributions: [
    { bookId: 'port-1', varContribution: '130000.00', percentageOfTotal: '65.00', standaloneVar: '150000.00', diversificationBenefit: '20000.00', marginalVar: '0.866667', incrementalVar: '140000.00' },
    { bookId: 'port-2', varContribution: '70000.00', percentageOfTotal: '35.00', standaloneVar: '100000.00', diversificationBenefit: '30000.00', marginalVar: '0.700000', incrementalVar: '80000.00' },
  ],
  totalStandaloneVar: '250000.00',
  diversificationBenefit: '50000.00',
  calculatedAt: '2025-01-15T12:00:00Z',
}

/**
 * Mocks `/api/v1/insights/chat` with a deferred SSE body and counts how
 * many times the route was hit. The body resolves only after `releaseMs`
 * so a second (duplicate) click can be observed *before* the first
 * stream finishes — the window double-click protection must cover.
 */
async function chatMockCounting(
  page: Page,
  releaseMs = 250,
): Promise<{ count: () => number }> {
  let hits = 0
  const citation = {
    tool: 'get_greeks_summary',
    params: { book_id: 'port-1' },
    result_field: 'delta',
    result_value: 2000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-20T08:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 120,
    quality_flags: [],
  }
  const body = [
    'data: {"delta":"The book is ","done":false}\n\n',
    'data: {"delta":"net long delta.","done":false}\n\n',
    'event: source\ndata: ' + JSON.stringify([citation]) + '\n\n',
    'data: ' +
      JSON.stringify({
        done: true,
        session_id: 'sess-greeks',
        conversation_id: 'conv-greeks',
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

/**
 * Mocks the cross-book VaR endpoints so the aggregated risk view renders
 * the correlation heatmap. Mirrors the helper in cross-book-var.spec.ts.
 */
async function mockCrossBookVaR(page: Page): Promise<void> {
  await page.route('**/api/v1/risk/var/cross-book', (route: Route) => {
    if (route.request().method() === 'POST') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(TEST_CROSS_BOOK_VAR_RESULT),
      })
    } else {
      route.fallback()
    }
  })
  await page.route('**/api/v1/risk/var/cross-book/*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_CROSS_BOOK_VAR_RESULT),
    })
  })
}

/** Navigate to the Risk tab and wait for the VaR dashboard to render. */
async function gotoRiskDashboard(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
  await page.waitForSelector('[data-testid="var-dashboard"]')
}

// ---------------------------------------------------------------------------
// Surface A — Greeks panel (aggregate Greeks card)
// ---------------------------------------------------------------------------

test.describe('Inline explainer — Greeks panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('clicking the Greeks explain button opens the streaming insight panel', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoRiskDashboard(page)

    await expect(page.getByTestId('explain-greeks')).toBeVisible()
    await page.getByTestId('explain-greeks').click()

    await expect(page.getByTestId('greeks-explain-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-streaming')).toBeVisible()

    // The streaming narrative renders the canned answer token by token.
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    // Panel title is scoped to the aggregate Greeks card.
    await expect(page.getByTestId('ai-insight-panel')).toContainText(
      'Aggregate Greeks',
    )
  })

  test('a rapid double-click does not open a duplicate Greeks panel or request', async ({
    page,
  }) => {
    const chatMock = await chatMockCounting(page, 400)
    await gotoRiskDashboard(page)

    const button = page.getByTestId('explain-greeks')
    await button.click()
    await button.click()
    await button.click()

    await expect(page.getByTestId('greeks-explain-panel')).toBeVisible()

    // Exactly one panel and exactly one /chat request despite three clicks.
    await expect(page.getByTestId('greeks-explain-panel')).toHaveCount(1)
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'The book is net long delta.',
    )
    expect(chatMock.count()).toBe(1)
  })

  test('the Greeks panel close button dismisses the explainer', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoRiskDashboard(page)

    await page.getByTestId('explain-greeks').click()
    await expect(page.getByTestId('greeks-explain-panel')).toBeVisible()

    await page.getByTestId('ai-insight-close').click()
    await expect(page.getByTestId('greeks-explain-panel')).toHaveCount(0)
  })

  test('the Greeks explain request carries the aggregate Greeks figures', async ({
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

    await gotoRiskDashboard(page)
    await page.getByTestId('explain-greeks').click()
    await expect(page.getByTestId('greeks-explain-panel')).toBeVisible()

    expect(captured).not.toBeNull()
    const ctx = (captured ?? {}) as Record<string, unknown>
    expect(ctx.page).toBe('greeks')

    // Aggregate Greeks payload — delta/gamma/vega summed, theta/rho scalars.
    const agg = ctx.aggregate_greeks as Record<string, number>
    expect(agg).toBeTruthy()
    expect(typeof agg.delta).toBe('number')
    expect(typeof agg.gamma).toBe('number')
    expect(typeof agg.vega).toBe('number')
    expect(typeof agg.theta).toBe('number')
    expect(typeof agg.rho).toBe('number')
  })
})

// ---------------------------------------------------------------------------
// Surface B — Correlation matrix (matrix-level explain for breaks)
// ---------------------------------------------------------------------------

test.describe('Inline explainer — Correlation matrix', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await page.unroute('**/api/v1/books')
    await page.route('**/api/v1/books', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(TEST_BOOKS_MULTI),
      })
    })
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      jobHistory: TEST_JOB_HISTORY,
    })
    await mockCrossBookVaR(page)
  })

  test('clicking the matrix explain button opens the streaming insight panel', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoRiskDashboard(page)

    await expect(page.getByTestId('correlation-heatmap')).toBeVisible()
    await expect(page.getByTestId('explain-correlation-matrix')).toBeVisible()
    await page.getByTestId('explain-correlation-matrix').click()

    await expect(page.getByTestId('correlation-explain-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-streaming')).toBeVisible()

    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    // Panel title is scoped to correlation breaks.
    await expect(page.getByTestId('ai-insight-panel')).toContainText(
      'Correlation Breaks',
    )
  })

  test('a rapid double-click does not open a duplicate matrix panel or request', async ({
    page,
  }) => {
    const chatMock = await chatMockCounting(page, 400)
    await gotoRiskDashboard(page)

    await expect(page.getByTestId('correlation-heatmap')).toBeVisible()
    const button = page.getByTestId('explain-correlation-matrix')
    await button.click()
    await button.click()
    await button.click()

    await expect(page.getByTestId('correlation-explain-panel')).toBeVisible()
    await expect(page.getByTestId('correlation-explain-panel')).toHaveCount(1)
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'The book is net long delta.',
    )
    expect(chatMock.count()).toBe(1)
  })

  test('the matrix panel close button dismisses the explainer', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoRiskDashboard(page)

    await expect(page.getByTestId('correlation-heatmap')).toBeVisible()
    await page.getByTestId('explain-correlation-matrix').click()
    await expect(page.getByTestId('correlation-explain-panel')).toBeVisible()

    await page.getByTestId('ai-insight-close').click()
    await expect(page.getByTestId('correlation-explain-panel')).toHaveCount(0)
  })

  test('the matrix explain request carries the derived correlation breaks', async ({
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

    await gotoRiskDashboard(page)
    await expect(page.getByTestId('correlation-heatmap')).toBeVisible()
    await page.getByTestId('explain-correlation-matrix').click()
    await expect(page.getByTestId('correlation-explain-panel')).toBeVisible()

    expect(captured).not.toBeNull()
    const ctx = (captured ?? {}) as Record<string, unknown>
    expect(ctx.page).toBe('correlation-matrix')
    expect(Array.isArray(ctx.asset_classes)).toBe(true)

    // Correlation breaks — off-diagonal pairs ranked most extreme first.
    const breaks = ctx.correlation_breaks as {
      a: string
      b: string
      correlation: number
    }[]
    expect(breaks.length).toBeGreaterThan(0)
    // Strongest pair is surfaced first.
    expect(Math.abs(breaks[0].correlation)).toBeGreaterThanOrEqual(
      Math.abs(breaks[breaks.length - 1].correlation),
    )
  })
})
