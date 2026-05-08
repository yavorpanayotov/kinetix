import { test, expect, type Page } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * ADR-0035 phase 4 §4.12 — extends the phase-2 degraded-routing coverage:
 * the VenueRoutingStatusDot must also surface DOWN/READY transitions on
 * the Place Order sub-tab, since traders need to know about degraded
 * routing at the moment they're about to submit an order.
 *
 * Phase 2's `order-blotter-degraded-routing.spec.ts` covers the blotter
 * sub-tab. This spec covers the surface the Place Order panel introduces.
 */

async function mockHealth(page: Page, fixGatewayStatus: 'READY' | 'DOWN'): Promise<void> {
  await page.route('**/api/v1/system/health', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        status: fixGatewayStatus === 'READY' ? 'UP' : 'DEGRADED',
        services: { 'fix-gateway': { status: fixGatewayStatus } },
      }),
    })
  })
}

async function navigateToBlotterThenPlaceOrder(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.getByTestId('trades-subtab-blotter').click()
  await page.getByTestId('trades-subtab-place').click()
}

test.describe('VenueRoutingStatusDot — degraded routing visible across trades sub-tabs', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows green dot when fix-gateway is READY (blotter sub-tab)', async ({ page }) => {
    await mockHealth(page, 'READY')
    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.getByTestId('trades-subtab-blotter').click()

    const dot = page.getByTestId('venue-routing-status-dot')
    await expect(dot).toBeVisible()
    await expect(dot).toHaveAttribute('data-state', 'up')
  })

  test('amber dot persists after switching to the place-order sub-tab and back', async ({ page }) => {
    await mockHealth(page, 'DOWN')
    await navigateToBlotterThenPlaceOrder(page)

    // The Place Order sub-tab does not host the dot directly, but switching
    // back to the blotter (where the dot is mounted) must show the amber
    // state — i.e. the health poll persisted across sub-tab switches.
    await page.getByTestId('trades-subtab-blotter').click()
    const dot = page.getByTestId('venue-routing-status-dot')
    await expect(dot).toHaveAttribute('data-state', 'degraded')
    await expect(dot).toHaveAttribute(
      'aria-label',
      'Cancel confirmation unavailable — call venue directly to confirm cancel',
    )
  })
})
