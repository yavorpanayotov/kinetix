import { test, expect, type Page, type Route } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockCounterpartyRiskRoutes,
  mockRiskTabRoutes,
  mockEodTimelineRoutes,
  mockIntradayPnlRoutes,
  mockIntradayVaRTimelineRoutes,
  chatMockCanned,
  TEST_VAR_RESULT,
  TEST_POSITION_RISK_FULL,
  TEST_JOB_HISTORY,
  TEST_INTRADAY_PNL_SNAPSHOTS,
  TEST_INTRADAY_VAR_POINTS,
  TEST_INTRADAY_TRADE_ANNOTATIONS,
} from '../fixtures'

// ---------------------------------------------------------------------------
// Screenshot capture — drives the running UI with deterministic mocked routes
// (the same fixtures the e2e suite uses) and writes PNGs to docs/screenshots/.
//
// This is not a behavioural test; it is a documentation artefact generator for
// the wiki User Guide, case studies, and README. Re-run after a relevant UI
// change:
//   cd ui && npx playwright test e2e/screenshots/capture.spec.ts --project=chromium
//
// Filenames are deterministic so each run overwrites cleanly with no churn.
// ---------------------------------------------------------------------------

const SHOTS = '../docs/screenshots'

// Laptop framing for every shot.
test.use({ viewport: { width: 1440, height: 900 } })

const fulfillJson = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

// Load the app with the workspace pre-seeded so the saved view's default tab IS
// the tab we want to screenshot. App.tsx resets the active tab to the view's
// default once the workspace finishes loading async; without seeding, that reset
// can bounce a late-rendering tab back to Positions between assertion and
// capture. Seeding the default tab makes the reset a no-op for our target.
async function gotoApp(page: Page, defaultTab = 'positions', extraPrefs: Record<string, unknown> = {}, bookId: string | null = 'port-1') {
  await page.addInitScript(
    ({ tab, extra }) => {
      localStorage.setItem(
        'kinetix:workspace',
        JSON.stringify({
          version: 2,
          activeViewId: 'capture-view',
          views: [{ id: 'capture-view', name: 'Default', prefs: { defaultTab: tab, ...extra } }],
        }),
      )
    },
    { tab: defaultTab, extra: extraPrefs },
  )
  // Stub ONLY the price-stream socket so it reports "open" and never drops —
  // that keeps the "Reconnecting…/Disconnected" banner off every screenshot
  // (prices themselves come from the HTTP position mocks). Every other socket
  // (pnl, var, copilot) falls through to the real WebSocket, which fails fast
  // against the mock server and lets those hooks use their HTTP fallback data.
  await page.addInitScript(() => {
    const Original = window.WebSocket
    class FakeWS extends EventTarget {
      static CONNECTING = 0
      static OPEN = 1
      static CLOSING = 2
      static CLOSED = 3
      url: string
      readyState = 1
      onopen: ((e: Event) => void) | null = null
      onmessage: ((e: MessageEvent) => void) | null = null
      onclose: ((e: Event) => void) | null = null
      onerror: ((e: Event) => void) | null = null
      constructor(url: string | URL) {
        super()
        this.url = String(url)
        setTimeout(() => {
          this.readyState = 1
          const e = new Event('open')
          this.onopen?.(e)
          this.dispatchEvent(e)
        }, 0)
      }
      send() {}
      close() {
        this.readyState = 3
        const e = new Event('close')
        this.onclose?.(e)
        this.dispatchEvent(e)
      }
    }
    ;(window as unknown as Record<string, unknown>).WebSocket = function (url: string | URL, protocols?: string | string[]) {
      return String(url).includes('/ws/prices')
        ? new FakeWS(url)
        : new Original(url, protocols)
    } as unknown as typeof WebSocket
    Object.assign((window as unknown as Record<string, unknown>).WebSocket as object, {
      CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3,
    })
  })
  await page.goto('/')
  await page.waitForSelector('[data-testid="tab-positions"]')
  if (bookId) {
    // The default firm view aggregates to all-books, which leaves single-book
    // tabs (P&L, Regulatory, EOD, …) empty or racy. Deterministically select a
    // book via the command palette — its book command sets the hierarchy and
    // both book hooks at once. Pass bookId=null to stay at firm (the Risk
    // dashboard's cross-book view).
    await page.waitForTimeout(500)
    await page.keyboard.press('ControlOrMeta+k')
    await page.waitForSelector('[data-testid="command-palette"]')
    await page.getByTestId('command-palette-input').fill(bookId)
    await page.getByTestId(`command-palette-item-book:${bookId}`).click()
    await page.waitForSelector('[data-testid="ticker-nav"]', { timeout: 15000 })
  } else {
    await page.waitForTimeout(1500)
  }
  await page.waitForTimeout(600)
}

// ---------------------------------------------------------------------------
// Trading cluster
// ---------------------------------------------------------------------------

test.describe('UI screenshots — Trading', () => {
  test('positions tab', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)

    // Reveal the Details columns so the grid shows quantity / cost / price.
    await gotoApp(page, 'positions', { showPositionDetails: true, defaultBook: 'port-1' })
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    await page.screenshot({ path: `${SHOTS}/positions-tab.png`, fullPage: true })
  })

  test('trades — blotter', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)

    await gotoApp(page, 'trades', { defaultBook: 'port-1' })
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid="trade-row-trade-3"]')

    await page.screenshot({ path: `${SHOTS}/trades-blotter.png`, fullPage: true })
  })

  test('trades — place order', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)

    await gotoApp(page, 'trades', { defaultBook: 'port-1' })
    await page.getByTestId('tab-trades').click()
    await page.getByTestId('trades-subtab-place').click()
    await expect(page.getByTestId('place-order-instrument')).toBeVisible()

    await page.screenshot({ path: `${SHOTS}/trades-place-order.png`, fullPage: true })
  })

  test('trades — execution cost', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await page.route('**/api/v1/execution/cost/**', (route: Route) =>
      fulfillJson(route, [
        {
          orderId: 'ord-e2e-001', bookId: 'port-1', instrumentId: 'AAPL', side: 'BUY',
          completedAt: '2026-03-24T15:00:00Z', arrivalPrice: '150.00', avgFillPrice: '150.18',
          quantityFilled: '1000', slippageBps: '12.0', marketImpactBps: '6.4', timingCostBps: '2.1', totalCostBps: '20.5',
        },
        {
          orderId: 'ord-e2e-002', bookId: 'port-1', instrumentId: 'GOOGL', side: 'SELL',
          completedAt: '2026-03-24T15:30:00Z', arrivalPrice: '2850.00', avgFillPrice: '2848.10',
          quantityFilled: '200', slippageBps: '-6.7', marketImpactBps: '3.0', timingCostBps: '1.2', totalCostBps: '-2.5',
        },
      ]),
    )

    await gotoApp(page, 'trades', { defaultBook: 'port-1' })
    await page.getByTestId('tab-trades').click()
    await page.getByTestId('trades-subtab-cost').click()
    await page.waitForSelector('[data-testid="cost-row-ord-e2e-001"]')

    await page.screenshot({ path: `${SHOTS}/trades-execution-cost.png`, fullPage: true })
  })

  test('trades — reconciliation', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await page.route('**/api/v1/execution/reconciliation/**', (route: Route) =>
      fulfillJson(route, [
        {
          reconciliationDate: '2026-03-24', bookId: 'port-1', status: 'BREAKS',
          totalPositions: 3, matchedCount: 2, breakCount: 1,
          breaks: [
            { instrumentId: 'GOOGL', internalQuantity: '50', pbQuantity: '45', breakQuantity: '5', breakNotional: '14250.00' },
          ],
          reconciledAt: '2026-03-24T18:00:00Z',
        },
        {
          reconciliationDate: '2026-03-23', bookId: 'port-1', status: 'CLEAN',
          totalPositions: 3, matchedCount: 3, breakCount: 0, breaks: [], reconciledAt: '2026-03-23T18:00:00Z',
        },
      ]),
    )

    await gotoApp(page, 'trades', { defaultBook: 'port-1' })
    await page.getByTestId('tab-trades').click()
    await page.getByTestId('trades-subtab-reconciliation').click()
    await page.waitForSelector('[data-testid="recon-row-2026-03-24"]')

    await page.screenshot({ path: `${SHOTS}/trades-reconciliation.png`, fullPage: true })
  })

  test('pnl tab', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await mockIntradayPnlRoutes(page, 'port-1', TEST_INTRADAY_PNL_SNAPSHOTS)
    // SOD baseline exists + computed attribution so the waterfall and factor
    // table render (not the "set a baseline" empty state).
    await page.route('**/api/v1/risk/sod-snapshot/*/status', (route: Route) =>
      fulfillJson(route, {
        exists: true, baselineDate: '2026-05-19', snapshotType: 'MANUAL',
        createdAt: '2026-05-19T09:30:00Z', sourceJobId: 'job-sod-1', calculationType: 'PARAMETRIC',
      }),
    )
    const pnlData = {
      totalPnl: '15250.00', deltaPnl: '8500.00', gammaPnl: '2200.00', vegaPnl: '1800.00', thetaPnl: '-1500.00', rhoPnl: '750.00', unexplainedPnl: '3500.00',
      positionAttributions: [
        { instrumentId: 'AAPL', assetClass: 'EQUITY', totalPnl: '9000.00', deltaPnl: '5500.00', gammaPnl: '1400.00', vegaPnl: '1100.00', thetaPnl: '-900.00', rhoPnl: '400.00', unexplainedPnl: '1500.00' },
        { instrumentId: 'GOOGL', assetClass: 'EQUITY', totalPnl: '4250.00', deltaPnl: '2500.00', gammaPnl: '600.00', vegaPnl: '500.00', thetaPnl: '-400.00', rhoPnl: '250.00', unexplainedPnl: '800.00' },
        { instrumentId: 'EUR_USD', assetClass: 'FX', totalPnl: '2000.00', deltaPnl: '500.00', gammaPnl: '200.00', vegaPnl: '200.00', thetaPnl: '-200.00', rhoPnl: '100.00', unexplainedPnl: '1200.00' },
      ],
    }
    await page.route('**/api/v1/risk/pnl-attribution/**', (route: Route) => fulfillJson(route, pnlData))

    await gotoApp(page, 'pnl')
    // Compute attribution if the tab is showing the compute prompt, then wait
    // for the populated attribution table.
    const computeBtn = page.getByTestId('pnl-compute-button')
    if (await computeBtn.count()) await computeBtn.click()
    await page.waitForSelector('[data-testid="attribution-table"]', { timeout: 10000 })
    await page.waitForTimeout(400)

    await page.screenshot({ path: `${SHOTS}/pnl-tab.png`, fullPage: true })
  })
})

// ---------------------------------------------------------------------------
// Risk cluster
// ---------------------------------------------------------------------------

test.describe('UI screenshots — Risk', () => {
  test('risk — dashboard', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    // Two books so the firm-level view aggregates; cross-book VaR mocked so the
    // portfolio dashboard renders with its diversification benefit.
    await page.route('**/api/v1/books', (route: Route) => fulfillJson(route, [{ bookId: 'port-1' }, { bookId: 'port-2' }]))
    await mockRiskTabRoutes(page, {
      varResult: TEST_VAR_RESULT,
      positionRisk: TEST_POSITION_RISK_FULL,
      jobHistory: TEST_JOB_HISTORY,
    })
    const crossBook = {
      portfolioGroupId: 'firm', bookIds: ['port-1', 'port-2'], calculationType: 'PARAMETRIC', confidenceLevel: 'CL_95',
      varValue: '200000.00', expectedShortfall: '300000.00',
      componentBreakdown: [
        { assetClass: 'EQUITY', varContribution: '120000.00', percentageOfTotal: '60.00' },
        { assetClass: 'FIXED_INCOME', varContribution: '80000.00', percentageOfTotal: '40.00' },
      ],
      bookContributions: [
        { bookId: 'port-1', varContribution: '130000.00', percentageOfTotal: '65.00', standaloneVar: '150000.00', diversificationBenefit: '20000.00', marginalVar: '0.866667', incrementalVar: '140000.00' },
        { bookId: 'port-2', varContribution: '70000.00', percentageOfTotal: '35.00', standaloneVar: '100000.00', diversificationBenefit: '30000.00', marginalVar: '0.700000', incrementalVar: '80000.00' },
      ],
      totalStandaloneVar: '250000.00', diversificationBenefit: '50000.00', calculatedAt: '2026-05-19T12:00:00Z',
    }
    await page.route('**/api/v1/risk/var/cross-book', (route: Route) =>
      route.request().method() === 'POST' ? fulfillJson(route, crossBook) : route.fallback())
    await page.route('**/api/v1/risk/var/cross-book/*', (route: Route) => fulfillJson(route, crossBook))

    // bookId=null: stay at firm level so the aggregated cross-book dashboard renders.
    await gotoApp(page, 'risk', {}, null)
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="var-dashboard"]')
    // Wait for the cross-book result to load (diversification summary), not just
    // the container, so we don't capture the "sum of book VaRs" fallback.
    await page.waitForSelector('[data-testid="diversification-summary"]', { timeout: 10000 })
    await page.waitForTimeout(400)

    await page.screenshot({ path: `${SHOTS}/risk-dashboard.png`, fullPage: true })
  })

  test('risk — intraday', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await mockIntradayVaRTimelineRoutes(page, 'port-1', TEST_INTRADAY_VAR_POINTS, TEST_INTRADAY_TRADE_ANNOTATIONS)

    await gotoApp(page, 'risk', { defaultBook: 'port-1' })
    await page.getByTestId('tab-risk').click()
    await page.getByTestId('risk-subtab-intraday').click()
    await expect(page.getByTestId('intraday-var-panel')).toBeVisible()
    await page.waitForSelector('[data-testid="intraday-var-chart"]')

    await page.screenshot({ path: `${SHOTS}/risk-intraday.png`, fullPage: true })
  })

  test('risk — market data (vol surface)', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    // The instrument selector is populated from position-risk data.
    await page.unroute('**/api/v1/risk/positions/*')
    await page.route('**/api/v1/risk/positions/*', (route: Route) => fulfillJson(route, TEST_POSITION_RISK_FULL))
    // Wildcard 404 first; AAPL-specific route registered after so it wins.
    await page.route('**/api/v1/volatility/*/surface', (route: Route) => fulfillJson(route, null, 404))
    await page.route('**/api/v1/volatility/AAPL/surface', (route: Route) =>
      fulfillJson(route, {
        instrumentId: 'AAPL', asOfDate: '2026-03-25T10:00:00Z', source: 'BLOOMBERG',
        points: [
          { strike: 140, maturityDays: 30, impliedVol: 0.32 }, { strike: 150, maturityDays: 30, impliedVol: 0.28 }, { strike: 160, maturityDays: 30, impliedVol: 0.25 },
          { strike: 140, maturityDays: 90, impliedVol: 0.34 }, { strike: 150, maturityDays: 90, impliedVol: 0.30 }, { strike: 160, maturityDays: 90, impliedVol: 0.27 },
          { strike: 140, maturityDays: 180, impliedVol: 0.36 }, { strike: 150, maturityDays: 180, impliedVol: 0.32 }, { strike: 160, maturityDays: 180, impliedVol: 0.29 },
        ],
      }),
    )
    await page.route('**/api/v1/rates/yield-curves/**', (route: Route) =>
      fulfillJson(route, {
        curveId: 'GBP', currency: 'GBP', asOfDate: '2026-03-25T10:00:00Z',
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
      }),
    )

    await gotoApp(page, 'risk', { defaultBook: 'port-1' })
    await page.getByTestId('tab-risk').click()
    await page.getByTestId('risk-subtab-market-data').click()
    await page.waitForSelector('[data-testid="vol-surface-panel"]')
    await page.getByTestId('instrument-selector').selectOption('AAPL')
    await page.getByTestId('vol-skew-chart').waitFor({ state: 'visible' })

    await page.screenshot({ path: `${SHOTS}/risk-market-data.png`, fullPage: true })
  })

  test('eod history', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await mockEodTimelineRoutes(page)

    await gotoApp(page, 'eod', { defaultBook: 'port-1' })
    await page.getByTestId('tab-eod').click()
    await page.waitForSelector('[data-testid="eod-trend-chart"]')

    await page.screenshot({ path: `${SHOTS}/eod-history.png`, fullPage: true })
  })

  test('scenarios tab', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    const equityCrash = {
      scenarioName: 'EQUITY_CRASH', baseVar: '125000', stressedVar: '375000', pnlImpact: '-250000',
      assetClassImpacts: [], positionImpacts: [],
      limitBreaches: [{ limitType: 'VAR', limitLevel: 'FIRM', limitValue: '300000', stressedValue: '375000', breachSeverity: 'BREACHED', scenarioName: 'EQUITY_CRASH' }],
      stressedGreeks: null, calculatedAt: '2026-05-19T12:00:00Z',
    }
    const ratesShock = {
      scenarioName: 'RATES_SHOCK', baseVar: '125000', stressedVar: '200000', pnlImpact: '-75000',
      assetClassImpacts: [], positionImpacts: [], limitBreaches: [], stressedGreeks: null, calculatedAt: '2026-05-19T12:01:00Z',
    }
    await page.unroute('**/api/v1/risk/stress/scenarios')
    await page.route('**/api/v1/risk/stress/scenarios', (route: Route) => fulfillJson(route, ['EQUITY_CRASH', 'RATES_SHOCK']))
    await page.route('**/api/v1/risk/stress/*/batch', (route: Route) => {
      if (route.request().method() === 'POST') {
        fulfillJson(route, { results: [equityCrash, ratesShock], failedScenarios: [], worstScenarioName: 'EQUITY_CRASH', worstPnlImpact: '-250000' })
      } else {
        fulfillJson(route, null, 404)
      }
    })

    await gotoApp(page, 'scenarios', { defaultBook: 'port-1' })
    await page.getByTestId('tab-scenarios').click()
    await page.waitForSelector('[data-testid="run-all-btn"]')
    await page.getByTestId('run-all-btn').click()
    await page.waitForSelector('[data-testid="scenario-comparison-table"]')

    await page.screenshot({ path: `${SHOTS}/scenarios-tab.png`, fullPage: true })
  })

  test('counterparty-risk tab', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page)

    await gotoApp(page, 'counterparty-risk', { defaultBook: 'port-1' })
    await page.getByTestId('tab-counterparty-risk').click()
    await expect(page.getByTestId('counterparty-risk-dashboard')).toBeVisible()
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await page.screenshot({ path: `${SHOTS}/counterparty-risk-tab.png`, fullPage: true })
  })
})

// ---------------------------------------------------------------------------
// Operations cluster
// ---------------------------------------------------------------------------

test.describe('UI screenshots — Operations', () => {
  test('regulatory — frtb', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await page.route('**/api/v1/regulatory/frtb/**', (route: Route) =>
      fulfillJson(route, {
        bookId: 'port-1',
        sbmCharges: [
          { riskClass: 'GIRR', deltaCharge: '19.86', vegaCharge: '180532.51', curvatureCharge: '2030.99', totalCharge: '182583.36' },
          { riskClass: 'CSR_NON_SEC', deltaCharge: '16247.93', vegaCharge: '7221.30', curvatureCharge: '243.72', totalCharge: '23712.95' },
          { riskClass: 'CSR_SEC_CTP', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
          { riskClass: 'CSR_SEC_NON_CTP', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
          { riskClass: 'EQUITY', deltaCharge: '227231.14', vegaCharge: '127817.52', curvatureCharge: '22723.11', totalCharge: '377771.78' },
          { riskClass: 'COMMODITY', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
          { riskClass: 'FX', deltaCharge: '41869.98', vegaCharge: '26796.79', curvatureCharge: '2093.50', totalCharge: '70760.27' },
        ],
        totalSbmCharge: '635674.38', grossJtd: '162486.91', hedgeBenefit: '3.83', netDrc: '162483.09',
        exoticNotional: '850.20', otherNotional: '27919327.83', totalRrao: '27927.83',
        totalCapitalCharge: '826085.29', calculatedAt: '2026-05-19T13:22:48Z',
      }),
    )

    await gotoApp(page, 'regulatory', { defaultBook: 'port-1' })
    await page.getByTestId('tab-regulatory').click()
    await expect(page.getByTestId('regulatory-dashboard')).toBeVisible()
    await page.getByTestId('frtb-calculate-btn').click()
    await page.waitForSelector('[data-testid="regulatory-results"]')

    await page.screenshot({ path: `${SHOTS}/regulatory-frtb.png`, fullPage: true })
  })

  test('reports tab', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    // The recent-reports endpoint must return an array (the catch-all stub does not).
    await page.route('**/api/v1/reports/recent**', (route: Route) =>
      fulfillJson(route, [
        { outputId: 'rep-001', templateId: 'Daily Risk Summary', user: 'alice', timestamp: '2026-05-19T17:30:00Z', status: 'COMPLETE', rowCount: 128, downloadUrl: '/api/v1/reports/rep-001/csv' },
        { outputId: 'rep-002', templateId: 'FRTB Capital', user: 'bob', timestamp: '2026-05-19T16:05:00Z', status: 'COMPLETE', rowCount: 64, downloadUrl: '/api/v1/reports/rep-002/csv' },
        { outputId: 'rep-003', templateId: 'P&L Attribution', user: 'carol', timestamp: '2026-05-19T15:40:00Z', status: 'RUNNING', rowCount: 0, downloadUrl: '' },
      ]),
    )

    await gotoApp(page, 'reports', { defaultBook: 'port-1' })
    await page.getByTestId('tab-reports').click()
    await page.waitForSelector('[data-testid="report-template-select"]')

    await page.screenshot({ path: `${SHOTS}/reports-tab.png`, fullPage: true })
  })

  test('activity — audit trail', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    const base = {
      id: 0, tradeId: null, bookId: 'port-1', instrumentId: 'AAPL', assetClass: 'EQUITY', side: 'BUY',
      quantity: '100', priceAmount: '150.00', priceCurrency: 'USD', tradedAt: '2026-05-19T10:00:00Z',
      receivedAt: '2026-05-19T10:00:01Z', previousHash: 'prev', recordHash: 'hash', userId: 'alice', userRole: 'TRADER',
      eventType: 'TRADE_BOOKED', modelName: null, scenarioId: null, limitId: null, submissionId: null, details: null, sequenceNumber: 1,
    }
    const events = [
      { ...base, id: 301, tradeId: 'trade-301', recordHash: 'hash-301', eventType: 'TRADE_BOOKED' },
      { ...base, id: 302, tradeId: null, limitId: 'LIM-7', recordHash: 'hash-302', eventType: 'LIMIT_BREACH', userRole: 'RISK_MANAGER' },
      { ...base, id: 303, tradeId: null, modelName: 'VAR-EOD', recordHash: 'hash-303', eventType: 'RUN_PROMOTED', userId: 'bob', userRole: 'RISK_MANAGER' },
      { ...base, id: 304, tradeId: null, bookId: 'port-2', recordHash: 'hash-304', eventType: 'SCENARIO_APPROVED', userId: 'carol', userRole: 'COMPLIANCE' },
    ]
    await page.route('**/api/v1/audit/events**', (route: Route) => fulfillJson(route, events))
    await page.route('**/api/v1/audit/verify', (route: Route) => fulfillJson(route, { valid: true, eventCount: events.length }))

    await gotoApp(page, 'activity', { defaultBook: 'port-1' })
    await page.getByTestId('tab-activity').click()
    await expect(page.getByTestId('audit-log-panel')).toBeVisible()
    await page.waitForSelector('[data-testid="audit-event-row"]', { state: 'visible' })

    await page.screenshot({ path: `${SHOTS}/activity-audit.png`, fullPage: true })
  })

  test('alerts tab', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    const now = Date.now()
    const alerts = [
      { id: 'a-crit', ruleId: 'r1', ruleName: 'VaR Critical', type: 'VAR_BREACH', severity: 'CRITICAL', message: 'Firm VaR limit breached on port-1', currentValue: 375000, threshold: 300000, bookId: 'port-1', triggeredAt: new Date(now - 20 * 60 * 1000).toISOString(), status: 'TRIGGERED' },
      { id: 'a-warn', ruleId: 'r2', ruleName: 'P&L Warning', type: 'PNL_THRESHOLD', severity: 'WARNING', message: 'Intraday P&L below warning threshold', currentValue: -180000, threshold: -150000, bookId: 'port-1', triggeredAt: new Date(now - 45 * 60 * 1000).toISOString(), status: 'TRIGGERED' },
      { id: 'a-info', ruleId: 'r3', ruleName: 'Concentration notice', type: 'CONCENTRATION', severity: 'INFO', message: 'Single-name concentration above 40%', currentValue: 0.42, threshold: 0.4, bookId: 'port-2', triggeredAt: new Date(now - 90 * 60 * 1000).toISOString(), status: 'ACKNOWLEDGED' },
    ]
    await page.unroute('**/api/v1/notifications/alerts*')
    await page.route('**/api/v1/notifications/alerts*', (route: Route) => fulfillJson(route, alerts))

    await gotoApp(page, 'alerts', { defaultBook: 'port-1' })
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.screenshot({ path: `${SHOTS}/alerts-tab.png`, fullPage: true })
  })

  test('system dashboard', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await page.route('**/api/v1/system/health', (route: Route) =>
      fulfillJson(route, {
        status: 'UP',
        services: {
          gateway: { status: 'READY' },
          'position-service': { status: 'READY' },
          'price-service': { status: 'READY' },
          'risk-orchestrator': { status: 'READY' },
          'notification-service': { status: 'READY' },
          'rates-service': { status: 'READY' },
          'reference-data-service': { status: 'READY' },
          'volatility-service': { status: 'READY' },
          'correlation-service': { status: 'READY' },
          'regulatory-service': { status: 'READY' },
          'audit-service': { status: 'READY' },
        },
      }),
    )

    await gotoApp(page, 'system', { defaultBook: 'port-1' })
    await page.getByTestId('tab-system').click()
    await page.waitForSelector('[data-testid="service-health-grid"]')

    await page.screenshot({ path: `${SHOTS}/system-dashboard.png`, fullPage: true })
  })
})

// ---------------------------------------------------------------------------
// AI copilot
// ---------------------------------------------------------------------------

test.describe('UI screenshots — Copilot', () => {
  test('copilot narrative with citation', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await chatMockCanned(page)

    await gotoApp(page)

    await page.getByTestId('copilot-launcher').click()
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    await input.fill('Why did my VaR move?')
    await page.keyboard.press('Enter')

    await expect(page.getByTestId('streaming-narrative-text')).toBeVisible()
    await expect(page.getByTestId('citation-list')).toBeVisible()
    await expect(page.getByTestId('citation-list-item').first()).toBeVisible()

    // The copilot palette is a fixed-position overlay — capture the viewport
    // (not full page) so the modal is in frame.
    await page.screenshot({ path: `${SHOTS}/copilot-narrative.png`, fullPage: false })
  })
})
