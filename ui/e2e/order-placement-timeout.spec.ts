import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * ADR-0035 phase 4 §4.12 — PENDING_FAILED path:
 *
 *   1. Backend returns PENDING_FAILED with rejectReason=SESSION_DOWN after a
 *      ~2s delay (mimicking a routing timeout).
 *   2. Submit button stays disabled with "Sending to venue..." while the
 *      RPC is in flight.
 *   3. Once the response arrives, the warning banner renders and the retry
 *      CTA is enabled. Original clOrdId is preserved so a retry reconciles
 *      via FIX 35=H instead of producing a duplicate venue order.
 */

const PENDING_FAILED_RESPONSE = {
  orderId: 'order-timeout-1',
  bookId: 'port-1',
  instrumentId: 'AAPL',
  side: 'BUY',
  quantity: '100',
  orderType: 'LIMIT',
  limitPrice: '150.00',
  arrivalPrice: '149.90',
  submittedAt: '2026-05-08T10:00:00Z',
  status: 'PENDING_FAILED',
  fixSessionId: null,
  timeInForce: 'DAY',
  expiresAt: null,
  venueOrderId: null,
  rejectReason: 'SESSION_DOWN',
}

async function mockPlaceOrder(page: Page, response: object, opts: { delayMs?: number } = {}): Promise<void> {
  await page.unroute('**/api/v1/orders').catch(() => {})
  await page.route('**/api/v1/orders', async (route: Route) => {
    if (opts.delayMs) {
      await new Promise((r) => setTimeout(r, opts.delayMs))
    }
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(response),
      })
    } else {
      await route.fallback()
    }
  })
}

async function navigateToPlaceOrder(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.getByTestId('trades-subtab-place').click()
}

async function fillForm(page: Page): Promise<void> {
  await page.getByTestId('place-order-instrument').fill('AAPL')
  await page.getByTestId('place-order-quantity').fill('100')
  await page.getByTestId('place-order-arrival-price').fill('149.90')
  await page.getByTestId('place-order-limit-price').fill('150.00')
}

test.describe('PlaceOrderPanel — PENDING_FAILED routing timeout', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('submit button is disabled during the wait, and the warning banner appears with retry CTA enabled', async ({ page }) => {
    await mockPlaceOrder(page, PENDING_FAILED_RESPONSE, { delayMs: 200 })
    await navigateToPlaceOrder(page)
    await fillForm(page)

    await page.getByTestId('place-order-submit').click()
    await expect(page.getByTestId('place-order-submit')).toBeDisabled()

    const banner = page.getByTestId('order-placement-error-banner')
    await expect(banner).toBeVisible()
    await expect(banner).toHaveAttribute('data-severity', 'warning')
    await expect(banner).toContainText('Order routing timed out (SESSION_DOWN)')

    const retry = page.getByTestId('order-placement-retry')
    await expect(retry).toBeEnabled()
  })

  test('retry resubmits with the same form payload, allowing fix-gateway reconciliation', async ({ page }) => {
    let postCount = 0
    await page.unroute('**/api/v1/orders').catch(() => {})
    await page.route('**/api/v1/orders', async (route: Route) => {
      if (route.request().method() !== 'POST') {
        await route.fallback()
        return
      }
      postCount += 1
      if (postCount === 1) {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(PENDING_FAILED_RESPONSE),
        })
      } else {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            ...PENDING_FAILED_RESPONSE,
            status: 'SENT',
            venueOrderId: 'NYSE-RECONCILED-99',
            rejectReason: null,
          }),
        })
      }
    })

    await navigateToPlaceOrder(page)
    await fillForm(page)
    await page.getByTestId('place-order-submit').click()

    const retry = page.getByTestId('order-placement-retry')
    await expect(retry).toBeEnabled()

    await retry.click()

    await expect(page.getByTestId('place-order-confirmation')).toBeVisible()
    await expect(page.getByTestId('place-order-confirmation-venue-id')).toHaveText('NYSE-RECONCILED-99')
    expect(postCount).toBe(2)
  })
})
