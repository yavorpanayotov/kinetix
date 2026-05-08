import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * ADR-0035 phase 4 §4.12 — DUPLICATE_IN_FLIGHT path: a second submit while a
 * previous RPC is still settling at fix-gateway. The banner-only treatment
 * blocks the retry CTA — clicking retry must not produce a duplicate venue
 * order. The trader has to wait for the original submission to resolve.
 */

const DUPLICATE_RESPONSE = {
  orderId: 'order-duplicate-1',
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
  rejectReason: 'DUPLICATE_IN_FLIGHT',
}

async function mockPlaceOrder(page: Page, response: object): Promise<void> {
  await page.unroute('**/api/v1/orders').catch(() => {})
  await page.route('**/api/v1/orders', async (route: Route) => {
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

test.describe('PlaceOrderPanel — DUPLICATE_IN_FLIGHT', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders no-retry banner with the explicit do-not-retry copy', async ({ page }) => {
    await mockPlaceOrder(page, DUPLICATE_RESPONSE)
    await navigateToPlaceOrder(page)
    await fillForm(page)

    await page.getByTestId('place-order-submit').click()

    const banner = page.getByTestId('order-placement-error-banner')
    await expect(banner).toBeVisible()
    await expect(banner).toHaveAttribute('data-severity', 'warning')
    await expect(banner).toContainText('Previous submission still in flight, do not retry yet')

    const retry = page.getByTestId('order-placement-retry')
    await expect(retry).toBeDisabled()
  })
})
