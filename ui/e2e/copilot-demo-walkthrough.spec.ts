/* eslint-disable @typescript-eslint/no-unused-vars */
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { briefMockCanned, chatMockCanned, mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Copilot v2 demo walkthrough — the 90-second demo script (plans/ai-v2.md §11.1)
// ---------------------------------------------------------------------------
//
// This spec walks the full v2 Copilot demo end-to-end against the same
// deterministic, canned routes the `/demo` orchestrator pre-stages — so the
// 90-second demo script runs without a single live SDK call. It is the
// browser-level proof of checkbox 11.1: with `DEMO_MODE=true` the morning
// brief, the queued intraday push, the ⌘K free-form ask, and a saved-query
// chip all render deterministically.
//
// The four demo beats, in order:
//   1. Morning brief lands — <MorningBriefCard> in the notification inbox.
//   2. An intraday push fires — an <IntradayPushItem> pushed over /ws/copilot.
//   3. ⌘K free-form question streams a copilot answer with citations.
//   4. A saved-query chip runs and streams the same canned answer.
//
// It reuses the existing canned mock helpers (`briefMockCanned`,
// `chatMockCanned`) and the WebSocket-injection pattern from
// `intraday-push.spec.ts`. No live backend, no live SDK — fully
// deterministic.

const BRIEF_SEEN_KEY = 'kinetix:morning-brief:last-seen-date'
const SAVED_QUERIES_KEY = 'kinetix:copilot:saved-queries'

interface PushEvent {
  alert_type: string
  severity: string
  book_id: string
  headline: string
  context_bullets: string[]
  sources: Array<{
    tool: string
    params: Record<string, unknown>
    result_field: string
    result_value: number | string
    result_currency: string | null
    as_of_timestamp: string
    data_source: string
    freshness_seconds: number
    quality_flags: string[]
  }>
  session_id: string
  generated_at: string
}

/**
 * Install a mock `WebSocket` that opens immediately for any `/ws/copilot`
 * connection and then emits each supplied push wrapped in the
 * `{type: "intraday_push", push}` envelope `useCopilotWebSocket()` decodes.
 * Non-copilot sockets fall through to the real constructor. Mirrors the
 * established pattern in `intraday-push.spec.ts`.
 */
async function injectCopilotPushSocket(
  page: Page,
  pushes: PushEvent[],
): Promise<void> {
  await page.addInitScript((scriptedPushes: PushEvent[]) => {
    const OriginalWebSocket = window.WebSocket

    class MockCopilotWebSocket extends EventTarget {
      static CONNECTING = 0
      static OPEN = 1
      static CLOSING = 2
      static CLOSED = 3
      CONNECTING = 0
      OPEN = 1
      CLOSING = 2
      CLOSED = 3

      readyState = 0
      url: string
      protocol = ''
      extensions = ''
      bufferedAmount = 0
      binaryType: BinaryType = 'blob'
      onopen: ((this: WebSocket, ev: Event) => void) | null = null
      onclose: ((this: WebSocket, ev: CloseEvent) => void) | null = null
      onmessage: ((this: WebSocket, ev: MessageEvent) => void) | null = null
      onerror: ((this: WebSocket, ev: Event) => void) | null = null

      constructor(url: string | URL, _protocols?: string | string[]) {
        super()
        this.url = typeof url === 'string' ? url : url.toString()

        setTimeout(() => {
          // A socket closed before it opened (React StrictMode mounts the
          // hook twice) must stay silent — otherwise pushes deliver twice.
          if (this.readyState === 3) return
          this.readyState = 1
          const openEvent = new Event('open')
          if (this.onopen) {
            this.onopen.call(this as unknown as WebSocket, openEvent)
          }
          this.dispatchEvent(openEvent)

          setTimeout(() => {
            if (this.readyState === 3) return
            for (const push of scriptedPushes) {
              const payload = JSON.stringify({ type: 'intraday_push', push })
              const msgEvent = new MessageEvent('message', { data: payload })
              if (this.onmessage) {
                this.onmessage.call(this as unknown as WebSocket, msgEvent)
              }
              this.dispatchEvent(msgEvent)
            }
          }, 50)
        }, 30)
      }

      send(_data: string | ArrayBuffer | Blob | ArrayBufferView): void {}

      close(_code?: number, _reason?: string): void {
        this.readyState = 3
      }
    }

    ;(window as unknown as Record<string, unknown>).WebSocket = function (
      url: string | URL,
      protocols?: string | string[],
    ) {
      const urlStr = typeof url === 'string' ? url : url.toString()
      if (urlStr.includes('/ws/copilot')) {
        return new MockCopilotWebSocket(url, protocols)
      }
      return new OriginalWebSocket(url, protocols)
    } as unknown as typeof WebSocket
    for (const [name, value] of [
      ['CONNECTING', 0],
      ['OPEN', 1],
      ['CLOSING', 2],
      ['CLOSED', 3],
    ] as const) {
      Object.defineProperty(
        (window as unknown as Record<string, unknown>).WebSocket,
        name,
        { value },
      )
    }
  }, pushes)
}

/** Minutes-ago ISO timestamp, so `formatRelativeTime` renders `Nm ago`. */
function minutesAgo(n: number): string {
  return new Date(Date.now() - n * 60_000).toISOString()
}

/**
 * The single deterministic intraday push the `/demo` orchestrator queues —
 * mirrors `ai-insights-service/src/kinetix_insights/fixtures/demo_intraday_push.json`.
 */
function demoIntradayPush(): PushEvent {
  return {
    alert_type: 'var_limit',
    severity: 'critical',
    book_id: 'fx-main',
    headline: 'Critical VaR breach on fx-main',
    context_bullets: ['Current VaR 7.5M USD — 50% over the 5M limit'],
    sources: [
      {
        tool: 'get_book_var',
        params: { book_id: 'fx-main' },
        result_field: 'total_var',
        result_value: 7_500_000,
        result_currency: 'USD',
        as_of_timestamp: minutesAgo(2),
        data_source: 'risk-orchestrator',
        freshness_seconds: 30,
        quality_flags: [],
      },
    ],
    session_id: 'sess-demo-push',
    generated_at: minutesAgo(3),
  }
}

test.describe('Copilot v2 demo walkthrough', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    // Canned chat SSE — drives both the ⌘K ask and the saved-query chip.
    await chatMockCanned(page)
    // Canned morning brief — the first demo beat.
    await briefMockCanned(page)
    // One queued intraday push — the second demo beat.
    await injectCopilotPushSocket(page, [demoIntradayPush()])
    // Stamp the brief as seen today so the strip does not auto-expand out
    // from under the explicit toggle clicks, and start with no user-saved
    // queries so the built-in chips are the ones exercised.
    await page.addInitScript(
      ([briefKey, savedKey]) => {
        window.localStorage.setItem(
          briefKey,
          new Date().toISOString().slice(0, 10),
        )
        window.localStorage.removeItem(savedKey)
      },
      [BRIEF_SEEN_KEY, SAVED_QUERIES_KEY],
    )
  })

  test('demo beat 1 — the morning brief lands in the inbox', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.getByTestId('notification-strip-toggle').click()

    const card = page.getByTestId('morning-brief-card')
    await expect(card).toBeVisible()
    await expect(card).toContainText('Morning Brief')
    await expect(card).toContainText('fx-main')
    await expect(page.getByTestId('brief-section-0')).toContainText(
      'Overnight VaR move',
    )
    // Canned — the "Demo mode" badge proves no live SDK was reached.
    await expect(page.getByTestId('morning-brief-demo-badge')).toHaveText(
      'Demo mode',
    )
  })

  test('demo beat 2 — an intraday push fires into the strip', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.getByTestId('notification-strip-toggle').click()

    const row = page.getByTestId('intraday-push-item-sess-demo-push')
    await expect(row).toBeVisible()
    await expect(row).toContainText('Critical VaR breach on fx-main')
    await expect(row).toContainText('Current VaR 7.5M USD')
    await expect(row.getByTestId('intraday-push-zap-icon')).toBeVisible()
    await expect(row.getByTestId('citation-list')).toContainText(
      'get_book_var',
    )
  })

  test('demo beat 3 — a ⌘K free-form question streams a copilot answer', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    await input.fill('zzznomatch why did my VaR move overnight')
    await page.keyboard.press('Enter')

    await expect(
      page.getByTestId('command-palette-copilot-response'),
    ).toBeVisible()
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    // Citations render — the trust anchor — and the demo-mode badge proves
    // the answer was canned, not a live SDK call.
    await expect(page.getByTestId('citation-list')).toBeVisible()
    await expect(
      page.getByTestId('command-palette-copilot-demo-badge'),
    ).toHaveText('Demo mode')
  })

  test('demo beat 4 — a saved-query chip runs the canned query', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.getByTestId('notification-strip-toggle').click()

    await page
      .getByTestId('saved-query-chips')
      .getByTestId('saved-query-chip-run-limit-breaches')
      .click()

    await expect(page.getByTestId('command-palette')).toBeVisible()
    await expect(
      page.getByTestId('command-palette-copilot-response'),
    ).toBeVisible()
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
  })

  test('the full 90-second demo runs end-to-end with zero live SDK calls', async ({
    page,
  }) => {
    // Fail the test if any request escapes to a live ai-insights SDK path
    // — the demo must be fully canned. The mocked routes above intercept
    // every /api/v1/insights/* call; this guard catches a regression
    // where a route falls through to the network.
    const liveRequests: string[] = []
    page.on('request', (req) => {
      const url = req.url()
      if (url.includes('/api/v1/insights/') && !url.includes('localhost')) {
        liveRequests.push(url)
      }
    })

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Beat 1 — morning brief.
    await page.getByTestId('notification-strip-toggle').click()
    await expect(page.getByTestId('morning-brief-card')).toBeVisible()

    // Beat 2 — intraday push.
    await expect(
      page.getByTestId('intraday-push-item-sess-demo-push'),
    ).toBeVisible()

    // Beat 3 — ⌘K free-form ask.
    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()
    const input = page.getByTestId('command-palette-input')
    await input.fill('zzznomatch what changed overnight')
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('command-palette')).not.toBeVisible()

    // Beat 4 — saved-query chip.
    await page
      .getByTestId('saved-query-chips')
      .getByTestId('saved-query-chip-run-var-week-drivers')
      .click()
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )

    // Every insights call was served from a canned mock — no live SDK.
    expect(liveRequests).toEqual([])
  })
})
