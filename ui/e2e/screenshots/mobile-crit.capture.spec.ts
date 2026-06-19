import { test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes, TEST_VAR_RESULT } from '../fixtures'

// Crit Loop — OBSERVE step (docs/plans/mobile-crit.md).
//
// Captures the phone surface at 390px for the trader + ux-designer crit. This is
// NOT an assertion test — it produces the PNG matrix the crit reads. It reuses
// the same mocked backend as ui/e2e/mobile-access.spec.ts so it runs fully
// locally against `npm run dev` with no platform deployed.
//
// Output: docs/screenshots/mobile-crit/<view>-<theme>.png
// Views:  risk | pnl | alerts | positions      Themes: light | dark
//
// Re-run a single view after a fix with:
//   cd ui && npx playwright test e2e/screenshots/mobile-crit.capture.spec.ts \
//     --project=chromium -g "risk \(dark\)"

const PHONE = { width: 390, height: 844 }
const OUT = '../docs/screenshots/mobile-crit'

test.use({ viewport: PHONE })

/** Override the VaR endpoint so the Risk view renders a real figure, not empty. */
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

const VIEWS: { name: string; tab: string; view: string }[] = [
  { name: 'risk', tab: 'mobile-tab-risk', view: 'mobile-risk-view' },
  { name: 'pnl', tab: 'mobile-tab-pnl', view: 'mobile-pnl-view' },
  { name: 'alerts', tab: 'mobile-tab-alerts', view: 'mobile-alerts-view' },
  { name: 'positions', tab: 'mobile-tab-positions', view: 'mobile-positions-view' },
]

for (const theme of ['light', 'dark'] as const) {
  test.describe(`mobile crit capture (${theme})`, () => {
    test.beforeEach(async ({ page }) => {
      // useTheme reads kinetix:theme from localStorage before first paint.
      await page.addInitScript((t) => {
        window.localStorage.setItem('kinetix:theme', t)
      }, theme)
      await mockAllApiRoutes(page)
      await mockVaR(page)
      await page.goto('/')
    })

    for (const { name, tab, view } of VIEWS) {
      test(`${name} (${theme})`, async ({ page }) => {
        await page.getByTestId(tab).click()
        // Alerts may render its empty state instead of the populated view.
        const target = page
          .getByTestId(view)
          .or(page.getByTestId(`${view.replace('-view', '')}-empty`))
        await target.first().waitFor({ state: 'visible' })
        await page.waitForTimeout(150) // let any stream/animation settle
        await page.screenshot({ path: `${OUT}/${name}-${theme}.png`, fullPage: true })
      })
    }
  })
}
