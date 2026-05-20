/* eslint-disable @typescript-eslint/no-unused-vars */
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Intraday Copilot push — notification-strip push items (plans/ai-v2.md §7.10)
// ---------------------------------------------------------------------------
//
// PR 7 streams intraday Copilot pushes over the `/ws/copilot` WebSocket
// route. `useCopilotWebSocket()` connects, decodes the
// `{type: "intraday_push", push: {...}}` envelope, and surfaces the
// received pushes (newest first); App.tsx feeds them to
// <NotificationStrip intradayPushes>, which renders each as an
// <IntradayPushItem> inbox row.
//
// Playwright's `page.route()` does NOT intercept WebSocket connections, so
// these specs replace the browser `WebSocket` constructor via
// `page.addInitScript()` (the established pattern in intraday-pnl.spec.ts /
// system-status-banner.spec.ts). The mocked socket emits a pre-scripted
// list of push envelopes shortly after the page wires up its `onmessage`
// handler.

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
 * Install a mock `WebSocket` that, for any `/ws/copilot` connection,
 * opens immediately and then emits each supplied push wrapped in the
 * `{type: "intraday_push", push}` envelope `useCopilotWebSocket()`
 * decodes. Non-copilot sockets fall through to the real constructor.
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
          // hook twice, tearing the first connection down) must stay
          // silent — otherwise the scripted pushes are delivered twice.
          if (this.readyState === 3) return
          this.readyState = 1
          const openEvent = new Event('open')
          if (this.onopen) this.onopen.call(this as unknown as WebSocket, openEvent)
          this.dispatchEvent(openEvent)

          // Emit each scripted push as a separate intraday_push frame.
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

      addEventListener(
        type: string,
        listener: EventListenerOrEventListenerObject,
        options?: boolean | AddEventListenerOptions,
      ): void {
        super.addEventListener(type, listener, options)
      }

      removeEventListener(
        type: string,
        listener: EventListenerOrEventListenerObject,
        options?: boolean | EventListenerOptions,
      ): void {
        super.removeEventListener(type, listener, options)
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
    Object.defineProperty(
      (window as unknown as Record<string, unknown>).WebSocket,
      'CONNECTING',
      { value: 0 },
    )
    Object.defineProperty(
      (window as unknown as Record<string, unknown>).WebSocket,
      'OPEN',
      { value: 1 },
    )
    Object.defineProperty(
      (window as unknown as Record<string, unknown>).WebSocket,
      'CLOSING',
      { value: 2 },
    )
    Object.defineProperty(
      (window as unknown as Record<string, unknown>).WebSocket,
      'CLOSED',
      { value: 3 },
    )
  }, pushes)
}

/** Minutes-ago ISO timestamp, so `formatRelativeTime` renders `Nm ago`. */
function minutesAgo(n: number): string {
  return new Date(Date.now() - n * 60_000).toISOString()
}

/** Build a single intraday-push payload with sensible defaults. */
function makePush(overrides: Partial<PushEvent> = {}): PushEvent {
  return {
    alert_type: 'var_limit',
    severity: 'warning',
    book_id: 'port-1',
    headline: 'VaR approaching limit on PORT-1',
    context_bullets: ['95% VaR at 92% of the desk limit'],
    sources: [
      {
        tool: 'get_var',
        params: { book_id: 'port-1' },
        result_field: 'var_95',
        result_value: 920000,
        result_currency: 'USD',
        as_of_timestamp: minutesAgo(2),
        data_source: 'risk-orchestrator',
        freshness_seconds: 30,
        quality_flags: [],
      },
    ],
    session_id: 'sess-push-1',
    generated_at: minutesAgo(5),
    ...overrides,
  }
}

/** Build `n` distinct pushes (unique session_id + headline). */
function makePushes(n: number): PushEvent[] {
  return Array.from({ length: n }, (_, i) =>
    makePush({
      session_id: `sess-push-${i}`,
      headline: `Intraday alert ${i}`,
      generated_at: minutesAgo(i + 1),
    }),
  )
}

test.describe('Intraday Copilot push', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders a pushed intraday alert with its narrative and sources', async ({
    page,
  }) => {
    const push = makePush({
      session_id: 'sess-narrative',
      headline: 'Concentration breach on AAPL',
      context_bullets: ['AAPL is 38% of book NAV'],
    })
    await injectCopilotPushSocket(page, [push])

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // The strip is non-empty because a push landed — expand the inbox.
    await page.getByTestId('notification-strip-toggle').click()

    const row = page.getByTestId('intraday-push-item-sess-narrative')
    await expect(row).toBeVisible()

    // Narrative = headline + first context bullet.
    await expect(row).toContainText('Concentration breach on AAPL')
    await expect(row).toContainText('AAPL is 38% of book NAV')
    // The Zap marker distinguishes a live push from a standing notification.
    await expect(row.getByTestId('intraday-push-zap-icon')).toBeVisible()

    // Sources = the provenance citation list.
    await expect(row.getByTestId('intraday-push-sources')).toBeVisible()
    await expect(row.getByTestId('citation-list')).toBeVisible()
    await expect(row.getByTestId('citation-list')).toContainText('get_var')
  })

  test('dismissing a pushed alert removes it from the inbox', async ({
    page,
  }) => {
    await injectCopilotPushSocket(page, [
      makePush({ session_id: 'sess-keep', headline: 'Keep this alert' }),
      makePush({ session_id: 'sess-dismiss', headline: 'Dismiss this alert' }),
    ])

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.getByTestId('notification-strip-toggle').click()

    const target = page.getByTestId('intraday-push-item-sess-dismiss')
    await expect(target).toBeVisible()

    await page.getByTestId('intraday-push-dismiss-sess-dismiss').click()

    // The dismissed row is gone; the other push remains.
    await expect(target).toHaveCount(0)
    await expect(
      page.getByTestId('intraday-push-item-sess-keep'),
    ).toBeVisible()
  })

  test('50 pushes collapse the overflow into a "45 more" badge', async ({
    page,
  }) => {
    await injectCopilotPushSocket(page, makePushes(50))

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.getByTestId('notification-strip-toggle').click()

    // Only the first five pushes render as rows.
    await expect(page.getByTestId(/^intraday-push-item-/)).toHaveCount(5)

    // The remaining 45 collapse into a single overflow badge.
    const overflow = page.getByTestId('intraday-push-overflow')
    await expect(overflow).toBeVisible()
    await expect(overflow).toHaveText('45 more')
  })
})
