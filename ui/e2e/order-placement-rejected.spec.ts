import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * ADR-0035 phase 4 §4.12 — REJECTED path: hard failure with no retry CTA.
 * The banner uses CRITICAL severity (red) so the trader can clearly tell it
 * apart from the amber PENDING_FAILED case.
 */

const REJECTED_RESPONSE = {
  orderId: 'order-rejected-1',
  bookId: 'port-1',
  instrumentId: 'AAPL',
  side: 'BUY',
  quantity: '100',
  orderType: 'LIMIT',
  limitPrice: '150.00',
  arrivalPrice: '149.90',
  submittedAt: '2026-05-08T10:00:00Z',
  status: 'REJECTED',
  fixSessionId: null,
  timeInForce: 'DAY',
  expiresAt: null,
  venueOrderId: null,
  rejectReason: 'INVALID_REQUEST',
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

test.describe('PlaceOrderPanel — REJECTED', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders critical-severity banner with reject reason and no retry CTA', async ({ page }) => {
    await mockPlaceOrder(page, REJECTED_RESPONSE)
    await navigateToPlaceOrder(page)
    await fillForm(page)

    await page.getByTestId('place-order-submit').click()

    const banner = page.getByTestId('order-placement-error-banner')
    await expect(banner).toBeVisible()
    await expect(banner).toHaveAttribute('data-severity', 'critical')
    await expect(banner).toContainText('Order rejected: INVALID_REQUEST')

    // No retry CTA on a hard reject.
    await expect(page.getByTestId('order-placement-retry')).toHaveCount(0)
  })
})
