import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

// Mirrors the seeded GBP yield curve with the Gap 8 missing-5Y anomaly:
// every canonical tenor is present, but 5Y carries interpolated=true because
// the source node was omitted at seed time and the gateway filled it in via
// the rates-service per-tenor interpolation endpoint.
const GBP_YIELD_CURVE_WITH_INTERPOLATED_5Y = {
  curveId: 'GBP',
  currency: 'GBP',
  asOfDate: '2026-02-22T10:00:00Z',
  source: 'CENTRAL_BANK',
  points: [
    { label: 'O/N', days: 1, rate: '0.0500', interpolated: false },
    { label: '1W', days: 7, rate: '0.0502', interpolated: false },
    { label: '1M', days: 30, rate: '0.0505', interpolated: false },
    { label: '3M', days: 90, rate: '0.0508', interpolated: false },
    { label: '6M', days: 180, rate: '0.0512', interpolated: false },
    { label: '1Y', days: 365, rate: '0.0515', interpolated: false },
    { label: '2Y', days: 730, rate: '0.0520', interpolated: false },
    { label: '5Y', days: 1825, rate: '0.052500', interpolated: true },
    { label: '10Y', days: 3650, rate: '0.0530', interpolated: false },
    { label: '30Y', days: 10950, rate: '0.0540', interpolated: false },
  ],
}

async function mockYieldCurveRoutes(page: Page): Promise<void> {
  await page.route('**/api/v1/rates/yield-curves/GBP', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(GBP_YIELD_CURVE_WITH_INTERPOLATED_5Y),
    })
  })
}

async function goToMarketDataTab(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
  await page.getByTestId('risk-subtab-market-data').click()
}

test.describe('Yield curve chart — GBP missing-5Y anomaly', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockYieldCurveRoutes(page)
  })

  test('yield curve panel is visible in the Market Data sub-tab', async ({ page }) => {
    await goToMarketDataTab(page)
    await expect(page.getByTestId('yield-curve-panel')).toBeVisible()
  })

  test('chart renders for GBP and marks the 5Y point as interpolated', async ({ page }) => {
    await goToMarketDataTab(page)

    // Default selection is GBP so the anomaly is visible without interaction.
    const chart = page.getByTestId('yield-curve-chart')
    await expect(chart).toBeVisible()

    const interpolatedMarker = page.getByTestId('yield-curve-marker-5Y')
    await expect(interpolatedMarker).toBeVisible()
    await expect(interpolatedMarker).toHaveAttribute('data-interpolated', 'true')

    // Non-interpolated peer should not carry the interpolated flag.
    const observedMarker = page.getByTestId('yield-curve-marker-2Y')
    await expect(observedMarker).toHaveAttribute('data-interpolated', 'false')
  })

  test('interpolated marker exposes the anomaly tooltip text', async ({ page }) => {
    await goToMarketDataTab(page)

    const interpolatedMarker = page.getByTestId('yield-curve-marker-5Y')
    await expect(interpolatedMarker).toBeVisible()

    // SVG <title> renders as the marker's native browser tooltip and is
    // exposed as the marker's accessible name; both surfaces should carry
    // the anomaly message so screen readers and hovering users see it.
    const titleText = await interpolatedMarker.locator('title').textContent()
    expect(titleText).toContain('Interpolated — source node unavailable')

    await expect(interpolatedMarker).toHaveAttribute(
      'aria-label',
      /interpolated/i,
    )
  })

  test('hovering the interpolated marker reveals the anomaly tooltip overlay', async ({ page }) => {
    await goToMarketDataTab(page)

    const interpolatedMarker = page.getByTestId('yield-curve-marker-5Y')
    await expect(interpolatedMarker).toBeVisible()

    // The marker sits beneath a larger transparent hit area; the parent
    // <g> wraps both, so hovering the wrapper triggers the React handler.
    await interpolatedMarker.locator('xpath=..').hover()

    const tooltip = page.getByTestId('yield-curve-tooltip-interpolated')
    await expect(tooltip).toBeVisible()
    await expect(tooltip).toContainText('Interpolated — source node unavailable')
  })

  test('legend communicates the observed vs interpolated marker distinction', async ({ page }) => {
    await goToMarketDataTab(page)

    const chart = page.getByTestId('yield-curve-chart')
    await expect(chart).toContainText('Observed')
    await expect(chart).toContainText('Interpolated')
  })
})
