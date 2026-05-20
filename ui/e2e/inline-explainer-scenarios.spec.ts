import { test, expect, type Page, type Route } from '@playwright/test'
import { chatMockCanned, mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Inline explainer — Stress / Scenarios panel (plans/ai-v2.md §9.4)
// ---------------------------------------------------------------------------
//
// The scenario comparison table on the Scenarios tab exposes an
// <ExplainButton> on every scenario result row. Clicking it opens an inline
// <AIInsightPanel> wired to a <StreamingNarrative> consuming the v2 chat
// endpoint (POST /api/v1/insights/chat). The `page_context` carries the
// clicked scenario's payload — scenario name, stressed P&L, and the top
// stressed positions derived from the result's positionImpacts.
//
// Coverage per the checkbox: per-row explain open + streaming narrative
// renders, double-click protection, "only one panel open", panel close, and
// the page_context payload.

const STRESS_RESULT_EQUITY_CRASH = {
  scenarioName: 'EQUITY_CRASH',
  baseVar: '125000',
  stressedVar: '375000',
  pnlImpact: '-250000',
  assetClassImpacts: [
    { assetClass: 'EQUITY', baseExposure: '500000', stressedExposure: '350000', pnlImpact: '-150000' },
  ],
  positionImpacts: [
    { instrumentId: 'AAPL', assetClass: 'EQUITY', baseMarketValue: '155000', stressedMarketValue: '108500', pnlImpact: '-46500', percentageOfTotal: '62.0' },
    { instrumentId: 'GOOGL', assetClass: 'EQUITY', baseMarketValue: '142500', stressedMarketValue: '113000', pnlImpact: '-29500', percentageOfTotal: '38.0' },
  ],
  limitBreaches: [],
  calculatedAt: '2025-01-15T12:00:00Z',
}

const STRESS_RESULT_RATES_SHOCK = {
  scenarioName: 'RATES_SHOCK',
  baseVar: '125000',
  stressedVar: '200000',
  pnlImpact: '-75000',
  assetClassImpacts: [
    { assetClass: 'FIXED_INCOME', baseExposure: '300000', stressedExposure: '250000', pnlImpact: '-50000' },
  ],
  positionImpacts: [
    { instrumentId: 'US10Y', assetClass: 'FIXED_INCOME', baseMarketValue: '300000', stressedMarketValue: '250000', pnlImpact: '-50000', percentageOfTotal: '100.0' },
  ],
  limitBreaches: [],
  calculatedAt: '2025-01-15T12:01:00Z',
}

/**
 * Override the batch stress endpoint so "Run All Scenarios" yields the two
 * canned results above. Mirrors the helper in scenarios-tab.spec.ts. Call
 * AFTER mockAllApiRoutes.
 */
async function mockScenariosRoutes(page: Page): Promise<void> {
  await page.unroute('**/api/v1/risk/stress/scenarios')
  await page.unroute('**/api/v1/risk/**')

  await page.route('**/api/v1/risk/**', (route: Route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })

  await page.route('**/api/v1/risk/stress/*/batch', (route: Route) => {
    if (route.request().method() === 'POST') {
      const results = [STRESS_RESULT_EQUITY_CRASH, STRESS_RESULT_RATES_SHOCK]
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          results,
          failedScenarios: [],
          worstScenarioName: 'EQUITY_CRASH',
          worstPnlImpact: '-250000',
        }),
      })
    } else {
      route.fallback()
    }
  })

  await page.route('**/api/v1/risk/stress/scenarios', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(['EQUITY_CRASH', 'RATES_SHOCK']),
    })
  })

  await page.route('**/api/v1/stress-scenarios/approved', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })
  await page.route('**/api/v1/stress-scenarios', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
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
    tool: 'get_stress_result',
    params: { scenario: 'EQUITY_CRASH' },
    result_field: 'pnl_impact',
    result_value: -250000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-20T08:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 120,
    quality_flags: [],
  }
  const body = [
    'data: {"delta":"Equities drove ","done":false}\n\n',
    'data: {"delta":"the stress loss.","done":false}\n\n',
    'event: source\ndata: ' + JSON.stringify([citation]) + '\n\n',
    'data: ' +
      JSON.stringify({
        done: true,
        session_id: 'sess-stress',
        conversation_id: 'conv-stress',
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

/** Navigate to the Scenarios tab and run all scenarios so the rows render. */
async function gotoScenarioResults(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-scenarios').click()
  await page.waitForSelector('[data-testid="run-all-btn"]')
  await page.getByTestId('run-all-btn').click()
  await page.waitForSelector('[data-testid="scenario-comparison-table"]')
}

test.describe('Inline explainer — Stress / Scenarios panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockScenariosRoutes(page)
  })

  test('clicking a scenario-row explain button opens the streaming insight panel', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoScenarioResults(page)

    await expect(
      page.getByTestId('explain-scenario-EQUITY_CRASH'),
    ).toBeVisible()
    await page.getByTestId('explain-scenario-EQUITY_CRASH').click()

    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toBeVisible()
    await expect(page.getByTestId('ai-insight-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-streaming')).toBeVisible()

    // The streaming narrative renders the canned answer token by token.
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    // Panel title is scoped to the clicked scenario.
    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toContainText('EQUITY CRASH')
  })

  test('the scenario-row explainer does not toggle the scenario detail panel', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoScenarioResults(page)

    await page.getByTestId('explain-scenario-EQUITY_CRASH').click()

    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toBeVisible()
    // The row's detail panel must NOT have opened — the explain click is
    // stopped from bubbling to the row's selection handler.
    await expect(page.getByTestId('detail-panel')).toHaveCount(0)
  })

  test('a rapid double-click does not open a duplicate panel or fire a duplicate request', async ({
    page,
  }) => {
    const chatMock = await chatMockCounting(page, 400)
    await gotoScenarioResults(page)

    const button = page.getByTestId('explain-scenario-EQUITY_CRASH')
    // Three clicks in quick succession while the first stream is still in
    // flight (the mock holds the response for 400ms).
    await button.click()
    await button.click()
    await button.click()

    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toBeVisible()

    // Exactly one panel and exactly one /chat request despite three clicks.
    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toHaveCount(1)
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Equities drove the stress loss.',
    )
    expect(chatMock.count()).toBe(1)
  })

  test('opening a second scenario explainer closes the first (only one panel open)', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoScenarioResults(page)

    await page.getByTestId('explain-scenario-EQUITY_CRASH').click()
    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toBeVisible()

    // Open a different scenario's explainer — the first panel is replaced.
    await page.getByTestId('explain-scenario-RATES_SHOCK').click()

    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toHaveCount(0)
    await expect(
      page.getByTestId('scenario-explain-panel-RATES_SHOCK'),
    ).toHaveCount(1)
    await expect(
      page.getByTestId('scenario-explain-panel-RATES_SHOCK'),
    ).toContainText('RATES SHOCK')
  })

  test('the panel close button dismisses the explainer', async ({ page }) => {
    await chatMockCanned(page)
    await gotoScenarioResults(page)

    await page.getByTestId('explain-scenario-EQUITY_CRASH').click()
    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toBeVisible()

    await page.getByTestId('ai-insight-close').click()
    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toHaveCount(0)
  })

  test('the explain request carries the scenario name, stressed P&L and top stressed positions', async ({
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

    await gotoScenarioResults(page)
    await page.getByTestId('explain-scenario-EQUITY_CRASH').click()
    await expect(
      page.getByTestId('scenario-explain-panel-EQUITY_CRASH'),
    ).toBeVisible()

    expect(captured).not.toBeNull()
    const ctx = (captured ?? {}) as Record<string, unknown>
    expect(ctx.page).toBe('scenarios')
    expect(ctx.scenario_name).toBe('EQUITY_CRASH')
    expect(ctx.stressed_pnl).toBe('-250000')

    const positions = ctx.top_stressed_positions as {
      instrumentId: string
      pnlImpact: string
    }[]
    // Ranked by absolute P&L impact: AAPL -46500 > GOOGL -29500.
    expect(positions.map((p) => p.instrumentId)).toEqual(['AAPL', 'GOOGL'])
  })
})
