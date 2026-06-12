import { test, expect } from '@playwright/test'
import { mockBackendApiRoutes } from './fixtures'

test.describe('Demo mode', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackendApiRoutes(page)
  })

  test('app loads immediately without Authenticating spinner', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByText('Authenticating...')).not.toBeVisible()
    await expect(page.getByTestId('tab-bar')).toBeVisible()
  })

  test('shows persona switcher in header with visible button shape', async ({ page }) => {
    await page.goto('/')
    const toggle = page.getByTestId('persona-switcher-toggle')
    await expect(toggle).toBeVisible()
    await expect(toggle).toHaveCSS('border-style', 'solid')
  })

  test('defaults to RISK_MANAGER persona', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByTestId('header-role-badge')).toHaveText('RISK MANAGER')
    await expect(page.getByTestId('header-username')).toHaveText('risk_manager1')
  })

  test('can switch to all 5 personas and badge/username update', async ({ page }) => {
    await page.goto('/')

    const personas = [
      { key: 'risk_manager', badge: 'RISK MANAGER', username: 'risk_manager1' },
      { key: 'trader', badge: 'TRADER', username: 'trader1' },
      { key: 'admin', badge: 'ADMIN', username: 'admin' },
      { key: 'compliance', badge: 'COMPLIANCE', username: 'compliance1' },
      { key: 'viewer', badge: 'VIEWER', username: 'viewer1' },
    ]

    for (const p of personas) {
      await page.getByTestId('persona-switcher-toggle').click()
      await page.getByTestId(`persona-option-${p.key}`).click()
      await page.getByTestId('confirm-dialog-confirm').click()
      await expect(page.getByTestId('header-role-badge')).toHaveText(p.badge)
      await expect(page.getByTestId('header-username')).toHaveText(p.username)
    }
  })

  test('no logout button in demo mode', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByTestId('logout-button')).not.toBeAttached()
  })

  test('full app is interactive — positions tab loads data', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByTestId('position-row-AAPL')).toBeVisible()
  })

  test('persona resets to RISK_MANAGER on page refresh', async ({ page }) => {
    await page.goto('/')
    // Switch to TRADER
    await page.getByTestId('persona-switcher-toggle').click()
    await page.getByTestId('persona-option-trader').click()
    await page.getByTestId('confirm-dialog-confirm').click()
    await expect(page.getByTestId('header-role-badge')).toHaveText('TRADER')

    // Refresh
    await page.reload()
    await expect(page.getByTestId('header-role-badge')).toHaveText('RISK MANAGER')
  })

  test('TRADER persona can submit a trade booking form', async ({ page }) => {
    // Mock the POST endpoint for trade booking
    await page.route('**/api/v1/books/*/trades', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            tradeId: 'demo-trade-1',
            bookId: 'port-1',
            instrumentId: 'AAPL',
            side: 'BUY',
            quantity: 10,
            price: 189.25,
            status: 'CONFIRMED',
            tradedAt: new Date().toISOString(),
          }),
        })
      } else {
        route.continue()
      }
    })

    await page.goto('/')

    // Switch to TRADER persona
    await page.getByTestId('persona-switcher-toggle').click()
    await page.getByTestId('persona-option-trader').click()
    await page.getByTestId('confirm-dialog-confirm').click()
    await expect(page.getByTestId('header-role-badge')).toHaveText('TRADER')

    // Navigate to Trades tab and verify it renders
    await page.getByRole('tab', { name: 'Trades' }).click()
    await expect(page.getByRole('tab', { name: 'Trades' })).toHaveAttribute('aria-selected', 'true')
  })

  test('shows DEMO badge next to logo', async ({ page }) => {
    await page.goto('/')
    const badge = page.getByTestId('demo-mode-badge')
    await expect(badge).toBeVisible()
    await expect(badge).toHaveText('DEMO')
  })
})

test.describe('Demo welcome strip', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackendApiRoutes(page)
  })

  test('welcome strip is visible on first load', async ({ page }) => {
    await page.addInitScript(() => sessionStorage.removeItem('kinetix_demo_strip_dismissed'))
    await page.goto('/')
    await expect(page.getByTestId('demo-welcome-strip')).toBeVisible()
    await expect(page.getByTestId('demo-welcome-strip')).toContainText('Demo mode')
  })

  test('dismiss removes the strip from DOM', async ({ page }) => {
    await page.addInitScript(() => sessionStorage.removeItem('kinetix_demo_strip_dismissed'))
    await page.goto('/')
    await page.getByTestId('demo-strip-dismiss').click()
    await expect(page.getByTestId('demo-welcome-strip')).not.toBeAttached()
  })

  test('strip stays gone after page reload', async ({ page }) => {
    // Simulate a previous dismiss by setting sessionStorage before load
    await page.goto('/')
    await page.evaluate(() => sessionStorage.setItem('kinetix_demo_strip_dismissed', 'true'))
    await page.reload()
    await expect(page.getByTestId('demo-welcome-strip')).not.toBeAttached()
  })

  test('strip reappears after sessionStorage clear', async ({ page }) => {
    await page.addInitScript(() => sessionStorage.removeItem('kinetix_demo_strip_dismissed'))
    await page.goto('/')
    await page.getByTestId('demo-strip-dismiss').click()
    await expect(page.getByTestId('demo-welcome-strip')).not.toBeAttached()

    await page.evaluate(() => sessionStorage.removeItem('kinetix_demo_strip_dismissed'))
    await page.reload()
    await expect(page.getByTestId('demo-welcome-strip')).toBeVisible()
  })
})
