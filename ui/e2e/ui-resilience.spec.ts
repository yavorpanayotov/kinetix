/* eslint-disable @typescript-eslint/no-unused-vars */
import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// Helper: injects a mock WebSocket that connects successfully, then drops after
// a short delay. This ensures lastConnectedAt is set before the disconnect,
// which is necessary for tests that assert on the stale-timestamp element.
//
// IMPORTANT: only the FIRST instance opens-then-drops. Subsequent constructions
// (which happen automatically as `usePriceStream` retries with exponential
// backoff) fail immediately without opening, so the app settles into a stable
// disconnected state and `disconnectedSince` stops flickering back to null.
// Without this stability, the elapsed-time counter resets on every reconnect
// cycle and never observably ticks past 0.
async function injectConnectThenDropWebSocket(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    const OriginalWebSocket = window.WebSocket
    let instanceCount = 0

    class ConnectThenDropWebSocket extends EventTarget {
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
        const isFirstInstance = instanceCount === 0
        instanceCount += 1

        if (isFirstInstance) {
          // First socket: open then drop so lastConnectedAt + disconnectedSince
          // both get recorded.
          setTimeout(() => {
            this.readyState = 1
            const openEvent = new Event('open')
            if (this.onopen) this.onopen.call(this as unknown as WebSocket, openEvent)
            this.dispatchEvent(openEvent)
          }, 50)
          setTimeout(() => {
            this.readyState = 3
            const closeEvent = new CloseEvent('close', { code: 1006, reason: 'Simulated drop' })
            if (this.onclose) this.onclose.call(this as unknown as WebSocket, closeEvent)
            this.dispatchEvent(closeEvent)
          }, 1500)
        } else {
          // Subsequent reconnect attempts fail immediately so the page stays
          // disconnected and the elapsed-time counter has a stable origin to
          // tick against.
          setTimeout(() => {
            this.readyState = 3
            const errorEvent = new Event('error')
            if (this.onerror) this.onerror.call(this as unknown as WebSocket, errorEvent)
            this.dispatchEvent(errorEvent)
            const closeEvent = new CloseEvent('close', { code: 1006, reason: 'Reconnect refused' })
            if (this.onclose) this.onclose.call(this as unknown as WebSocket, closeEvent)
            this.dispatchEvent(closeEvent)
          }, 20)
        }
      }

      send(_data: string | ArrayBuffer | Blob | ArrayBufferView): void {}

      close(_code?: number, _reason?: string): void {
        this.readyState = 3
      }

      addEventListener(type: string, listener: EventListenerOrEventListenerObject, options?: boolean | AddEventListenerOptions): void {
        super.addEventListener(type, listener, options)
      }

      removeEventListener(type: string, listener: EventListenerOrEventListenerObject, options?: boolean | EventListenerOptions): void {
        super.removeEventListener(type, listener, options)
      }
    }

    ;(window as unknown as Record<string, unknown>).WebSocket = function (url: string | URL, protocols?: string | string[]) {
      const urlStr = typeof url === 'string' ? url : url.toString()
      if (urlStr.includes('/ws/prices')) {
        return new ConnectThenDropWebSocket(url, protocols)
      }
      return new OriginalWebSocket(url, protocols)
    } as unknown as typeof WebSocket
    Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CONNECTING', { value: 0 })
    Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'OPEN', { value: 1 })
    Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSING', { value: 2 })
    Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSED', { value: 3 })
  })
}

// Helper: injects a mock WebSocket that fails immediately without ever opening.
// This triggers the reconnecting state directly, skipping the initial Live phase.
async function injectFailingWebSocket(page: import('@playwright/test').Page) {
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

      addEventListener(type: string, listener: EventListenerOrEventListenerObject, options?: boolean | AddEventListenerOptions): void {
        super.addEventListener(type, listener, options)
      }

      removeEventListener(type: string, listener: EventListenerOrEventListenerObject, options?: boolean | EventListenerOptions): void {
        super.removeEventListener(type, listener, options)
      }
    }

    ;(window as unknown as Record<string, unknown>).WebSocket = function (url: string | URL, protocols?: string | string[]) {
      const urlStr = typeof url === 'string' ? url : url.toString()
      if (urlStr.includes('/ws/prices')) {
        return new FailingWebSocket(url, protocols)
      }
      return new OriginalWebSocket(url, protocols)
    } as unknown as typeof WebSocket
    Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CONNECTING', { value: 0 })
    Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'OPEN', { value: 1 })
    Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSING', { value: 2 })
    Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSED', { value: 3 })
  })
}

test.describe('UI Resilience', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  // ---------------------------------------------------------------------------
  // Error classification
  // ---------------------------------------------------------------------------

  test('503 response on what-if API renders amber transient error message', async ({ page }) => {
    // Register the 503 override before navigating.  Playwright uses the first
    // matching handler, so this must be registered after mockAllApiRoutes.
    await page.route('**/api/v1/risk/what-if/**', (route) => {
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Service Unavailable' }),
        headers: { 'Status-Text': 'Service Unavailable' },
      })
    })

    await page.goto('/')
    await page.waitForSelector('[data-testid="whatif-open-button"]')

    await page.getByTestId('whatif-open-button').click()
    await page.waitForSelector('[data-testid="whatif-instrument-0"]')

    await page.getByTestId('whatif-instrument-0').fill('SPY')
    await page.getByTestId('whatif-quantity-0').fill('100')
    await page.getByTestId('whatif-price-0').fill('450')

    await page.getByTestId('whatif-run').click()

    // The error element must be visible and carry the transient amber styling
    const errorEl = page.getByTestId('whatif-error')
    await expect(errorEl).toBeVisible({ timeout: 5000 })

    // The amber background class distinguishes a transient error from a hard failure
    await expect(errorEl).toHaveClass(/bg-amber-50/, { timeout: 5000 })

    // The message tells the user the service is temporarily unavailable
    await expect(errorEl).toContainText('service temporarily unavailable')

    // A retry control should also appear alongside the amber message
    await expect(page.getByTestId('whatif-retry')).toBeVisible()
  })

  // ---------------------------------------------------------------------------
  // Stale data indicator
  // ---------------------------------------------------------------------------

  test('stale-timestamp appears in connection status after WebSocket disconnects', async ({ page }) => {
    // Use connect-then-drop: the socket opens (recording lastConnectedAt), then
    // drops so the app transitions to the disconnected state and shows the timestamp.
    await injectConnectThenDropWebSocket(page)

    await page.goto('/')
    await page.waitForSelector('[data-testid="connection-status"]')

    // Wait for the initial Live phase to confirm the connection was established
    await expect(page.getByTestId('connection-status')).toContainText('Live', {
      timeout: 3000,
    })

    // After the simulated drop the status transitions to Disconnected and the
    // stale timestamp becomes visible
    const staleTimestamp = page.getByTestId('stale-timestamp')
    await expect(staleTimestamp).toBeVisible({ timeout: 5000 })

    // The element must say "as of" followed by a time so users know when data was last live
    await expect(staleTimestamp).toContainText('as of')
  })

  // ---------------------------------------------------------------------------
  // Reconnecting banner
  // ---------------------------------------------------------------------------

  test('reconnecting banner appears when WebSocket connection fails', async ({ page }) => {
    await injectFailingWebSocket(page)

    await page.goto('/')
    await page.waitForSelector('[data-testid="connection-status"]')

    const banner = page.getByTestId('reconnecting-banner')
    await expect(banner).toBeVisible({ timeout: 5000 })
    await expect(banner).toContainText('Reconnecting...')
  })

  test('reconnecting banner shows elapsed time context after disconnect', async ({ page }) => {
    // Use the connect-then-drop variant so disconnectElapsed ticks up from a
    // known disconnect point and the banner text gains the "(Xs)" suffix.
    await injectConnectThenDropWebSocket(page)

    await page.goto('/')
    await page.waitForSelector('[data-testid="connection-status"]')

    // Confirm initial live state
    await expect(page.getByTestId('connection-status')).toContainText('Live', {
      timeout: 3000,
    })

    // Wait for the banner to appear after the drop
    const banner = page.getByTestId('reconnecting-banner')
    await expect(banner).toBeVisible({ timeout: 5000 })

    // The banner must carry the "Reconnecting..." core text
    await expect(banner).toContainText('Reconnecting...')

    // The status text lives in a child span with role="alert" so screen readers
    // announce it once on appearance. The elapsed-time counter is a sibling with
    // aria-live="off" so it does not re-announce on every tick.
    await expect(banner.getByRole('alert')).toBeVisible()

    // After a further wait the elapsed-time counter appears as "(Xs)" inside its
    // own aria-live="off" sibling. The counter ticks via setInterval(1000ms);
    // under heavy parallel test load Chromium can throttle background timers in
    // backgrounded tabs. Force the page to the foreground and nudge the mouse
    // every second so the page is treated as active.
    await page.bringToFront()
    const elapsed = banner.getByTestId('reconnecting-banner-elapsed')
    await expect(elapsed).toHaveAttribute('aria-live', 'off')

    // Race the assertion against a heartbeat that keeps the page foregrounded.
    // Mouse moves count as user input and prevent setInterval throttling.
    const stopHeartbeat = { value: false }
    const heartbeat = (async () => {
      let x = 100
      while (!stopHeartbeat.value) {
        await page.mouse.move(x, 100)
        x = x === 100 ? 200 : 100
        await page.waitForTimeout(500)
      }
    })()

    try {
      await expect(elapsed).toContainText(/\(\d+s\)/, { timeout: 20000 })
    } finally {
      stopHeartbeat.value = true
      await heartbeat
    }
  })

  // ---------------------------------------------------------------------------
  // Price cell opacity on disconnect
  // ---------------------------------------------------------------------------

  test('price and P&L cells get reduced opacity when WebSocket is reconnecting', async ({ page }) => {
    await injectFailingWebSocket(page)

    await page.goto('/')

    // Wait for positions to render before asserting on cell styles
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    // Wait for the reconnecting state to kick in
    const banner = page.getByTestId('reconnecting-banner')
    await expect(banner).toBeVisible({ timeout: 5000 })

    // The P&L cell for AAPL carries data-testid="pnl-AAPL" and picks up
    // the opacity-60 Tailwind class when reconnecting is true
    const pnlCell = page.getByTestId('pnl-AAPL')
    await expect(pnlCell).toBeVisible()
    await expect(pnlCell).toHaveClass(/opacity-60/)
  })
})
