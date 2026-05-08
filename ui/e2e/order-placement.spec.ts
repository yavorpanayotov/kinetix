import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * ADR-0035 phase 4 §4.12 — golden path for the order-placement workflow:
 *
 *   1. Trader fills the form on the new "Place Order" sub-tab.
 *   2. Submit button shows the loading spinner + "Sending to venue..." copy.
 *   3. Backend returns SENT + venueOrderId.
 *   4. A confirmation modal renders with the Venue Order ID and a clipboard
 *      copy button.
 */

const PLACE_ORDER_RESPONSE = {
  orderId: 'order-success-1',
  bookId: 'port-1',
  instrumentId: 'AAPL',
  side: 'BUY',
  quantity: '100',
  orderType: 'LIMIT',
  limitPrice: '150.00',
  arrivalPrice: '149.90',
  submittedAt: '2026-05-08T10:00:00Z',
  status: 'SENT',
  fixSessionId: null,
  timeInForce: 'DAY',
  expiresAt: null,
  venueOrderId: 'NYSE-99887766',
  rejectReason: null,
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

async function fillPlaceOrderForm(page: Page): Promise<void> {
  await page.getByTestId('place-order-instrument').fill('AAPL')
  await page.getByTestId('place-order-quantity').fill('100')
  await page.getByTestId('place-order-arrival-price').fill('149.90')
  await page.getByTestId('place-order-limit-price').fill('150.00')
}

test.describe('PlaceOrderPanel — golden path', () => {
  test.beforeEach(async ({ page, context }) => {
    await mockAllApiRoutes(page)
    // Grant clipboard permission so the copy button can succeed in the test.
    await context.grantPermissions(['clipboard-read', 'clipboard-write']).catch(() => {})
  })

  test('submitting the form shows a confirmation modal with the venue order ID', async ({ page }) => {
    await mockPlaceOrder(page, PLACE_ORDER_RESPONSE)
    await navigateToPlaceOrder(page)
    await fillPlaceOrderForm(page)

    await page.getByTestId('place-order-submit').click()

    const confirmation = page.getByTestId('place-order-confirmation')
    await expect(confirmation).toBeVisible()
    await expect(page.getByTestId('place-order-confirmation-venue-id')).toHaveText('NYSE-99887766')
    await expect(page.getByTestId('place-order-confirmation-clord-id')).toHaveText('order-success-1')
    await expect(page.getByTestId('place-order-confirmation-copy')).toHaveAttribute(
      'aria-label',
      'Copy venue order ID',
    )
  })

  test('submit button shows loading state with "Sending to venue..." while the RPC is in flight', async ({ page }) => {
    await mockPlaceOrder(page, PLACE_ORDER_RESPONSE, { delayMs: 250 })
    await navigateToPlaceOrder(page)
    await fillPlaceOrderForm(page)

    await page.getByTestId('place-order-submit').click()

    const submit = page.getByTestId('place-order-submit')
    await expect(submit).toBeDisabled()
    await expect(submit).toContainText('Sending to venue...')

    await expect(page.getByTestId('place-order-confirmation')).toBeVisible()
  })

  test('disables submit when required fields are empty', async ({ page }) => {
    await navigateToPlaceOrder(page)
    await expect(page.getByTestId('place-order-submit')).toBeDisabled()
  })
})
