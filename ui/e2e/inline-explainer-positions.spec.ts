import { test, expect, type Page, type Route } from '@playwright/test'
import {
  chatMockCanned,
  mockAllApiRoutes,
  mockRiskTabRoutes,
  TEST_JOB_HISTORY,
  TEST_POSITION_RISK_FULL,
  TEST_VAR_RESULT,
} from './fixtures'

// ---------------------------------------------------------------------------
// Inline explainer — Positions table (plans/ai-v2.md §9.1)
// ---------------------------------------------------------------------------
//
// The Position Risk table exposes an <ExplainButton> on every row (rightmost
// 32px action column) plus a portfolio-level explain button in the header.
// Each click opens an <AIInsightPanel> wired to a <StreamingNarrative>
// consuming the v2 chat endpoint (POST /api/v1/insights/chat) with the
// clicked row's position payload as `page_context`.
//
// Coverage per the checkbox: per-row open, double-click protection, the
// "only one panel open" behaviour, and the header (portfolio) explainer.

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
    tool: 'get_positions',
    params: { book_id: 'port-1' },
    result_field: 'var_contribution',
    result_value: 5000000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-20T08:00:00Z',
    data_source: 'position-service',
    freshness_seconds: 120,
    quality_flags: [],
  }
  const body = [
    'data: {"delta":"This position drives ","done":false}\n\n',
    'data: {"delta":"35% of book VaR.","done":false}\n\n',
    'event: source\ndata: ' + JSON.stringify([citation]) + '\n\n',
    'data: ' +
      JSON.stringify({
        done: true,
        session_id: 'sess-pos',
        conversation_id: 'conv-pos',
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

async function gotoPositionRiskTable(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
  await page.waitForSelector('[data-testid="position-risk-table"]')
}

test.describe('Inline explainer — Positions table', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })
  })

  test('clicking a row explain button opens the streaming insight panel', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoPositionRiskTable(page)

    await expect(page.getByTestId('explain-position-AAPL')).toBeVisible()
    await page.getByTestId('explain-position-AAPL').click()

    await expect(page.getByTestId('position-explain-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-panel')).toBeVisible()
    await expect(page.getByTestId('ai-insight-streaming')).toBeVisible()

    // The streaming narrative renders the canned answer token by token.
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    // Panel title is scoped to the clicked instrument.
    await expect(page.getByTestId('ai-insight-panel')).toContainText('AAPL')
  })

  test('the row explainer does not toggle the row detail drawer', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoPositionRiskTable(page)

    await page.getByTestId('explain-position-AAPL').click()

    await expect(page.getByTestId('position-explain-panel')).toBeVisible()
    // The row's expandable detail must NOT have opened — the explain click
    // is stopped from bubbling to the row's expand handler.
    await expect(page.getByTestId('position-risk-detail-AAPL')).toHaveCount(0)
  })

  test('a rapid double-click does not open a duplicate panel or fire a duplicate request', async ({
    page,
  }) => {
    const chatMock = await chatMockCounting(page, 400)
    await gotoPositionRiskTable(page)

    const button = page.getByTestId('explain-position-AAPL')
    // Two clicks in quick succession while the first stream is still in
    // flight (the mock holds the response for 400ms).
    await button.click()
    await button.click()
    await button.click()

    await expect(page.getByTestId('position-explain-panel')).toBeVisible()

    // Exactly one panel and exactly one /chat request despite three clicks.
    await expect(page.getByTestId('position-explain-panel')).toHaveCount(1)
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'This position drives 35% of book VaR.',
    )
    expect(chatMock.count()).toBe(1)
  })

  test('opening a second row explainer closes the first (only one panel open)', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoPositionRiskTable(page)

    await page.getByTestId('explain-position-AAPL').click()
    await expect(page.getByTestId('position-explain-panel')).toContainText(
      'AAPL',
    )

    // Open a different row's explainer — the AAPL panel is replaced.
    await page.getByTestId('explain-position-GOOGL').click()

    await expect(page.getByTestId('position-explain-panel')).toHaveCount(1)
    await expect(page.getByTestId('position-explain-panel')).toContainText(
      'GOOGL',
    )
    await expect(page.getByTestId('position-explain-panel')).not.toContainText(
      'Explain — AAPL',
    )
  })

  test('the panel close button dismisses the explainer', async ({ page }) => {
    await chatMockCanned(page)
    await gotoPositionRiskTable(page)

    await page.getByTestId('explain-position-AAPL').click()
    await expect(page.getByTestId('position-explain-panel')).toBeVisible()

    await page.getByTestId('ai-insight-close').click()
    await expect(page.getByTestId('position-explain-panel')).toHaveCount(0)
  })

  test('the header explain button opens a portfolio-level insight panel', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoPositionRiskTable(page)

    await expect(
      page.getByTestId('explain-positions-portfolio'),
    ).toBeVisible()
    await page.getByTestId('explain-positions-portfolio').click()

    await expect(page.getByTestId('position-explain-panel')).toBeVisible()
    await expect(page.getByTestId('position-explain-panel')).toContainText(
      'Portfolio',
    )
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
  })

  test('opening a row explainer after the header explainer replaces it', async ({
    page,
  }) => {
    await chatMockCanned(page)
    await gotoPositionRiskTable(page)

    await page.getByTestId('explain-positions-portfolio').click()
    await expect(page.getByTestId('position-explain-panel')).toContainText(
      'Portfolio',
    )

    await page.getByTestId('explain-position-EUR_USD').click()
    await expect(page.getByTestId('position-explain-panel')).toHaveCount(1)
    await expect(page.getByTestId('position-explain-panel')).toContainText(
      'EUR_USD',
    )
  })
})
