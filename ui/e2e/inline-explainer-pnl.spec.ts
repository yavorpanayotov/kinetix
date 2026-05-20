import { test, expect, type Page, type Route } from '@playwright/test'
import { chatMockCanned, mockAllApiRoutes, mockRiskTabRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Inline explainer — P&L attribution chart (plans/ai-v2.md §9.2)
// ---------------------------------------------------------------------------
//
// The P&L attribution waterfall chart exposes an <ExplainButton> in its
// header, above the waterfall body. Clicking it opens an <AIInsightPanel>
// wired to a <StreamingNarrative> consuming the v2 chat endpoint
// (POST /api/v1/insights/chat). The `page_context` carries the attribution
// period date plus the top-N P&L drivers.
//
// Coverage per the checkbox: open the explainer + streaming narrative
// renders, double-click protection, and panel close.

const POPULATED_SOD = {
  exists: true,
  baselineDate: '2026-05-19',
  snapshotType: 'AUTO_CLOSE',
  createdAt: '2026-05-19T09:00:00Z',
  sourceJobId: 'sod-job-1',
  calculationType: 'PARAMETRIC',
}

const POPULATED_WATERFALL = {
  bookId: 'port-1',
  date: '2026-05-19',
  totalPnl: '85432.10',
  deltaPnl: '17704.06',
  gammaPnl: '12300.00',
  vegaPnl: '8400.50',
  thetaPnl: '-2100.00',
  rhoPnl: '450.25',
  unexplainedPnl: '0.00',
  positionAttributions: [],
  calculatedAt: '2026-05-19T10:30:00Z',
}

/**
 * Mocks `/api/v1/insights/chat` with a deferred SSE body and counts how
 * many times the route was hit. The body resolves only after `releaseMs`
 * so a second (duplicate) click can be observed *before* the first stream
 * finishes — the window double-click protection must cover.
 */
async function chatMockCounting(
  page: Page,
  releaseMs = 250,
): Promise<{ count: () => number }> {
  let hits = 0
  const citation = {
    tool: 'get_pnl_attribution',
    params: { book_id: 'port-1', date: '2026-05-19' },
    result_field: 'delta_pnl',
    result_value: 17704.06,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-19T10:30:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 120,
    quality_flags: [],
  }
  const body = [
    'data: {"delta":"Delta drove ","done":false}\n\n',
    'data: {"delta":"the P&L move.","done":false}\n\n',
    'event: source\ndata: ' + JSON.stringify([citation]) + '\n\n',
    'data: ' +
      JSON.stringify({
        done: true,
        session_id: 'sess-pnl',
        conversation_id: 'conv-pnl',
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

async function gotoPnlWaterfall(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-pnl').click()
  await page.waitForSelector('[data-testid="waterfall-chart"]')
}

test.describe('Inline explainer — P&L attribution chart', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      sodStatus: POPULATED_SOD,
      pnlAttribution: POPULATED_WATERFALL,
    })
  })

  test('clicking the explain button opens the streaming insight panel', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoPnlWaterfall(page)

    await expect(page.getByTestId('explain-pnl-attribution')).toBeVisible()
    await page.getByTestId('explain-pnl-attribution').click()

    await expect(page.getByTestId('pnl-explain-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-streaming')).toBeVisible()

    // The streaming narrative renders the canned answer token by token.
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    // Panel title is scoped to the P&L attribution chart.
    await expect(page.getByTestId('ai-insight-panel')).toContainText(
      'P&L Attribution',
    )
  })

  test('the explain button sits above the waterfall body', async ({ page }) => {
    await chatMockCanned(page)
    await gotoPnlWaterfall(page)

    const buttonBox = await page
      .getByTestId('explain-pnl-attribution')
      .boundingBox()
    const chartBox = await page.getByTestId('waterfall-chart').boundingBox()

    expect(buttonBox).not.toBeNull()
    expect(chartBox).not.toBeNull()
    // The explain affordance is rendered above the waterfall bars.
    expect(buttonBox!.y).toBeLessThan(chartBox!.y)
  })

  test('a rapid double-click does not open a duplicate panel or fire a duplicate request', async ({
    page,
  }) => {
    const chatMock = await chatMockCounting(page, 400)
    await gotoPnlWaterfall(page)

    const button = page.getByTestId('explain-pnl-attribution')
    // Three clicks in quick succession while the first stream is still in
    // flight (the mock holds the response for 400ms).
    await button.click()
    await button.click()
    await button.click()

    await expect(page.getByTestId('pnl-explain-panel')).toBeVisible()

    // Exactly one panel and exactly one /chat request despite three clicks.
    await expect(page.getByTestId('pnl-explain-panel')).toHaveCount(1)
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Delta drove the P&L move.',
    )
    expect(chatMock.count()).toBe(1)
  })

  test('the panel close button dismisses the explainer', async ({ page }) => {
    await chatMockCanned(page)
    await gotoPnlWaterfall(page)

    await page.getByTestId('explain-pnl-attribution').click()
    await expect(page.getByTestId('pnl-explain-panel')).toBeVisible()

    await page.getByTestId('ai-insight-close').click()
    await expect(page.getByTestId('pnl-explain-panel')).toHaveCount(0)
  })

  test('the explain request carries the attribution date and top P&L drivers', async ({
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

    await gotoPnlWaterfall(page)
    await page.getByTestId('explain-pnl-attribution').click()
    await expect(page.getByTestId('pnl-explain-panel')).toBeVisible()

    expect(captured).not.toBeNull()
    const ctx = (captured ?? {}) as Record<string, unknown>
    expect(ctx.page).toBe('pnl-attribution')
    expect(ctx.date).toBe('2026-05-19')
    expect(ctx.book_id).toBe('port-1')

    const drivers = ctx.top_drivers as { factor: string; value: number }[]
    expect(drivers).toHaveLength(3)
    // Sorted by absolute contribution: delta 17704 > gamma 12300 > vega 8400.
    expect(drivers.map((d) => d.factor)).toEqual(['Delta', 'Gamma', 'Vega'])
  })
})
