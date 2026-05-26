import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * E2E coverage for DemoBootstrapGate (kx-abw).
 *
 * The gate wraps the Risk / Positions / P&L / Regulatory routes and shows a
 * non-blocking overlay banner while demo-orchestrator is still bootstrapping
 * book data. Once `state=READY` the overlay must dismiss; on network error
 * or 404 (non-demo environments) the gate must immediately no-op so non-demo
 * users never see a stuck splash.
 */

test.describe('Demo bootstrap gate', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    // The neighbouring BootstrapBanner stores a dismiss flag in sessionStorage;
    // clear it so it never confounds the gate's own splash assertions in case
    // routes share a process between runs.
    await page.addInitScript(() =>
      sessionStorage.removeItem('kinetix_bootstrap_banner_dismissed'),
    )
  })

  test('shows the splash on the Positions route while IN_PROGRESS and dismisses on READY', async ({
    page,
  }) => {
    // Stay IN_PROGRESS for the first ~6 polls (across both DemoBootstrapGate
     // and the neighbouring BootstrapBanner, each of which calls this endpoint
     // on its own cadence), then flip to READY so the splash auto-dismisses.
    let callCount = 0
    await page.route('**/demo/bootstrap-status', (route) => {
      callCount += 1
      const body =
        callCount <= 6
          ? {
              state: 'IN_PROGRESS',
              successCount: Math.min(callCount, 7),
              failureCount: 0,
              failedBooks: [],
            }
          : {
              state: 'READY',
              successCount: 8,
              failureCount: 0,
              failedBooks: [],
            }
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
      })
    })

    await page.goto('/')
    await page.getByTestId('tab-positions').click()

    // Splash overlay surfaces with the expected copy on the first poll.
    const splash = page.getByTestId('demo-bootstrap-splash')
    await expect(splash).toBeVisible({ timeout: 10_000 })
    await expect(splash).toContainText(/Demo environment initializing/i)
    await expect(splash).toContainText(/of 8 books ready/i)

    // After a couple of polls the orchestrator flips to READY and the splash
    // must detach.
    await expect(splash).toBeHidden({ timeout: 15_000 })
  })

  test('immediately no-ops when the endpoint returns 404 (non-demo environment)', async ({
    page,
  }) => {
    let callCount = 0
    await page.route('**/demo/bootstrap-status', (route) => {
      callCount += 1
      route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'not found' }),
      })
    })

    await page.goto('/')
    await page.getByTestId('tab-positions').click()

    // Give the gate enough time to poll once.
    await page.waitForTimeout(500)

    // No splash should ever render in a non-demo environment.
    await expect(page.getByTestId('demo-bootstrap-splash')).toHaveCount(0)
    expect(callCount).toBeGreaterThanOrEqual(1)
  })
})
