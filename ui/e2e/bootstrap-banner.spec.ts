import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

test.describe('Bootstrap banner', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    // Make sure the session-storage dismiss flag from a prior test (or a
    // previous run of this test inside the same browser context) doesn't hide
    // the banner before we get to assert on it.
    await page.addInitScript(() =>
      sessionStorage.removeItem('kinetix_bootstrap_banner_dismissed'),
    )
  })

  test('banner appears while bootstrap is IN_PROGRESS and auto-hides when READY', async ({
    page,
  }) => {
    let callCount = 0
    await page.route('**/demo/bootstrap-status', (route) => {
      callCount += 1
      // First six polls are IN_PROGRESS — this gives both the banner and the
      // sibling DemoBootstrapGate enough IN_PROGRESS responses (each polls
      // independently, and StrictMode double-mounts each in dev) before
      // flipping to READY so both can auto-dismiss within the polling window.
      const body =
        callCount <= 6
          ? {
              state: 'IN_PROGRESS',
              successCount: Math.min(callCount, 7),
              failureCount: 0,
              sodSuccessCount: null,
              sodFailureCount: null,
            }
          : {
              state: 'READY',
              successCount: 8,
              failureCount: 0,
              sodSuccessCount: 8,
              sodFailureCount: 0,
            }
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
      })
    })

    await page.goto('/')

    // Initial poll lands during page load; the banner should be visible
    // quickly with the "Initialising demo data" copy.
    const banner = page.getByTestId('bootstrap-banner')
    await expect(banner).toBeVisible({ timeout: 15_000 })
    await expect(banner).toContainText(/Initialising demo data/i)

    // The component polls every 3s; after the third call it flips to READY
    // and the banner must detach within the polling window.
    await expect(banner).toBeHidden({ timeout: 15_000 })
  })
})
