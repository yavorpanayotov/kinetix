import { test, expect, type Page, type Route } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockCounterpartyRiskRoutes,
  TEST_COUNTERPARTY_EXPOSURES,
} from './fixtures'

/**
 * kx-qfqn regression: the Counterparty Risk tab used to hang forever on
 * "Loading counterparty exposures..." when the gateway's trade-enrichment
 * fan-out was slow, because the UI fetch had no client-side timeout.
 *
 * These specs pin the three terminal states the tab must always reach:
 *   1. loaded rows render,
 *   2. an empty payload shows the empty state,
 *   3. a failing/hanging gateway resolves to the error banner — never a
 *      perpetual spinner.
 */

async function goToCounterpartyRiskTab(page: Page) {
  await page.goto('/')
  await page.getByTestId('tab-counterparty-risk').click()
}

test.describe('Counterparty Risk tab resilience (kx-qfqn)', () => {
  test('renders the counterparty rows when the gateway returns data', async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page, TEST_COUNTERPARTY_EXPOSURES)

    await goToCounterpartyRiskTab(page)

    await expect(page.getByTestId('counterparty-row-CP-GS')).toBeVisible()
    await expect(page.getByTestId('counterparty-row-CP-JPM')).toBeVisible()
    // The spinner must be gone once data has loaded.
    await expect(page.getByText('Loading counterparty exposures...')).not.toBeVisible()
  })

  test('shows the empty state when the gateway returns []', async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page, [])

    await goToCounterpartyRiskTab(page)

    await expect(page.getByTestId('counterparty-empty-state')).toBeVisible()
    await expect(page.getByText('Loading counterparty exposures...')).not.toBeVisible()
  })

  test('shows the error banner (not a stuck spinner) when the gateway hangs', async ({ page }) => {
    await mockAllApiRoutes(page)
    // Simulate a gateway that never produces a usable response: abort the
    // request the way a hung/failed connection would. Without a client-side
    // deadline the tab would spin forever; the fix surfaces the existing
    // error banner instead.
    await page.route('**/api/v1/counterparty-risk', (route: Route) => {
      if (route.request().method() === 'GET') {
        route.abort('connectionfailed')
      } else {
        route.continue()
      }
    })

    await goToCounterpartyRiskTab(page)

    await expect(page.getByTestId('counterparty-error')).toBeVisible()
    await expect(page.getByText('Loading counterparty exposures...')).not.toBeVisible()
  })
})
