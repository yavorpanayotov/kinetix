/* eslint-disable @typescript-eslint/no-unused-vars */
import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * §4.1 "Consolidate banners into a single status bar".
 *
 * The previous implementation stacked up to four banners (DemoWelcomeStrip +
 * exhausted + reconnecting + maintenance) above the tab content. The new
 * SystemStatusBanner collapses exhausted/reconnecting/maintenance into a
 * single severity-prioritised bar, leaving the demo strip as its own
 * dismissible row.
 *
 * These tests assert the banner consolidation behaviour end-to-end so the
 * regression surface is visible to a real browser, not just unit mocks.
 */
test.describe('System status banner consolidation (§4.1)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('maintenance banner is shown and no other system banner is stacked under DEGRADED health', async ({
    page,
  }) => {
    await page.route('**/api/v1/system/health', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'DEGRADED',
          services: {
            gateway: { status: 'READY' },
            'position-service': { status: 'DOWN' },
            'price-service': { status: 'READY' },
            'risk-orchestrator': { status: 'READY' },
            'notification-service': { status: 'READY' },
          },
        }),
      }),
    )

    await page.goto('/')

    const maintenance = page.getByTestId('maintenance-banner')
    await expect(maintenance).toBeVisible({ timeout: 5000 })

    // The consolidated bar must NOT stack other system banners alongside it.
    await expect(page.getByTestId('connection-lost-banner')).not.toBeVisible()
    await expect(page.getByTestId('reconnecting-banner')).not.toBeVisible()
  })

  test('reconnecting banner replaces (not stacks) the maintenance banner when WebSocket drops under DEGRADED health', async ({
    page,
  }) => {
    await page.route('**/api/v1/system/health', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'DEGRADED',
          services: {
            gateway: { status: 'READY' },
            'position-service': { status: 'DOWN' },
            'price-service': { status: 'READY' },
            'risk-orchestrator': { status: 'READY' },
            'notification-service': { status: 'READY' },
          },
        }),
      }),
    )

    // Force the price-stream WebSocket to fail so the reconnecting banner
    // surfaces. Same pattern used in websocket-reconnect.spec.ts.
    await page.addInitScript(() => {
      const OriginalWebSocket = window.WebSocket

      class FailingWebSocket extends EventTarget {
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
            this.readyState = 3
            const errorEvent = new Event('error')
            if (this.onerror) this.onerror.call(this as unknown as WebSocket, errorEvent)
            this.dispatchEvent(errorEvent)
            const closeEvent = new CloseEvent('close', { code: 1006, reason: 'Connection refused' })
            if (this.onclose) this.onclose.call(this as unknown as WebSocket, closeEvent)
            this.dispatchEvent(closeEvent)
          }, 50)
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
        if (urlStr.includes('/ws/prices')) {
          return new FailingWebSocket(url, protocols)
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
    })

    await page.goto('/')

    const reconnecting = page.getByTestId('reconnecting-banner')
    await expect(reconnecting).toBeVisible({ timeout: 5000 })

    // The maintenance banner must NOT also be on screen — they're consolidated
    // into a single severity-prioritised bar.
    await expect(page.getByTestId('maintenance-banner')).not.toBeVisible()
    await expect(page.getByTestId('connection-lost-banner')).not.toBeVisible()
  })

  test('only one role=alert | role=status live region from the system bar is in the DOM at a time', async ({
    page,
  }) => {
    await page.route('**/api/v1/system/health', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'DEGRADED',
          services: {
            gateway: { status: 'READY' },
            'position-service': { status: 'DOWN' },
            'price-service': { status: 'READY' },
            'risk-orchestrator': { status: 'READY' },
            'notification-service': { status: 'READY' },
          },
        }),
      }),
    )

    await page.goto('/')

    await expect(page.getByTestId('maintenance-banner')).toBeVisible({
      timeout: 5000,
    })

    // The consolidated bar publishes at most one live region of its own —
    // the other system banner test-ids must be absent. Other live regions on
    // the page (RiskTickerStrip cells, etc.) are unrelated and out of scope.
    const stackedCount = await page.evaluate(() => {
      const ids = [
        'connection-lost-banner',
        'reconnecting-banner',
        'maintenance-banner',
      ]
      return ids.reduce(
        (n, id) => n + (document.querySelector(`[data-testid="${id}"]`) ? 1 : 0),
        0,
      )
    })
    expect(stackedCount).toBe(1)
  })
})
