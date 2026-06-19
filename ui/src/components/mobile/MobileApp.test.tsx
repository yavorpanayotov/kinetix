import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { VaRResultDto, BookAggregationDto, IntradayPnlSnapshotDto, AlertEventDto, PositionDto } from '../../types'
import type { UseVaRResult } from '../../hooks/useVaR'
import type { UseVarLimitResult } from '../../hooks/useVarLimit'
import type { UseHierarchySummaryResult } from '../../hooks/useHierarchySummary'
import type { UseNotificationsResult } from '../../hooks/useNotifications'
import type { UsePositionsResult } from '../../hooks/usePositions'

// The shell composes four real views; mock every data hook they reach for so
// the shell test renders deterministically without real data. Mock shapes
// follow the per-view tests (MobileRiskView.test.tsx etc.).
vi.mock('../../hooks/useBookSelector')
vi.mock('../../auth/useAuth')
vi.mock('../../hooks/useVaR')
vi.mock('../../hooks/useVarLimit')
vi.mock('../../hooks/useHierarchySummary')
vi.mock('../../hooks/useIntradayPnlStream')
vi.mock('../../hooks/useNotifications')
vi.mock('../../hooks/usePositions')

import { MobileApp } from './MobileApp'
import { useBookSelector } from '../../hooks/useBookSelector'
import { useAuth } from '../../auth/useAuth'
import { useVaR } from '../../hooks/useVaR'
import { useVarLimit } from '../../hooks/useVarLimit'
import { useHierarchySummary } from '../../hooks/useHierarchySummary'
import { useIntradayPnlStream } from '../../hooks/useIntradayPnlStream'
import { useNotifications } from '../../hooks/useNotifications'
import { usePositions } from '../../hooks/usePositions'

const mockUseBookSelector = vi.mocked(useBookSelector)
const mockUseAuth = vi.mocked(useAuth)
const mockUseVaR = vi.mocked(useVaR)
const mockUseVarLimit = vi.mocked(useVarLimit)
const mockUseHierarchySummary = vi.mocked(useHierarchySummary)
const mockUseIntradayPnlStream = vi.mocked(useIntradayPnlStream)
const mockUseNotifications = vi.mocked(useNotifications)
const mockUsePositions = vi.mocked(usePositions)

function setupBookSelector() {
  mockUseBookSelector.mockReturnValue({
    bookOptions: [
      { value: '__ALL__', label: 'All Books' },
      { value: 'book-1', label: 'book-1' },
      { value: 'book-2', label: 'book-2' },
    ],
    selectedBookId: 'book-1',
    isAllSelected: false,
    allBookIds: ['book-1', 'book-2'],
    positions: [],
    aggregatedPositions: [],
    selectBook: vi.fn(),
    loading: false,
    error: null,
  })
}

function varResult(): VaRResultDto {
  return {
    bookId: 'book-1',
    calculationType: 'PARAMETRIC',
    confidenceLevel: 'CL_95',
    varValue: '500000',
    expectedShortfall: '600000',
    componentBreakdown: [],
    calculatedAt: new Date().toISOString(),
  }
}

function bookSummary(): BookAggregationDto {
  return {
    bookId: 'book-1',
    baseCurrency: 'USD',
    totalNav: { amount: '12000000', currency: 'USD' },
    totalUnrealizedPnl: { amount: '250000', currency: 'USD' },
    currencyBreakdown: [],
  }
}

function snapshot(): IntradayPnlSnapshotDto {
  return {
    snapshotAt: new Date().toISOString(),
    baseCurrency: 'USD',
    trigger: 'PRICE',
    totalPnl: '180000',
    realisedPnl: '0',
    unrealisedPnl: '0',
    deltaPnl: '0',
    gammaPnl: '0',
    vegaPnl: '0',
    thetaPnl: '0',
    rhoPnl: '0',
    unexplainedPnl: '0',
    highWaterMark: '0',
  }
}

function alert(): AlertEventDto {
  return {
    id: 'alert-1',
    ruleId: 'rule-1',
    ruleName: 'VaR breach',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR exceeded the limit',
    currentValue: 1_500_000,
    threshold: 1_000_000,
    bookId: 'BOOK-EQ',
    triggeredAt: new Date().toISOString(),
    status: 'TRIGGERED',
  }
}

function position(): PositionDto {
  return {
    bookId: 'book-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    quantity: '100',
    averageCost: { amount: '150', currency: 'USD' },
    marketPrice: { amount: '170', currency: 'USD' },
    marketValue: { amount: '17000', currency: 'USD' },
    unrealizedPnl: { amount: '2000', currency: 'USD' },
  }
}

function setupViewHooks() {
  mockUseAuth.mockReturnValue({
    authenticated: true,
    initialising: false,
    token: 'token',
    username: 'trader-1',
    roles: ['RISK_VIEWER'],
    logout: vi.fn(),
  })
  mockUseVaR.mockReturnValue({
    varResult: varResult(),
    greeksResult: null,
    history: [],
    filteredHistory: [],
    loading: false,
    historyLoading: false,
    refreshing: false,
    error: null,
    errorTransient: false,
    refresh: vi.fn(),
    timeRange: { from: '', to: '', label: '' },
    setTimeRange: vi.fn(),
    selectedConfidenceLevel: 'CL_95',
    setSelectedConfidenceLevel: vi.fn(),
    zoomIn: vi.fn(),
    resetZoom: vi.fn(),
    zoomDepth: 0,
    isLive: true,
  } as UseVaRResult)
  mockUseVarLimit.mockReturnValue({
    varLimit: 1_000_000,
    loading: false,
  } as UseVarLimitResult)
  mockUseHierarchySummary.mockReturnValue({
    summary: bookSummary(),
    baseCurrency: 'USD',
    setBaseCurrency: vi.fn(),
    loading: false,
    error: null,
    summaryLabel: 'Book Summary',
  } as UseHierarchySummaryResult)
  mockUseIntradayPnlStream.mockReturnValue({
    snapshots: [snapshot()],
    latest: snapshot(),
    connected: true,
  })
  mockUseNotifications.mockReturnValue({
    rules: [],
    alerts: [alert()],
    loading: false,
    error: null,
    connected: true,
    createRule: vi.fn(),
    deleteRule: vi.fn(),
    acknowledgeAlert: vi.fn(),
    escalateAlert: vi.fn(),
    resolveAlert: vi.fn(),
    snoozeAlert: vi.fn(),
  } as UseNotificationsResult)
  mockUsePositions.mockReturnValue({
    positions: [position()],
    bookId: 'book-1',
    books: ['book-1'],
    selectBook: vi.fn(),
    refreshPositions: vi.fn(),
    retryInitialLoad: vi.fn(),
    loading: false,
    error: null,
  } as UsePositionsResult)
}

describe('MobileApp', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setupBookSelector()
    setupViewHooks()
  })

  describe('shell', () => {
    it('renders the header with the Kinetix logo', () => {
      render(<MobileApp />)

      const header = screen.getByTestId('mobile-header')
      expect(header).toBeInTheDocument()
      expect(within(header).getByText('Kinetix')).toBeInTheDocument()
    })

    it('renders a book selector reflecting the available books', () => {
      render(<MobileApp />)

      const selector = screen.getByTestId('mobile-book-selector')
      expect(selector).toBeInTheDocument()
      expect(selector).toHaveValue('book-1')
      expect(within(selector).getByRole('option', { name: 'book-2' })).toBeInTheDocument()
    })

    it('gives the book selector a >=40px touch target and a min width', () => {
      render(<MobileApp />)

      const selector = screen.getByTestId('mobile-book-selector')
      expect(selector).toHaveClass('py-2.5')
      expect(selector).toHaveClass('min-w-[7rem]')
    })

    it('changing the book selector calls selectBook', () => {
      const selectBook = vi.fn()
      mockUseBookSelector.mockReturnValue({
        bookOptions: [
          { value: 'book-1', label: 'book-1' },
          { value: 'book-2', label: 'book-2' },
        ],
        selectedBookId: 'book-1',
        isAllSelected: false,
        allBookIds: ['book-1', 'book-2'],
        positions: [],
        aggregatedPositions: [],
        selectBook,
        loading: false,
        error: null,
      })

      render(<MobileApp />)
      fireEvent.change(screen.getByTestId('mobile-book-selector'), {
        target: { value: 'book-2' },
      })

      expect(selectBook).toHaveBeenCalledWith('book-2')
    })

    it('renders a theme toggle', () => {
      render(<MobileApp />)

      expect(screen.getByTestId('mobile-dark-mode-toggle')).toBeInTheDocument()
    })

    it('gives the theme toggle an enlarged touch target', () => {
      render(<MobileApp />)

      const toggle = screen.getByTestId('mobile-dark-mode-toggle')
      expect(toggle).toHaveClass('p-2.5')
      expect(toggle).not.toHaveClass('p-1.5')
    })

    it('renders a bottom tab bar with all four views', () => {
      render(<MobileApp />)

      const tabBar = screen.getByTestId('mobile-tab-bar')
      expect(tabBar).toHaveAttribute('role', 'tablist')
      expect(within(tabBar).getByTestId('mobile-tab-risk')).toBeInTheDocument()
      expect(within(tabBar).getByTestId('mobile-tab-pnl')).toBeInTheDocument()
      expect(within(tabBar).getByTestId('mobile-tab-alerts')).toBeInTheDocument()
      expect(within(tabBar).getByTestId('mobile-tab-positions')).toBeInTheDocument()
    })

    it('places the bottom tab bar after the main content in DOM order (thumb reach)', () => {
      render(<MobileApp />)

      const main = screen.getByTestId('mobile-main')
      const tabBar = screen.getByTestId('mobile-tab-bar')
      const order = main.compareDocumentPosition(tabBar)
      expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('gives each bottom-nav tab a >=48px hit zone for thumb taps', () => {
      render(<MobileApp />)

      const tabBar = screen.getByTestId('mobile-tab-bar')
      for (const view of ['risk', 'pnl', 'alerts', 'positions']) {
        expect(within(tabBar).getByTestId(`mobile-tab-${view}`)).toHaveClass(
          'min-h-[48px]',
        )
      }
    })
  })

  describe('default view', () => {
    it('mounts the real Risk view by default', () => {
      render(<MobileApp />)

      expect(screen.getByTestId('mobile-risk-view')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-pnl-view')).not.toBeInTheDocument()
      expect(screen.queryByTestId('mobile-alerts-view')).not.toBeInTheDocument()
      expect(screen.queryByTestId('mobile-positions-view')).not.toBeInTheDocument()
    })

    it('marks the Risk tab as selected by default', () => {
      render(<MobileApp />)

      expect(screen.getByTestId('mobile-tab-risk')).toHaveAttribute(
        'aria-selected',
        'true',
      )
      expect(screen.getByTestId('mobile-tab-pnl')).toHaveAttribute(
        'aria-selected',
        'false',
      )
    })
  })

  describe('navigation', () => {
    it('mounts the P&L view when the P&L tab is tapped', () => {
      render(<MobileApp />)

      fireEvent.click(screen.getByTestId('mobile-tab-pnl'))

      expect(screen.getByTestId('mobile-pnl-view')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-risk-view')).not.toBeInTheDocument()
      expect(screen.getByTestId('mobile-tab-pnl')).toHaveAttribute(
        'aria-selected',
        'true',
      )
    })

    it('mounts the Alerts view when the Alerts tab is tapped', () => {
      render(<MobileApp />)

      fireEvent.click(screen.getByTestId('mobile-tab-alerts'))

      expect(screen.getByTestId('mobile-alerts-view')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-risk-view')).not.toBeInTheDocument()
    })

    it('mounts the Positions view when the Positions tab is tapped', () => {
      render(<MobileApp />)

      fireEvent.click(screen.getByTestId('mobile-tab-positions'))

      expect(screen.getByTestId('mobile-positions-view')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-risk-view')).not.toBeInTheDocument()
    })

    it('returns to the Risk view after navigating away and back', () => {
      render(<MobileApp />)

      fireEvent.click(screen.getByTestId('mobile-tab-positions'))
      expect(screen.getByTestId('mobile-positions-view')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('mobile-tab-risk'))
      expect(screen.getByTestId('mobile-risk-view')).toBeInTheDocument()
      expect(screen.queryByTestId('mobile-positions-view')).not.toBeInTheDocument()
    })
  })

  describe('shared state wiring', () => {
    it('threads the authenticated username into the Alerts view notifications hook', () => {
      render(<MobileApp />)
      fireEvent.click(screen.getByTestId('mobile-tab-alerts'))

      expect(mockUseNotifications).toHaveBeenCalledWith('trader-1')
    })

    it('feeds the selected book id to the Risk view', () => {
      render(<MobileApp />)

      expect(mockUseVaR).toHaveBeenCalledWith('book-1')
    })

    it('passes null book id to the Risk view when All Books is selected', () => {
      mockUseBookSelector.mockReturnValue({
        bookOptions: [{ value: '__ALL__', label: 'All Books' }],
        selectedBookId: '__ALL__',
        isAllSelected: true,
        allBookIds: ['book-1', 'book-2'],
        positions: [],
        aggregatedPositions: [],
        selectBook: vi.fn(),
        loading: false,
        error: null,
      })

      render(<MobileApp />)

      expect(mockUseVaR).toHaveBeenCalledWith(null)
    })
  })
})
