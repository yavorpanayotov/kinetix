import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes, mockHedgeSuggest, TEST_HEDGE_RECOMMENDATION } from './fixtures'

const VAR_BREACH_RULE = {
  id: 'rule-var-breach',
  name: 'VaR Breach',
  type: 'VAR_BREACH',
  threshold: 100_000,
  operator: 'GREATER_THAN',
  severity: 'HIGH',
  channels: [],
  enabled: true,
}

const SAMPLE_VAR_BREACH = {
  bookId: 'port-1',
  varValue: '90000.00', // 90% of 100k > 80% threshold
  expectedShortfall: '95000.00',
  confidenceLevel: 'CL_95',
  calculationType: 'PARAMETRIC',
  componentBreakdown: [],
  calculatedAt: '2026-03-24T12:00:00Z',
  greeks: {
    bookId: 'port-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '1000', gamma: '5', vega: '200' },
    ],
    theta: '-30',
    rho: '12',
    calculatedAt: '2026-03-24T12:00:00Z',
  },
}

async function overrideRulesRoute(page: Page, rules: object[]) {
  await page.unroute('**/api/v1/notifications/rules')
  await page.route('**/api/v1/notifications/rules', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(rules),
    })
  })
}

async function overrideVarRoute(page: Page, varResult: object) {
  await page.unroute('**/api/v1/risk/var/*')
  await page.route('**/api/v1/risk/var/*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(varResult),
    })
  })
}

/**
 * Navigate to the Risk tab with a specific book selected so that
 * aggregatedView is false and the "Suggest Hedge" button is visible.
 *
 * mockAllApiRoutes returns [] for divisions, keeping the hierarchy at
 * firm level. We override that here (registering AFTER mockAllApiRoutes
 * so Playwright's LIFO handler ordering picks our handler first) with a
 * single division and desk, then navigate the hierarchy to port-1.
 */
async function goToBookRiskTab(page: Page) {
  // Override divisions — Playwright picks the last-registered handler first.
  await page.route('**/api/v1/divisions', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: 'div-1', name: 'Equities', description: '', deskCount: 1 }]),
    })
  })
  await page.route('**/api/v1/divisions/div-1/desks', (route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: 'desk-1', name: 'EU Equities', divisionId: 'div-1', deskHead: 'Alice', bookCount: 1 }]),
    })
  })

  await page.goto('/')

  // Open the hierarchy selector and navigate to port-1
  await page.getByTestId('hierarchy-selector-toggle').click()
  await page.waitForSelector('[data-testid="hierarchy-division-div-1"]')
  await page.getByTestId('hierarchy-division-div-1').click()
  await page.waitForSelector('[data-testid="hierarchy-desk-desk-1"]')
  await page.getByTestId('hierarchy-desk-desk-1').click()
  await page.waitForSelector('[data-testid="hierarchy-book-port-1"]')
  await page.getByTestId('hierarchy-book-port-1').click()

  // Now navigate to the Risk tab
  await page.getByTestId('tab-risk').click()
}

test.describe('Hedge Recommendation Panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockHedgeSuggest(page)
  })

  // ---------------------------------------------------------------------------
  // Panel open/close
  // ---------------------------------------------------------------------------

  test('opens panel when Suggest Hedge button is clicked', async ({ page }) => {
    await goToBookRiskTab(page)
    await page.waitForSelector('[data-testid="suggest-hedge-open-button"]')
    await page.getByTestId('suggest-hedge-open-button').click()
    await expect(page.getByTestId('hedge-recommendation-panel')).toBeVisible()
  })

  test('opens panel with Shift+H keyboard shortcut', async ({ page }) => {
    await goToBookRiskTab(page)
    await page.waitForSelector('[data-testid="suggest-hedge-open-button"]')
    await page.keyboard.press('Shift+H')
    await expect(page.getByTestId('hedge-recommendation-panel')).toBeVisible()
  })

  test('closes panel when close button is clicked', async ({ page }) => {
    await goToBookRiskTab(page)
    await page.waitForSelector('[data-testid="suggest-hedge-open-button"]')
    await page.getByTestId('suggest-hedge-open-button').click()
    await expect(page.getByTestId('hedge-recommendation-panel')).toBeVisible()
    await page.getByLabel('Close hedge recommendation panel').click()
    await expect(page.getByTestId('hedge-recommendation-panel')).not.toBeVisible()
  })

  // ---------------------------------------------------------------------------
  // Form interaction and suggestion display
  // ---------------------------------------------------------------------------

  test('displays empty state before first suggestion request', async ({ page }) => {
    await goToBookRiskTab(page)
    await page.waitForSelector('[data-testid="suggest-hedge-open-button"]')
    await page.getByTestId('suggest-hedge-open-button').click()
    await expect(page.getByTestId('empty-state')).toBeVisible()
  })

  test('submits form and shows suggestion results', async ({ page }) => {
    await goToBookRiskTab(page)
    await page.waitForSelector('[data-testid="suggest-hedge-open-button"]')
    await page.getByTestId('suggest-hedge-open-button').click()

    await page.getByTestId('suggest-hedge-button').click()

    await expect(page.getByTestId('suggestion-1')).toBeVisible()
    await expect(page.getByTestId('suggestion-instrument-1')).toContainText('AAPL-P-2026')
  })

  test('shows correct side for the suggestion', async ({ page }) => {
    await goToBookRiskTab(page)
    await page.waitForSelector('[data-testid="suggest-hedge-open-button"]')
    await page.getByTestId('suggest-hedge-open-button').click()
    await page.getByTestId('suggest-hedge-button').click()

    await expect(page.getByTestId('suggestion-side-1')).toContainText('BUY')
  })

  test('shows reduction percentage for the suggestion', async ({ page }) => {
    await goToBookRiskTab(page)
    await page.waitForSelector('[data-testid="suggest-hedge-open-button"]')
    await page.getByTestId('suggest-hedge-open-button').click()
    await page.getByTestId('suggest-hedge-button').click()

    // 95% reduction
    await expect(page.getByTestId('suggestion-reduction-1')).toContainText('95')
  })

  test('shows STALE badge for stale price data', async ({ page }) => {
    const staleRec = {
      ...TEST_HEDGE_RECOMMENDATION,
      suggestions: [
        {
          ...TEST_HEDGE_RECOMMENDATION.suggestions[0],
          dataQuality: 'STALE',
        },
      ],
    }
    await mockHedgeSuggest(page, staleRec)

    await goToBookRiskTab(page)
    await page.waitForSelector('[data-testid="suggest-hedge-open-button"]')
    await page.getByTestId('suggest-hedge-open-button').click()
    await page.getByTestId('suggest-hedge-button').click()

    await expect(page.getByTestId('stale-badge-1')).toBeVisible()
  })

  test('shows Greek impact table when expanded (Lyapunov problem visible)', async ({ page }) => {
    await goToBookRiskTab(page)
    await page.waitForSelector('[data-testid="suggest-hedge-open-button"]')
    await page.getByTestId('suggest-hedge-open-button').click()
    await page.getByTestId('suggest-hedge-button').click()

    await page.getByText('Show Greek impact').click()

    // Greek impact table shows Before/After columns; Gamma increases from 200 to 210
    await expect(page.getByTestId('greek-impact-table').getByText('Gamma')).toBeVisible()
  })

  // ---------------------------------------------------------------------------
  // Need a hedge CTA on breach (plan §8.2)
  // ---------------------------------------------------------------------------

  test('need a hedge cta in the ticker strip opens the hedge panel on VaR breach', async ({ page }) => {
    // Activate a VaR breach so the ticker strip renders the red CTA.
    await overrideRulesRoute(page, [VAR_BREACH_RULE])
    await overrideVarRoute(page, SAMPLE_VAR_BREACH)

    await goToBookRiskTab(page)

    // Switch back to Positions to prove the ticker CTA is globally
    // reachable (the breach banner also renders on Positions, so we click
    // by stable test id to avoid ambiguity).
    await page.getByTestId('tab-positions').click()

    const cta = page.getByTestId('ticker-hedge-cta')
    await expect(cta).toBeVisible({ timeout: 5000 })
    await expect(cta).toHaveText(/need a hedge/i)

    await cta.click()

    await expect(page.getByTestId('hedge-recommendation-panel')).toBeVisible()
  })

  test('need a hedge cta in the breach banner opens the hedge panel', async ({ page }) => {
    await overrideRulesRoute(page, [VAR_BREACH_RULE])
    await overrideVarRoute(page, SAMPLE_VAR_BREACH)

    await goToBookRiskTab(page)

    const bannerCta = page.getByTestId('breach-banner-hedge-cta')
    await expect(bannerCta).toBeVisible({ timeout: 5000 })
    await expect(bannerCta).toHaveText(/need a hedge/i)

    await bannerCta.click()

    await expect(page.getByTestId('hedge-recommendation-panel')).toBeVisible()
  })
})
