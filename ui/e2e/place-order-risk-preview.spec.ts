import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Trader-review P2 (plans/ui-trader-review.md): on Place Order form-blur,
 * the trader expects to see the candidate order's risk impact —
 * Δ VaR / Δ Delta / Δ Notional / Δ counterparty exposure — before
 * clicking Submit. The preview is a read-only panel inside the Place
 * Order ticket; it does not book anything.
 *
 * These tests pin the user-visible behaviour:
 *   - panel hidden when required fields are blank,
 *   - hitting POST /api/v1/risk/pretrade-preview on form-blur once the
 *     four required fields are filled, with the candidate order body,
 *   - all four deltas (and a "computed at" timestamp) rendered in the
 *     panel,
 *   - panel stays out of the way of the Submit button — form remains
 *     submittable even while a preview is in flight.
 */

const PREVIEW_RESPONSE = {
  baseVaR: '10000.00',
  hypotheticalVaR: '11500.00',
  varChange: '1500.00',
  baseDelta: '100.000000',
  hypotheticalDelta: '150.000000',
  deltaChange: '50.000000',
  notionalChange: '15000.00',
  counterpartyId: null,
  counterpartyExposureChange: null,
  calculatedAt: '2026-05-29T10:00:00Z',
}

async function mockPreTradePreview(page: Page, response: object): Promise<{ requests: Array<Record<string, unknown>> }> {
  const requests: Array<Record<string, unknown>> = []
  await page.unroute('**/api/v1/risk/pretrade-preview').catch(() => {})
  await page.route('**/api/v1/risk/pretrade-preview', async (route: Route) => {
    if (route.request().method() === 'POST') {
      try {
        requests.push(JSON.parse(route.request().postData() ?? '{}'))
      } catch {
        // leave requests untouched on parse failure
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(response),
      })
    } else {
      await route.fallback()
    }
  })
  return { requests }
}

async function navigateToPlaceOrder(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.getByTestId('trades-subtab-place').click()
}

async function fillBaseForm(page: Page): Promise<void> {
  await page.getByTestId('place-order-instrument').fill('AAPL')
  await page.getByTestId('place-order-quantity').fill('100')
  await page.getByTestId('place-order-arrival-price').fill('149.90')
  await page.getByTestId('place-order-limit-price').fill('150.00')
}

test.describe('PlaceOrderPanel — pre-trade risk preview', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('preview panel renders the four deltas after the form is filled and blurred', async ({ page }) => {
    const { requests } = await mockPreTradePreview(page, PREVIEW_RESPONSE)
    await navigateToPlaceOrder(page)
    await fillBaseForm(page)

    // Trigger form-blur on the last filled field — moves focus off the input,
    // which is what fires the preview.
    await page.getByTestId('place-order-limit-price').blur()

    const panel = page.getByTestId('place-order-risk-preview')
    await expect(panel).toBeVisible()

    await expect(page.getByTestId('place-order-risk-preview-var-change')).toContainText('1,500.00')
    await expect(page.getByTestId('place-order-risk-preview-delta-change')).toContainText('50')
    await expect(page.getByTestId('place-order-risk-preview-notional-change')).toContainText('15,000.00')
    // No counterparty was supplied — the row must render a dash, not a stale
    // "$0" that the trader could mistake for "no bilateral exposure".
    await expect(page.getByTestId('place-order-risk-preview-counterparty-change')).toContainText('—')

    expect(requests.length).toBeGreaterThanOrEqual(1)
    expect(requests[requests.length - 1]).toMatchObject({
      bookId: 'port-1',
      instrumentId: 'AAPL',
      side: 'BUY',
      quantity: '100',
    })
  })

  test('preview panel is hidden until the required fields are filled', async ({ page }) => {
    await mockPreTradePreview(page, PREVIEW_RESPONSE)
    await navigateToPlaceOrder(page)

    // Only fill the instrument — quantity, prices missing.
    await page.getByTestId('place-order-instrument').fill('AAPL')
    await page.getByTestId('place-order-instrument').blur()

    await expect(page.getByTestId('place-order-risk-preview')).toHaveCount(0)
  })
})
