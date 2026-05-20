import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Copilot latency budget — first useful content within 3 s (plans/ai-v2.md §10.6)
// ---------------------------------------------------------------------------
//
// The §10.6 hardening requirement ("Latency budget — 3 s to first useful
// content"): when a copilot question is submitted, the streaming-content
// element must render its first non-empty token well inside the 3-second
// budget.
//
// This spec drives the running app against a deterministic mocked SSE
// stream whose FIRST chunk is delayed by ~50 ms after the request is
// received — a small, fixed delay that mimics a realistic time-to-first-byte
// while leaving a wide margin below the 3 s ceiling. The test captures a
// timestamp at submit, then `waitForFunction`s that the streaming-content
// element (`<StreamingNarrative>` → `streaming-narrative-text`) holds
// non-empty text, and asserts the elapsed time stayed under 3000 ms.
//
// The streaming-content element's stable test id is `streaming-narrative-text`
// (rendered by `<StreamingNarrative>` once the first delta arrives). The
// §10.6 checkbox names it `chat-stream-content`; we use the actual existing
// id so no component change is needed.

const FIRST_CHUNK_DELAY_MS = 50
const LATENCY_BUDGET_MS = 3000

/**
 * Mocks POST /api/v1/insights/chat with a deferred SSE body: the route
 * holds the response for `FIRST_CHUNK_DELAY_MS` after the request is
 * received, then fulfils with a complete multi-chunk Server-Sent Events
 * stream. Frame shapes mirror the wire contract parsed by `chat()` in
 * `ui/src/api/copilot.ts` (see `chatMockCanned` in `fixtures.ts`).
 *
 * Because Playwright fulfils the whole body at once, the delay before
 * `route.fulfill` is the deterministic time-to-first-content: the UI
 * cannot render any token before the body lands.
 */
async function chatMockDelayed(page: Page, delayMs: number): Promise<void> {
  const citation = {
    tool: 'get_book_var',
    params: { book_id: 'port-1' },
    result_field: 'total_var',
    result_value: 5200000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-19T08:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 120,
    quality_flags: [],
  }
  const body = [
    'data: {"delta":"Your VaR rose ","done":false}\n\n',
    'data: {"delta":"on tech beta.","done":false}\n\n',
    'event: source\ndata: ' + JSON.stringify([citation]) + '\n\n',
    'data: ' +
      JSON.stringify({
        done: true,
        session_id: 'sess-latency',
        conversation_id: 'conv-latency',
        model: 'canned-chat',
        mode: 'canned',
        citations: [citation],
      }) +
      '\n\n',
  ].join('')

  await page.unroute('**/api/v1/insights/chat')
  await page.route('**/api/v1/insights/chat', async (route: Route) => {
    await new Promise((resolve) => setTimeout(resolve, delayMs))
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body,
    })
  })
}

test.describe('Copilot latency budget', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await chatMockDelayed(page, FIRST_CHUNK_DELAY_MS)
  })

  test('streams the first non-empty token within the 3 s budget', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    // A free-form question that matches no command in the palette, so it
    // is routed to the v2 chat endpoint.
    await input.fill('zzznomatch why did my VaR move')

    // Capture the submit timestamp, then submit. `waitForFunction` polls
    // until the streaming-content element holds non-empty text.
    const submittedAt = Date.now()
    await page.keyboard.press('Enter')

    await page.waitForFunction(
      () => {
        const el = document.querySelector(
          '[data-testid="streaming-narrative-text"]',
        )
        return el != null && (el.textContent ?? '').trim().length > 0
      },
      undefined,
      { timeout: LATENCY_BUDGET_MS },
    )

    const elapsedMs = Date.now() - submittedAt
    // The mock emits the first chunk after ~50 ms; the UI render path must
    // surface it inside the 3 s budget with a wide margin.
    expect(elapsedMs).toBeLessThan(LATENCY_BUDGET_MS)
  })
})
