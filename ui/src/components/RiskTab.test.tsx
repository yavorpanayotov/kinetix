import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PnlAttributionDto, StressTestResultDto } from '../types'

vi.mock('../hooks/useVaR')
vi.mock('../hooks/useJobHistory')
vi.mock('../hooks/usePositionRisk')
vi.mock('../hooks/useVarLimit')
vi.mock('../hooks/useAlerts')
vi.mock('../hooks/useSodBaseline')
vi.mock('../hooks/usePnlAttribution')
vi.mock('../hooks/useHierarchyNodeRisk')
vi.mock('../hooks/useWorkspace')
vi.mock('../hooks/useIntradayVaRTimeline')

import { RiskTab } from './RiskTab'
import { useVaR } from '../hooks/useVaR'
import { useJobHistory } from '../hooks/useJobHistory'
import { usePositionRisk } from '../hooks/usePositionRisk'
import { useVarLimit } from '../hooks/useVarLimit'
import { useAlerts } from '../hooks/useAlerts'
import { useSodBaseline } from '../hooks/useSodBaseline'
import { usePnlAttribution } from '../hooks/usePnlAttribution'
import { useHierarchyNodeRisk } from '../hooks/useHierarchyNodeRisk'
import { useWorkspace, DEFAULT_PREFERENCES } from '../hooks/useWorkspace'
import { useIntradayVaRTimeline } from '../hooks/useIntradayVaRTimeline'

const mockUseVaR = vi.mocked(useVaR)
const mockUseJobHistory = vi.mocked(useJobHistory)
const mockUsePositionRisk = vi.mocked(usePositionRisk)
const mockUseVarLimit = vi.mocked(useVarLimit)
const mockUseAlerts = vi.mocked(useAlerts)
const mockUseSodBaseline = vi.mocked(useSodBaseline)
const mockUsePnlAttribution = vi.mocked(usePnlAttribution)
const mockUseHierarchyNodeRisk = vi.mocked(useHierarchyNodeRisk)
const mockUseWorkspace = vi.mocked(useWorkspace)
const mockUseIntradayVaRTimeline = vi.mocked(useIntradayVaRTimeline)

const stressResult: StressTestResultDto = {
  scenarioName: 'MARKET_CRASH',
  baseVar: '1000000',
  stressedVar: '2500000',
  pnlImpact: '-1500000',
  assetClassImpacts: [],
  positionImpacts: [],
  limitBreaches: [],
  calculatedAt: '2025-01-15T10:30:00Z',
}

const defaultStressProps = {
  stressResults: [] as StressTestResultDto[],
  stressLoading: false,
  onRunStress: vi.fn(),
  onViewStressDetails: vi.fn(),
}

describe('RiskTab', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockUseVaR.mockReturnValue({
      varResult: null,
      greeksResult: null,
      history: [],
      filteredHistory: [],
      loading: false,
      historyLoading: false,
      error: null,
      errorTransient: false,
      refresh: vi.fn(),
      refreshing: false,
      timeRange: { from: '2025-01-14T10:30:00Z', to: '2025-01-15T10:30:00Z', label: 'Last 24h' },
      setTimeRange: vi.fn(),
      zoomIn: vi.fn(),
      resetZoom: vi.fn(),
      zoomDepth: 0,
      selectedConfidenceLevel: 'CL_95',
      setSelectedConfidenceLevel: vi.fn(),
      isLive: true,
    })
    mockUseJobHistory.mockReturnValue({
      runs: [],
      chartData: null,
      expandedJobs: {},
      loadingJobIds: new Set(),
      loading: false,
      error: null,
      timeRange: { from: '2025-01-14T10:00:00Z', to: '2025-01-15T10:00:00Z', label: 'Last 24h' },
      setTimeRange: vi.fn(),
      toggleJob: vi.fn(),
      closeJob: vi.fn(),
      clearSelection: vi.fn(),
      refresh: vi.fn(),
      zoomIn: vi.fn(),
      resetZoom: vi.fn(),
      zoomDepth: 0,
      page: 1,
      totalPages: 1,
      hasNextPage: false,
      nextPage: vi.fn(),
      prevPage: vi.fn(),
      firstPage: vi.fn(),
      lastPage: vi.fn(),
      goToPage: vi.fn(),
      pageSize: 10,
      setPageSize: vi.fn(),
      totalCount: 0,
    })
    mockUsePositionRisk.mockReturnValue({
      positionRisk: [],
      loading: false,
      error: null,
      refresh: vi.fn(),
    })
    mockUseVarLimit.mockReturnValue({
      varLimit: null,
      loading: false,
    })
    mockUseAlerts.mockReturnValue({
      alerts: [],
      dismissAlert: vi.fn(),
    })
    mockUseSodBaseline.mockReturnValue({
      status: null,
      loading: false,
      error: null,
      creating: false,
      resetting: false,
      computing: false,
      createSnapshot: vi.fn(),
      resetBaseline: vi.fn(),
      computeAttribution: vi.fn().mockResolvedValue(null),
      refresh: vi.fn(),
    })
    mockUsePnlAttribution.mockReturnValue({
      data: null,
      loading: false,
      error: null,
    })
    mockUseHierarchyNodeRisk.mockReturnValue({
      node: null,
      loading: false,
      error: null,
    })
    mockUseWorkspace.mockReturnValue({
      preferences: DEFAULT_PREFERENCES,
      updatePreference: vi.fn(),
      resetPreferences: vi.fn(),
      views: [{ id: 'view-default', name: 'Default', prefs: DEFAULT_PREFERENCES }],
      activeViewId: 'view-default',
      switchView: vi.fn(),
      saveAsNewView: vi.fn(() => 'view-new'),
      updateActiveView: vi.fn(),
      deleteView: vi.fn(),
      renameView: vi.fn(),
    })
    mockUseIntradayVaRTimeline.mockReturnValue({
      varPoints: [],
      tradeAnnotations: [],
      loading: false,
      error: null,
    })
  })

  it('calls useVaR with the given bookId', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(mockUseVaR).toHaveBeenCalledWith('book-1', null)
  })

  it('renders VaR dashboard and job history', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('var-empty')).toBeInTheDocument()
    expect(screen.getByTestId('job-history')).toBeInTheDocument()
  })

  it('calls usePositionRisk with the given bookId', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(mockUsePositionRisk).toHaveBeenCalledWith('book-1', null)
  })

  it('renders PositionRiskTable between VaR dashboard and job history', () => {
    mockUsePositionRisk.mockReturnValue({
      positionRisk: [
        {
          instrumentId: 'AAPL',
          assetClass: 'EQUITY',
          marketValue: '15500.00',
          delta: '1234.56',
          gamma: '45.67',
          vega: '89.01',
          theta: null,
          rho: null,
          varContribution: '800.00',
          esContribution: '1000.00',
          percentageOfTotal: '64.85',
        },
      ],
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('position-risk-section')).toBeInTheDocument()
  })

  it('passes varLimit from useVarLimit to VaRDashboard', () => {
    mockUseVarLimit.mockReturnValue({
      varLimit: 2000000,
      loading: false,
    })
    mockUseVaR.mockReturnValue({
      varResult: {
        bookId: 'book-1',
        calculationType: 'HISTORICAL',
        confidenceLevel: 'CL_95',
        varValue: '1000000',
        expectedShortfall: '1500000',
        componentBreakdown: [],
        calculatedAt: '2025-01-15T10:00:00Z',
      },
      greeksResult: null,
      history: [],
      filteredHistory: [],
      loading: false,
      historyLoading: false,
      error: null,
      errorTransient: false,
      refresh: vi.fn(),
      refreshing: false,
      timeRange: { from: '2025-01-14T10:30:00Z', to: '2025-01-15T10:30:00Z', label: 'Last 24h' },
      setTimeRange: vi.fn(),
      zoomIn: vi.fn(),
      resetZoom: vi.fn(),
      zoomDepth: 0,
      selectedConfidenceLevel: 'CL_95',
      setSelectedConfidenceLevel: vi.fn(),
      isLive: true,
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    const limitLabel = screen.getByTestId('var-limit')
    expect(limitLabel).toHaveTextContent('Limit')
    // VaR is 1,000,000 / 2,000,000 = 50%
    expect(limitLabel).toHaveTextContent('50%')
  })

  it('renders risk alert banner when alerts are present', () => {
    mockUseAlerts.mockReturnValue({
      alerts: [
        {
          id: 'alert-1',
          ruleId: 'rule-1',
          ruleName: 'VaR Limit',
          type: 'VAR_BREACH',
          severity: 'CRITICAL',
          message: 'VaR exceeds limit',
          currentValue: 2300000,
          threshold: 2000000,
          bookId: 'book-1',
          triggeredAt: '2026-02-28T10:00:00Z',
          status: 'TRIGGERED',
        },
      ],
      dismissAlert: vi.fn(),
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('risk-alert-banner')).toBeInTheDocument()
    expect(screen.getByText('CRITICAL: VaR breached $2,000,000 limit — current: $2,300,000 (book-1)')).toBeInTheDocument()
  })

  it('does not render risk alert banner when no alerts', () => {
    mockUseAlerts.mockReturnValue({
      alerts: [],
      dismissAlert: vi.fn(),
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.queryByTestId('risk-alert-banner')).not.toBeInTheDocument()
  })

  it('refreshes position risk data when VaR dashboard is refreshed', async () => {
    const user = userEvent.setup()
    const mockRefreshVaR = vi.fn().mockResolvedValue(undefined)
    const mockRefreshPositionRisk = vi.fn().mockResolvedValue(undefined)

    mockUseVaR.mockReturnValue({
      varResult: {
        bookId: 'book-1',
        calculationType: 'HISTORICAL',
        confidenceLevel: '0.95',
        varValue: '5000',
        expectedShortfall: '7000',
        componentBreakdown: [],
        calculatedAt: '2025-01-15T10:00:00Z',
      },
      greeksResult: null,
      history: [],
      filteredHistory: [],
      loading: false,
      historyLoading: false,
      error: null,
      errorTransient: false,
      refresh: mockRefreshVaR,
      refreshing: false,
      timeRange: { from: '2025-01-14T10:30:00Z', to: '2025-01-15T10:30:00Z', label: 'Last 24h' },
      setTimeRange: vi.fn(),
      zoomIn: vi.fn(),
      resetZoom: vi.fn(),
      zoomDepth: 0,
      selectedConfidenceLevel: 'CL_95',
      setSelectedConfidenceLevel: vi.fn(),
      isLive: true,
    })
    mockUsePositionRisk.mockReturnValue({
      positionRisk: [],
      loading: false,
      error: null,
      refresh: mockRefreshPositionRisk,
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    const refreshButton = screen.getByTestId('var-recalculate')
    await user.click(refreshButton)

    expect(mockRefreshVaR).toHaveBeenCalled()
    expect(mockRefreshPositionRisk).toHaveBeenCalled()
  })

  it('renders StressSummaryCard', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('stress-summary-card')).toBeInTheDocument()
  })

  it('renders StressSummaryCard between PositionRiskTable and JobHistory', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} stressResults={[stressResult]} />)

    const card = screen.getByTestId('stress-summary-card')
    const jobHistory = screen.getByTestId('job-history')
    expect(card.compareDocumentPosition(jobHistory) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('passes stress result to StressSummaryCard', () => {
    render(
      <RiskTab
        bookId="book-1"
        {...defaultStressProps}
        stressResults={[stressResult]}
      />,
    )

    expect(screen.getByTestId('stress-summary-table')).toBeInTheDocument()
    expect(screen.getByText('MARKET CRASH')).toBeInTheDocument()
  })

  it('calls onRunStress when Run Stress Tests button is clicked', async () => {
    const user = userEvent.setup()
    const onRunStress = vi.fn()

    render(
      <RiskTab
        bookId="book-1"
        {...defaultStressProps}
        onRunStress={onRunStress}
      />,
    )

    await user.click(screen.getByTestId('stress-summary-run-btn'))
    expect(onRunStress).toHaveBeenCalledOnce()
  })

  it('calls onViewStressDetails when View Details is clicked', async () => {
    const user = userEvent.setup()
    const onViewStressDetails = vi.fn()

    render(
      <RiskTab
        bookId="book-1"
        {...defaultStressProps}
        stressResults={[stressResult]}
        onViewStressDetails={onViewStressDetails}
      />,
    )

    await user.click(screen.getByTestId('stress-summary-view-details'))
    expect(onViewStressDetails).toHaveBeenCalledOnce()
  })

  it('calls useSodBaseline with the given bookId', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(mockUseSodBaseline).toHaveBeenCalledWith('book-1')
  })

  it('calls usePnlAttribution with the given bookId', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(mockUsePnlAttribution).toHaveBeenCalledWith('book-1')
  })

  it('renders PnlSummaryCard', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('pnl-summary-card')).toBeInTheDocument()
  })

  it('renders PnlSummaryCard and StressSummaryCard in a two-column grid', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    const pnlCard = screen.getByTestId('pnl-summary-card')
    const stressCard = screen.getByTestId('stress-summary-card')
    const gridContainer = pnlCard.parentElement!
    expect(gridContainer).toBe(stressCard.parentElement)
    expect(gridContainer.className).toContain('grid')
    expect(gridContainer.className).toContain('md:grid-cols-2')
  })

  it('shows no-baseline state when SOD status has no baseline', () => {
    mockUseSodBaseline.mockReturnValue({
      status: { exists: false, baselineDate: null, snapshotType: null, createdAt: null, sourceJobId: null, calculationType: null },
      loading: false,
      error: null,
      creating: false,
      resetting: false,
      computing: false,
      createSnapshot: vi.fn(),
      resetBaseline: vi.fn(),
      computeAttribution: vi.fn().mockResolvedValue(null),
      refresh: vi.fn(),
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('pnl-no-baseline')).toBeInTheDocument()
  })

  it('shows compute prompt when SOD baseline exists but no P&L data', () => {
    mockUseSodBaseline.mockReturnValue({
      status: { exists: true, baselineDate: '2026-02-28', snapshotType: 'MANUAL', createdAt: '2026-02-28T08:00:00Z', sourceJobId: null, calculationType: 'HISTORICAL' },
      loading: false,
      error: null,
      creating: false,
      resetting: false,
      computing: false,
      createSnapshot: vi.fn(),
      resetBaseline: vi.fn(),
      computeAttribution: vi.fn().mockResolvedValue(null),
      refresh: vi.fn(),
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('pnl-compute-prompt')).toBeInTheDocument()
  })

  it('passes onWhatIf to VaRDashboard and renders What-If button', () => {
    mockUseVaR.mockReturnValue({
      varResult: {
        bookId: 'book-1',
        calculationType: 'HISTORICAL',
        confidenceLevel: 'CL_95',
        varValue: '1000000',
        expectedShortfall: '1500000',
        componentBreakdown: [],
        calculatedAt: '2025-01-15T10:00:00Z',
      },
      greeksResult: null,
      history: [],
      filteredHistory: [],
      loading: false,
      historyLoading: false,
      error: null,
      errorTransient: false,
      refresh: vi.fn(),
      refreshing: false,
      timeRange: { from: '2025-01-14T10:30:00Z', to: '2025-01-15T10:30:00Z', label: 'Last 24h' },
      setTimeRange: vi.fn(),
      zoomIn: vi.fn(),
      resetZoom: vi.fn(),
      zoomDepth: 0,
      selectedConfidenceLevel: 'CL_95',
      setSelectedConfidenceLevel: vi.fn(),
      isLive: true,
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} onWhatIf={() => {}} />)

    expect(screen.getByTestId('var-whatif-button')).toBeInTheDocument()
  })

  it('does not render What-If button when onWhatIf is not provided', () => {
    mockUseVaR.mockReturnValue({
      varResult: {
        bookId: 'book-1',
        calculationType: 'HISTORICAL',
        confidenceLevel: 'CL_95',
        varValue: '1000000',
        expectedShortfall: '1500000',
        componentBreakdown: [],
        calculatedAt: '2025-01-15T10:00:00Z',
      },
      greeksResult: null,
      history: [],
      filteredHistory: [],
      loading: false,
      historyLoading: false,
      error: null,
      errorTransient: false,
      refresh: vi.fn(),
      refreshing: false,
      timeRange: { from: '2025-01-14T10:30:00Z', to: '2025-01-15T10:30:00Z', label: 'Last 24h' },
      setTimeRange: vi.fn(),
      zoomIn: vi.fn(),
      resetZoom: vi.fn(),
      zoomDepth: 0,
      selectedConfidenceLevel: 'CL_95',
      setSelectedConfidenceLevel: vi.fn(),
      isLive: true,
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.queryByTestId('var-whatif-button')).not.toBeInTheDocument()
  })

  it('passes onViewPnlTab to PnlSummaryCard as onViewFullAttribution', async () => {
    const user = userEvent.setup()
    const onViewPnlTab = vi.fn()

    mockUsePnlAttribution.mockReturnValue({
      data: {
        bookId: 'book-1',
        date: '2026-02-28',
        totalPnl: '5000.00',
        deltaPnl: '3000.00',
        gammaPnl: '-500.00',
        vegaPnl: '1000.00',
        thetaPnl: '-800.00',
        rhoPnl: '300.00',
        unexplainedPnl: '0',
        positionAttributions: [],
        calculatedAt: '2026-02-28T10:30:00Z',
      },
      loading: false,
      error: null,
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} onViewPnlTab={onViewPnlTab} />)

    await user.click(screen.getByTestId('pnl-view-full-attribution'))
    expect(onViewPnlTab).toHaveBeenCalledOnce()
  })

  it('renders LastUpdatedIndicator with VaR calculatedAt timestamp', () => {
    mockUseVaR.mockReturnValue({
      varResult: {
        bookId: 'book-1',
        calculationType: 'HISTORICAL',
        confidenceLevel: 'CL_95',
        varValue: '1000000',
        expectedShortfall: '1500000',
        componentBreakdown: [],
        calculatedAt: '2026-02-28T14:32:00Z',
      },
      greeksResult: null,
      history: [],
      filteredHistory: [],
      loading: false,
      historyLoading: false,
      error: null,
      errorTransient: false,
      refresh: vi.fn(),
      refreshing: false,
      timeRange: { from: '2025-01-14T10:30:00Z', to: '2025-01-15T10:30:00Z', label: 'Last 24h' },
      setTimeRange: vi.fn(),
      zoomIn: vi.fn(),
      resetZoom: vi.fn(),
      zoomDepth: 0,
      selectedConfidenceLevel: 'CL_95',
      setSelectedConfidenceLevel: vi.fn(),
      isLive: true,
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('last-updated')).toBeInTheDocument()
    expect(screen.getByTestId('last-updated')).toHaveTextContent('Last refreshed')
  })

  it('does not render LastUpdatedIndicator when no VaR data', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.queryByTestId('last-updated')).not.toBeInTheDocument()
  })

  it('renders the limits breach header on the Dashboard sub-tab', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('limits-breach-header')).toBeInTheDocument()
  })

  it('does not render the limits breach header on the Intraday sub-tab', async () => {
    const user = userEvent.setup()
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    await user.click(screen.getByTestId('risk-subtab-intraday'))

    expect(screen.queryByTestId('limits-breach-header')).not.toBeInTheDocument()
  })

  it('renders the limits breach header above the VaR dashboard in the DOM', () => {
    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    const header = screen.getByTestId('limits-breach-header')
    const varEmpty = screen.getByTestId('var-empty')
    expect(header.compareDocumentPosition(varEmpty) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('shows P&L summary data when attribution data is available', () => {
    const pnlData: PnlAttributionDto = {
      bookId: 'book-1',
      date: '2026-02-28',
      totalPnl: '12500.50',
      deltaPnl: '8000.00',
      gammaPnl: '-1200.00',
      vegaPnl: '3500.00',
      thetaPnl: '-2000.00',
      rhoPnl: '500.50',
      unexplainedPnl: '3700.00',
      positionAttributions: [],
      calculatedAt: '2026-02-28T10:30:00Z',
    }

    mockUsePnlAttribution.mockReturnValue({
      data: pnlData,
      loading: false,
      error: null,
    })

    render(<RiskTab bookId="book-1" {...defaultStressProps} />)

    expect(screen.getByTestId('pnl-summary-data')).toBeInTheDocument()
    expect(screen.getByTestId('pnl-total-value')).toHaveTextContent('12,500.50')
  })

  describe('Dashboard section blocks (plan §2.2)', () => {
    it('renders the four group headings on the Dashboard sub-tab', () => {
      render(<RiskTab bookId="book-1" {...defaultStressProps} />)

      expect(screen.getByTestId('section-block-market-risk')).toBeInTheDocument()
      expect(screen.getByTestId('section-block-position-factor')).toBeInTheDocument()
      expect(screen.getByTestId('section-block-pnl-stress-liquidity')).toBeInTheDocument()
      expect(screen.getByTestId('section-block-limits-jobs')).toBeInTheDocument()
    })

    it('defaults all four sections to open and renders their panels', () => {
      render(<RiskTab bookId="book-1" {...defaultStressProps} />)

      // Market Risk section content
      expect(screen.getByTestId('var-empty')).toBeInTheDocument()
      // Position & Factor section content
      expect(screen.getByTestId('position-risk-section')).toBeInTheDocument()
      // P&L / Stress / Liquidity section content
      expect(screen.getByTestId('pnl-summary-card')).toBeInTheDocument()
      expect(screen.getByTestId('stress-summary-card')).toBeInTheDocument()
      // Limits & Jobs section content
      expect(screen.getByTestId('job-history')).toBeInTheDocument()
    })

    it('renders the LimitsBreachHeader above the four section blocks', () => {
      render(<RiskTab bookId="book-1" {...defaultStressProps} />)

      const header = screen.getByTestId('limits-breach-header')
      const marketRiskSection = screen.getByTestId('section-block-market-risk')
      expect(header.compareDocumentPosition(marketRiskSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('persists section collapse state through the useWorkspace updatePreference hook', async () => {
      const user = userEvent.setup()
      const updatePreference = vi.fn()
      mockUseWorkspace.mockReturnValue({
        preferences: DEFAULT_PREFERENCES,
        updatePreference,
        resetPreferences: vi.fn(),
        views: [{ id: 'view-default', name: 'Default', prefs: DEFAULT_PREFERENCES }],
        activeViewId: 'view-default',
        switchView: vi.fn(),
        saveAsNewView: vi.fn(() => 'view-new'),
        updateActiveView: vi.fn(),
        deleteView: vi.fn(),
        renameView: vi.fn(),
      })

      render(<RiskTab bookId="book-1" {...defaultStressProps} />)

      await user.click(screen.getByRole('button', { name: /market risk/i }))

      expect(updatePreference).toHaveBeenCalledWith(
        'riskDashboardSections',
        expect.objectContaining({ marketRisk: false }),
      )
    })

    it('honours a saved collapsed section from workspace prefs', () => {
      mockUseWorkspace.mockReturnValue({
        preferences: {
          ...DEFAULT_PREFERENCES,
          riskDashboardSections: {
            ...DEFAULT_PREFERENCES.riskDashboardSections,
            marketRisk: false,
          },
        },
        updatePreference: vi.fn(),
        resetPreferences: vi.fn(),
        views: [{ id: 'view-default', name: 'Default', prefs: DEFAULT_PREFERENCES }],
        activeViewId: 'view-default',
        switchView: vi.fn(),
        saveAsNewView: vi.fn(() => 'view-new'),
        updateActiveView: vi.fn(),
        deleteView: vi.fn(),
        renameView: vi.fn(),
      })

      render(<RiskTab bookId="book-1" {...defaultStressProps} />)

      const marketRiskToggle = screen.getByRole('button', { name: /market risk/i })
      expect(marketRiskToggle).toHaveAttribute('aria-expanded', 'false')
      // Market Risk panel content hidden when section collapsed
      expect(screen.queryByTestId('var-empty')).not.toBeInTheDocument()
    })
  })

  describe('Compare with snapshot (plan §8.6)', () => {
    const VAR_RESULT = {
      bookId: 'book-1',
      calculationType: 'HISTORICAL',
      confidenceLevel: 'CL_95',
      varValue: '120000',
      expectedShortfall: '180000',
      componentBreakdown: [],
      calculatedAt: '2026-03-25T15:00:00Z',
    }

    function mockVaRReturn() {
      mockUseVaR.mockReturnValue({
        varResult: VAR_RESULT,
        greeksResult: null,
        history: [],
        filteredHistory: [],
        loading: false,
        historyLoading: false,
        error: null,
        errorTransient: false,
        refresh: vi.fn(),
        refreshing: false,
        timeRange: { from: '2026-03-24T15:00:00Z', to: '2026-03-25T15:00:00Z', label: 'Last 24h' },
        setTimeRange: vi.fn(),
        zoomIn: vi.fn(),
        resetZoom: vi.fn(),
        zoomDepth: 0,
        selectedConfidenceLevel: 'CL_95',
        setSelectedConfidenceLevel: vi.fn(),
        isLive: true,
      })
    }

    it('renders the snapshot compare control on the Dashboard sub-tab', () => {
      mockVaRReturn()
      render(<RiskTab bookId="book-1" {...defaultStressProps} />)

      expect(screen.getByTestId('snapshot-compare-control')).toBeInTheDocument()
    })

    it('renders the snapshot Δ badge when a preset is selected and intraday VaR data is available', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      vi.setSystemTime(new Date('2026-03-25T15:00:00Z'))
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

      mockVaRReturn()
      // Series spans the trading day with a point ~1h before "now".
      mockUseIntradayVaRTimeline.mockReturnValue({
        varPoints: [
          { timestamp: '2026-03-25T13:00:00Z', varValue: 95000, expectedShortfall: 140000, delta: null, gamma: null, vega: null },
          { timestamp: '2026-03-25T14:00:00Z', varValue: 100000, expectedShortfall: 150000, delta: null, gamma: null, vega: null },
          { timestamp: '2026-03-25T14:55:00Z', varValue: 118000, expectedShortfall: 170000, delta: null, gamma: null, vega: null },
        ],
        tradeAnnotations: [],
        loading: false,
        error: null,
      })

      render(<RiskTab bookId="book-1" {...defaultStressProps} />)

      try {
        await user.click(screen.getByTestId('snapshot-compare--1h'))

        const badge = screen.getByTestId('snapshot-delta-var')
        // current 120,000 − snapshot 100,000 (point at 14:00:00Z is the nearest
        // at-or-before -1h target) = +20,000 → formatted compactly as +$20K.
        expect(badge).toHaveTextContent('Δ vs -1h')
        expect(badge).toHaveTextContent('+$20K')
        expect(badge).toHaveTextContent('(+20.0%)')
      } finally {
        vi.useRealTimers()
      }
    })

    it('does not render the snapshot Δ badge when no intraday VaR points are available', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      vi.setSystemTime(new Date('2026-03-25T15:00:00Z'))
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

      mockVaRReturn()
      mockUseIntradayVaRTimeline.mockReturnValue({
        varPoints: [],
        tradeAnnotations: [],
        loading: false,
        error: null,
      })

      render(<RiskTab bookId="book-1" {...defaultStressProps} />)
      try {
        await user.click(screen.getByTestId('snapshot-compare--1h'))
        expect(screen.queryByTestId('snapshot-delta-var')).not.toBeInTheDocument()
      } finally {
        vi.useRealTimers()
      }
    })
  })
})
