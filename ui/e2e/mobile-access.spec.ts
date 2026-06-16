import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes, TEST_VAR_RESULT } from './fixtures'

// Plan mobile-phone-access §"Playwright E2E at a phone viewport" — drive the app
// at a 390px (iPhone 12/13/14 portrait) viewport so App.tsx renders the
// phone-first <MobileApp> surface instead of the desktop tree. App.tsx flips to
// <MobileApp> when window.innerWidth < MIN_VIEWPORT_WIDTH_PX (1280px); the
// project default viewport is Desktop Chrome (1280px), so this file overrides
// the viewport to land below the floor.
//
// The mobile surface mounts ONLY the active view's data hooks, so each tab
// switch re-fetches. We reuse the standard mocked backend (mockAllApiRoutes)
// that the desktop specs use — the mobile views call the same gateway endpoints
// via the same hooks. The default mocks leave VaR at 404 (risk empty state), so
// we override the VaR endpoint to assert real data renders in the Risk view.

const PHONE = { width: 390, height: 844 }

// Drive every test in this file at a phone viewport so the MobileApp surface
// renders. Per-file test.use cleanly overrides the project default (no need to
// touch playwright.config.ts — confirmed the config sets no `use.viewport`).
test.use({ viewport: PHONE })

/** Override the VaR endpoint so the Risk view renders a real figure. */
async function mockVaR(page: Page): Promise<void> {
  await page.unroute('**/api/v1/risk/var/*')
  await page.route('**/api/v1/risk/var/*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_VAR_RESULT),
    })
  })
}

test.describe('Mobile phone access (390px)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockVaR(page)
    await page.goto('/')
  })

  test('renders the mobile surface, not the desktop-only warning or desktop app', async ({ page }) => {
    // App loads on a phone viewport and shows the mobile shell.
    await expect(page.getByTestId('mobile-app')).toBeVisible()
    await expect(page.getByTestId('mobile-header')).toBeVisible()
    await expect(page.getByTestId('mobile-tab-bar')).toBeVisible()

    // The "desktop-only" warning is NOT shown (the mobile surface replaces it).
    await expect(page.getByTestId('small-viewport-warning')).toHaveCount(0)

    // The desktop app (its tab bar) is NOT mounted.
    await expect(page.getByTestId('tab-bar')).toHaveCount(0)
  })

  test('defaults to the Risk view', async ({ page }) => {
    await expect(page.getByTestId('mobile-risk-view')).toBeVisible()
    await expect(page.getByTestId('mobile-pnl-view')).toHaveCount(0)
    await expect(page.getByTestId('mobile-alerts-view')).toHaveCount(0)
    await expect(page.getByTestId('mobile-positions-view')).toHaveCount(0)
  })

  test('the bottom nav switches between the four views', async ({ page }) => {
    // Risk is the default active view.
    await expect(page.getByTestId('mobile-risk-view')).toBeVisible()

    // P&L
    await page.getByTestId('mobile-tab-pnl').click()
    await expect(page.getByTestId('mobile-pnl-view')).toBeVisible()
    await expect(page.getByTestId('mobile-risk-view')).toHaveCount(0)

    // Alerts
    await page.getByTestId('mobile-tab-alerts').click()
    await expect(page.getByTestId('mobile-alerts-view').or(page.getByTestId('mobile-alerts-empty'))).toBeVisible()
    await expect(page.getByTestId('mobile-pnl-view')).toHaveCount(0)

    // Positions
    await page.getByTestId('mobile-tab-positions').click()
    await expect(page.getByTestId('mobile-positions-view')).toBeVisible()
    await expect(page.getByTestId('mobile-alerts-view')).toHaveCount(0)
    await expect(page.getByTestId('mobile-alerts-empty')).toHaveCount(0)

    // Back to Risk
    await page.getByTestId('mobile-tab-risk').click()
    await expect(page.getByTestId('mobile-risk-view')).toBeVisible()
    await expect(page.getByTestId('mobile-positions-view')).toHaveCount(0)
  })

  test('mocked data renders in each view', async ({ page }) => {
    // Risk — the VaR override means a real figure renders, not the empty state.
    await expect(page.getByTestId('mobile-risk-view')).toBeVisible()
    await expect(page.getByTestId('mobile-risk-empty')).toHaveCount(0)
    await expect(page.getByTestId('mobile-risk-var-value')).not.toBeEmpty()

    // P&L — the mocked book summary provides a NAV figure.
    await page.getByTestId('mobile-tab-pnl').click()
    await expect(page.getByTestId('mobile-pnl-view')).toBeVisible()
    await expect(page.getByTestId('mobile-pnl-nav')).not.toBeEmpty()

    // Alerts — the default mock returns no alerts, so the empty state renders
    // deterministically.
    await page.getByTestId('mobile-tab-alerts').click()
    await expect(page.getByTestId('mobile-alerts-empty')).toBeVisible()

    // Positions — the mocked positions (AAPL/GOOGL/EUR_USD) render as rows.
    await page.getByTestId('mobile-tab-positions').click()
    await expect(page.getByTestId('mobile-positions-view')).toBeVisible()
    await expect(page.getByTestId('mobile-position-row-AAPL')).toBeVisible()
    await expect(page.getByTestId('mobile-position-row-GOOGL')).toBeVisible()
  })

  test('no horizontal overflow at 390px on any view', async ({ page }) => {
    // The document must not be wider than the viewport on any of the four
    // views — a horizontal scrollbar is the classic "not actually responsive"
    // smell. A 1px tolerance absorbs sub-pixel rounding.
    const views: { tab: string; view: string }[] = [
      { tab: 'mobile-tab-risk', view: 'mobile-risk-view' },
      { tab: 'mobile-tab-pnl', view: 'mobile-pnl-view' },
      { tab: 'mobile-tab-alerts', view: 'mobile-alerts-view' },
      { tab: 'mobile-tab-positions', view: 'mobile-positions-view' },
    ]
    for (const { tab } of views) {
      await page.getByTestId(tab).click()
      // Let layout settle before measuring.
      await page.waitForTimeout(50)
      const scrollWidth = await page.evaluate(
        () => document.documentElement.scrollWidth,
      )
      expect(scrollWidth, `scrollWidth on ${tab}`).toBeLessThanOrEqual(PHONE.width + 1)
    }
  })

  test('desktop-only tools are not present in the mobile DOM', async ({ page }) => {
    // Walk every view so a lazily-mounted desktop widget would have a chance to
    // appear if the wrong tree were rendered.
    for (const tab of [
      'mobile-tab-risk',
      'mobile-tab-pnl',
      'mobile-tab-alerts',
      'mobile-tab-positions',
    ]) {
      await page.getByTestId(tab).click()
    }

    // Desktop tab-bar buttons.
    for (const testid of [
      'tab-positions',
      'tab-trades',
      'tab-scenarios',
      'tab-regulatory',
    ]) {
      await expect(page.getByTestId(testid)).toHaveCount(0)
    }

    // Desktop-only component roots: the positions grid, the risk VaR dashboard,
    // the scenarios tab, and the trade blotter never mount on the mobile shell.
    for (const testid of [
      'book-summary', // PositionGrid
      'var-dashboard', // RiskTab dashboard
      'scenarios-tab', // ScenariosTab
      'venue-routing-status', // TradeBlotter
      'filter-instrument', // TradeBlotter filters
    ]) {
      await expect(page.getByTestId(testid)).toHaveCount(0)
    }
  })
})
