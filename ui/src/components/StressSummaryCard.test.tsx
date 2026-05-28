import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { CannedStressResultDto, StressTestResultDto } from '../types'
import { StressSummaryCard } from './StressSummaryCard'

const stressResult: StressTestResultDto = {
  scenarioName: 'MARKET_CRASH',
  baseVar: '1000000',
  stressedVar: '2500000',
  pnlImpact: '-1500000',
  assetClassImpacts: [
    {
      assetClass: 'EQUITY',
      baseExposure: '5000000',
      stressedExposure: '3500000',
      pnlImpact: '-1500000',
    },
  ],
  positionImpacts: [],
  limitBreaches: [],
  calculatedAt: '2025-01-15T10:30:00Z',
}

const stressResult2: StressTestResultDto = {
  scenarioName: 'RATES_SPIKE',
  baseVar: '1000000',
  stressedVar: '1800000',
  pnlImpact: '-800000',
  assetClassImpacts: [],
  positionImpacts: [],
  limitBreaches: [],
  calculatedAt: '2025-01-15T10:35:00Z',
}

const stressResult3: StressTestResultDto = {
  scenarioName: 'VOL_SPIKE',
  baseVar: '1000000',
  stressedVar: '2100000',
  pnlImpact: '-1100000',
  assetClassImpacts: [],
  positionImpacts: [],
  limitBreaches: [],
  calculatedAt: '2025-01-15T10:40:00Z',
}

const stressResult4: StressTestResultDto = {
  scenarioName: 'EM_BLOWOUT',
  baseVar: '1000000',
  stressedVar: '1500000',
  pnlImpact: '-500000',
  assetClassImpacts: [],
  positionImpacts: [],
  limitBreaches: [],
  calculatedAt: '2025-01-15T10:45:00Z',
}

const defaultProps = {
  results: [] as StressTestResultDto[],
  loading: false,
  onRun: vi.fn(),
  onViewDetails: vi.fn(),
}

describe('StressSummaryCard', () => {
  it('renders empty state when no results and not loading', () => {
    render(<StressSummaryCard {...defaultProps} />)

    expect(screen.getByTestId('stress-summary-card')).toBeInTheDocument()
    expect(screen.getByText(/no stress test results/i)).toBeInTheDocument()
  })

  it('renders Run Stress Tests button', () => {
    render(<StressSummaryCard {...defaultProps} />)

    expect(screen.getByTestId('stress-summary-run-btn')).toBeInTheDocument()
  })

  it('calls onRun when Run Stress Tests button is clicked', () => {
    const onRun = vi.fn()
    render(<StressSummaryCard {...defaultProps} onRun={onRun} />)

    fireEvent.click(screen.getByTestId('stress-summary-run-btn'))

    expect(onRun).toHaveBeenCalledOnce()
  })

  it('shows loading state on the run button', () => {
    render(<StressSummaryCard {...defaultProps} loading={true} />)

    const btn = screen.getByTestId('stress-summary-run-btn')
    expect(btn).toHaveTextContent('Running...')
  })

  describe('single result', () => {
    it('renders the results table when one result is present', () => {
      render(<StressSummaryCard {...defaultProps} results={[stressResult]} />)

      expect(screen.getByTestId('stress-summary-table')).toBeInTheDocument()
    })

    it('displays scenario name in the table', () => {
      render(<StressSummaryCard {...defaultProps} results={[stressResult]} />)

      expect(screen.getByText('MARKET CRASH')).toBeInTheDocument()
    })

    it('displays Base VaR formatted as currency', () => {
      render(<StressSummaryCard {...defaultProps} results={[stressResult]} />)

      expect(screen.getByText('$1,000,000.00')).toBeInTheDocument()
    })

    it('displays Stressed VaR formatted as currency', () => {
      render(<StressSummaryCard {...defaultProps} results={[stressResult]} />)

      expect(screen.getByText('$2,500,000.00')).toBeInTheDocument()
    })

    it('displays P&L Impact with red text for losses', () => {
      render(<StressSummaryCard {...defaultProps} results={[stressResult]} />)

      const pnlCells = screen.getAllByTestId('stress-summary-pnl-impact')
      expect(pnlCells[0]).toHaveTextContent('-$1,500,000.00')
      expect(pnlCells[0].className).toContain('text-red-600')
    })

    it('does not apply red text to positive P&L impact', () => {
      const positiveResult: StressTestResultDto = {
        ...stressResult,
        pnlImpact: '500000',
      }

      render(<StressSummaryCard {...defaultProps} results={[positiveResult]} />)

      const pnlCells = screen.getAllByTestId('stress-summary-pnl-impact')
      expect(pnlCells[0]).toHaveTextContent('$500,000.00')
      expect(pnlCells[0].className).not.toContain('text-red-600')
    })

    it('renders View Details link when results are present', () => {
      render(<StressSummaryCard {...defaultProps} results={[stressResult]} />)

      expect(screen.getByTestId('stress-summary-view-details')).toBeInTheDocument()
    })

    it('calls onViewDetails when View Details link is clicked', () => {
      const onViewDetails = vi.fn()
      render(
        <StressSummaryCard
          {...defaultProps}
          results={[stressResult]}
          onViewDetails={onViewDetails}
        />,
      )

      fireEvent.click(screen.getByTestId('stress-summary-view-details'))

      expect(onViewDetails).toHaveBeenCalledOnce()
    })

    it('does not render View Details link when no results', () => {
      render(<StressSummaryCard {...defaultProps} />)

      expect(screen.queryByTestId('stress-summary-view-details')).not.toBeInTheDocument()
    })
  })

  describe('multiple results', () => {
    it('sorts results by absolute P&L impact descending', () => {
      render(
        <StressSummaryCard
          {...defaultProps}
          results={[stressResult2, stressResult, stressResult3]}
        />,
      )

      const rows = screen.getAllByTestId('stress-summary-row')
      expect(rows[0]).toHaveTextContent('MARKET CRASH')
      expect(rows[1]).toHaveTextContent('VOL SPIKE')
      expect(rows[2]).toHaveTextContent('RATES SPIKE')
    })

    it('shows only top 3 results when more than 3 are provided', () => {
      render(
        <StressSummaryCard
          {...defaultProps}
          results={[stressResult, stressResult2, stressResult3, stressResult4]}
        />,
      )

      const rows = screen.getAllByTestId('stress-summary-row')
      expect(rows).toHaveLength(3)
    })

    it('shows "View all N scenarios" when more than 3 results', () => {
      render(
        <StressSummaryCard
          {...defaultProps}
          results={[stressResult, stressResult2, stressResult3, stressResult4]}
        />,
      )

      expect(screen.getByText(/View all 4 scenarios/)).toBeInTheDocument()
    })

    it('does not show "View all" when 3 or fewer results', () => {
      render(
        <StressSummaryCard
          {...defaultProps}
          results={[stressResult, stressResult2, stressResult3]}
        />,
      )

      expect(screen.queryByText(/View all/)).not.toBeInTheDocument()
    })

    it('renders P&L impact for each row with colour coding', () => {
      render(
        <StressSummaryCard
          {...defaultProps}
          results={[stressResult, stressResult3]}
        />,
      )

      const pnlCells = screen.getAllByTestId('stress-summary-pnl-impact')
      expect(pnlCells).toHaveLength(2)
      expect(pnlCells[0]).toHaveTextContent('-$1,500,000.00')
      expect(pnlCells[0].className).toContain('text-red-600')
    })
  })

  // trader-review P0 #10: the summary card and the inline StressScenarioTile
  // must agree on whether stress results exist for the active book. When the
  // orchestrator has seeded a canned scenario but the user hasn't kicked off
  // a batch run yet, fall back to a single-row summary derived from the
  // canned payload instead of the "no stress test results yet" empty state.
  describe('canned scenario fallback', () => {
    const cannedResult: CannedStressResultDto = {
      bookId: 'port-1',
      scenario: '+100BPS_PARALLEL',
      deltaPv: '-1589994.06',
      asOf: '2026-05-28T10:25:00Z',
    }

    it('hides the empty state when results are empty but a canned result is present', () => {
      render(<StressSummaryCard {...defaultProps} cannedResult={cannedResult} />)

      expect(screen.queryByText(/no stress test results/i)).not.toBeInTheDocument()
      expect(screen.getByTestId('stress-summary-table')).toBeInTheDocument()
    })

    it('renders the canned scenario as a single fallback row', () => {
      render(<StressSummaryCard {...defaultProps} cannedResult={cannedResult} />)

      const rows = screen.getAllByTestId('stress-summary-row')
      expect(rows).toHaveLength(1)
      expect(rows[0]).toHaveTextContent('+100BPS PARALLEL')
      expect(rows[0]).toHaveTextContent('-$1,589,994.06')
    })

    it('renders base/stressed VaR as "—" when the canned fallback row is shown (those fields are unknown)', () => {
      render(<StressSummaryCard {...defaultProps} cannedResult={cannedResult} />)

      const row = screen.getByTestId('stress-summary-row')
      // Two "—" cells (Base VaR, Stressed VaR) — the P&L Impact cell renders the deltaPv.
      const dashCells = row.querySelectorAll('td')
      expect(dashCells[1].textContent).toBe('—')
      expect(dashCells[2].textContent).toBe('—')
    })

    it('prefers user-triggered batch results over the canned fallback when both are present', () => {
      render(
        <StressSummaryCard
          {...defaultProps}
          results={[stressResult]}
          cannedResult={cannedResult}
        />,
      )

      const rows = screen.getAllByTestId('stress-summary-row')
      expect(rows).toHaveLength(1)
      expect(rows[0]).toHaveTextContent('MARKET CRASH')
      expect(rows[0]).not.toHaveTextContent('+100BPS')
    })

    it('still shows the empty state when results are empty and there is no canned result', () => {
      render(<StressSummaryCard {...defaultProps} cannedResult={null} />)

      expect(screen.getByText(/no stress test results/i)).toBeInTheDocument()
    })
  })
})
