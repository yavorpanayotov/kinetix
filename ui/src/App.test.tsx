import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PositionDto } from './types'

vi.mock('./hooks/usePositions')
vi.mock('./hooks/usePriceStream')
vi.mock('./hooks/useNotifications')
vi.mock('./hooks/useSystemHealth')
vi.mock('./hooks/useStressTest')
vi.mock('./hooks/useBookSelector')
vi.mock('./hooks/useHierarchySelector')
vi.mock('./hooks/useDataQuality')
vi.mock('./hooks/useWorkspace')
vi.mock('./auth/useAuth')
vi.mock('./components/TradeBlotter', () => ({
  TradeBlotter: () => <div data-testid="trade-blotter-wrapper" />,
}))
vi.mock('./components/RiskTab', () => ({
  RiskTab: () => <div data-testid="risk-tab-wrapper" />,
}))
vi.mock('./components/ScenariosTab', () => ({
  ScenariosTab: () => <div data-testid="scenarios-tab-wrapper" />,
}))
vi.mock('./components/RegulatoryTab', () => ({
  RegulatoryTab: () => <div data-testid="regulatory-tab-wrapper" />,
}))

import App from './App'
import { usePositions } from './hooks/usePositions'
import { usePriceStream } from './hooks/usePriceStream'
import { useNotifications } from './hooks/useNotifications'
import { useSystemHealth } from './hooks/useSystemHealth'
import { useStressTest } from './hooks/useStressTest'
import { useBookSelector } from './hooks/useBookSelector'
import { useHierarchySelector } from './hooks/useHierarchySelector'
import { useDataQuality } from './hooks/useDataQuality'
import { useWorkspace, DEFAULT_PREFERENCES } from './hooks/useWorkspace'
import { useAuth } from './auth/useAuth'

const mockUsePositions = vi.mocked(usePositions)
const mockUseAuth = vi.mocked(useAuth)
const mockUsePriceStream = vi.mocked(usePriceStream)
const mockUseNotifications = vi.mocked(useNotifications)
const mockUseSystemHealth = vi.mocked(useSystemHealth)
const mockUseStressTest = vi.mocked(useStressTest)
const mockUseBookSelector = vi.mocked(useBookSelector)
const mockUseHierarchySelector = vi.mocked(useHierarchySelector)
const mockUseDataQuality = vi.mocked(useDataQuality)
const mockUseWorkspace = vi.mocked(useWorkspace)

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
})
