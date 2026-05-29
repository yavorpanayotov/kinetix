import { test, expect } from '@playwright/test'
import { mockBackendApiRoutes } from './fixtures'

/**
 * Plan P3 #30: the demo-mode banner must be session-dismissible.
 *
 * The dismissed state is persisted in sessionStorage (not localStorage) so it
 * stays dismissed across reloads *within the same browser session* but returns
 * the next time the demo is opened in a fresh session.
 */
test.describe('Demo banner session dismiss', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackendApiRoutes(page)
  })

  // Playwright gives each test a fresh browser context, so sessionStorage
  // starts empty — no explicit clear is needed for "fresh session" cases.
  test('banner is visible on first load of a fresh session', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByTestId('demo-welcome-strip')).toBeVisible()
  })

  test('clicking dismiss hides the banner', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByTestId('demo-welcome-strip')).toBeVisible()
    await page.getByTestId('demo-strip-dismiss').click()
    await expect(page.getByTestId('demo-welcome-strip')).not.toBeAttached()
  })

  test('dismissed state persists across a reload within the session', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('demo-strip-dismiss').click()
    await expect(page.getByTestId('demo-welcome-strip')).not.toBeAttached()

    // sessionStorage survives a reload within the same session.
    await page.reload()
    await expect(page.getByTestId('demo-welcome-strip')).not.toBeAttached()

    // Confirm the persistence mechanism is sessionStorage, not localStorage.
    const sessionFlag = await page.evaluate(() =>
      sessionStorage.getItem('kinetix_demo_strip_dismissed'),
    )
    expect(sessionFlag).toBe('true')
    const localFlag = await page.evaluate(() =>
      localStorage.getItem('kinetix_demo_strip_dismissed'),
    )
    expect(localFlag).toBeNull()
  })

  test('banner returns when the session flag is cleared', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('demo-strip-dismiss').click()
    await expect(page.getByTestId('demo-welcome-strip')).not.toBeAttached()

    await page.evaluate(() => sessionStorage.removeItem('kinetix_demo_strip_dismissed'))
    await page.reload()
    await expect(page.getByTestId('demo-welcome-strip')).toBeVisible()
  })
})
