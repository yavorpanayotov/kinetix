import { act, render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { VaRResultDto, GreeksResultDto, TimeRange } from '../types'
import type { VaRHistoryEntry } from '../hooks/useVaR'
import type { InsightResponse } from '../api/insights'
import { explainVar } from '../api/insights'
import { VaRDashboard } from './VaRDashboard'

vi.mock('../api/insights', async () => {
  const actual = await vi.importActual<typeof import('../api/insights')>('../api/insights')
  return {
    ...actual,
    explainVar: vi.fn(),
  }
})

const mockedExplainVar = vi.mocked(explainVar)

const CONTAINER_WIDTH = 800

class FakeResizeObserver {
  callback: ResizeObserverCallback
  constructor(callback: ResizeObserverCallback) {
    this.callback = callback
  }
  observe() {
    this.callback(
      [{ contentRect: { width: CONTAINER_WIDTH } } as unknown as ResizeObserverEntry],
      this as unknown as ResizeObserver,
    )
  }
  unobserve() {}
  disconnect() {}
}

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', FakeResizeObserver)
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(CONTAINER_WIDTH)
})

const varResult: VaRResultDto = {
  bookId: 'book-1',
  calculationType: 'HISTORICAL',
  confidenceLevel: 'CL_95',
  varValue: '1234567.89',
  expectedShortfall: '1567890.12',
  componentBreakdown: [
    { assetClass: 'EQUITY', varContribution: '800000.00', percentageOfTotal: '64.85' },
    { assetClass: 'FIXED_INCOME', varContribution: '300000.00', percentageOfTotal: '24.30' },
    { assetClass: 'COMMODITY', varContribution: '134567.89', percentageOfTotal: '10.85' },
  ],
  calculatedAt: '2025-01-15T10:30:00Z',
}

const history: VaRHistoryEntry[] = [
  { varValue: 1200000, expectedShortfall: 1500000, calculatedAt: '2025-01-15T10:00:00Z', confidenceLevel: 'CL_95' },
  { varValue: 1234567.89, expectedShortfall: 1567890.12, calculatedAt: '2025-01-15T10:30:00Z', confidenceLevel: 'CL_95' },
  { varValue: 1300000, expectedShortfall: 1600000, calculatedAt: '2025-01-15T11:00:00Z', confidenceLevel: 'CL_95' },
]

const defaultTimeRange: TimeRange = {
  from: '2025-01-14T10:30:00Z',
  to: '2025-01-15T10:30:00Z',
  label: 'Last 24h',
}

const greeksResult: GreeksResultDto = {
  bookId: 'book-1',
  assetClassGreeks: [
    { assetClass: 'EQUITY', delta: '1234.560000', gamma: '78.900000', vega: '5678.120000' },
    { assetClass: 'COMMODITY', delta: '567.890000', gamma: '12.340000', vega: '2345.670000' },
  ],
  theta: '-123.450000',
  rho: '456.780000',
  calculatedAt: '2025-01-15T10:00:00Z',
}

const defaultZoomProps = {
  timeRange: defaultTimeRange,
  setTimeRange: vi.fn(),
  filteredHistory: history,
  zoomIn: vi.fn(),
  resetZoom: vi.fn(),
  zoomDepth: 0,
  refreshing: false,
  greeksResult: null as GreeksResultDto | null,
}

describe('VaRDashboard', () => {
  it('shows loading state', () => {
    render(
      <VaRDashboard
        varResult={null}

        loading={true}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
        filteredHistory={[]}
      />,
    )

    expect(screen.getByTestId('var-loading')).toBeInTheDocument()
  })

  it('shows error state', () => {
    render(
      <VaRDashboard
        varResult={null}

        loading={false}
        error="Failed to fetch VaR"
        onRefresh={() => {}}
        {...defaultZoomProps}
        filteredHistory={[]}
      />,
    )

    expect(screen.getByTestId('var-error')).toBeInTheDocument()
    expect(screen.getByTestId('var-error')).toHaveTextContent('Failed to fetch VaR')
  })

  it('renders the error state using the shared ErrorCard (role=alert)', () => {
    render(
      <VaRDashboard
        varResult={null}
        loading={false}
        error="Failed to fetch VaR"
        onRefresh={() => {}}
        {...defaultZoomProps}
        filteredHistory={[]}
      />,
    )

    const alert = screen.getByRole('alert')
    expect(alert).toBeInTheDocument()
    expect(alert).toHaveTextContent('Failed to fetch VaR')
    // Retry button lives inside ErrorCard and exposes the legacy test ID.
    expect(alert.querySelector('[data-testid="var-error-retry"]')).not.toBeNull()
  })

  it('shows a Retry button in the error state', () => {
    render(
      <VaRDashboard
        varResult={null}
        loading={false}
        error="Failed to fetch VaR"
        onRefresh={() => {}}
        {...defaultZoomProps}
        filteredHistory={[]}
      />,
    )

    expect(screen.getByTestId('var-error-retry')).toBeInTheDocument()
  })

  it('calls onRefresh when the Retry button in the error state is clicked', () => {
    const onRefresh = vi.fn()

    render(
      <VaRDashboard
        varResult={null}
        loading={false}
        error="Failed to fetch VaR"
        onRefresh={onRefresh}
        {...defaultZoomProps}
        filteredHistory={[]}
      />,
    )

    fireEvent.click(screen.getByTestId('var-error-retry'))
    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it('shows empty state when no result', () => {
    render(
      <VaRDashboard
        varResult={null}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
        filteredHistory={[]}
      />,
    )

    const empty = screen.getByTestId('var-empty')
    expect(empty).toBeInTheDocument()
    expect(empty).toHaveTextContent('No VaR results yet')
  })

  it('explains the missing aggregate when sibling contribution data is on screen', () => {
    // UX review: "No VaR results yet — run a calculation" rendered directly
    // above a populated Top Division Contributors table. When sibling panels
    // have data, the empty state must say what is actually missing.
    render(
      <VaRDashboard
        varResult={null}
        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
        filteredHistory={[]}
        contributionsVisible={true}
      />,
    )

    const empty = screen.getByTestId('var-empty')
    expect(empty).toHaveTextContent(/no aggregated VaR/i)
    expect(empty).toHaveTextContent(/contribution/i)
    expect(empty).not.toHaveTextContent('No VaR results yet')
  })

  it('renders the VaR gauge with data', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    expect(screen.getByTestId('var-dashboard')).toBeInTheDocument()
    expect(screen.getByTestId('var-gauge')).toBeInTheDocument()
    expect(screen.getByTestId('var-value')).toHaveTextContent('$1.2M')
  })

  it('in aggregatedView defaults to the Greeks chart and guides the non-additive VaR/ES line', () => {
    render(
      <VaRDashboard
        varResult={varResult}
        loading={false}
        error={null}
        onRefresh={() => {}}
        aggregatedView
        {...defaultZoomProps}
      />,
    )

    // Defaults to the Greeks trend (additive), so the VaR-history placeholder is
    // not shown until the user explicitly switches to the VaR/ES tab.
    expect(screen.getByTestId('greeks-trend-chart')).toBeInTheDocument()
    expect(screen.queryByTestId('var-history-aggregated-placeholder')).not.toBeInTheDocument()

    // Switching to VaR/ES shows the "select a book" affordance, not a misleading
    // additive VaR line.
    fireEvent.click(screen.getByTestId('chart-toggle-var'))
    expect(screen.getByTestId('var-history-aggregated-placeholder')).toBeInTheDocument()
  })

  it('stamps the VaR figure with its scope and as-of time', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    expect(screen.getByTestId('var-dashboard-scope-scope')).toHaveTextContent('book-1')
    expect(screen.getByTestId('var-dashboard-scope-asof')).toBeInTheDocument()
  })

  it('renders component breakdown segments', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    expect(screen.getByTestId('var-breakdown')).toBeInTheDocument()
    expect(screen.getByTestId('breakdown-EQUITY')).toBeInTheDocument()
    expect(screen.getByTestId('breakdown-FIXED_INCOME')).toBeInTheDocument()
    expect(screen.getByTestId('breakdown-COMMODITY')).toBeInTheDocument()
  })

  it('renders trend chart with sufficient data points', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    const trend = screen.getByTestId('var-trend-chart')
    expect(trend).toBeInTheDocument()
    expect(trend.querySelector('svg')).toBeInTheDocument()
  })

  it('shows message instead of chart for single data point', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
        filteredHistory={[history[0]]}
      />,
    )

    const trend = screen.getByTestId('var-trend-chart')
    expect(trend).toHaveTextContent('Needs at least 2 calculations to draw a trend.')
    expect(trend.querySelector('svg')).not.toBeInTheDocument()
  })

  it('uses a 4-column grid with full-width trend chart below', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    const dashboard = screen.getByTestId('var-dashboard')
    const grid = dashboard.querySelector('.grid-cols-4')
    expect(grid).toBeInTheDocument()

    const trendChart = screen.getByTestId('var-trend-chart')
    expect(trendChart).toBeInTheDocument()
    expect(grid!.contains(trendChart)).toBe(false)
  })

  it('calls onRefresh when Recalculate button is clicked', () => {
    const onRefresh = vi.fn()

    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={onRefresh}
        {...defaultZoomProps}
      />,
    )

    fireEvent.click(screen.getByTestId('var-recalculate'))
    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it('displays calculation type and timestamp', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    expect(screen.getByTestId('var-dashboard')).toHaveTextContent('HISTORICAL')
  })

  it('shows tooltip on info icon click and hides on second click', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    expect(screen.queryByTestId('calc-type-tooltip')).not.toBeInTheDocument()

    fireEvent.click(screen.getByTestId('calc-type-info'))
    expect(screen.getByTestId('calc-type-tooltip')).toHaveTextContent(/historical simulation/i)

    fireEvent.click(screen.getByTestId('calc-type-info'))
    expect(screen.queryByTestId('calc-type-tooltip')).not.toBeInTheDocument()
  })

  it('closes calc-type popover when Escape is pressed', () => {
    render(
      <VaRDashboard
        varResult={{ ...varResult, calculationType: 'PARAMETRIC' }}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    fireEvent.click(screen.getByTestId('calc-type-info'))
    expect(screen.getByTestId('calc-type-tooltip')).toHaveTextContent(/variance-covariance/i)

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByTestId('calc-type-tooltip')).not.toBeInTheDocument()
  })

  it('closes calc-type popover when clicking outside', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    fireEvent.click(screen.getByTestId('calc-type-info'))
    expect(screen.getByTestId('calc-type-tooltip')).toBeInTheDocument()

    fireEvent.mouseDown(document.body)
    expect(screen.queryByTestId('calc-type-tooltip')).not.toBeInTheDocument()
  })

  it('closes calc-type popover when close button is clicked', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    fireEvent.click(screen.getByTestId('calc-type-info'))
    expect(screen.getByTestId('calc-type-tooltip')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('calc-type-tooltip-close'))
    expect(screen.queryByTestId('calc-type-tooltip')).not.toBeInTheDocument()
  })

  it('shows correct tooltip for each calculation type', () => {
    render(
      <VaRDashboard
        varResult={{ ...varResult, calculationType: 'MONTE_CARLO' }}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    fireEvent.click(screen.getByTestId('calc-type-info'))
    expect(screen.getByTestId('calc-type-tooltip')).toHaveTextContent(/monte carlo/i)
  })

  it('shows spinner on Recalculate button when refreshing, not full loading state', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
        refreshing={true}
      />,
    )

    // Dashboard should still be visible (not replaced by loading spinner)
    expect(screen.getByTestId('var-dashboard')).toBeInTheDocument()
    expect(screen.queryByTestId('var-loading')).not.toBeInTheDocument()

    // Recalculate button should show a spinner
    const button = screen.getByTestId('var-recalculate')
    expect(button.querySelector('.animate-spin')).toBeInTheDocument()
  })

  it('renders TimeRangeSelector with the time range', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    expect(screen.getByTestId('time-range-selector')).toBeInTheDocument()
  })

  it('renders risk sensitivities when greeksResult is provided', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
        greeksResult={greeksResult}
      />,
    )

    expect(screen.getByTestId('risk-sensitivities')).toBeInTheDocument()
    expect(screen.getByTestId('greeks-heatmap')).toBeInTheDocument()
  })

  it('passes pvValue to RiskSensitivities when present', () => {
    const varResultWithPv = { ...varResult, pvValue: '1800000.00' }
    render(
      <VaRDashboard
        varResult={varResultWithPv}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
        greeksResult={greeksResult}
      />,
    )

    expect(screen.getByTestId('pv-display')).toBeInTheDocument()
    expect(screen.getByTestId('pv-display')).toHaveTextContent('$1.8M')
  })

  it('passes varLimit to VaRGauge when provided', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
        varLimit={2000000}
      />,
    )

    const limitLabel = screen.getByTestId('var-limit')
    expect(limitLabel).toHaveTextContent('Limit')
    // VaR is 1,234,567.89 / 2,000,000 = ~62%
    expect(limitLabel).toHaveTextContent('62%')
  })

  it('does not show limit label when varLimit is not provided', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    expect(screen.queryByTestId('var-limit')).not.toBeInTheDocument()
  })

  it('renders placeholder when greeksResult is null', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
        greeksResult={null}
      />,
    )

    expect(screen.getByTestId('sensitivities-placeholder')).toBeInTheDocument()
    expect(screen.queryByTestId('risk-sensitivities')).not.toBeInTheDocument()
  })

  it('does not render What-If button when onWhatIf is not provided', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        {...defaultZoomProps}
      />,
    )

    expect(screen.queryByTestId('var-whatif-button')).not.toBeInTheDocument()
  })

  it('renders What-If button when onWhatIf is provided', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        onWhatIf={() => {}}
        {...defaultZoomProps}
      />,
    )

    const button = screen.getByTestId('var-whatif-button')
    expect(button).toBeInTheDocument()
    expect(button).toHaveTextContent('What-If')
  })

  it('calls onWhatIf when What-If button is clicked', () => {
    const onWhatIf = vi.fn()

    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        onWhatIf={onWhatIf}
        {...defaultZoomProps}
      />,
    )

    fireEvent.click(screen.getByTestId('var-whatif-button'))
    expect(onWhatIf).toHaveBeenCalledTimes(1)
  })

  it('renders What-If button before Refresh button in footer', () => {
    render(
      <VaRDashboard
        varResult={varResult}

        loading={false}
        error={null}
        onRefresh={() => {}}
        onWhatIf={() => {}}
        {...defaultZoomProps}
      />,
    )

    const whatIfButton = screen.getByTestId('var-whatif-button')
    const refreshButton = screen.getByTestId('var-recalculate')
    expect(whatIfButton.compareDocumentPosition(refreshButton) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  describe('confidence toggle', () => {
    it('renders confidence toggle in VaRGauge when handler is provided', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          selectedConfidenceLevel="CL_95"
          onConfidenceLevelChange={() => {}}
        />,
      )

      expect(screen.getByTestId('confidence-toggle-95')).toBeInTheDocument()
      expect(screen.getByTestId('confidence-toggle-99')).toBeInTheDocument()
    })

    it('calls onConfidenceLevelChange when toggle is clicked', () => {
      const onChange = vi.fn()
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          selectedConfidenceLevel="CL_95"
          onConfidenceLevelChange={onChange}
        />,
      )

      fireEvent.click(screen.getByTestId('confidence-toggle-99'))
      expect(onChange).toHaveBeenCalledWith('CL_99')
    })

    it('disables confidence toggle during refresh', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          refreshing={true}
          selectedConfidenceLevel="CL_95"
          onConfidenceLevelChange={() => {}}
        />,
      )

      expect(screen.getByTestId('confidence-toggle-95')).toBeDisabled()
      expect(screen.getByTestId('confidence-toggle-99')).toBeDisabled()
    })
  })

  describe('historical mode', () => {
    it('shows historical badge when isLive is false', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          isLive={false}
          valuationDate="2025-03-10"
        />,
      )

      const badge = screen.getByTestId('historical-badge')
      expect(badge).toBeInTheDocument()
      expect(badge).toHaveTextContent('Historical — 2025-03-10')
    })

    it('does not show historical badge when isLive is true', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          isLive={true}
        />,
      )

      expect(screen.queryByTestId('historical-badge')).not.toBeInTheDocument()
    })

    it('disables Refresh button in historical mode', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          isLive={false}
          valuationDate="2025-03-10"
        />,
      )

      expect(screen.getByTestId('var-recalculate')).toBeDisabled()
    })

    it('enables Refresh button in live mode', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          isLive={true}
        />,
      )

      expect(screen.getByTestId('var-recalculate')).not.toBeDisabled()
    })

    it('applies amber background tint in historical mode', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          isLive={false}
          valuationDate="2025-03-10"
        />,
      )

      const card = screen.getByTestId('var-dashboard')
      expect(card.className).toContain('bg-amber-50/30')
    })

    it('shows valuation date label in footer when valuationDate is present in result', () => {
      render(
        <VaRDashboard
          varResult={{ ...varResult, valuationDate: '2025-03-10' }}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      expect(screen.getByTestId('valuation-date-label')).toHaveTextContent('Risk as of: 2025-03-10')
    })
  })

  describe('scenario / regime annotation', () => {
    it('does not render a scenario badge when no scenario is active and regime is null', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      expect(screen.queryByTestId('scenario-badge')).not.toBeInTheDocument()
    })

    it('does not render a scenario badge when regime is NORMAL and no scenario', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          activeScenario={null}
          marketRegime="NORMAL"
        />,
      )

      expect(screen.queryByTestId('scenario-badge')).not.toBeInTheDocument()
    })

    it('renders a scenario badge on the VaR number when a scenario is active', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          activeScenario="stress"
        />,
      )

      const badges = screen.getAllByTestId('scenario-badge')
      expect(badges.length).toBeGreaterThan(0)
      expect(badges[0].getAttribute('aria-label')).toMatch(/scenario/i)
      expect(badges[0].getAttribute('aria-label')).toMatch(/stress/i)
    })

    it('renders a regime annotation when regime is non-normal', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
          marketRegime="CRISIS"
        />,
      )

      const badges = screen.getAllByTestId('scenario-badge')
      expect(badges.length).toBeGreaterThan(0)
      expect(badges[0].getAttribute('aria-label')).toMatch(/regime/i)
      expect(badges[0].getAttribute('aria-label')).toMatch(/CRISIS/)
    })
  })

  describe('chart toggle', () => {
    it('renders toggle buttons for VaR/ES and Greeks', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      expect(screen.getByTestId('chart-toggle-var')).toBeInTheDocument()
      expect(screen.getByTestId('chart-toggle-greeks')).toBeInTheDocument()
    })

    it('defaults to VaR/ES chart view', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      expect(screen.getByTestId('var-trend-chart')).toBeInTheDocument()
      expect(screen.queryByTestId('greeks-trend-chart')).not.toBeInTheDocument()
    })

    it('switches to Greeks chart when Greeks toggle is clicked', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      fireEvent.click(screen.getByTestId('chart-toggle-greeks'))

      expect(screen.getByTestId('greeks-trend-chart')).toBeInTheDocument()
      expect(screen.queryByTestId('var-trend-chart')).not.toBeInTheDocument()
    })

    it('switches back to VaR/ES chart when VaR toggle is clicked', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      fireEvent.click(screen.getByTestId('chart-toggle-greeks'))
      expect(screen.getByTestId('greeks-trend-chart')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('chart-toggle-var'))
      expect(screen.getByTestId('var-trend-chart')).toBeInTheDocument()
      expect(screen.queryByTestId('greeks-trend-chart')).not.toBeInTheDocument()
    })

    it('highlights the active toggle button', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      const varToggle = screen.getByTestId('chart-toggle-var')
      const greeksToggle = screen.getByTestId('chart-toggle-greeks')

      // VaR/ES active by default
      expect(varToggle.className).toContain('bg-primary-100')
      expect(greeksToggle.className).not.toContain('bg-primary-100')

      fireEvent.click(greeksToggle)

      expect(greeksToggle.className).toContain('bg-primary-100')
      expect(varToggle.className).not.toContain('bg-primary-100')
    })
  })

  describe('Explain (AI insight)', () => {
    beforeEach(() => {
      mockedExplainVar.mockReset()
      // Default: never-resolving promise so state updates from a stray click
      // never run after the test finishes. Tests that need a real result
      // override this with mockResolvedValueOnce.
      mockedExplainVar.mockReturnValue(new Promise(() => {}))
    })

    it('renders the Explain button in the gauge header', () => {
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      const btn = screen.getByTestId('explain-var-button')
      expect(btn).toBeInTheDocument()
      expect(btn).toHaveTextContent(/explain/i)
    })

    it('shows the loading state immediately after clicking Explain', async () => {
      // The default never-resolving promise from beforeEach keeps the panel
      // in the loading state so we can observe it synchronously.
      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      // Panel not yet visible.
      expect(screen.queryByTestId('ai-insight-panel')).not.toBeInTheDocument()

      await act(async () => {
        fireEvent.click(screen.getByTestId('explain-var-button'))
      })

      // Panel opens in loading state.
      expect(screen.getByTestId('ai-insight-panel')).toBeInTheDocument()
      expect(screen.getByTestId('ai-insight-loading')).toBeInTheDocument()
    })

    it('renders the narrative and bullets once explainVar resolves', async () => {
      const insight: InsightResponse = {
        narrative: 'VaR is concentrated in equity exposure.',
        bullets: [
          'EQUITY contributes 64.85% of total VaR',
          'Diversification across asset classes is moderate',
        ],
        model: 'claude-canned',
        mode: 'canned',
      }
      mockedExplainVar.mockReset()
      mockedExplainVar.mockResolvedValueOnce(insight)

      render(
        <VaRDashboard
          varResult={varResult}
          loading={false}
          error={null}
          onRefresh={() => {}}
          {...defaultZoomProps}
        />,
      )

      fireEvent.click(screen.getByTestId('explain-var-button'))

      expect(
        await screen.findByText('VaR is concentrated in equity exposure.'),
      ).toBeInTheDocument()
      expect(
        screen.getByText('EQUITY contributes 64.85% of total VaR'),
      ).toBeInTheDocument()
      expect(screen.queryByTestId('ai-insight-loading')).not.toBeInTheDocument()
      expect(mockedExplainVar).toHaveBeenCalledTimes(1)

      // Sanity-check the payload built from VaR state.
      const payload = mockedExplainVar.mock.calls[0][0]
      expect(payload.method).toBe('HISTORICAL')
      expect(payload.confidence).toBe(0.95)
      expect(payload.value_usd).toBeCloseTo(1234567.89, 2)
      expect(payload.top_contributors.length).toBeGreaterThan(0)
    })
  })

})
