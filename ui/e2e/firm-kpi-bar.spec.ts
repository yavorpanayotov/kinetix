import { test, expect, type Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Plan §4.3 (kx-71o) — Playwright assertion that the firm KPI bar shows
 * real numbers (not `$0.00`) once the bootstrap banner reports READY.
 *
 * The strip mounts above the tabs and is fed by:
 *   - `/api/v1/firm/summary`  → NAV and Unrealised P&L (via useHierarchySummary
 *     at the firm hierarchy level — the default landing selection)
 *   - `/api/v1/risk/var/<bookId>` → VaR value + nested Greeks (via useVaR;
 *     the bookId here is the auto-selected first book from useBookSelector
 *     because the strip needs a bookId-scoped VaR/Greeks fetch even when the
 *     hierarchy is at firm level).
 *
 * Both endpoints are mocked with realistic non-zero values. We also mock
 * `/demo/bootstrap-status` to flip from IN_PROGRESS → READY (mirrors
 * bootstrap-banner.spec.ts) so the banner detaches before we assert.
 *
 * Per the issue brief: we do NOT assert "no em-dash" — em-dash plus
 * "Calculating…" tooltip remains the correct fallback for genuinely-missing
 * aggregates (UX review).  But every cell named in the issue (NAV,
 * UNREALISED P&L, VAR 1D 95%, NET DELTA, NET VEGA) MUST render a non-zero
 * formatted number given the mocks below.
 */

// The strip's NAV cell renders unsigned currency (e.g. "$168,850.00").
// The unrealised-P&L cell renders signed currency for non-zero P&L
// (e.g. "+$3,050.00" or "-$1,200.00") via formatSignedMoney.
// The VaR cell renders unsigned currency.
// The delta/vega cells render plain numbers (e.g. "2,000.00") via formatNum,
// which may include a leading minus when aggregated greeks net negative.
//
// The combined regex allows an optional sign prefix (+/-) and an optional
// currency symbol so all five cell formats are accepted.
const NUMERIC_CELL = /^[+-]?[$£€]?[\d,]+(\.\d+)?[KMB]?$/

test.describe('Firm KPI bar (plan §4.3)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    // Ensure the bootstrap banner is not pre-dismissed by a previous run
    // sharing the same browser context.
    await page.addInitScript(() =>
      sessionStorage.removeItem('kinetix_bootstrap_banner_dismissed'),
    )
  })

  test('every KPI cell shows a real non-zero number once the platform is READY', async ({
    page,
  }) => {
    // --- 1. Bootstrap status: IN_PROGRESS twice, then READY ---------------
    let bootstrapCalls = 0
    await page.route('**/demo/bootstrap-status', (route: Route) => {
      bootstrapCalls += 1
      const body =
        bootstrapCalls <= 2
          ? {
              state: 'IN_PROGRESS',
              successCount: bootstrapCalls,
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

    // --- 2. Firm aggregate (NAV + Unrealised P&L) -------------------------
    // The strip uses useHierarchySummary({ level: 'firm' }) which calls
    // GET /api/v1/firm/summary. Override the fixture's default with a
    // distinctly-shaped realistic firm-level aggregate.
    await page.unroute('**/api/v1/firm/summary*')
    await page.route('**/api/v1/firm/summary*', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          bookId: 'firm',
          baseCurrency: 'USD',
          // ~$12.5M firm NAV — realistic for a multi-desk demo book.
          totalNav: { amount: '12500000.00', currency: 'USD' },
          // Strictly positive so formatSignedMoney prefixes a "+".
          totalUnrealizedPnl: { amount: '275450.50', currency: 'USD' },
          currencyBreakdown: [],
        }),
      })
    })

    // --- 3. Firm-level VaR + Greeks ---------------------------------------
    // useVaR(effectiveBookId) calls GET /api/v1/risk/var/<bookId>. The
    // fixtures default to 404, which would force the strip into em-dash
    // fallback — override with a realistic VaR result that nests Greeks.
    // The catch-all `**/api/v1/risk/**` in fixtures.ts is registered FIRST
    // (so it's lower priority than this later registration in Playwright's
    // last-registered-wins matching).
    await page.unroute('**/api/v1/risk/var/*')
    await page.route('**/api/v1/risk/var/*', (route: Route) => {
      // Only intercept GETs — POSTs (triggerVaRCalculation) hit the same
      // path and should fall through to the default 404.
      if (route.request().method() !== 'GET') {
        return route.fallback()
      }
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          bookId: 'port-1',
          varValue: '425000.75',
          expectedShortfall: '587500.20',
          confidenceLevel: 'CL_95',
          calculationType: 'PARAMETRIC',
          componentBreakdown: [
            { assetClass: 'EQUITY', varContribution: '275000.50', percentageOfTotal: '65' },
            { assetClass: 'FX', varContribution: '150000.25', percentageOfTotal: '35' },
          ],
          calculatedAt: '2026-05-26T10:30:00Z',
          greeks: {
            bookId: 'port-1',
            assetClassGreeks: [
              // Net delta = 1500 + 500 = 2000.00
              // Net vega  = 800 + 200  = 1000.00
              { assetClass: 'EQUITY', delta: '1500.00', gamma: '25.00', vega: '800.00' },
              { assetClass: 'FX', delta: '500.00', gamma: '10.00', vega: '200.00' },
            ],
            theta: '-350.00',
            rho: '120.00',
            calculatedAt: '2026-05-26T10:30:00Z',
          },
          pvValue: '12500000.00',
          computedOutputs: ['VAR', 'EXPECTED_SHORTFALL', 'GREEKS', 'PV'],
        }),
      })
    })

    // --- 4. Load and wait for bootstrap banner to detach ------------------
    await page.goto('/')

    // Banner must show up first while IN_PROGRESS is returned, then detach
    // once the third poll flips to READY. Per the issue brief, we use
    // state: 'detached' so the assertion survives both "hidden but mounted"
    // and "fully removed" implementations.
    await page.waitForSelector('[data-testid="bootstrap-banner"]', {
      state: 'detached',
      timeout: 20_000,
    })

    // --- 5. Assert every KPI cell shows a real non-zero number ------------
    const cellIds = [
      'ticker-nav',
      'ticker-unrealised-pnl',
      'ticker-var',
      'ticker-net-delta',
      'ticker-net-vega',
    ] as const

    for (const testId of cellIds) {
      const cell = page.getByTestId(testId)
      // Wait until the cell carries a non-em-dash, non-empty value before
      // pattern-matching — the strip's hooks resolve asynchronously even
      // after the banner detaches (firm summary + VaR fetches race the
      // bootstrap poll cadence).
      await expect(cell, `${testId} should be visible`).toBeVisible()
      await expect
        .poll(
          async () => (await cell.textContent())?.trim() ?? '',
          {
            message: `${testId} should resolve to a numeric value`,
            timeout: 15_000,
          },
        )
        .not.toBe('—')

      const text = (await cell.textContent())?.trim() ?? ''

      // The cell must look like a formatted currency/numeric value.
      expect(
        text,
        `${testId} text "${text}" should match numeric pattern ${NUMERIC_CELL}`,
      ).toMatch(NUMERIC_CELL)

      // And it must NOT be the zero sentinel — that's what plan §4.3 is
      // guarding against (the bug where the bar comes up reading $0.00
      // because demo data hasn't been seeded).
      expect(
        text,
        `${testId} text "${text}" must not be the zero sentinel`,
      ).not.toBe('$0.00')
    }
  })
})
