/* eslint-disable @typescript-eslint/no-unused-vars */
import { test, expect } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockIntradayPnlRoutes,
  TEST_INTRADAY_PNL_SNAPSHOTS,
} from './fixtures'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function goToPnlTab(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.getByTestId('tab-pnl').click()
  // Wait for the intraday chart container to be present
  await page.waitForSelector('[data-testid="intraday-pnl-chart"]')
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Intraday P&L tab', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders the intraday P&L chart when the P&L tab is active', async ({ page }) => {
    await goToPnlTab(page)

    await expect(page.getByTestId('intraday-pnl-chart')).toBeVisible()
  })

  test('shows empty state when no intraday snapshots are available', async ({ page }) => {
    // Default mockAllApiRoutes returns empty snapshots for intraday endpoint
    await goToPnlTab(page)

    await expect(page.getByTestId('intraday-pnl-chart-empty')).toBeVisible()
    await expect(page.getByTestId('intraday-pnl-chart-empty')).toContainText('No intraday data')
  })

  test('renders SVG chart when historical snapshots are loaded', async ({ page }) => {
    await mockIntradayPnlRoutes(page, 'port-1', TEST_INTRADAY_PNL_SNAPSHOTS)

    await goToPnlTab(page)

    // With 2 snapshots we should see an SVG
    const chart = page.getByTestId('intraday-pnl-chart')
    await expect(chart.locator('svg')).toBeVisible()
  })

  test('displays latest total P&L in chart header from historical data', async ({ page }) => {
    await mockIntradayPnlRoutes(page, 'port-1', TEST_INTRADAY_PNL_SNAPSHOTS)

    await goToPnlTab(page)

    // Latest snapshot has totalPnl = 1500.00
    await expect(page.getByTestId('intraday-chart-latest-total')).toBeVisible()
    await expect(page.getByTestId('intraday-chart-latest-total')).toContainText('1,500.00')
  })

  test('risk ticker strip is always visible (global slot under the tab bar)', async ({ page }) => {
    // The global ticker strip is rendered below the tab bar regardless of whether
    // there is any live P&L data — empty cells fall back to em-dashes.
    await goToPnlTab(page)

    await expect(page.getByTestId('risk-ticker-strip')).toBeVisible()
  })

  test('ticker strip surfaces intraday P&L when WebSocket sends a P&L update', async ({ page }) => {
    // Inject a WebSocket mock for /ws/pnl that sends a P&L message on connect
    await page.addInitScript(() => {
      const OriginalWebSocket = window.WebSocket

      class MockPnlWebSocket extends EventTarget {
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
            this.readyState = 1
            const openEvent = new Event('open')
            if (this.onopen) this.onopen.call(this as unknown as WebSocket, openEvent)
            this.dispatchEvent(openEvent)

            // Send a P&L update shortly after connect
            setTimeout(() => {
              const payload = JSON.stringify({
                type: 'pnl',
                bookId: 'port-1',
                snapshotAt: '2026-03-24T09:30:00Z',
                baseCurrency: 'USD',
                trigger: 'position_change',
                totalPnl: '2500.00',
                realisedPnl: '800.00',
                unrealisedPnl: '1700.00',
                deltaPnl: '2000.00',
                gammaPnl: '100.00',
                vegaPnl: '60.00',
                thetaPnl: '-20.00',
                rhoPnl: '10.00',
                unexplainedPnl: '350.00',
                highWaterMark: '3000.00',
              })
              const msgEvent = new MessageEvent('message', { data: payload })
              if (this.onmessage) this.onmessage.call(this as unknown as WebSocket, msgEvent)
              this.dispatchEvent(msgEvent)
            }, 100)
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
        if (urlStr.includes('/ws/pnl')) {
          return new MockPnlWebSocket(url, protocols)
        }
        return new OriginalWebSocket(url, protocols)
      } as unknown as typeof WebSocket
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CONNECTING', { value: 0 })
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'OPEN', { value: 1 })
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSING', { value: 2 })
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSED', { value: 3 })
    })

    await goToPnlTab(page)

    // The global strip is already mounted; the intraday cell updates from the
    // WebSocket-driven snapshot.
    await expect(page.getByTestId('risk-ticker-strip')).toBeVisible({ timeout: 3000 })
    await expect(page.getByTestId('ticker-intraday-pnl')).toContainText('2,500.00', { timeout: 3000 })
  })

  test('ticker strip shows connected indicator when WebSocket is open', async ({ page }) => {
    await page.addInitScript(() => {
      const OriginalWebSocket = window.WebSocket

      class MockPnlWebSocket extends EventTarget {
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
            this.readyState = 1
            const openEvent = new Event('open')
            if (this.onopen) this.onopen.call(this as unknown as WebSocket, openEvent)
            this.dispatchEvent(openEvent)

            setTimeout(() => {
              const payload = JSON.stringify({
                type: 'pnl',
                bookId: 'port-1',
                snapshotAt: '2026-03-24T09:30:00Z',
                baseCurrency: 'USD',
                trigger: 'position_change',
                totalPnl: '1000.00',
                realisedPnl: '300.00',
                unrealisedPnl: '700.00',
                deltaPnl: '800.00',
                gammaPnl: '50.00',
                vegaPnl: '30.00',
                thetaPnl: '-10.00',
                rhoPnl: '5.00',
                unexplainedPnl: '125.00',
                highWaterMark: '1200.00',
              })
              const msgEvent = new MessageEvent('message', { data: payload })
              if (this.onmessage) this.onmessage.call(this as unknown as WebSocket, msgEvent)
              this.dispatchEvent(msgEvent)
            }, 100)
          }, 50)
        }

        send(_data: string | ArrayBuffer | Blob | ArrayBufferView): void {}
        close(_code?: number, _reason?: string): void { this.readyState = 3 }
        addEventListener(type: string, listener: EventListenerOrEventListenerObject, options?: boolean | AddEventListenerOptions): void {
          super.addEventListener(type, listener, options)
        }
        removeEventListener(type: string, listener: EventListenerOrEventListenerObject, options?: boolean | EventListenerOptions): void {
          super.removeEventListener(type, listener, options)
        }
      }

      ;(window as unknown as Record<string, unknown>).WebSocket = function (url: string | URL, protocols?: string | string[]) {
        const urlStr = typeof url === 'string' ? url : url.toString()
        if (urlStr.includes('/ws/pnl')) {
          return new MockPnlWebSocket(url, protocols)
        }
        return new OriginalWebSocket(url, protocols)
      } as unknown as typeof WebSocket
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CONNECTING', { value: 0 })
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'OPEN', { value: 1 })
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSING', { value: 2 })
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSED', { value: 3 })
    })

    await goToPnlTab(page)

    await expect(page.getByTestId('risk-ticker-strip')).toBeVisible({ timeout: 3000 })
    // The connection indicator should be green
    const status = page.getByTestId('ticker-connection-status')
    await expect(status).toHaveClass(/bg-green-500/, { timeout: 3000 })
  })

  test('live chart updates with streamed snapshots', async ({ page }) => {
    // Inject mock WebSocket that sends two updates
    await page.addInitScript(() => {
      const OriginalWebSocket = window.WebSocket

      class MockPnlWebSocket extends EventTarget {
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
            this.readyState = 1
            const openEvent = new Event('open')
            if (this.onopen) this.onopen.call(this as unknown as WebSocket, openEvent)
            this.dispatchEvent(openEvent)

            const send = (pnl: string, time: string) => {
              const payload = JSON.stringify({
                type: 'pnl', bookId: 'port-1', snapshotAt: time,
                baseCurrency: 'USD', trigger: 'price_update',
                totalPnl: pnl, realisedPnl: '100.00', unrealisedPnl: '400.00',
                deltaPnl: '400.00', gammaPnl: '20.00', vegaPnl: '15.00',
                thetaPnl: '-5.00', rhoPnl: '2.00', unexplainedPnl: '68.00',
                highWaterMark: '600.00',
              })
              const msgEvent = new MessageEvent('message', { data: payload })
              if (this.onmessage) this.onmessage.call(this as unknown as WebSocket, msgEvent)
              this.dispatchEvent(msgEvent)
            }

            setTimeout(() => send('500.00', '2026-03-24T09:30:00Z'), 100)
            setTimeout(() => send('600.00', '2026-03-24T09:31:00Z'), 200)
          }, 50)
        }

        send(_data: string | ArrayBuffer | Blob | ArrayBufferView): void {}
        close(_code?: number, _reason?: string): void { this.readyState = 3 }
        addEventListener(type: string, listener: EventListenerOrEventListenerObject, options?: boolean | AddEventListenerOptions): void {
          super.addEventListener(type, listener, options)
        }
        removeEventListener(type: string, listener: EventListenerOrEventListenerObject, options?: boolean | EventListenerOptions): void {
          super.removeEventListener(type, listener, options)
        }
      }

      ;(window as unknown as Record<string, unknown>).WebSocket = function (url: string | URL, protocols?: string | string[]) {
        const urlStr = typeof url === 'string' ? url : url.toString()
        if (urlStr.includes('/ws/pnl')) {
          return new MockPnlWebSocket(url, protocols)
        }
        return new OriginalWebSocket(url, protocols)
      } as unknown as typeof WebSocket
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CONNECTING', { value: 0 })
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'OPEN', { value: 1 })
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSING', { value: 2 })
      Object.defineProperty((window as unknown as Record<string, unknown>).WebSocket, 'CLOSED', { value: 3 })
    })

    await goToPnlTab(page)

    // After two messages the chart should render an SVG (2 snapshots = chart, not empty/single)
    await page.waitForSelector('[data-testid="intraday-pnl-chart"] svg', { timeout: 3000 })
    const chart = page.getByTestId('intraday-pnl-chart')
    await expect(chart.locator('svg')).toBeVisible()

    // Latest total should be 600.00
    await expect(page.getByTestId('intraday-chart-latest-total')).toContainText('600.00')
  })
})
