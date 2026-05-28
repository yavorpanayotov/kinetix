import type { Page, Route } from '@playwright/test'

export interface PositionFixture {
  bookId: string
  instrumentId: string
  displayName?: string
  instrumentType?: string
  assetClass: string
  quantity: string
  averageCost: { amount: string; currency: string }
  marketPrice: { amount: string; currency: string }
  marketValue: { amount: string; currency: string }
  unrealizedPnl: { amount: string; currency: string }
}

export const TEST_BOOKS = [{ bookId: 'port-1' }]

export const TEST_POSITIONS: PositionFixture[] = [
  {
    bookId: 'port-1',
    instrumentId: 'AAPL',
    displayName: 'Apple Inc',
    instrumentType: 'STOCK',
    assetClass: 'EQUITY',
    quantity: '100',
    averageCost: { amount: '150.00', currency: 'USD' },
    marketPrice: { amount: '155.00', currency: 'USD' },
    marketValue: { amount: '15500.00', currency: 'USD' },
    unrealizedPnl: { amount: '500.00', currency: 'USD' },
  },
  {
    bookId: 'port-1',
    instrumentId: 'GOOGL',
    displayName: 'Alphabet Inc',
    instrumentType: 'STOCK',
    assetClass: 'EQUITY',
    quantity: '50',
    averageCost: { amount: '2800.00', currency: 'USD' },
    marketPrice: { amount: '2850.00', currency: 'USD' },
    marketValue: { amount: '142500.00', currency: 'USD' },
    unrealizedPnl: { amount: '2500.00', currency: 'USD' },
  },
  {
    bookId: 'port-1',
    instrumentId: 'EUR_USD',
    displayName: 'Euro/US Dollar',
    instrumentType: 'FX_SPOT',
    assetClass: 'FX',
    quantity: '10000',
    averageCost: { amount: '1.0800', currency: 'USD' },
    marketPrice: { amount: '1.0850', currency: 'USD' },
    marketValue: { amount: '10850.00', currency: 'USD' },
    unrealizedPnl: { amount: '50.00', currency: 'USD' },
  },
]

export interface TradeFixture {
  tradeId: string
  bookId: string
  instrumentId: string
  assetClass: string
  side: 'BUY' | 'SELL'
  quantity: string
  price: { amount: string; currency: string }
  tradedAt: string
  status?: string
  counterpartyId?: string
}

export const TEST_TRADES: TradeFixture[] = [
  {
    tradeId: 'trade-1',
    bookId: 'port-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '100',
    price: { amount: '150.00', currency: 'USD' },
    tradedAt: '2025-01-15T10:30:00Z',
    status: 'LIVE',
  },
  {
    tradeId: 'trade-2',
    bookId: 'port-1',
    instrumentId: 'GOOGL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '50',
    price: { amount: '2800.00', currency: 'USD' },
    tradedAt: '2025-01-15T11:00:00Z',
    status: 'CANCELLED',
  },
  {
    tradeId: 'trade-3',
    bookId: 'port-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    side: 'SELL',
    quantity: '25',
    price: { amount: '155.00', currency: 'USD' },
    tradedAt: '2025-01-15T14:00:00Z',
    status: 'AMENDED',
  },
]

export const DATA_QUALITY_STATUS = {
  overall: 'OK',
  checks: [
    {
      name: 'Price Staleness',
      status: 'OK',
      message: 'All prices updated within 5 minutes',
      lastChecked: '2025-01-15T10:00:00Z',
    },
  ],
}

export const TEST_REGIME_NORMAL = {
  regime: 'NORMAL',
  isConfirmed: true,
  confidence: 0.92,
  consecutiveObservations: 0,
  detectedAt: '2026-03-24T10:00:00Z',
  degradedInputs: false,
  signals: {
    realisedVol20d: 0.10,
    crossAssetCorrelation: 0.40,
    creditSpreadBps: null,
    pnlVolatility: null,
  },
  varParameters: {
    calculationType: 'PARAMETRIC',
    confidenceLevel: 'CL_95',
    timeHorizonDays: 1,
    correlationMethod: 'standard',
    numSimulations: null,
  },
}

export const TEST_REGIME_CRISIS = {
  regime: 'CRISIS',
  isConfirmed: true,
  confidence: 0.87,
  consecutiveObservations: 3,
  detectedAt: '2026-03-24T14:30:00Z',
  degradedInputs: false,
  signals: {
    realisedVol20d: 0.28,
    crossAssetCorrelation: 0.80,
    creditSpreadBps: 220.0,
    pnlVolatility: 0.07,
  },
  varParameters: {
    calculationType: 'MONTE_CARLO',
    confidenceLevel: 'CL_99',
    timeHorizonDays: 5,
    correlationMethod: 'stressed',
    numSimulations: 50000,
  },
}

export async function mockRegimeRoutes(
  page: Page,
  regime: object = TEST_REGIME_NORMAL,
): Promise<void> {
  await page.unroute('**/api/v1/risk/regime/current')
  await page.route('**/api/v1/risk/regime/current', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(regime),
    })
  })
}

/**
 * Mocks the active demo scenario endpoint used by `useActiveScenario`.
 * Pass a scenario name (e.g. 'stress', 'multi-asset') to simulate a demo run,
 * or `null` to simulate "no active scenario".
 */
export async function mockActiveScenario(
  page: Page,
  scenario: string | null,
): Promise<void> {
  await page.unroute('**/api/v1/demo/scenario')
  await page.route('**/api/v1/demo/scenario', (route: Route) => {
    if (scenario === null) {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ scenario: null }),
      })
    } else {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ scenario }),
      })
    }
  })
}

export const TEST_REPORT_TEMPLATES = [
  {
    templateId: 'tpl-risk-summary',
    name: 'Risk Summary',
    templateType: 'RISK_SUMMARY',
    ownerUserId: 'SYSTEM',
    description: 'Per-book VaR and Greeks',
    source: 'risk_positions_flat',
  },
  {
    templateId: 'tpl-stress-summary',
    name: 'Stress Test Summary',
    templateType: 'STRESS_TEST_SUMMARY',
    ownerUserId: 'SYSTEM',
    description: 'Stressed P&L by scenario',
    source: 'stress_test_results',
  },
  {
    templateId: 'tpl-pnl-attribution',
    name: 'P&L Attribution',
    templateType: 'PNL_ATTRIBUTION',
    ownerUserId: 'SYSTEM',
    description: 'P&L attribution by component',
    source: 'pnl_attributions',
  },
]

export const TEST_REPORT_OUTPUT = {
  outputId: 'out-test-001',
  templateId: 'tpl-risk-summary',
  generatedAt: '2025-01-15T10:00:00Z',
  outputFormat: 'JSON',
  rowCount: 2,
}

/**
 * Sets up route handlers to mock all API endpoints the app calls on startup.
 * This allows Playwright tests to run without a real backend.
 */
export interface MockAuthOptions {
  username?: string
  roles?: string[]
  sub?: string
}

export async function mockKeycloakAuth(page: Page, opts: MockAuthOptions = {}): Promise<void> {
  const username = opts.username ?? 'testuser'
  const roles = opts.roles ?? ['ADMIN']
  const sub = opts.sub ?? 'a0000000-0000-0000-0000-000000000001'

  // Intercept Keycloak OIDC endpoints so the real Keycloak is never contacted
  await page.route('**/auth/realms/**', (route: Route) => {
    const url = route.request().url()
    if (url.includes('/protocol/openid-connect/token')) {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          access_token: 'mock-e2e-token',
          token_type: 'Bearer',
          expires_in: 3600,
          refresh_token: 'mock-refresh-token',
        }),
      })
    } else if (url.includes('/protocol/openid-connect/certs')) {
      route.fulfill({ status: 200, contentType: 'application/json', body: '{"keys":[]}' })
    } else if (url.includes('/protocol/openid-connect/3p-cookies')) {
      route.fulfill({ status: 200, contentType: 'text/html', body: '' })
    } else if (url.includes('/.well-known/openid-configuration')) {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          issuer: 'http://localhost:8180/realms/kinetix',
          authorization_endpoint: 'http://localhost:8180/realms/kinetix/protocol/openid-connect/auth',
          token_endpoint: 'http://localhost:8180/realms/kinetix/protocol/openid-connect/token',
          jwks_uri: 'http://localhost:8180/realms/kinetix/protocol/openid-connect/certs',
          end_session_endpoint: 'http://localhost:8180/realms/kinetix/protocol/openid-connect/logout',
        }),
      })
    } else {
      route.fulfill({ status: 200, contentType: 'text/html', body: '' })
    }
  })

  // Mock the Keycloak constructor at the JS level before the app loads.
  // This replaces the real keycloak-js init flow with an immediately-resolved mock.
  await page.addInitScript(`
    window.__KEYCLOAK_MOCK__ = {
      username: ${JSON.stringify(username)},
      roles: ${JSON.stringify(roles)},
      sub: ${JSON.stringify(sub)},
    };
  `)

  // Intercept the keycloak-js module load by patching the Keycloak global
  // after the module is loaded but before init() is called
  await page.addInitScript(`
    const _origDefineProperty = Object.defineProperty;
    let _keycloakPatched = false;

    // Patch any imported Keycloak class prototype
    const _patchInterval = setInterval(() => {
      if (_keycloakPatched) { clearInterval(_patchInterval); return; }

      // The AuthProvider creates a Keycloak instance and calls init()
      // We intercept by replacing the init method on any Keycloak-like object
    }, 10);

    // More reliable: patch the module at the window level for Vite bundles
    window.Keycloak = function MockKeycloak() {
      const mock = window.__KEYCLOAK_MOCK__;
      this.authenticated = false;
      this.token = null;
      this.tokenParsed = null;
      this.init = function(opts) {
        this.authenticated = true;
        this.token = 'mock-e2e-token';
        this.tokenParsed = {
          preferred_username: mock.username,
          roles: mock.roles,
          sub: mock.sub,
        };
        return Promise.resolve(true);
      };
      this.updateToken = function() { return Promise.resolve(false); };
      this.logout = function() {};
      this.login = function() {};
    };
  `)
}

export async function mockBackendApiRoutes(page: Page): Promise<void> {
  await page.route('**/api/v1/divisions', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    })
  })

  await page.route('**/api/v1/desks', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    })
  })

  await page.route('**/api/v1/firm/summary*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        bookId: 'firm',
        baseCurrency: 'USD',
        totalNav: { amount: '168850.00', currency: 'USD' },
        totalUnrealizedPnl: { amount: '3050.00', currency: 'USD' },
        currencyBreakdown: [],
      }),
    })
  })

  await page.route('**/api/v1/books', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_BOOKS),
    })
  })

  await page.route('**/api/v1/books/*/positions', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_POSITIONS),
    })
  })

  await page.route('**/api/v1/books/*/trades/page**', (route: Route) => {
    const url = new URL(route.request().url())
    const offset = Number(url.searchParams.get('offset') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 100)
    const counterpartyId = url.searchParams.get('counterpartyId')
    const filtered = counterpartyId
      ? TEST_TRADES.filter((t) => t.counterpartyId === counterpartyId)
      : TEST_TRADES
    const items = filtered.slice(offset, offset + limit)
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        total: filtered.length,
        offset,
        limit,
        hasMore: offset + items.length < filtered.length,
      }),
    })
  })

  await page.route('**/api/v1/books/*/trades', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_TRADES),
    })
  })

  await page.route('**/api/v1/data-quality/status', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(DATA_QUALITY_STATUS),
    })
  })

  await page.route('**/api/v1/notifications/rules', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    })
  })

  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    })
  })

  await page.route('**/api/v1/system/health', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'HEALTHY', services: [] }),
    })
  })

  // Position risk endpoint must return an array
  await page.route('**/api/v1/risk/positions/*', (route: Route) => {
    route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify([]),
    })
  })

  // VaR endpoint -- return 404 (no VaR data available)
  await page.route('**/api/v1/risk/var/*', (route: Route) => {
    route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify(null),
    })
  })

  // Stress test scenarios
  await page.route('**/api/v1/risk/stress/scenarios', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    })
  })

  // Intraday P&L series endpoint — return empty snapshots by default
  await page.route('**/api/v1/risk/pnl/intraday/**', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ bookId: 'port-1', snapshots: [] }),
    })
  })

  // Catch-all for remaining risk endpoints (registered FIRST — Playwright uses last-match-wins)
  await page.route('**/api/v1/risk/**', (route: Route) => {
    route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify(null),
    })
  })

  // Specific risk routes registered AFTER catch-all so they take priority
  await page.route('**/api/v1/risk/var/*/intraday*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ bookId: 'port-1', varPoints: [], tradeAnnotations: [] }),
    })
  })

  await page.route('**/api/v1/risk/regime/current', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_REGIME_NORMAL),
    })
  })

  await page.route('**/api/v1/risk/regime/history*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], total: 0 }),
    })
  })

  await page.route('**/api/v1/books/*/summary*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        bookId: 'port-1',
        baseCurrency: 'USD',
        totalNav: { amount: '168850.00', currency: 'USD' },
        totalUnrealizedPnl: { amount: '3050.00', currency: 'USD' },
        currencyBreakdown: [],
      }),
    })
  })

  // Default liquidity risk endpoints — return 404 so the panel shows its empty state.
  // Tests that need real liquidity data must call mockLiquidityRiskRoutes() afterward.
  await page.route('**/api/v1/books/*/liquidity-risk/latest', (route: Route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })

  await page.route('**/api/v1/books/*/liquidity-risk', (route: Route) => {
    route.fulfill({ status: 204 })
  })

  // Default factor risk endpoints — return 404/empty so panels show empty states.
  // Tests that need factor risk data must call mockFactorRiskRoutes() afterward.
  await page.route('**/api/v1/books/*/factor-risk/latest', (route: Route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })

  await page.route('**/api/v1/books/*/factor-risk*', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })

  // Default margin endpoint — return 404 so the panel shows its empty state.
  // Tests that need real margin data must call mockMarginRoutes() afterward.
  await page.route('**/api/v1/books/*/margin*', (route: Route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })

  // Default limits endpoint — return an empty list so the panel shows its empty state.
  // Tests that need real limit data must call mockLimitsRoutes() afterward.
  await page.route('**/api/v1/limits', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })

  // Default counterparty risk endpoint — return empty list so the tab shows its empty state.
  // Tests that need real counterparty data must call mockCounterpartyRiskRoutes() afterward.
  await page.route('**/api/v1/counterparty-risk*', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })

  // Execution cost analysis — return empty list by default
  await page.route('**/api/v1/execution/cost/**', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })

  // Prime broker reconciliation — return empty list by default
  await page.route('**/api/v1/execution/reconciliation/**', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
  })

  // Report routes — register specific routes last so they take priority over wildcards.
  // Playwright uses the last-registered matching handler.

  // Catch-all for report output by ID
  await page.route('**/api/v1/reports/*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_REPORT_OUTPUT),
    })
  })

  // CSV download for a specific output ID (more specific than /*)
  await page.route('**/api/v1/reports/*/csv', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'text/csv',
      body: 'book_id,instrument_id,var_contribution\nBOOK-1,AAPL,12345.67',
    })
  })

  // Report generate (registered after /*, so takes priority)
  await page.route('**/api/v1/reports/generate', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_REPORT_OUTPUT),
    })
  })

  // Report templates (most specific, registered last — highest priority)
  await page.route('**/api/v1/reports/templates', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_REPORT_TEMPLATES),
    })
  })

  // Position notes — default empty list (plan §7.3.3). Tests that need notes
  // data should override these routes after calling mockAllApiRoutes.
  await page.route('**/api/v1/positions/*/notes*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    })
  })

  // Note: Playwright's page.route() does NOT intercept WebSocket connections.
  // To mock WebSocket behaviour, tests must use page.addInitScript() to replace
  // the browser's WebSocket constructor before the page loads.
  // By default (without mocking), the app's WebSocket will connect to whatever
  // the Vite dev server proxies to -- which may succeed or fail depending on
  // whether a real backend is running.
}

const ASSET_CLASSES = ['EQUITY', 'FX', 'BOND', 'COMMODITY', 'OPTION']
const TICKERS = [
  'AAPL', 'GOOGL', 'MSFT', 'AMZN', 'META', 'TSLA', 'NVDA', 'JPM',
  'V', 'JNJ', 'WMT', 'PG', 'UNH', 'HD', 'MA', 'DIS', 'PYPL', 'BAC',
  'INTC', 'VZ', 'KO', 'PEP', 'NFLX', 'ADBE', 'CRM', 'CMCSA', 'NKE',
  'T', 'MRK', 'ABT', 'XOM', 'CVX', 'LLY', 'TMO', 'AVGO', 'COST',
  'MDT', 'DHR', 'ACN', 'NEE', 'TXN', 'LIN', 'PM', 'HON', 'UPS',
  'ORCL', 'MS', 'BMY', 'QCOM', 'RTX', 'SBUX', 'BLK', 'AMGN', 'GE',
  'CAT', 'DE', 'LOW', 'ISRG', 'GS', 'AXP', 'SYK', 'MDLZ', 'EL',
  'BKNG', 'TGT', 'ADP', 'CI', 'MO', 'PLD', 'ZTS', 'CB', 'GILD',
  'SPGI', 'BDX', 'DUK', 'SO', 'CL', 'ICE', 'CSX', 'MMC', 'SHW',
  'CME', 'PNC', 'TFC', 'USB', 'AON', 'APD', 'NSC', 'FIS', 'EMR',
  'ECL', 'WM', 'ITW', 'EW', 'D', 'HUM', 'MCO', 'ETN', 'PSA', 'F',
  'GM', 'ATVI', 'REGN', 'KLAC', 'SLB', 'MPC', 'PSX', 'VLO', 'OXY',
  'AIG', 'MET', 'PRU', 'ALL', 'TRV', 'WBA', 'KHC', 'CTVA', 'DOW',
  'DD', 'BIIB', 'VRTX', 'MRNA', 'DXCM', 'IDXX', 'ALGN', 'ILMN',
  'ENPH', 'ODFL', 'CTAS', 'CDNS', 'SNPS', 'MCHP', 'FTNT', 'PANW',
]

/**
 * Generates N position fixtures with unique instrument IDs.
 */
export function generatePositions(count: number): PositionFixture[] {
  return Array.from({ length: count }, (_, i) => {
    const ticker = i < TICKERS.length ? TICKERS[i] : `INST_${String(i).padStart(3, '0')}`
    const price = 50 + (i * 7) % 300
    const qty = 10 + (i * 3) % 500
    const cost = price - (i % 10)
    const mv = price * qty
    const pnl = (price - cost) * qty
    return {
      bookId: 'port-1',
      instrumentId: ticker,
      assetClass: ASSET_CLASSES[i % ASSET_CLASSES.length],
      quantity: String(qty),
      averageCost: { amount: cost.toFixed(2), currency: 'USD' },
      marketPrice: { amount: price.toFixed(2), currency: 'USD' },
      marketValue: { amount: mv.toFixed(2), currency: 'USD' },
      unrealizedPnl: { amount: pnl.toFixed(2), currency: 'USD' },
    }
  })
}

export async function mockAllApiRoutes(page: Page): Promise<void> {
  await mockKeycloakAuth(page)
  await mockBackendApiRoutes(page)
}

/**
 * Overrides the positions endpoint to return `count` generated positions.
 * Call this AFTER mockAllApiRoutes (Playwright uses the first matching route handler).
 */
export async function mockManyPositions(page: Page, count: number): Promise<PositionFixture[]> {
  const positions = generatePositions(count)
  // Unroute the default positions handler and add a new one
  await page.unroute('**/api/v1/books/*/positions')
  await page.route('**/api/v1/books/*/positions', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(positions),
    })
  })
  return positions
}

/**
 * Generates N trade fixtures with unique trade IDs.
 */
export function generateTrades(count: number): TradeFixture[] {
  const sides: ('BUY' | 'SELL')[] = ['BUY', 'SELL']
  return Array.from({ length: count }, (_, i) => {
    const ticker = i < TICKERS.length ? TICKERS[i] : `INST_${String(i).padStart(3, '0')}`
    const price = 50 + (i * 7) % 300
    const qty = 10 + (i * 3) % 500
    // Spread trades over a range of timestamps (1 minute apart)
    const baseTime = new Date('2025-01-15T09:00:00Z').getTime()
    const tradedAt = new Date(baseTime + i * 60_000).toISOString()
    return {
      tradeId: `trade-gen-${i}`,
      bookId: 'port-1',
      instrumentId: ticker,
      assetClass: ASSET_CLASSES[i % ASSET_CLASSES.length],
      side: sides[i % sides.length],
      quantity: String(qty),
      price: { amount: price.toFixed(2), currency: 'USD' },
      tradedAt,
    }
  })
}

/**
 * Overrides the trades endpoint to return `count` generated trades.
 * Call this AFTER mockAllApiRoutes (Playwright uses the first matching route handler).
 */
/**
 * Overrides both the legacy /trades and the paginated /trades/page endpoints
 * with the same data, so blotter tests can serve custom fixtures via the
 * paginated path that the blotter now consumes.
 */
export async function mockTradesOverride(page: Page, trades: TradeFixture[]): Promise<void> {
  await page.unroute('**/api/v1/books/*/trades/page**')
  await page.unroute('**/api/v1/books/*/trades')
  await page.route('**/api/v1/books/*/trades/page**', (route: Route) => {
    const url = new URL(route.request().url())
    const offset = Number(url.searchParams.get('offset') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 100)
    const counterpartyId = url.searchParams.get('counterpartyId')
    const filtered = counterpartyId
      ? trades.filter((t) => t.counterpartyId === counterpartyId)
      : trades
    const items = filtered.slice(offset, offset + limit)
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        total: filtered.length,
        offset,
        limit,
        hasMore: offset + items.length < filtered.length,
      }),
    })
  })
  await page.route('**/api/v1/books/*/trades', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(trades),
    })
  })
}

export async function mockManyTrades(page: Page, count: number): Promise<TradeFixture[]> {
  const trades = generateTrades(count)
  await page.unroute('**/api/v1/books/*/trades/page**')
  await page.unroute('**/api/v1/books/*/trades')
  await page.route('**/api/v1/books/*/trades/page**', (route: Route) => {
    const url = new URL(route.request().url())
    const offset = Number(url.searchParams.get('offset') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 100)
    const counterpartyId = url.searchParams.get('counterpartyId')
    const filtered = counterpartyId
      ? trades.filter((t) => t.counterpartyId === counterpartyId)
      : trades
    const items = filtered.slice(offset, offset + limit)
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        total: filtered.length,
        offset,
        limit,
        hasMore: offset + items.length < filtered.length,
      }),
    })
  })
  await page.route('**/api/v1/books/*/trades', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(trades),
    })
  })
  return trades
}

// ---------------------------------------------------------------------------
// Liquidity risk fixture data
// ---------------------------------------------------------------------------

export const TEST_LIQUIDITY_RISK_RESULT = {
  bookId: 'port-1',
  portfolioLvar: 316227.76,
  dataCompleteness: 0.85,
  portfolioConcentrationStatus: 'OK',
  calculatedAt: '2026-03-24T10:00:00Z',
  positionRisks: [
    {
      instrumentId: 'AAPL',
      assetClass: 'EQUITY',
      marketValue: 15500,
      tier: 'HIGH_LIQUID',
      horizonDays: 1,
      adv: 80000000,
      advMissing: false,
      advStale: false,
      lvarContribution: 280000,
      stressedLiquidationValue: 15000,
      concentrationStatus: 'OK',
    },
    {
      instrumentId: 'GOOGL',
      assetClass: 'EQUITY',
      marketValue: 142500,
      tier: 'LIQUID',
      horizonDays: 2,
      adv: 50000000,
      advMissing: false,
      advStale: false,
      lvarContribution: 36227.76,
      stressedLiquidationValue: 140000,
      concentrationStatus: 'OK',
    },
  ],
}

export const TEST_LIQUIDITY_RISK_RESULT_ADV_MISSING = {
  ...TEST_LIQUIDITY_RISK_RESULT,
  dataCompleteness: 0.5,
  positionRisks: [
    {
      ...TEST_LIQUIDITY_RISK_RESULT.positionRisks[0],
      instrumentId: 'UNKNOWN-BOND',
      tier: 'ILLIQUID',
      advMissing: true,
      adv: null,
    },
  ],
}

/**
 * Mocks the liquidity risk API endpoints for a book.
 * The `latest` parameter controls what GET /liquidity-risk/latest returns (null → 404).
 * The `calculated` parameter controls what POST /liquidity-risk returns (null → 204).
 */
export async function mockLiquidityRiskRoutes(
  page: Page,
  opts: { latest?: object | null; calculated?: object | null } = {},
): Promise<void> {
  // POST /api/v1/books/:bookId/liquidity-risk  (trigger calculation)
  await page.route('**/api/v1/books/*/liquidity-risk', (route: Route) => {
    if (route.request().method() === 'POST') {
      if (opts.calculated) {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(opts.calculated),
        })
      } else {
        route.fulfill({ status: 204 })
      }
    } else {
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
    }
  })

  // GET /api/v1/books/:bookId/liquidity-risk/latest
  await page.route('**/api/v1/books/*/liquidity-risk/latest', (route: Route) => {
    if (opts.latest) {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(opts.latest),
      })
    } else {
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
    }
  })
}

// ---------------------------------------------------------------------------
// Position risk fixture data
// ---------------------------------------------------------------------------

export interface PositionRiskFixture {
  instrumentId: string
  assetClass: string
  marketValue: string
  delta: string | null
  gamma: string | null
  vega: string | null
  theta: string | null
  rho: string | null
  /**
   * DV01 (dollar value of a 1bp parallel rates shift). Surfaced only for
   * FIXED_INCOME positions; optional in the fixture so existing equity/FX
   * fixtures stay unchanged.
   */
  dv01?: string | null
  varContribution: string
  esContribution: string
  percentageOfTotal: string
}

export const TEST_POSITION_RISK: PositionRiskFixture[] = [
  {
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    marketValue: '15500.00',
    delta: '1550.25',
    gamma: '12.50',
    vega: '320.00',
    theta: '-45.00',
    rho: '15.00',
    varContribution: '485.50',
    esContribution: '620.00',
    percentageOfTotal: '42.50',
  },
  {
    instrumentId: 'GOOGL',
    assetClass: 'EQUITY',
    marketValue: '142500.00',
    delta: '7125.00',
    gamma: '45.00',
    vega: '890.00',
    theta: '-80.00',
    rho: '27.00',
    varContribution: '520.75',
    esContribution: '680.00',
    percentageOfTotal: '45.60',
  },
  {
    instrumentId: 'EUR_USD',
    assetClass: 'FX',
    marketValue: '10850.00',
    delta: null,
    gamma: null,
    vega: null,
    theta: null,
    rho: null,
    varContribution: '135.75',
    esContribution: '175.00',
    percentageOfTotal: '11.90',
  },
]

// ---------------------------------------------------------------------------
// Mixed P&L fixture (positive, negative, zero)
// ---------------------------------------------------------------------------

export const TEST_POSITIONS_MIXED_PNL: PositionFixture[] = [
  {
    bookId: 'port-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    quantity: '100',
    averageCost: { amount: '150.00', currency: 'USD' },
    marketPrice: { amount: '155.00', currency: 'USD' },
    marketValue: { amount: '15500.00', currency: 'USD' },
    unrealizedPnl: { amount: '500.00', currency: 'USD' },
  },
  {
    bookId: 'port-1',
    instrumentId: 'TSLA',
    assetClass: 'EQUITY',
    quantity: '30',
    averageCost: { amount: '800.00', currency: 'USD' },
    marketPrice: { amount: '600.00', currency: 'USD' },
    marketValue: { amount: '18000.00', currency: 'USD' },
    unrealizedPnl: { amount: '-6000.00', currency: 'USD' },
  },
  {
    bookId: 'port-1',
    instrumentId: 'MSFT',
    assetClass: 'EQUITY',
    quantity: '50',
    averageCost: { amount: '300.00', currency: 'USD' },
    marketPrice: { amount: '300.00', currency: 'USD' },
    marketValue: { amount: '15000.00', currency: 'USD' },
    unrealizedPnl: { amount: '0.00', currency: 'USD' },
  },
]

// ---------------------------------------------------------------------------
// Multi-currency portfolio summary
// ---------------------------------------------------------------------------

export const TEST_PORTFOLIO_SUMMARY_MULTI_CURRENCY = {
  bookId: 'port-1',
  baseCurrency: 'USD',
  totalNav: { amount: '250000.00', currency: 'USD' },
  totalUnrealizedPnl: { amount: '12500.00', currency: 'USD' },
  currencyBreakdown: [
    {
      currency: 'USD',
      localValue: { amount: '168850.00', currency: 'USD' },
      baseValue: { amount: '168850.00', currency: 'USD' },
      fxRate: '1.0000',
    },
    {
      currency: 'EUR',
      localValue: { amount: '75000.00', currency: 'EUR' },
      baseValue: { amount: '81150.00', currency: 'USD' },
      fxRate: '1.0820',
    },
  ],
}

// ---------------------------------------------------------------------------
// What-If analysis response
// ---------------------------------------------------------------------------

export const TEST_WHATIF_RESPONSE = {
  baseVaR: '1142.00',
  baseExpectedShortfall: '1502.90',
  baseGreeks: {
    bookId: 'port-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '8675.25', gamma: '57.50', vega: '1210.00' },
    ],
    theta: '-125.50',
    rho: '42.30',
    calculatedAt: '2025-01-15T12:00:00Z',
  },
  basePositionRisk: [
    {
      instrumentId: 'AAPL',
      assetClass: 'EQUITY',
      marketValue: '15500.00',
      delta: '1550.25',
      gamma: '12.50',
      vega: '320.00',
      theta: '-45.00',
      rho: '15.00',
      varContribution: '485.50',
      esContribution: '620.00',
      percentageOfTotal: '42.50',
    },
  ],
  hypotheticalVaR: '1380.50',
  hypotheticalExpectedShortfall: '1820.00',
  hypotheticalGreeks: {
    bookId: 'port-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '13175.25', gamma: '82.50', vega: '1710.00' },
    ],
    theta: '-180.75',
    rho: '65.10',
    calculatedAt: '2025-01-15T12:00:00Z',
  },
  hypotheticalPositionRisk: [
    {
      instrumentId: 'AAPL',
      assetClass: 'EQUITY',
      marketValue: '15500.00',
      delta: '1550.25',
      gamma: '12.50',
      vega: '320.00',
      theta: '-45.00',
      rho: '15.00',
      varContribution: '485.50',
      esContribution: '620.00',
      percentageOfTotal: '35.20',
    },
    {
      instrumentId: 'SPY',
      assetClass: 'EQUITY',
      marketValue: '45000.00',
      delta: '4500.00',
      gamma: '25.00',
      vega: '500.00',
      theta: '-55.25',
      rho: '22.80',
      varContribution: '895.00',
      esContribution: '1200.00',
      percentageOfTotal: '64.80',
    },
  ],
  varChange: '238.50',
  esChange: '317.10',
  calculatedAt: '2025-01-15T12:00:00Z',
}

// ---------------------------------------------------------------------------
// Mock helpers -- call AFTER mockAllApiRoutes
// ---------------------------------------------------------------------------

/**
 * Overrides the position risk endpoint to return the given risk data.
 * Call AFTER mockAllApiRoutes.
 */
export async function mockPositionRisk(page: Page, risk: PositionRiskFixture[]): Promise<void> {
  await page.unroute('**/api/v1/risk/positions/*')
  await page.route('**/api/v1/risk/positions/*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(risk),
    })
  })
}

export interface LimitDefinitionFixture {
  id: string
  level: 'FIRM' | 'DIVISION' | 'DESK' | 'BOOK' | 'TRADER' | 'COUNTERPARTY'
  entityId: string
  limitType: 'POSITION' | 'NOTIONAL' | 'VAR' | 'CONCENTRATION' | 'ADV_CONCENTRATION' | 'VAR_BUDGET'
  limitValue: string
  intradayLimit: string | null
  overnightLimit: string | null
  active: boolean
}

/**
 * Overrides the limits endpoint with the given limit definitions, or with
 * a specific status code (e.g. 500 for error). Call AFTER mockAllApiRoutes.
 */
export async function mockLimitsRoutes(
  page: Page,
  opts: { limits?: LimitDefinitionFixture[]; status?: number } = {},
): Promise<void> {
  await page.unroute('**/api/v1/limits')
  await page.route('**/api/v1/limits', (route: Route) => {
    if (opts.status != null && opts.status >= 400) {
      route.fulfill({
        status: opts.status,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'upstream' }),
      })
      return
    }
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(opts.limits ?? []),
    })
  })
}

export interface MarginEstimateFixture {
  initialMargin: string
  variationMargin: string
  totalMargin: string
  currency: string
}

/**
 * Overrides the margin endpoint to return the given estimate, or to return a
 * specific status (404 for empty, 5xx for error). Call AFTER mockAllApiRoutes.
 */
export async function mockMarginRoutes(
  page: Page,
  opts: { estimate?: MarginEstimateFixture; status?: number } = {},
): Promise<void> {
  await page.unroute('**/api/v1/books/*/margin*')
  await page.route('**/api/v1/books/*/margin*', (route: Route) => {
    if (opts.status != null && opts.status >= 400) {
      route.fulfill({
        status: opts.status,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'upstream' }),
      })
      return
    }
    if (opts.estimate) {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(opts.estimate),
      })
      return
    }
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })
}

/**
 * Overrides the positions endpoint to return the given positions.
 * Call AFTER mockAllApiRoutes.
 */
export async function mockPositions(page: Page, positions: PositionFixture[]): Promise<void> {
  await page.unroute('**/api/v1/books/*/positions')
  await page.route('**/api/v1/books/*/positions', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(positions),
    })
  })
}

/**
 * Overrides the portfolio summary endpoint to return the given summary.
 * Call AFTER mockAllApiRoutes.
 */
export async function mockPortfolioSummary(page: Page, summary: object): Promise<void> {
  await page.unroute('**/api/v1/books/*/summary*')
  await page.route('**/api/v1/books/*/summary*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(summary),
    })
  })
  // Also override firm-level summary (used at firm hierarchy level)
  await page.unroute('**/api/v1/firm/summary*')
  await page.route('**/api/v1/firm/summary*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(summary),
    })
  })
}

/**
 * Adds a route handler for the What-If POST endpoint.
 * Call AFTER mockAllApiRoutes.
 */
export async function mockWhatIfAnalysis(page: Page, response: object): Promise<void> {
  await page.route('**/api/v1/risk/what-if/*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(response),
    })
  })
}

export const TEST_REBALANCING_RESPONSE = {
  baseVar: '100000.00',
  rebalancedVar: '80000.00',
  varChange: '-20000.00',
  varChangePct: '-20.00',
  baseExpectedShortfall: '130000.00',
  rebalancedExpectedShortfall: '104000.00',
  esChange: '-26000.00',
  baseGreeks: null,
  rebalancedGreeks: null,
  greeksChange: {
    deltaChange: '-5000.000000',
    gammaChange: '-100.000000',
    vegaChange: '-500.000000',
    thetaChange: '50.000000',
    rhoChange: '-20.000000',
  },
  tradeContributions: [
    {
      instrumentId: 'AAPL',
      side: 'SELL',
      quantity: '50',
      marginalVarImpact: '-12000.00',
      executionCost: '42.50',
    },
    {
      instrumentId: 'GOOGL',
      side: 'SELL',
      quantity: '10',
      marginalVarImpact: '-8000.00',
      executionCost: '28.00',
    },
  ],
  estimatedExecutionCost: '70.50',
  calculatedAt: '2026-03-25T10:00:00Z',
}

/**
 * Overrides the rebalancing what-if endpoint to return the given response.
 * Uses a more specific route pattern so it takes priority over mockAllApiRoutes catch-all.
 * Call AFTER mockAllApiRoutes.
 */
export async function mockRebalancingAnalysis(page: Page, response: object): Promise<void> {
  await page.route('**/api/v1/risk/what-if/*/rebalance', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(response),
    })
  })
}

/**
 * Generates deterministic position risk data for an array of positions.
 * Useful for sorting and pagination tests that need many positions with risk.
 */
export function generatePositionRisk(positions: PositionFixture[]): PositionRiskFixture[] {
  return positions.map((pos, i) => {
    const isFx = pos.assetClass === 'FX'
    return {
      instrumentId: pos.instrumentId,
      assetClass: pos.assetClass,
      marketValue: pos.marketValue.amount,
      delta: isFx ? null : String((100 + i * 50).toFixed(2)),
      gamma: isFx ? null : String((1 + i * 0.5).toFixed(2)),
      vega: isFx ? null : String((10 + i * 3).toFixed(2)),
      theta: isFx ? null : String((-5 - i).toFixed(2)),
      rho: isFx ? null : String((2 + i * 0.3).toFixed(2)),
      varContribution: String((50 + i * 10).toFixed(2)),
      esContribution: String((65 + i * 13).toFixed(2)),
      percentageOfTotal: (100 / positions.length).toFixed(2),
    }
  })
}

// ---------------------------------------------------------------------------
// Alert event fixture
// ---------------------------------------------------------------------------

export interface AlertEventFixture {
  id: string
  ruleId: string
  ruleName: string
  type: string
  severity: string
  message: string
  currentValue: number
  threshold: number
  bookId: string
  triggeredAt: string
  status: string
}

// ---------------------------------------------------------------------------
// Risk Tab fixture data
// ---------------------------------------------------------------------------

export const TEST_VAR_RESULT = {
  bookId: 'port-1',
  varValue: '125000.50',
  expectedShortfall: '187500.75',
  confidenceLevel: 'CL_95',
  calculationType: 'PARAMETRIC',
  componentBreakdown: [
    { assetClass: 'EQUITY', varContribution: '80000.30', percentageOfTotal: '64' },
    { assetClass: 'FX', varContribution: '55000.20', percentageOfTotal: '44' },
  ],
  calculatedAt: '2025-01-15T12:00:00Z',
  greeks: {
    bookId: 'port-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '1500.00', gamma: '25.00', vega: '800.00' },
      { assetClass: 'FX', delta: '500.00', gamma: '10.00', vega: '200.00' },
    ],
    theta: '-350.00',
    rho: '120.00',
    calculatedAt: '2025-01-15T12:00:00Z',
  },
  pvValue: '5000000.00',
  computedOutputs: ['VAR', 'EXPECTED_SHORTFALL', 'GREEKS', 'PV'],
}

export const TEST_HISTORICAL_VAR_RESULT = {
  bookId: 'port-1',
  varValue: '98000.25',
  expectedShortfall: '147000.38',
  confidenceLevel: 'CL_95',
  calculationType: 'PARAMETRIC',
  componentBreakdown: [
    { assetClass: 'EQUITY', varContribution: '63000.15', percentageOfTotal: '64' },
    { assetClass: 'FX', varContribution: '35000.10', percentageOfTotal: '36' },
  ],
  calculatedAt: '2025-01-14T18:00:00Z',
  greeks: {
    bookId: 'port-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '1200.00', gamma: '20.00', vega: '650.00' },
      { assetClass: 'FX', delta: '400.00', gamma: '8.00', vega: '150.00' },
    ],
    theta: '-280.00',
    rho: '95.00',
    calculatedAt: '2025-01-14T18:00:00Z',
  },
  pvValue: '4500000.00',
  computedOutputs: ['VAR', 'EXPECTED_SHORTFALL', 'GREEKS', 'PV'],
  valuationDate: '2025-01-14',
}

export const TEST_HISTORICAL_POSITION_RISK: PositionRiskFixture[] = [
  { instrumentId: 'AAPL', assetClass: 'EQUITY', marketValue: '14800.00', delta: '148.00', gamma: '2.30', vega: '42.00', theta: '-11.50', rho: '7.50', varContribution: '4200.00', esContribution: '6300.00', percentageOfTotal: '38.00' },
  { instrumentId: 'EUR_USD', assetClass: 'FX', marketValue: '10200.00', delta: '102.00', gamma: null, vega: null, theta: null, rho: '14.00', varContribution: '2800.00', esContribution: '4200.00', percentageOfTotal: '25.00' },
]

export const TEST_POSITION_RISK_FULL: PositionRiskFixture[] = [
  { instrumentId: 'AAPL', assetClass: 'EQUITY', marketValue: '15500.00', delta: '155.00', gamma: '2.50', vega: '45.00', theta: '-12.50', rho: '8.00', varContribution: '5000.00', esContribution: '7500.00', percentageOfTotal: '35.00' },
  { instrumentId: 'EUR_USD', assetClass: 'FX', marketValue: '10850.00', delta: '108.50', gamma: null, vega: null, theta: null, rho: '15.00', varContribution: '3000.00', esContribution: '4500.00', percentageOfTotal: '21.00' },
  { instrumentId: 'GOOGL', assetClass: 'EQUITY', marketValue: '142500.00', delta: '1425.00', gamma: '15.00', vega: '350.00', theta: '-42.00', rho: '95.00', varContribution: '2000.00', esContribution: '3000.00', percentageOfTotal: '14.00' },
  // Rates instrument — DV01 column should be populated for this row (kx-dmw).
  { instrumentId: 'UST_10Y', assetClass: 'FIXED_INCOME', marketValue: '500000.00', delta: '425.00', gamma: null, vega: null, theta: null, rho: '4250.00', dv01: '4250.75', varContribution: '1500.00', esContribution: '2200.00', percentageOfTotal: '12.00' },
]

export const TEST_JOB_HISTORY = {
  items: [
    {
      jobId: 'job-1',
      bookId: 'port-1',
      triggerType: 'ON_DEMAND',
      status: 'COMPLETED',
      startedAt: '2025-01-15T12:00:00Z',
      completedAt: '2025-01-15T12:00:05Z',
      durationMs: 5000,
      calculationType: 'PARAMETRIC',
      confidenceLevel: 'CL_95',
      varValue: 125000.50,
      expectedShortfall: 187500.75,
      pvValue: 5000000.00,
      delta: null,
      gamma: null,
      vega: null,
      theta: null,
      rho: null,
      runLabel: null,
      promotedAt: null,
      promotedBy: null,
      manifestId: 'manifest-abc-123',
    },
  ],
  totalCount: 1,
}

export const TEST_ALERTS: AlertEventFixture[] = [
  {
    id: 'alert-1',
    ruleId: 'r1',
    ruleName: 'VAR_BREACH',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR exceeded limit',
    currentValue: 125000,
    threshold: 100000,
    bookId: 'port-1',
    triggeredAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    status: 'TRIGGERED',
  },
  {
    id: 'alert-2',
    ruleId: 'r1',
    ruleName: 'VAR_BREACH',
    type: 'VAR_BREACH',
    severity: 'WARNING',
    message: 'VaR approaching limit',
    currentValue: 95000,
    threshold: 100000,
    bookId: 'port-1',
    triggeredAt: new Date(Date.now() - 30 * 60_000).toISOString(),
    status: 'TRIGGERED',
  },
]

export const TEST_VAR_LIMIT_RULE = [
  {
    id: 'rule-1',
    name: 'VaR Breach',
    type: 'VAR_BREACH',
    threshold: 200000,
    operator: 'GREATER_THAN',
    severity: 'CRITICAL',
    channels: ['UI'],
    enabled: true,
  },
]

export const TEST_JOB_DETAIL = {
  jobId: 'job-1',
  bookId: 'port-1',
  triggerType: 'ON_DEMAND',
  status: 'COMPLETED',
  startedAt: '2025-01-15T12:00:00Z',
  completedAt: '2025-01-15T12:00:05Z',
  durationMs: 5000,
  calculationType: 'PARAMETRIC',
  confidenceLevel: 'CL_95',
  varValue: 125000.50,
  expectedShortfall: 187500.75,
  pvValue: 5000000.00,
  runLabel: null,
  promotedAt: null,
  promotedBy: null,
  manifestId: 'manifest-abc-123',
  phases: [
    {
      name: 'FETCH_POSITIONS',
      status: 'COMPLETED',
      startedAt: '2025-01-15T12:00:00Z',
      completedAt: '2025-01-15T12:00:01Z',
      durationMs: 1000,
      details: { positionCount: '5' },
      error: null,
    },
    {
      name: 'FETCH_MARKET_DATA',
      status: 'COMPLETED',
      startedAt: '2025-01-15T12:00:01Z',
      completedAt: '2025-01-15T12:00:02Z',
      durationMs: 1000,
      details: {},
      error: null,
    },
    {
      name: 'VALUATION',
      status: 'COMPLETED',
      startedAt: '2025-01-15T12:00:02Z',
      completedAt: '2025-01-15T12:00:05Z',
      durationMs: 3000,
      details: {},
      error: null,
    },
  ],
  error: null,
  valuationDate: '2025-01-15',
}

export const TEST_RUN_MANIFEST = {
  manifestId: 'manifest-abc-123',
  jobId: 'job-1',
  bookId: 'port-1',
  valuationDate: '2025-01-15',
  capturedAt: '2025-01-15T12:00:02Z',
  modelVersion: '1.4.2-abc9876',
  calculationType: 'PARAMETRIC',
  confidenceLevel: 'CL_95',
  timeHorizonDays: 1,
  numSimulations: 10000,
  monteCarloSeed: 0,
  positionCount: 5,
  positionDigest: 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',
  marketDataDigest: 'b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3',
  inputDigest: 'c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4',
  status: 'COMPLETE',
  varValue: 125000.50,
  expectedShortfall: 187500.75,
  outputDigest: 'd4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5',
}

export const TEST_REPLAY_RESPONSE_MATCH = {
  manifest: TEST_RUN_MANIFEST,
  replayVarValue: 125000.50,
  replayExpectedShortfall: 187500.75,
  replayModelVersion: '1.4.2-abc9876',
  inputDigestMatch: true,
  originalInputDigest: TEST_RUN_MANIFEST.inputDigest,
  replayInputDigest: TEST_RUN_MANIFEST.inputDigest,
  originalVarValue: 125000.50,
  originalExpectedShortfall: 187500.75,
}

export const TEST_REPLAY_RESPONSE_MISMATCH = {
  manifest: TEST_RUN_MANIFEST,
  replayVarValue: 124850.25,
  replayExpectedShortfall: 187000.00,
  replayModelVersion: '1.4.3-def1234',
  inputDigestMatch: false,
  originalInputDigest: TEST_RUN_MANIFEST.inputDigest,
  replayInputDigest: 'e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6',
  originalVarValue: 125000.50,
  originalExpectedShortfall: 187500.75,
}

export const TEST_PNL_ATTRIBUTION = {
  totalPnl: '15250.00',
  deltaPnl: '8500.00',
  gammaPnl: '2200.00',
  vegaPnl: '1800.00',
  thetaPnl: '-1500.00',
  rhoPnl: '750.00',
  unexplainedPnl: '3500.00',
}

// ---------------------------------------------------------------------------
// Risk Tab route overrides
// ---------------------------------------------------------------------------

export interface MockRiskTabOptions {
  varResult?: object | null
  varStatus?: number
  historicalVarResult?: object | null
  positionRisk?: object[] | null
  positionRiskStatus?: number
  historicalPositionRisk?: object[] | null
  jobHistory?: object | null
  jobDetail?: object | null
  rules?: object[]
  alerts?: object[]
  sodStatus?: object | null
  pnlAttribution?: object | null
  stressScenarios?: string[]
  stressResult?: object | null
  postVarResult?: object | null
  postVarDelay?: number
  manifestResponse?: object | null
  replayResponse?: object | null
  replayStatus?: number
}

/**
 * Overrides the default risk-related API mocks registered by `mockAllApiRoutes`.
 * Call this AFTER `mockAllApiRoutes`. Unroutes the catch-all handlers and registers
 * specific ones based on the options provided.
 */
export async function mockRiskTabRoutes(
  page: Page,
  opts: MockRiskTabOptions = {},
): Promise<void> {
  // Remove all existing handlers that we need to override
  await page.unroute('**/api/v1/risk/**')
  await page.unroute('**/api/v1/risk/positions/*')
  await page.unroute('**/api/v1/risk/var/*')
  await page.unroute('**/api/v1/risk/stress/scenarios')
  await page.unroute('**/api/v1/notifications/rules')
  await page.unroute('**/api/v1/notifications/alerts*')

  // NOTE: Playwright gives priority to the LAST registered matching route.
  // Register the catch-all FIRST so that more specific routes registered
  // afterward take precedence.

  // 1. Catch-all for remaining risk endpoints (lowest priority)
  await page.route('**/api/v1/risk/**', (route: Route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })

  // 2. Notifications alerts
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.alerts ?? []) })
  })

  // 3. Notifications rules
  await page.route('**/api/v1/notifications/rules', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.rules ?? []) })
  })

  // 4. Stress run (generic pattern before scenarios)
  await page.route('**/api/v1/risk/stress/*', (route: Route) => {
    if (route.request().method() === 'POST') {
      if (opts.stressResult) {
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.stressResult) })
      } else {
        route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
      }
    } else {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) })
    }
  })

  // 5. Stress scenarios (more specific, registered after generic stress)
  await page.route('**/api/v1/risk/stress/scenarios', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.stressScenarios ?? []) })
  })

  // 6. P&L attribution GET (generic pattern first)
  await page.route('**/api/v1/risk/pnl-attribution/*', (route: Route) => {
    if (opts.pnlAttribution) {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.pnlAttribution) })
    } else {
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
    }
  })

  // 7. P&L attribution compute POST (more specific, registered after)
  await page.route('**/api/v1/risk/pnl-attribution/*/compute', (route: Route) => {
    if (opts.pnlAttribution) {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.pnlAttribution) })
    } else {
      route.fulfill({ status: 412, contentType: 'application/json', body: JSON.stringify({ message: 'No baseline' }) })
    }
  })

  // 8. SOD baseline status
  await page.route('**/api/v1/risk/sod-snapshot/*/status', (route: Route) => {
    if (opts.sodStatus === undefined || opts.sodStatus === null) {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ exists: false, baselineDate: null, snapshotType: null, createdAt: null, sourceJobId: null, calculationType: null }),
      })
    } else {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.sodStatus) })
    }
  })

  // 8b. Replay POST
  await page.route('**/api/v1/risk/runs/*/replay', (route: Route) => {
    if (route.request().method() === 'POST') {
      const status = opts.replayStatus ?? 200
      route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(opts.replayResponse ?? null),
      })
    } else {
      route.fallback()
    }
  })

  // 8c. Manifest GET
  await page.route('**/api/v1/risk/runs/*/manifest', (route: Route) => {
    if (opts.manifestResponse) {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(opts.manifestResponse),
      })
    } else {
      route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify(null),
      })
    }
  })

  // 9. Job history (list)
  await page.route('**/api/v1/risk/jobs/*', (route: Route) => {
    const data = opts.jobHistory ?? { items: [], totalCount: 0 }
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) })
  })

  // 9b. Job detail (more specific — registered after so it takes priority)
  await page.route('**/api/v1/risk/jobs/detail/*', (route: Route) => {
    if (opts.jobDetail) {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.jobDetail) })
    } else {
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
    }
  })

  // 10. Position risk
  await page.route('**/api/v1/risk/positions/*', (route: Route) => {
    const url = route.request().url()
    const isHistorical = url.includes('valuationDate=')
    const data = isHistorical ? (opts.historicalPositionRisk ?? opts.positionRisk) : opts.positionRisk
    if (data === undefined || data === null) {
      route.fulfill({ status: opts.positionRiskStatus ?? 404, contentType: 'application/json', body: JSON.stringify([]) })
    } else {
      route.fulfill({
        status: opts.positionRiskStatus ?? 200,
        contentType: 'application/json',
        body: JSON.stringify(data),
      })
    }
  })

  // 11. VaR GET/POST (highest priority — registered last)
  await page.route('**/api/v1/risk/var/*', (route: Route) => {
    const method = route.request().method()
    if (method === 'POST') {
      const body = opts.postVarResult ?? opts.varResult ?? null
      const delay = opts.postVarDelay ?? 0
      if (delay > 0) {
        setTimeout(
          () => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) }),
          delay,
        )
      } else {
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
      }
    } else {
      const url = route.request().url()
      const isHistorical = url.includes('valuationDate=')
      const result = isHistorical ? (opts.historicalVarResult ?? opts.varResult) : opts.varResult
      if (result === undefined || result === null) {
        route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
      } else {
        route.fulfill({
          status: opts.varStatus ?? 200,
          contentType: 'application/json',
          body: JSON.stringify(result),
        })
      }
    }
  })

  // 12. Cross-book VaR — registered AFTER the generic var/* handler so it takes
  //     priority for /api/v1/risk/var/cross-book and /api/v1/risk/var/cross-book/*
  await page.route('**/api/v1/risk/var/cross-book', (route: Route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })
  await page.route('**/api/v1/risk/var/cross-book/*', (route: Route) => {
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
  })
}

// ---------------------------------------------------------------------------
// EOD Timeline fixture data and mock helpers
// ---------------------------------------------------------------------------

export interface EodTimelineEntryFixture {
  valuationDate: string
  jobId: string
  varValue: number | null
  expectedShortfall: number | null
  pvValue: number | null
  delta: number | null
  gamma: number | null
  vega: number | null
  theta: number | null
  rho: number | null
  promotedAt: string | null
  promotedBy: string | null
  varChange: number | null
  varChangePct: number | null
  esChange: number | null
  calculationType: string | null
  confidenceLevel: number | null
}

export const TEST_EOD_TIMELINE_ENTRIES: EodTimelineEntryFixture[] = [
  {
    valuationDate: '2026-03-13',
    jobId: 'job-eod-13',
    varValue: 108000,
    expectedShortfall: 162000,
    pvValue: 5200000,
    delta: 0.52,
    gamma: 0.011,
    vega: 220,
    theta: -52,
    rho: 26,
    promotedAt: '2026-03-13T19:05:00Z',
    promotedBy: 'risk-manager',
    varChange: 8000,
    varChangePct: 8.0,
    esChange: 12000,
    calculationType: 'PARAMETRIC',
    confidenceLevel: 0.99,
  },
  {
    valuationDate: '2026-03-12',
    jobId: 'job-eod-12',
    varValue: 100000,
    expectedShortfall: 150000,
    pvValue: 5000000,
    delta: 0.5,
    gamma: 0.01,
    vega: 200,
    theta: -50,
    rho: 25,
    promotedAt: '2026-03-12T19:00:00Z',
    promotedBy: 'risk-manager',
    varChange: null,
    varChangePct: null,
    esChange: null,
    calculationType: 'PARAMETRIC',
    confidenceLevel: 0.99,
  },
  {
    valuationDate: '2026-03-11',
    jobId: 'job-eod-11',
    varValue: 95000,
    expectedShortfall: 142500,
    pvValue: 4900000,
    delta: 0.48,
    gamma: 0.009,
    vega: 190,
    theta: -48,
    rho: 24,
    promotedAt: '2026-03-11T18:55:00Z',
    promotedBy: 'risk-manager',
    varChange: -5000,
    varChangePct: -5.0,
    esChange: -7500,
    calculationType: 'PARAMETRIC',
    confidenceLevel: 0.99,
  },
  {
    // Missing EOD — no varValue
    valuationDate: '2026-03-10',
    jobId: '',
    varValue: null,
    expectedShortfall: null,
    pvValue: null,
    delta: null,
    gamma: null,
    vega: null,
    theta: null,
    rho: null,
    promotedAt: null,
    promotedBy: null,
    varChange: null,
    varChangePct: null,
    esChange: null,
    calculationType: null,
    confidenceLevel: null,
  },
  {
    valuationDate: '2026-03-07',
    jobId: 'job-eod-07',
    varValue: 102000,
    expectedShortfall: 153000,
    pvValue: 5050000,
    delta: 0.51,
    gamma: 0.010,
    vega: 205,
    theta: -51,
    rho: 25,
    promotedAt: '2026-03-07T19:10:00Z',
    promotedBy: 'risk-manager',
    varChange: 2000,
    varChangePct: 2.0,
    esChange: 3000,
    calculationType: 'PARAMETRIC',
    confidenceLevel: 0.99,
  },
]

export const TEST_EOD_TIMELINE_RESPONSE = {
  bookId: 'port-1',
  from: '2026-02-01',
  to: '2026-03-15',
  entries: TEST_EOD_TIMELINE_ENTRIES,
}

// ---------------------------------------------------------------------------
// Live-seed shape: mirrors the response the SEED ValuationJob factory in
// risk-orchestrator produces against the live demo. Pinned by the
// EOD History PV-column regression guard in eod-timeline.spec.ts: every
// promoted row must carry a numeric pvValue (the SEED computes it from
// the demo book's notional baseline). When the seed produces null
// pvValues the UI falls back to em-dash and the guard fails.
//
// NB: this fixture's pvValues are kept in lock-step with what the
// `DevDataSeeder.buildSeedJobs()` factory persists. Update both
// together when the seed's PV computation changes.
// ---------------------------------------------------------------------------
export const TEST_EOD_TIMELINE_LIVE_SEED_ENTRIES: EodTimelineEntryFixture[] = [
  {
    valuationDate: '2026-05-27',
    jobId: 'job-seed-2026-05-27',
    varValue: 2_162_163.54,
    expectedShortfall: 2_653_564.35,
    // Tracks DevDataSeeder.basePv (~30x baseVaR, drifts O(1%) daily).
    pvValue: 64_800_000,
    delta: null,
    gamma: null,
    vega: null,
    theta: null,
    rho: null,
    promotedAt: '2026-05-27T17:30:00Z',
    promotedBy: 'SEED',
    varChange: 16_251.87,
    varChangePct: 0.76,
    esChange: 19_945.48,
    calculationType: 'PARAMETRIC',
    confidenceLevel: 0.95,
  },
  {
    valuationDate: '2026-05-26',
    jobId: 'job-seed-2026-05-26',
    varValue: 2_145_911.67,
    expectedShortfall: 2_633_618.87,
    pvValue: 64_600_000,
    delta: null,
    gamma: null,
    vega: null,
    theta: null,
    rho: null,
    promotedAt: '2026-05-26T17:30:00Z',
    promotedBy: 'SEED',
    varChange: 5_675.34,
    varChangePct: 0.27,
    esChange: 6_965.19,
    calculationType: 'PARAMETRIC',
    confidenceLevel: 0.95,
  },
  {
    valuationDate: '2026-05-25',
    jobId: 'job-seed-2026-05-25',
    varValue: 2_140_236.34,
    expectedShortfall: 2_626_653.69,
    pvValue: 64_500_000,
    delta: null,
    gamma: null,
    vega: null,
    theta: null,
    rho: null,
    promotedAt: '2026-05-25T17:30:00Z',
    promotedBy: 'SEED',
    varChange: -83_732.68,
    varChangePct: -3.76,
    esChange: -102_762.83,
    calculationType: 'PARAMETRIC',
    confidenceLevel: 0.95,
  },
]

export const TEST_EOD_TIMELINE_LIVE_SEED_RESPONSE = {
  bookId: 'equity-growth',
  from: '2026-05-25',
  to: '2026-05-27',
  entries: TEST_EOD_TIMELINE_LIVE_SEED_ENTRIES,
}

export const TEST_EOD_TIMELINE_EMPTY = {
  bookId: 'port-1',
  from: '2026-02-01',
  to: '2026-03-15',
  entries: [],
}

/**
 * Sets up mock routes for the EOD timeline endpoint.
 * Call AFTER mockAllApiRoutes — this must come before the catch-all risk/** handler.
 */
export async function mockEodTimelineRoutes(
  page: Page,
  response: object = TEST_EOD_TIMELINE_RESPONSE,
  status = 200,
): Promise<void> {
  await page.route('**/api/v1/risk/eod-timeline/**', (route: Route) => {
    route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(status === 200 ? response : { error: 'Internal server error' }),
    })
  })
}

// ---------------------------------------------------------------------------
// Existing types and helpers below
// ---------------------------------------------------------------------------

export interface AlertRuleFixture {
  id: string
  name: string
  type: string
  threshold: number
  operator: string
  severity: string
  channels: string[]
  enabled: boolean
}

/**
 * Sets up mock routes for alert rule CRUD operations.
 * Maintains an in-memory array of rules that the tests can populate.
 * Call AFTER mockAllApiRoutes so that the new handlers override the defaults.
 */
export async function mockAlertRuleCrud(
  page: Page,
  initialRules: AlertRuleFixture[] = [],
): Promise<void> {
  let rules = [...initialRules]
  let nextId = initialRules.length + 1

  // Unroute default rules and alerts handlers so we can replace them
  await page.unroute('**/api/v1/notifications/rules')
  await page.unroute('**/api/v1/notifications/alerts*')

  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    })
  })

  await page.route('**/api/v1/notifications/rules', (route: Route) => {
    const method = route.request().method()
    if (method === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(rules),
      })
    } else if (method === 'POST') {
      const body = route.request().postDataJSON()
      const newRule: AlertRuleFixture = {
        id: `rule-${nextId++}`,
        name: body.name,
        type: body.type,
        threshold: body.threshold,
        operator: body.operator,
        severity: body.severity,
        channels: body.channels,
        enabled: true,
      }
      rules.push(newRule)
      route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(newRule),
      })
    } else {
      route.fallback()
    }
  })

  await page.route('**/api/v1/notifications/rules/*', (route: Route) => {
    const method = route.request().method()
    if (method === 'DELETE') {
      const url = route.request().url()
      const ruleId = url.split('/').pop()!
      rules = rules.filter((r) => r.id !== ruleId)
      route.fulfill({ status: 204 })
    } else {
      route.fallback()
    }
  })
}

// ---------------------------------------------------------------------------
// Intraday P&L fixtures
// ---------------------------------------------------------------------------

export interface IntradayPnlSnapshotFixture {
  snapshotAt: string
  baseCurrency: string
  trigger: string
  totalPnl: string
  realisedPnl: string
  unrealisedPnl: string
  deltaPnl: string
  gammaPnl: string
  vegaPnl: string
  thetaPnl: string
  rhoPnl: string
  unexplainedPnl: string
  highWaterMark: string
}

export const TEST_INTRADAY_PNL_SNAPSHOTS: IntradayPnlSnapshotFixture[] = [
  {
    snapshotAt: '2026-03-24T09:30:00Z',
    baseCurrency: 'USD',
    trigger: 'position_change',
    totalPnl: '1000.00',
    realisedPnl: '200.00',
    unrealisedPnl: '800.00',
    deltaPnl: '800.00',
    gammaPnl: '50.00',
    vegaPnl: '30.00',
    thetaPnl: '-10.00',
    rhoPnl: '5.00',
    unexplainedPnl: '125.00',
    highWaterMark: '1200.00',
  },
  {
    snapshotAt: '2026-03-24T09:31:00Z',
    baseCurrency: 'USD',
    trigger: 'price_update',
    totalPnl: '1500.00',
    realisedPnl: '500.00',
    unrealisedPnl: '1000.00',
    deltaPnl: '1200.00',
    gammaPnl: '80.00',
    vegaPnl: '40.00',
    thetaPnl: '-15.00',
    rhoPnl: '7.00',
    unexplainedPnl: '188.00',
    highWaterMark: '1800.00',
  },
]

export async function mockIntradayPnlRoutes(
  page: Page,
  bookId: string,
  snapshots: IntradayPnlSnapshotFixture[],
): Promise<void> {
  await page.unroute(`**/api/v1/risk/pnl/intraday/${bookId}*`)
  await page.route(`**/api/v1/risk/pnl/intraday/${bookId}*`, (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ bookId, snapshots }),
    })
  })
}

// ---------------------------------------------------------------------------
// Intraday VaR timeline fixtures
// ---------------------------------------------------------------------------

export interface IntradayVaRPointFixture {
  timestamp: string
  varValue: number
  expectedShortfall: number
  delta: number | null
  gamma: number | null
  vega: number | null
}

export interface TradeAnnotationFixture {
  timestamp: string
  instrumentId: string
  side: string
  quantity: string
  tradeId: string
}

export const TEST_INTRADAY_VAR_POINTS: IntradayVaRPointFixture[] = [
  {
    timestamp: '2026-03-25T09:00:00Z',
    varValue: 10000.0,
    expectedShortfall: 12500.0,
    delta: 0.60,
    gamma: null,
    vega: null,
  },
  {
    timestamp: '2026-03-25T09:30:00Z',
    varValue: 12500.0,
    expectedShortfall: 15000.0,
    delta: 0.65,
    gamma: null,
    vega: null,
  },
]

export const TEST_INTRADAY_TRADE_ANNOTATIONS: TradeAnnotationFixture[] = [
  {
    timestamp: '2026-03-25T09:15:00Z',
    instrumentId: 'AAPL',
    side: 'BUY',
    quantity: '100',
    tradeId: 'T001',
  },
]

export async function mockIntradayVaRTimelineRoutes(
  page: Page,
  bookId: string,
  varPoints: IntradayVaRPointFixture[],
  tradeAnnotations: TradeAnnotationFixture[] = [],
): Promise<void> {
  await page.unroute(`**/api/v1/risk/var/${bookId}/intraday*`)
  await page.route(`**/api/v1/risk/var/${bookId}/intraday*`, (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ bookId, varPoints, tradeAnnotations }),
    })
  })
}

// ---------------------------------------------------------------------------
// Factor risk fixture data
// ---------------------------------------------------------------------------

export const TEST_FACTOR_RISK_RESULT = {
  bookId: 'port-1',
  calculatedAt: '2026-03-24T10:00:00Z',
  totalVar: 50_000.0,
  systematicVar: 38_000.0,
  idiosyncraticVar: 12_000.0,
  rSquared: 0.576,
  concentrationWarning: false,
  factors: [
    {
      factorType: 'EQUITY_BETA',
      varContribution: 20_000.0,
      pctOfTotal: 40.0,
      loading: 1.12,
      loadingMethod: 'OLS',
    },
    {
      factorType: 'RATES_DURATION',
      varContribution: 10_000.0,
      pctOfTotal: 20.0,
      loading: -0.35,
      loadingMethod: 'OLS',
    },
    {
      factorType: 'CREDIT_SPREAD',
      varContribution: 8_000.0,
      pctOfTotal: 16.0,
      loading: 0.22,
      loadingMethod: 'OLS',
    },
    {
      factorType: 'FX_DELTA',
      varContribution: 6_000.0,
      pctOfTotal: 12.0,
      loading: 0.08,
      loadingMethod: 'OLS',
    },
    {
      factorType: 'VOL_EXPOSURE',
      varContribution: 6_000.0,
      pctOfTotal: 12.0,
      loading: -0.15,
      loadingMethod: 'OLS',
    },
  ],
}

export const TEST_FACTOR_RISK_CONCENTRATION_WARNING = {
  ...TEST_FACTOR_RISK_RESULT,
  concentrationWarning: true,
  factors: [
    {
      ...TEST_FACTOR_RISK_RESULT.factors[0],
      pctOfTotal: 75.0,
    },
    ...TEST_FACTOR_RISK_RESULT.factors.slice(1),
  ],
}

/**
 * Mocks the factor risk API endpoints for a book.
 * - `latest`: controls GET /factor-risk/latest (null → 404)
 * - `history`: controls GET /factor-risk?limit=... (defaults to empty array)
 */
export async function mockFactorRiskRoutes(
  page: Page,
  opts: { latest?: object | null; history?: object[] } = {},
): Promise<void> {
  await page.route('**/api/v1/books/*/factor-risk/latest', (route: Route) => {
    if (opts.latest) {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(opts.latest),
      })
    } else {
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
    }
  })

  await page.route('**/api/v1/books/*/factor-risk*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(opts.history ?? []),
    })
  })
}

export const TEST_HEDGE_RECOMMENDATION = {
  id: '00000000-0000-0000-0000-000000000001',
  bookId: 'port-1',
  targetMetric: 'DELTA',
  targetReductionPct: 0.80,
  requestedAt: '2026-03-24T10:00:00Z',
  status: 'PENDING',
  expiresAt: '2026-03-24T10:30:00Z',
  acceptedBy: null,
  acceptedAt: null,
  sourceJobId: 'job-123',
  suggestions: [
    {
      instrumentId: 'AAPL-P-2026',
      instrumentType: 'OPTION',
      side: 'BUY',
      quantity: 1000,
      estimatedCost: 5250,
      crossingCost: 250,
      carrycostPerDay: -5,
      targetReduction: 950,
      targetReductionPct: 0.95,
      residualMetric: 50,
      greekImpact: {
        deltaBefore: 1000,
        deltaAfter: 50,
        gammaBefore: 200,
        gammaAfter: 210,
        vegaBefore: 500,
        vegaAfter: 480,
        thetaBefore: -30,
        thetaAfter: -31.5,
        rhoBefore: 10,
        rhoAfter: 9.8,
      },
      liquidityTier: 'HIGH_LIQUID',
      dataQuality: 'FRESH',
    },
  ],
  preHedgeGreeks: {
    deltaBefore: 1000,
    deltaAfter: 50,
    gammaBefore: 200,
    gammaAfter: 210,
    vegaBefore: 500,
    vegaAfter: 480,
    thetaBefore: -30,
    thetaAfter: -31.5,
    rhoBefore: 10,
    rhoAfter: 9.8,
  },
  totalEstimatedCost: 5250,
  isExpired: false,
}

/**
 * Registers a mock for the hedge suggest POST endpoint.
 * Call this AFTER mockAllApiRoutes — it must come before the catch-all risk/** handler.
 */
export async function mockHedgeSuggest(
  page: Page,
  response: object = TEST_HEDGE_RECOMMENDATION,
  statusCode = 201,
): Promise<void> {
  await page.route('**/api/v1/risk/hedge-suggest/*', (route: Route) => {
    if (route.request().method() === 'POST') {
      route.fulfill({
        status: statusCode,
        contentType: 'application/json',
        body: JSON.stringify(response),
      })
    } else {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([response]),
      })
    }
  })
}

// ---------------------------------------------------------------------------
// Counterparty risk fixture data
// ---------------------------------------------------------------------------

export const TEST_COUNTERPARTY_EXPOSURES = [
  {
    counterpartyId: 'CP-GS',
    calculatedAt: '2026-03-24T10:00:00Z',
    currentNetExposure: 2_000_000,
    peakPfe: 1_800_000,
    cva: 12_500,
    cvaEstimated: false,
    currency: 'USD',
    pfeProfile: [
      { tenor: '1Y', tenorYears: 1, expectedExposure: 1_500_000, pfe95: 1_800_000, pfe99: 2_000_000 },
      { tenor: '2Y', tenorYears: 2, expectedExposure: 1_200_000, pfe95: 1_500_000, pfe99: 1_700_000 },
      { tenor: '3Y', tenorYears: 3, expectedExposure: 900_000, pfe95: 1_200_000, pfe99: 1_400_000 },
    ],
  },
  {
    counterpartyId: 'CP-JPM',
    calculatedAt: '2026-03-24T10:00:00Z',
    currentNetExposure: 6_500_000,
    peakPfe: 7_200_000,
    cva: null,
    cvaEstimated: false,
    currency: 'USD',
    pfeProfile: [],
  },
]

export const TEST_COUNTERPARTY_CVA_RESULT = {
  ...TEST_COUNTERPARTY_EXPOSURES[0],
  cva: 18_750,
  cvaEstimated: false,
}

/**
 * Registers mock handlers for all counterparty risk endpoints.
 * Call this AFTER mockAllApiRoutes.
 */
export async function mockCounterpartyRiskRoutes(
  page: Page,
  exposures: object[] = TEST_COUNTERPARTY_EXPOSURES,
): Promise<void> {
  await page.route('**/api/v1/counterparty-risk', (route: Route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(exposures),
      })
    } else {
      route.continue()
    }
  })

  await page.route('**/api/v1/counterparty-risk/*/history*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(exposures.slice(0, 1)),
    })
  })

  await page.route('**/api/v1/counterparty-risk/*/pfe', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(exposures[0]),
    })
  })

  await page.route('**/api/v1/counterparty-risk/*/cva', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TEST_COUNTERPARTY_CVA_RESULT),
    })
  })

  await page.route('**/api/v1/counterparty-risk/*', (route: Route) => {
    const url = route.request().url()
    const counterpartyId = url.split('/api/v1/counterparty-risk/')[1]?.split('?')[0]
    const found = (exposures as Array<{ counterpartyId: string }>).find(
      (e) => e.counterpartyId === counterpartyId,
    )
    if (found) {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(found),
      })
    } else {
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
    }
  })
}

// ---------------------------------------------------------------------------
// AI insights fixture data and mock helpers
// ---------------------------------------------------------------------------

export const TEST_INSIGHT_RESPONSE = {
  narrative: 'VaR is driven by concentrated equity exposure.',
  bullets: [
    'AAPL contributes 32% of total VaR.',
    'MSFT contributes 18%.',
    'Regime is high-vol; widen confidence bands.',
  ],
  model: 'canned',
  mode: 'canned',
}

/**
 * Mocks the AI insights explainer endpoints used by §2 of plans/ai-v1.md.
 *
 * - `varResponse`: payload returned for POST /api/v1/insights/explain/var
 *   (defaults to TEST_INSIGHT_RESPONSE, a "Demo mode" canned answer).
 * - `reportResponse`: payload returned for POST /api/v1/insights/explain/report
 *   (defaults to the same canned answer — used by the report explainer in §3).
 * - `varStatus` / `reportStatus`: override the HTTP status to exercise error paths.
 *
 * Call this AFTER mockAllApiRoutes so the routes take priority.
 */
export async function insightsMock(
  page: Page,
  opts: {
    varResponse?: object
    reportResponse?: object
    varStatus?: number
    reportStatus?: number
  } = {},
): Promise<void> {
  await page.unroute('**/api/v1/insights/explain/var')
  await page.unroute('**/api/v1/insights/explain/report')

  await page.route('**/api/v1/insights/explain/var', (route: Route) => {
    route.fulfill({
      status: opts.varStatus ?? 200,
      contentType: 'application/json',
      body: JSON.stringify(opts.varResponse ?? TEST_INSIGHT_RESPONSE),
    })
  })

  await page.route('**/api/v1/insights/explain/report', (route: Route) => {
    route.fulfill({
      status: opts.reportStatus ?? 200,
      contentType: 'application/json',
      body: JSON.stringify(opts.reportResponse ?? TEST_INSIGHT_RESPONSE),
    })
  })
}

/**
 * Mocks POST /api/v1/insights/chat with a deterministic multi-chunk
 * Server-Sent Events body so Playwright specs can exercise the v2
 * copilot surface (CommandPalette copilot zone, StreamingNarrative,
 * CitationList) without a live ai-insights-service.
 *
 * The body emits, in order: two `delta` frames (incremental tokens),
 * one `event: source` frame carrying a JSON ARRAY of one Citation, and
 * a terminal `done` frame with `mode:"canned"` so the demo-mode badge
 * renders. Frame shapes mirror the wire contract parsed by `chat()` in
 * `ui/src/api/copilot.ts`. Opt-in — only specs that need the copilot
 * surface should call it.
 */
export async function chatMockCanned(page: Page): Promise<void> {
  const citation = {
    tool: 'get_book_var',
    params: { book_id: 'port-1' },
    result_field: 'total_var',
    result_value: 5200000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-19T08:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 120,
    quality_flags: [],
  }
  const body = [
    'data: {"delta":"Your VaR rose ","done":false}\n\n',
    'data: {"delta":"on tech beta.","done":false}\n\n',
    'event: source\ndata: ' + JSON.stringify([citation]) + '\n\n',
    'data: ' +
      JSON.stringify({
        done: true,
        session_id: 'sess-e2e',
        conversation_id: 'conv-e2e',
        model: 'canned-chat',
        mode: 'canned',
        citations: [citation],
      }) +
      '\n\n',
  ].join('')

  await page.unroute('**/api/v1/insights/chat')
  await page.route('**/api/v1/insights/chat', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body,
    })
  })
}

/**
 * Variant of {@link chatMockCanned} that includes two ``tool_calls`` in the
 * terminal ``done`` frame. Used by Playwright specs that assert the
 * ``tool-call-list`` reasoning panel.
 *
 * The ``tool_calls`` array mirrors the shape used by the VaR-explainer fixture
 * (``var-explainer-default.json``) so the E2E test exercises the same data
 * path as the real canned transcripts.
 */
export async function chatMockCannedWithToolCalls(page: Page): Promise<void> {
  const citation = {
    tool: 'get_book_var',
    params: { book_id: 'port-1' },
    result_field: 'total_var',
    result_value: 5200000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-19T08:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 120,
    quality_flags: [],
  }
  const toolCalls = [
    {
      name: 'get_book_var',
      params: { book_id: 'port-1', horizon_days: 1 },
      status: 'ok',
      started_at: '2026-05-19T08:00:00Z',
      completed_at: '2026-05-19T08:00:00.250000Z',
    },
    {
      name: 'get_greeks_summary',
      params: { book_id: 'port-1' },
      status: 'ok',
      started_at: '2026-05-19T08:00:00.250000Z',
      completed_at: '2026-05-19T08:00:00.480000Z',
    },
  ]
  const body = [
    'data: {"delta":"Your VaR rose ","done":false}\n\n',
    'data: {"delta":"on tech beta.","done":false}\n\n',
    'event: source\ndata: ' + JSON.stringify([citation]) + '\n\n',
    'data: ' +
      JSON.stringify({
        done: true,
        session_id: 'sess-e2e-tc',
        conversation_id: 'conv-e2e-tc',
        model: 'canned-chat',
        mode: 'canned',
        citations: [citation],
        tool_calls: toolCalls,
      }) +
      '\n\n',
  ].join('')

  await page.unroute('**/api/v1/insights/chat')
  await page.route('**/api/v1/insights/chat', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body,
    })
  })
}

/**
 * Mocks GET /api/v1/insights/brief/today with a deterministic, canned
 * morning brief so Playwright specs can exercise the §6.10 morning-brief
 * surface (<MorningBriefCard> inside <NotificationStrip>) without a live
 * ai-insights-service.
 *
 * Fulfils 200 with a `status:"ready"` body carrying a single book brief
 * (`mode:"canned"` so the "Demo mode" badge renders), with two sections
 * — each with bullets, and the first with one citation. Opt-in — only
 * specs that need the morning-brief surface should call it. Call AFTER
 * mockAllApiRoutes so it takes priority over the catch-all.
 */
export async function briefMockCanned(page: Page): Promise<void> {
  const citation = {
    tool: 'get_book_var',
    params: { book_id: 'fx-main' },
    result_field: 'total_var',
    result_value: 5_200_000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-20T07:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 300,
    quality_flags: [],
  }
  const body = {
    status: 'ready',
    mode: 'canned',
    generated_at: '2026-05-20T07:05:00Z',
    briefs: [
      {
        book_id: 'fx-main',
        generated_at: '2026-05-20T07:05:00Z',
        mode: 'canned',
        sections: [
          {
            title: 'Overnight VaR move',
            narrative: 'VaR rose 12% overnight on widening EUR vol.',
            bullets: ['EURUSD vol up 4 pts', 'Net delta unchanged'],
            sources: [citation],
            severity: 'warning',
            status: 'ok',
          },
          {
            title: 'Top movers',
            narrative: 'Three positions drove the bulk of the move.',
            bullets: ['EURUSD', 'GBPUSD', 'USDJPY'],
            sources: [],
            severity: 'info',
            status: 'ok',
          },
        ],
      },
    ],
  }

  await page.unroute('**/api/v1/insights/brief/today')
  await page.route('**/api/v1/insights/brief/today', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    })
  })
}

// ---------------------------------------------------------------------------
// Brinson attribution fixture data and mock helpers
// ---------------------------------------------------------------------------

export const TEST_BRINSON_ATTRIBUTION = {
  bookId: 'port-1',
  benchmarkId: 'SP500',
  asOfDate: '2026-03-25',
  sectors: [
    {
      sectorLabel: 'AAPL',
      portfolioWeight: 0.35,
      benchmarkWeight: 0.07,
      portfolioReturn: 0.12,
      benchmarkReturn: 0.10,
      allocationEffect: 0.028,
      selectionEffect: 0.014,
      interactionEffect: 0.004,
      totalActiveContribution: 0.046,
    },
    {
      sectorLabel: 'GOOGL',
      portfolioWeight: 0.25,
      benchmarkWeight: 0.065,
      portfolioReturn: 0.08,
      benchmarkReturn: 0.09,
      allocationEffect: 0.010,
      selectionEffect: -0.0065,
      interactionEffect: -0.0019,
      totalActiveContribution: 0.0016,
    },
  ],
  totalActiveReturn: 0.0476,
  totalAllocationEffect: 0.038,
  totalSelectionEffect: 0.0075,
  totalInteractionEffect: 0.0021,
}

/**
 * Mocks the Brinson attribution endpoint for a specific book.
 * Call this AFTER mockAllApiRoutes.
 */
export async function mockBrinsonAttributionRoutes(
  page: Page,
  opts: { data?: object | null; errorStatus?: number; errorMessage?: string } = {},
): Promise<void> {
  await page.route('**/api/v1/books/*/attribution*', (route: Route) => {
    if (opts.errorStatus) {
      route.fulfill({
        status: opts.errorStatus,
        contentType: 'text/plain',
        body: opts.errorMessage ?? 'Error',
      })
    } else if (opts.data === null) {
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(null) })
    } else {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(opts.data ?? TEST_BRINSON_ATTRIBUTION),
      })
    }
  })
}
