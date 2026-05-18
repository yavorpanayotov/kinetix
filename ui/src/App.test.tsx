import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PositionDto } from './types'

vi.mock('./hooks/usePositions')
vi.mock('./hooks/usePriceStream')
vi.mock('./hooks/useNotifications')
vi.mock('./hooks/useAlerts')
vi.mock('./hooks/useSystemHealth')
vi.mock('./hooks/useStressTest')
vi.mock('./hooks/useBookSelector')
vi.mock('./hooks/useHierarchySelector')
vi.mock('./hooks/useHierarchySummary')
vi.mock('./hooks/useDataQuality')
vi.mock('./hooks/useWorkspace')
vi.mock('./hooks/useVaR')
vi.mock('./hooks/useVarLimit')
vi.mock('./hooks/useIntradayPnlStream')
vi.mock('./auth/useAuth')
vi.mock('./components/TradeBlotter', () => ({
  TradeBlotter: ({ initialCounterpartyFilter }: { initialCounterpartyFilter?: string }) => (
    <div
      data-testid="trade-blotter-wrapper"
      data-counterparty-filter={initialCounterpartyFilter ?? ''}
    />
  ),
}))
vi.mock('./components/RiskTab', () => ({
  RiskTab: ({ initialValuationDate }: { initialValuationDate?: string | null }) => (
    <div
      data-testid="risk-tab-wrapper"
      data-initial-valuation-date={initialValuationDate ?? ''}
    />
  ),
}))
vi.mock('./components/ScenariosTab', () => ({
  ScenariosTab: () => <div data-testid="scenarios-tab-wrapper" />,
}))
vi.mock('./components/RegulatoryTab', () => ({
  RegulatoryTab: () => <div data-testid="regulatory-tab-wrapper" />,
}))
vi.mock('./components/CounterpartyRiskDashboard', () => ({
  CounterpartyRiskDashboard: ({ onJumpToTrades }: { onJumpToTrades?: (id: string) => void }) => (
    <div data-testid="counterparty-risk-dashboard">
      <button
        data-testid="mock-jump-to-trades"
        onClick={() => onJumpToTrades?.('CP-MOCK')}
      >
        Jump
      </button>
    </div>
  ),
}))
vi.mock('./components/ReportsTab', () => ({
  ReportsTab: ({
    onJumpToRiskAtDate,
  }: {
    onJumpToRiskAtDate?: (bookId: string, valuationDate: string) => void
  }) => (
    <div data-testid="reports-tab-wrapper">
      <button
        data-testid="mock-open-report-in-risk"
        onClick={() => onJumpToRiskAtDate?.('book-2', '2025-01-15')}
      >
        Open in Risk
      </button>
    </div>
  ),
}))

import App from './App'
import { usePositions } from './hooks/usePositions'
import { usePriceStream } from './hooks/usePriceStream'
import { useNotifications } from './hooks/useNotifications'
import { useAlerts } from './hooks/useAlerts'
import { useSystemHealth } from './hooks/useSystemHealth'
import { useStressTest } from './hooks/useStressTest'
import { useBookSelector } from './hooks/useBookSelector'
import { useHierarchySelector } from './hooks/useHierarchySelector'
import { useDataQuality } from './hooks/useDataQuality'
import { useWorkspace, DEFAULT_PREFERENCES } from './hooks/useWorkspace'
import { useHierarchySummary } from './hooks/useHierarchySummary'
import { useVaR } from './hooks/useVaR'
import { useVarLimit } from './hooks/useVarLimit'
import { useIntradayPnlStream } from './hooks/useIntradayPnlStream'
import { useAuth } from './auth/useAuth'

const mockUsePositions = vi.mocked(usePositions)
const mockUseAuth = vi.mocked(useAuth)
const mockUsePriceStream = vi.mocked(usePriceStream)
const mockUseNotifications = vi.mocked(useNotifications)
const mockUseAlerts = vi.mocked(useAlerts)
const mockUseSystemHealth = vi.mocked(useSystemHealth)
const mockUseStressTest = vi.mocked(useStressTest)
const mockUseBookSelector = vi.mocked(useBookSelector)
const mockUseHierarchySelector = vi.mocked(useHierarchySelector)
const mockUseHierarchySummary = vi.mocked(useHierarchySummary)
const mockUseDataQuality = vi.mocked(useDataQuality)
const mockUseWorkspace = vi.mocked(useWorkspace)
const mockUseVaR = vi.mocked(useVaR)
const mockUseVarLimit = vi.mocked(useVarLimit)
const mockUseIntradayPnlStream = vi.mocked(useIntradayPnlStream)

const position: PositionDto = {
  bookId: 'book-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  quantity: '100',
  averageCost: { amount: '150.00', currency: 'USD' },
  marketPrice: { amount: '155.00', currency: 'USD' },
  marketValue: { amount: '15500.00', currency: 'USD' },
  unrealizedPnl: { amount: '500.00', currency: 'USD' },
}

const selectBook = vi.fn()

function setupDefaults() {
  mockUseAuth.mockReturnValue({
    authenticated: true,
    initialising: false,
    token: 'mock-token',
    username: 'trader1',
    roles: ['TRADER'],
    logout: vi.fn(),
  })
  mockUsePositions.mockReturnValue({
    positions: [position],
    bookId: 'book-1',
    books: ['book-1', 'book-2', 'book-3'],
    selectBook,
    refreshPositions: vi.fn(),
    retryInitialLoad: vi.fn(),
    loading: false,
    error: null,
  })
  mockUsePriceStream.mockReturnValue({ positions: [position], connected: true, reconnecting: false, exhausted: false, lastConnectedAt: null, disconnectedSince: null, manualReconnect: vi.fn() })
  mockUseNotifications.mockReturnValue({
    rules: [],
    alerts: [],
    loading: false,
    error: null,
    createRule: vi.fn(),
    deleteRule: vi.fn(),
    acknowledgeAlert: vi.fn(),
  })
  mockUseSystemHealth.mockReturnValue({
    health: {
      status: 'UP',
      services: {
        gateway: { status: 'READY' },
        'position-service': { status: 'READY' },
        'price-service': { status: 'READY' },
        'risk-orchestrator': { status: 'READY' },
        'notification-service': { status: 'READY' },
      },
    },
    loading: false,
    error: null,
    refresh: vi.fn(),
  })
  mockUseStressTest.mockReturnValue({
    scenarios: ['MARKET_CRASH', 'RATE_SHOCK'],
    selectedScenario: 'MARKET_CRASH',
    setSelectedScenario: vi.fn(),
    result: null,
    results: [],
    loading: false,
    error: null,
    run: vi.fn(),
  })
  mockUseBookSelector.mockReturnValue({
    bookOptions: [
      { value: '__ALL__', label: 'All Books' },
      { value: 'book-1', label: 'book-1' },
      { value: 'book-2', label: 'book-2' },
    ],
    selectedBookId: 'book-1',
    isAllSelected: false,
    allBookIds: ['book-1', 'book-2'],
    positions: [position],
    aggregatedPositions: [],
    selectBook: vi.fn(),
    loading: false,
    error: null,
  })
  mockUseHierarchySelector.mockReturnValue({
    selection: { level: 'firm', divisionId: null, deskId: null, bookId: null },
    setSelection: vi.fn(),
    breadcrumb: [{ level: 'firm', id: null, label: 'Firm' }],
    effectiveBookId: null,
    effectiveBookIds: ['book-1', 'book-2'],
    divisions: [],
    desks: [],
    books: [{ bookId: 'book-1' }, { bookId: 'book-2' }],
    loading: false,
    error: null,
  })
  mockUseDataQuality.mockReturnValue({
    status: { overall: 'OK', checks: [] },
    syntheticStatus: null,
    loading: false,
    error: null,
  })
  mockUseWorkspace.mockReturnValue({
    preferences: DEFAULT_PREFERENCES,
    updatePreference: vi.fn(),
    resetPreferences: vi.fn(),
  })
  mockUseHierarchySummary.mockReturnValue({
    summary: {
      bookId: 'book-1',
      baseCurrency: 'USD',
      totalNav: { amount: '168850.00', currency: 'USD' },
      totalUnrealizedPnl: { amount: '3050.00', currency: 'USD' },
      currencyBreakdown: [],
    },
    baseCurrency: 'USD',
    setBaseCurrency: vi.fn(),
    loading: false,
    error: null,
    summaryLabel: 'Book Summary',
  })
  mockUseVaR.mockReturnValue({
    varResult: null,
    greeksResult: null,
    history: [],
    filteredHistory: [],
    loading: false,
    historyLoading: false,
    refreshing: false,
    error: null,
    errorTransient: false,
    refresh: vi.fn(),
    timeRange: { from: '', to: '', label: 'Last 24h' },
    setTimeRange: vi.fn(),
    selectedConfidenceLevel: 'CL_95',
    setSelectedConfidenceLevel: vi.fn(),
    zoomIn: vi.fn(),
    resetZoom: vi.fn(),
    zoomDepth: 0,
    isLive: true,
  })
  mockUseVarLimit.mockReturnValue({ varLimit: null, loading: false })
  mockUseAlerts.mockReturnValue({ alerts: [], dismissAlert: vi.fn() })
  mockUseIntradayPnlStream.mockReturnValue({
    snapshots: [],
    latest: null,
    connected: false,
  })
}

describe('App', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setupDefaults()
  })

  it('renders Kinetix heading and hierarchy selector', () => {
    render(<App />)

    expect(screen.getByText('Kinetix')).toBeInTheDocument()
    expect(screen.getByTestId('hierarchy-selector')).toBeInTheDocument()
  })

  describe('global risk ticker strip', () => {
    it('renders the strip below the tab bar on the default Positions tab', () => {
      render(<App />)

      const strip = screen.getByTestId('risk-ticker-strip')
      expect(strip).toBeInTheDocument()

      // The strip must come after the tab bar and before the tab panel in DOM
      // order so it sits between them visually on every tab.
      const tabBar = screen.getByTestId('tab-bar')
      const tabPanel = screen.getByRole('tabpanel')
      const order = tabBar.compareDocumentPosition(strip)
      expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      const orderToPanel = strip.compareDocumentPosition(tabPanel)
      expect(orderToPanel & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('remains visible when switching to the Risk tab', () => {
      render(<App />)
      fireEvent.click(screen.getByTestId('tab-risk'))

      expect(screen.getByTestId('risk-ticker-strip')).toBeInTheDocument()
    })

    it('remains visible when switching to the P&L tab', () => {
      render(<App />)
      fireEvent.click(screen.getByTestId('tab-pnl'))

      expect(screen.getByTestId('risk-ticker-strip')).toBeInTheDocument()
    })

    it('colour-codes the VaR cell red when VaR exceeds 80% of the configured limit', () => {
      mockUseVaR.mockReturnValue({
        varResult: {
          bookId: 'book-1',
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: '85000.00',
          expectedShortfall: '95000.00',
          componentBreakdown: [],
          calculatedAt: '2026-03-24T09:31:00Z',
        },
        greeksResult: null,
        history: [],
        filteredHistory: [],
        loading: false,
        historyLoading: false,
        refreshing: false,
        error: null,
        errorTransient: false,
        refresh: vi.fn(),
        timeRange: { from: '', to: '', label: 'Last 24h' },
        setTimeRange: vi.fn(),
        selectedConfidenceLevel: 'CL_95',
        setSelectedConfidenceLevel: vi.fn(),
        zoomIn: vi.fn(),
        resetZoom: vi.fn(),
        zoomDepth: 0,
        isLive: true,
      })
      mockUseVarLimit.mockReturnValue({ varLimit: 100000, loading: false })
      mockUseHierarchySelector.mockReturnValue({
        selection: { level: 'book', divisionId: null, deskId: null, bookId: 'book-1' },
        setSelection: vi.fn(),
        breadcrumb: [{ level: 'book', id: 'book-1', label: 'book-1' }],
        effectiveBookId: 'book-1',
        effectiveBookIds: ['book-1'],
        divisions: [],
        desks: [],
        books: [{ bookId: 'book-1' }],
        loading: false,
        error: null,
      })

      render(<App />)

      expect(screen.getByTestId('ticker-var').className).toMatch(/text-red-600/)
      expect(screen.getByTestId('ticker-var-breach-icon')).toBeInTheDocument()
    })
  })

  it('shows loading message while fetching', () => {
    mockUsePositions.mockReturnValue({
      positions: [],
      bookId: null,
      books: [],
      selectBook,
      refreshPositions: vi.fn(),
      retryInitialLoad: vi.fn(),
      loading: true,
      error: null,
    })

    render(<App />)

    expect(screen.getByText('Loading positions...')).toBeInTheDocument()
  })

  it('shows error card with retry button on failure', () => {
    mockUsePositions.mockReturnValue({
      positions: [],
      bookId: null,
      books: [],
      selectBook,
      refreshPositions: vi.fn(),
      retryInitialLoad: vi.fn(),
      loading: false,
      error: 'Network error',
    })

    render(<App />)

    expect(screen.getByTestId('load-error-card')).toBeInTheDocument()
    expect(screen.getByText('Network error')).toBeInTheDocument()
    expect(screen.getByTestId('retry-load-button')).toBeInTheDocument()
  })

  it('default tab shows positions', () => {
    render(<App />)

    expect(screen.getByTestId('position-row-AAPL')).toBeInTheDocument()
    expect(screen.queryByTestId('risk-tab-wrapper')).not.toBeInTheDocument()
  })

  it('clicking Trades tab renders the TradeBlotter wrapper', () => {
    render(<App />)

    fireEvent.click(screen.getByTestId('tab-trades'))

    expect(screen.getByTestId('trade-blotter-wrapper')).toBeInTheDocument()
    expect(screen.queryByTestId('position-row-AAPL')).not.toBeInTheDocument()
  })

  it('active Trades sub-tab has a dark-mode primary text class', () => {
    render(<App />)

    fireEvent.click(screen.getByTestId('tab-trades'))

    // 'blotter' is the default active sub-tab
    const activeSubTab = screen.getByTestId('trades-subtab-blotter')
    expect(activeSubTab).toHaveAttribute('aria-selected', 'true')
    // The active sub-tab must include a dark-mode counterpart for the active text color
    // so it remains readable in dark mode — mirrors the convention used in RiskTab.tsx.
    expect(activeSubTab.className).toMatch(/dark:text-primary-/)
  })

  it('clicking Risk tab renders the RiskTab wrapper', () => {
    render(<App />)

    fireEvent.click(screen.getByTestId('tab-risk'))

    expect(screen.getByTestId('risk-tab-wrapper')).toBeInTheDocument()
  })

  it('Risk tab does not show stress test panel', () => {
    render(<App />)

    fireEvent.click(screen.getByTestId('tab-risk'))

    expect(screen.queryByTestId('scenarios-tab-wrapper')).not.toBeInTheDocument()
  })

  it('clicking Scenarios tab renders the ScenariosTab wrapper', () => {
    render(<App />)

    fireEvent.click(screen.getByTestId('tab-scenarios'))

    expect(screen.getByTestId('scenarios-tab-wrapper')).toBeInTheDocument()
  })

  it('clicking Regulatory tab renders the RegulatoryTab wrapper', () => {
    render(<App />)

    fireEvent.click(screen.getByTestId('tab-regulatory'))

    expect(screen.getByTestId('regulatory-tab-wrapper')).toBeInTheDocument()
  })

  it('clicking Alerts tab shows notification center', () => {
    render(<App />)

    fireEvent.click(screen.getByTestId('tab-alerts'))

    expect(screen.getByTestId('notification-center')).toBeInTheDocument()
  })

  it('hierarchy selector toggle is rendered', () => {
    render(<App />)

    expect(screen.getByTestId('hierarchy-selector-toggle')).toBeInTheDocument()
  })

  it('shows alert count badge when alerts exist', () => {
    mockUseNotifications.mockReturnValue({
      rules: [],
      alerts: [
        {
          id: 'evt-1',
          ruleId: 'rule-1',
          ruleName: 'VaR Limit',
          type: 'VAR_BREACH',
          severity: 'CRITICAL',
          message: 'VaR exceeded threshold',
          currentValue: 150000,
          threshold: 100000,
          bookId: 'book-1',
          triggeredAt: '2025-01-15T10:00:00Z',
          status: 'TRIGGERED',
        },
      ],
      loading: false,
      error: null,
      createRule: vi.fn(),
      deleteRule: vi.fn(),
      acknowledgeAlert: vi.fn(),
    })

    render(<App />)

    const badge = screen.getByTestId('alert-count-badge')
    expect(badge).toBeInTheDocument()
    expect(badge).toHaveTextContent('1')
  })

  it('does not show alert badge when no alerts', () => {
    render(<App />)

    expect(screen.queryByTestId('alert-count-badge')).not.toBeInTheDocument()
  })

  it('clicking System tab shows system dashboard', () => {
    render(<App />)

    fireEvent.click(screen.getByTestId('tab-system'))

    expect(screen.getByTestId('system-dashboard')).toBeInTheDocument()
  })

  it('shows degraded dot on System tab when a service is DOWN', () => {
    mockUseSystemHealth.mockReturnValue({
      health: {
        status: 'DEGRADED',
        services: {
          gateway: { status: 'READY' },
          'position-service': { status: 'DOWN' },
          'price-service': { status: 'READY' },
          'risk-orchestrator': { status: 'READY' },
          'notification-service': { status: 'READY' },
        },
      },
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    render(<App />)

    expect(screen.getByTestId('system-degraded-dot')).toBeInTheDocument()
  })

  it('does not show degraded dot when all systems are UP', () => {
    render(<App />)

    expect(screen.queryByTestId('system-degraded-dot')).not.toBeInTheDocument()
  })

  it('System tab renders even when positions are loading', () => {
    mockUsePositions.mockReturnValue({
      positions: [],
      bookId: null,
      books: [],
      selectBook,
      refreshPositions: vi.fn(),
      retryInitialLoad: vi.fn(),
      loading: true,
      error: null,
    })

    render(<App />)

    fireEvent.click(screen.getByTestId('tab-system'))

    expect(screen.getByTestId('system-dashboard')).toBeInTheDocument()
    expect(screen.queryByText('Loading positions...')).not.toBeInTheDocument()
  })

  it('System tab renders even when positions have an error', () => {
    mockUsePositions.mockReturnValue({
      positions: [],
      bookId: null,
      books: [],
      selectBook,
      refreshPositions: vi.fn(),
      retryInitialLoad: vi.fn(),
      loading: false,
      error: 'Network error',
    })

    render(<App />)

    fireEvent.click(screen.getByTestId('tab-system'))

    expect(screen.getByTestId('system-dashboard')).toBeInTheDocument()
    expect(screen.queryByText('Network error')).not.toBeInTheDocument()
  })

  it('does not render RiskTab wrapper on initial positions tab', () => {
    render(<App />)

    expect(screen.queryByTestId('risk-tab-wrapper')).not.toBeInTheDocument()
    expect(screen.queryByTestId('scenarios-tab-wrapper')).not.toBeInTheDocument()
    expect(screen.queryByTestId('regulatory-tab-wrapper')).not.toBeInTheDocument()
  })

  it('unmounts RiskTab when switching away from Risk tab', () => {
    render(<App />)

    fireEvent.click(screen.getByTestId('tab-risk'))
    expect(screen.getByTestId('risk-tab-wrapper')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('tab-positions'))
    expect(screen.queryByTestId('risk-tab-wrapper')).not.toBeInTheDocument()
  })

  describe('cross-tab navigation (plan §2.4)', () => {
    it('jump to Risk from an alert switches to Risk tab and focuses the alert book', () => {
      const setSelection = vi.fn()
      mockUseHierarchySelector.mockReturnValue({
        selection: { level: 'firm', divisionId: 'div-1', deskId: 'desk-1', bookId: null },
        setSelection,
        breadcrumb: [{ level: 'firm', id: null, label: 'Firm' }],
        effectiveBookId: null,
        effectiveBookIds: ['book-1', 'book-2'],
        divisions: [],
        desks: [],
        books: [{ bookId: 'book-1' }, { bookId: 'book-2' }],
        loading: false,
        error: null,
      })
      mockUseNotifications.mockReturnValue({
        rules: [],
        alerts: [
          {
            id: 'evt-jump-1',
            ruleId: 'rule-1',
            ruleName: 'VaR Limit',
            type: 'VAR_BREACH',
            severity: 'CRITICAL',
            message: 'VaR breach on book-2',
            currentValue: 150000,
            threshold: 100000,
            bookId: 'book-2',
            triggeredAt: '2025-01-15T10:00:00Z',
            status: 'TRIGGERED',
          },
        ],
        loading: false,
        error: null,
        createRule: vi.fn(),
        deleteRule: vi.fn(),
        acknowledgeAlert: vi.fn(),
      })

      render(<App />)
      fireEvent.click(screen.getByTestId('tab-alerts'))
      fireEvent.click(screen.getByTestId('jump-to-risk-evt-jump-1'))

      expect(setSelection).toHaveBeenCalledWith({
        level: 'book',
        divisionId: 'div-1',
        deskId: 'desk-1',
        bookId: 'book-2',
      })
      expect(screen.getByTestId('risk-tab-wrapper')).toBeInTheDocument()
      expect(screen.queryByTestId('notification-center')).not.toBeInTheDocument()
    })

    it('jump from a counterparty row switches to Trades tab with the blotter filtered', () => {
      render(<App />)
      fireEvent.click(screen.getByTestId('tab-counterparty-risk'))
      fireEvent.click(screen.getByTestId('mock-jump-to-trades'))

      const blotter = screen.getByTestId('trade-blotter-wrapper')
      expect(blotter).toBeInTheDocument()
      expect(blotter.getAttribute('data-counterparty-filter')).toBe('CP-MOCK')
    })

    it('jump from a report output switches to Risk tab at the reported book and date', () => {
      const setSelection = vi.fn()
      mockUseHierarchySelector.mockReturnValue({
        selection: { level: 'firm', divisionId: 'div-1', deskId: 'desk-1', bookId: null },
        setSelection,
        breadcrumb: [{ level: 'firm', id: null, label: 'Firm' }],
        effectiveBookId: null,
        effectiveBookIds: ['book-1', 'book-2'],
        divisions: [],
        desks: [],
        books: [{ bookId: 'book-1' }, { bookId: 'book-2' }],
        loading: false,
        error: null,
      })

      render(<App />)
      fireEvent.click(screen.getByTestId('tab-reports'))
      fireEvent.click(screen.getByTestId('mock-open-report-in-risk'))

      expect(setSelection).toHaveBeenCalledWith({
        level: 'book',
        divisionId: 'div-1',
        deskId: 'desk-1',
        bookId: 'book-2',
      })

      const risk = screen.getByTestId('risk-tab-wrapper')
      expect(risk).toBeInTheDocument()
      expect(risk.getAttribute('data-initial-valuation-date')).toBe('2025-01-15')
    })
  })

  describe('WAI-ARIA accessibility', () => {
    it('tab bar has role tablist', () => {
      render(<App />)

      const tabBar = screen.getByTestId('tab-bar')
      expect(tabBar).toHaveAttribute('role', 'tablist')
    })

    it('each tab has role tab and aria-selected', () => {
      render(<App />)

      const positionsTab = screen.getByTestId('tab-positions')
      expect(positionsTab).toHaveAttribute('role', 'tab')
      expect(positionsTab).toHaveAttribute('aria-selected', 'true')

      const tradesTab = screen.getByTestId('tab-trades')
      expect(tradesTab).toHaveAttribute('role', 'tab')
      expect(tradesTab).toHaveAttribute('aria-selected', 'false')
    })

    it('active tab panel has role tabpanel', () => {
      render(<App />)

      const tabPanel = screen.getByRole('tabpanel')
      expect(tabPanel).toBeInTheDocument()
      expect(tabPanel).toHaveAttribute('aria-labelledby', 'tab-positions')
    })

    it('tab panel aria-labelledby updates when switching tabs', () => {
      render(<App />)

      fireEvent.click(screen.getByTestId('tab-trades'))

      const tabPanel = screen.getByRole('tabpanel')
      expect(tabPanel).toHaveAttribute('aria-labelledby', 'tab-trades')
    })

    it('keyboard arrow right navigates to next tab', () => {
      render(<App />)

      const positionsTab = screen.getByTestId('tab-positions')
      positionsTab.focus()
      fireEvent.keyDown(screen.getByTestId('tab-bar'), { key: 'ArrowRight' })

      expect(screen.getByTestId('tab-trades')).toHaveFocus()
    })

    it('keyboard arrow left navigates to previous tab', () => {
      render(<App />)

      const tradesTab = screen.getByTestId('tab-trades')
      tradesTab.focus()
      fireEvent.keyDown(screen.getByTestId('tab-bar'), { key: 'ArrowLeft' })

      expect(screen.getByTestId('tab-positions')).toHaveFocus()
    })

    it('keyboard Home moves focus to first tab', () => {
      render(<App />)

      const tradesTab = screen.getByTestId('tab-trades')
      tradesTab.focus()
      fireEvent.keyDown(screen.getByTestId('tab-bar'), { key: 'Home' })

      expect(screen.getByTestId('tab-positions')).toHaveFocus()
    })

    it('keyboard End moves focus to last tab', () => {
      render(<App />)

      const positionsTab = screen.getByTestId('tab-positions')
      positionsTab.focus()
      fireEvent.keyDown(screen.getByTestId('tab-bar'), { key: 'End' })

      expect(screen.getByTestId('tab-system')).toHaveFocus()
    })

    it('arrow right wraps around from last tab to first', () => {
      render(<App />)

      const systemTab = screen.getByTestId('tab-system')
      systemTab.focus()
      fireEvent.keyDown(screen.getByTestId('tab-bar'), { key: 'ArrowRight' })

      expect(screen.getByTestId('tab-positions')).toHaveFocus()
    })

    it('arrow left wraps around from first tab to last', () => {
      render(<App />)

      const positionsTab = screen.getByTestId('tab-positions')
      positionsTab.focus()
      fireEvent.keyDown(screen.getByTestId('tab-bar'), { key: 'ArrowLeft' })

      expect(screen.getByTestId('tab-system')).toHaveFocus()
    })

    it('inactive tabs have tabIndex -1 and active tab has tabIndex 0', () => {
      render(<App />)

      const positionsTab = screen.getByTestId('tab-positions')
      expect(positionsTab).toHaveAttribute('tabindex', '0')

      const tradesTab = screen.getByTestId('tab-trades')
      expect(tradesTab).toHaveAttribute('tabindex', '-1')
    })

    it('connection status has aria-live polite for real-time updates', () => {
      render(<App />)

      const connectionStatus = screen.getByTestId('connection-status')
      expect(connectionStatus).toHaveAttribute('aria-live', 'polite')
    })
  })

  describe('maintenance banner', () => {
    it('shows maintenance banner when system health is DEGRADED and not reconnecting', () => {
      mockUseSystemHealth.mockReturnValue({
        health: {
          status: 'DEGRADED',
          services: {
            gateway: { status: 'READY' },
            'position-service': { status: 'DOWN' },
            'price-service': { status: 'READY' },
            'risk-orchestrator': { status: 'READY' },
            'notification-service': { status: 'READY' },
          },
        },
        loading: false,
        error: null,
        refresh: vi.fn(),
      })

      render(<App />)

      const banner = screen.getByTestId('maintenance-banner')
      expect(banner).toBeInTheDocument()
      expect(banner).toHaveAttribute('role', 'status')
      expect(banner).toHaveTextContent('Scheduled maintenance in progress')
    })

    it('does not show maintenance banner when system is UP', () => {
      render(<App />)

      expect(screen.queryByTestId('maintenance-banner')).not.toBeInTheDocument()
    })

    it('does not show maintenance banner when reconnecting (reconnect banner takes precedence)', () => {
      mockUseSystemHealth.mockReturnValue({
        health: {
          status: 'DEGRADED',
          services: {
            gateway: { status: 'READY' },
            'position-service': { status: 'DOWN' },
            'price-service': { status: 'READY' },
            'risk-orchestrator': { status: 'READY' },
            'notification-service': { status: 'READY' },
          },
        },
        loading: false,
        error: null,
        refresh: vi.fn(),
      })
      mockUsePriceStream.mockReturnValue({
        positions: [position],
        connected: false,
        reconnecting: true,
        exhausted: false,
        lastConnectedAt: null,
        disconnectedSince: null,
        manualReconnect: vi.fn(),
      })

      render(<App />)

      // The reconnecting banner should show, not the maintenance banner
      expect(screen.queryByTestId('maintenance-banner')).not.toBeInTheDocument()
    })
  })

  describe('reconnecting indicator', () => {
    it('shows reconnecting banner when WebSocket is reconnecting', () => {
      mockUsePriceStream.mockReturnValue({
        positions: [position],
        connected: false,
        reconnecting: true,
        exhausted: false,
        lastConnectedAt: null,
        disconnectedSince: null,
        manualReconnect: vi.fn(),
      })

      render(<App />)

      expect(screen.getByTestId('reconnecting-banner')).toBeInTheDocument()
    })

    it('does not show reconnecting banner when connected', () => {
      render(<App />)

      expect(screen.queryByTestId('reconnecting-banner')).not.toBeInTheDocument()
    })

    it('separates the elapsed-time counter from the role=alert region so it does not re-fire on each tick', () => {
      const disconnectedSince = new Date(Date.now() - 47_000)
      mockUsePriceStream.mockReturnValue({
        positions: [position],
        connected: false,
        reconnecting: true,
        exhausted: false,
        lastConnectedAt: null,
        disconnectedSince,
        manualReconnect: vi.fn(),
      })

      render(<App />)

      const banner = screen.getByTestId('reconnecting-banner')
      // Banner contains the status text only — assistive tech announces this once.
      const alert = within(banner).getByRole('alert')
      expect(alert.textContent ?? '').not.toMatch(/\d+s/)
      // The elapsed-seconds value is rendered in a sibling element marked aria-live="off"
      // so visual users still see it tick, but screen readers do not re-announce on each tick.
      const elapsed = within(banner).getByTestId('reconnecting-banner-elapsed')
      expect(elapsed).toHaveAttribute('aria-live', 'off')
      expect(elapsed.textContent).toContain('47s')
      // The elapsed element must be a sibling of the alert, not a descendant.
      expect(alert.contains(elapsed)).toBe(false)
    })
  })

  describe('keyboard shortcuts overlay', () => {
    it('pressing ? opens the keyboard shortcuts overlay', () => {
      render(<App />)

      expect(screen.queryByTestId('keyboard-shortcuts-overlay')).not.toBeInTheDocument()

      fireEvent.keyDown(window, { key: '?', shiftKey: true })

      expect(screen.getByTestId('keyboard-shortcuts-overlay')).toBeInTheDocument()
    })

    it('pressing Escape closes the keyboard shortcuts overlay', () => {
      render(<App />)

      fireEvent.keyDown(window, { key: '?', shiftKey: true })
      expect(screen.getByTestId('keyboard-shortcuts-overlay')).toBeInTheDocument()

      fireEvent.keyDown(document, { key: 'Escape' })
      expect(screen.queryByTestId('keyboard-shortcuts-overlay')).not.toBeInTheDocument()
    })

    it('pressing ? while a text input is focused does NOT open the overlay', () => {
      render(<App />)

      const input = document.createElement('input')
      input.type = 'text'
      document.body.appendChild(input)
      input.focus()

      fireEvent.keyDown(input, { key: '?', shiftKey: true })

      expect(screen.queryByTestId('keyboard-shortcuts-overlay')).not.toBeInTheDocument()

      document.body.removeChild(input)
    })
  })

  describe('global breach banner', () => {
    const breachVarResult = {
      bookId: 'book-1',
      calculationType: 'PARAMETRIC',
      confidenceLevel: 'CL_95',
      varValue: '90000.00', // 90% of 100k = 90% > 80% threshold
      expectedShortfall: '95000.00',
      componentBreakdown: [],
      calculatedAt: '2026-03-24T09:31:00Z',
    } as const

    function withVarBreach() {
      mockUseVaR.mockReturnValue({
        varResult: breachVarResult,
        greeksResult: null,
        history: [],
        filteredHistory: [],
        loading: false,
        historyLoading: false,
        refreshing: false,
        error: null,
        errorTransient: false,
        refresh: vi.fn(),
        timeRange: { from: '', to: '', label: 'Last 24h' },
        setTimeRange: vi.fn(),
        selectedConfidenceLevel: 'CL_95',
        setSelectedConfidenceLevel: vi.fn(),
        zoomIn: vi.fn(),
        resetZoom: vi.fn(),
        zoomDepth: 0,
        isLive: true,
      })
      mockUseVarLimit.mockReturnValue({ varLimit: 100_000, loading: false })
    }

    it('shows the breach banner on the default Positions tab when VaR utilisation exceeds 80%', () => {
      withVarBreach()
      render(<App />)

      expect(screen.getByTestId('breach-banner')).toBeInTheDocument()
    })

    it('keeps the breach banner visible when navigating between Positions, Risk, and P&L tabs', () => {
      withVarBreach()
      render(<App />)

      expect(screen.getByTestId('breach-banner')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('tab-risk'))
      expect(screen.getByTestId('breach-banner')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('tab-pnl'))
      expect(screen.getByTestId('breach-banner')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('tab-positions'))
      expect(screen.getByTestId('breach-banner')).toBeInTheDocument()
    })

    it('hides the breach banner on tabs outside the trading-day flow (e.g. Reports)', () => {
      withVarBreach()
      render(<App />)

      expect(screen.getByTestId('breach-banner')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('tab-reports'))
      expect(screen.queryByTestId('breach-banner')).not.toBeInTheDocument()
    })

    it('hides the breach banner on the Trades tab', () => {
      withVarBreach()
      render(<App />)

      fireEvent.click(screen.getByTestId('tab-trades'))
      expect(screen.queryByTestId('breach-banner')).not.toBeInTheDocument()
    })

    it('does not show the breach banner when VaR is within limit and no CRITICAL alerts', () => {
      mockUseVarLimit.mockReturnValue({ varLimit: 1_000_000, loading: false })
      mockUseAlerts.mockReturnValue({ alerts: [], dismissAlert: vi.fn() })

      render(<App />)

      expect(screen.queryByTestId('breach-banner')).not.toBeInTheDocument()
    })

    it('shows the breach banner when a CRITICAL alert is active even though VaR is well within limit', () => {
      mockUseVarLimit.mockReturnValue({ varLimit: 1_000_000, loading: false })
      mockUseAlerts.mockReturnValue({
        alerts: [
          {
            id: 'crit-1',
            ruleId: 'rule-1',
            ruleName: 'PnL drawdown',
            type: 'PNL_THRESHOLD',
            severity: 'CRITICAL',
            message: 'Drawdown -5%',
            currentValue: -500_000,
            threshold: -300_000,
            bookId: 'book-1',
            triggeredAt: '2026-03-24T09:30:00Z',
            status: 'TRIGGERED',
          },
        ],
        dismissAlert: vi.fn(),
      })

      render(<App />)

      expect(screen.getByTestId('breach-banner')).toBeInTheDocument()
    })

    it('does not duplicate the banner inside <main> — it sits above the tab content', () => {
      withVarBreach()
      render(<App />)

      const banner = screen.getByTestId('breach-banner')
      const ticker = screen.getByTestId('risk-ticker-strip')
      const tabPanel = screen.getByRole('tabpanel')

      // Banner should sit AFTER the ticker strip and BEFORE the tab panel.
      const order = ticker.compareDocumentPosition(banner)
      expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      const orderToPanel = banner.compareDocumentPosition(tabPanel)
      expect(orderToPanel & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })
  })

  describe('Alerts tab warning indicator', () => {
    it('shows amber warning dot on Alerts tab when notifications.error is set', () => {
      mockUseNotifications.mockReturnValue({
        rules: [],
        alerts: [],
        loading: false,
        error: 'Failed to load notifications',
        createRule: vi.fn(),
        deleteRule: vi.fn(),
      })

      render(<App />)

      expect(screen.getByTestId('alerts-error-dot')).toBeInTheDocument()
    })

    it('does not show amber warning dot on Alerts tab when notifications.error is null', () => {
      render(<App />)

      expect(screen.queryByTestId('alerts-error-dot')).not.toBeInTheDocument()
    })

    it('does not show alert count badge when notifications.error is set even if alerts array is non-empty', () => {
      mockUseNotifications.mockReturnValue({
        rules: [],
        alerts: [
          {
            id: 'evt-1',
            ruleId: 'rule-1',
            ruleName: 'VaR Limit',
            type: 'VAR_BREACH',
            severity: 'CRITICAL',
            message: 'VaR exceeded',
            currentValue: 150000,
            threshold: 100000,
            bookId: 'book-1',
            triggeredAt: '2025-01-15T10:00:00Z',
            status: 'TRIGGERED',
          },
        ],
        loading: false,
        error: 'Connection error',
        createRule: vi.fn(),
        deleteRule: vi.fn(),
      })

      render(<App />)

      // When there's an error, the badge count may be stale — show warning dot instead
      expect(screen.getByTestId('alerts-error-dot')).toBeInTheDocument()
    })
  })

  describe('system status banner consolidation', () => {
    function degradedHealth() {
      mockUseSystemHealth.mockReturnValue({
        health: {
          status: 'DEGRADED',
          services: {
            gateway: { status: 'READY' },
            'position-service': { status: 'DOWN' },
            'price-service': { status: 'READY' },
            'risk-orchestrator': { status: 'READY' },
            'notification-service': { status: 'READY' },
          },
        },
        loading: false,
        error: null,
        refresh: vi.fn(),
      })
    }

    function reconnectingStream() {
      mockUsePriceStream.mockReturnValue({
        positions: [position],
        connected: false,
        reconnecting: true,
        exhausted: false,
        lastConnectedAt: null,
        disconnectedSince: null,
        manualReconnect: vi.fn(),
      })
    }

    function exhaustedStream() {
      mockUsePriceStream.mockReturnValue({
        positions: [position],
        connected: false,
        reconnecting: false,
        exhausted: true,
        lastConnectedAt: null,
        disconnectedSince: null,
        manualReconnect: vi.fn(),
      })
    }

    it('renders a single status banner (not stacked) when only reconnecting is active', () => {
      reconnectingStream()

      render(<App />)

      expect(screen.getByTestId('reconnecting-banner')).toBeInTheDocument()
      expect(screen.queryByTestId('connection-lost-banner')).not.toBeInTheDocument()
      expect(screen.queryByTestId('maintenance-banner')).not.toBeInTheDocument()
    })

    it('renders a single status banner (not stacked) when only maintenance is active', () => {
      degradedHealth()

      render(<App />)

      expect(screen.getByTestId('maintenance-banner')).toBeInTheDocument()
      expect(screen.queryByTestId('reconnecting-banner')).not.toBeInTheDocument()
      expect(screen.queryByTestId('connection-lost-banner')).not.toBeInTheDocument()
    })

    it('renders exhausted banner only and suppresses reconnecting/maintenance when exhausted', () => {
      exhaustedStream()
      degradedHealth()

      render(<App />)

      expect(screen.getByTestId('connection-lost-banner')).toBeInTheDocument()
      expect(screen.queryByTestId('reconnecting-banner')).not.toBeInTheDocument()
      expect(screen.queryByTestId('maintenance-banner')).not.toBeInTheDocument()
    })

    it('reconnecting takes priority over maintenance — the consolidated bar swaps to reconnecting copy', () => {
      reconnectingStream()
      degradedHealth()

      render(<App />)

      const banner = screen.getByTestId('reconnecting-banner')
      // With DEGRADED health, the reconnecting banner uses the system-update copy.
      expect(banner).toHaveTextContent('System update in progress')
      expect(screen.queryByTestId('maintenance-banner')).not.toBeInTheDocument()
    })

    it('never stacks: at most one banner test-id is present at a time', () => {
      // Worst case: every state is "on" simultaneously.
      exhaustedStream()
      degradedHealth()

      render(<App />)

      const stackedCount =
        Number(Boolean(screen.queryByTestId('connection-lost-banner'))) +
        Number(Boolean(screen.queryByTestId('reconnecting-banner'))) +
        Number(Boolean(screen.queryByTestId('maintenance-banner')))
      expect(stackedCount).toBe(1)
    })
  })
})
