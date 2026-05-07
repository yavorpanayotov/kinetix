import { test, expect, type Page } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

async function navigateToTradesBlotter(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
}

/**
 * ADR-0035 phase 2: when fix-gateway reports DOWN on the system health
 * aggregator, the trade blotter must surface the degraded-routing state
 * via the VenueRoutingStatusDot indicator and tooltip — the trader needs
 * to know that cancel confirmation is unavailable and they may have to
 * call the venue directly.
 */

async function mockHealthForFixGateway(
  page: Page,
  fixGatewayStatus: 'READY' | 'DOWN',
): Promise<void> {
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

test.describe('TradeBlotter — venue routing degraded indicator', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows green dot when fix-gateway is READY', async ({ page }) => {
    await mockHealthForFixGateway(page, 'READY')
    await navigateToTradesBlotter(page)

    const dot = page.getByTestId('venue-routing-status-dot')
    await expect(dot).toBeVisible()
    await expect(dot).toHaveAttribute('data-state', 'up')
    await expect(dot).toHaveAttribute('aria-label', 'Venue routing healthy')
  })

  test('shows amber dot with degraded tooltip when fix-gateway is DOWN', async ({ page }) => {
    await mockHealthForFixGateway(page, 'DOWN')
    await navigateToTradesBlotter(page)

    const dot = page.getByTestId('venue-routing-status-dot')
    await expect(dot).toBeVisible()
    await expect(dot).toHaveAttribute('data-state', 'degraded')
    await expect(dot).toHaveAttribute(
      'aria-label',
      'Cancel confirmation unavailable — call venue directly to confirm cancel',
    )
    await expect(dot).toHaveAttribute(
      'title',
      'Cancel confirmation unavailable — call venue directly to confirm cancel',
    )
  })
})
