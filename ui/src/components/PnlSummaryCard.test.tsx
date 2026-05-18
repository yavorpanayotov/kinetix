import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { PnlAttributionDto, SodBaselineStatusDto } from '../types'
import { PnlSummaryCard } from './PnlSummaryCard'

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

const sodStatusExists: SodBaselineStatusDto = {
  exists: true,
  baselineDate: '2026-02-28',
  snapshotType: 'MANUAL',
  createdAt: '2026-02-28T08:00:00Z',
  sourceJobId: null,
  calculationType: 'HISTORICAL',
}

const sodStatusMissing: SodBaselineStatusDto = {
  exists: false,
  baselineDate: null,
  snapshotType: null,
  createdAt: null,
  sourceJobId: null,
  calculationType: null,
}

const defaultProps = {
  sodStatus: null as SodBaselineStatusDto | null,
  pnlData: null as PnlAttributionDto | null,
  computing: false,
  onComputePnl: vi.fn(),
}

describe('PnlSummaryCard', () => {
  it('renders the card with P&L Attribution header', () => {
    render(<PnlSummaryCard {...defaultProps} />)

    expect(screen.getByTestId('pnl-summary-card')).toBeInTheDocument()
    expect(screen.getByText('P&L Attribution')).toBeInTheDocument()
  })

  describe('State A: No SOD baseline', () => {
    it('shows no-baseline message when sodStatus is null and no pnlData', () => {
      render(<PnlSummaryCard {...defaultProps} sodStatus={null} pnlData={null} />)

      expect(screen.getByTestId('pnl-no-baseline')).toBeInTheDocument()
      expect(
        screen.getByText(/No SOD baseline/),
      ).toBeInTheDocument()
    })

    it('shows no-baseline message when sodStatus.exists is false and no pnlData', () => {
      render(
        <PnlSummaryCard {...defaultProps} sodStatus={sodStatusMissing} pnlData={null} />,
      )

      expect(screen.getByTestId('pnl-no-baseline')).toBeInTheDocument()
    })
  })

  describe('State B: Baseline exists, no attribution', () => {
    it('shows compute prompt when baseline exists but no pnlData', () => {
      render(
        <PnlSummaryCard {...defaultProps} sodStatus={sodStatusExists} pnlData={null} />,
      )

      expect(screen.getByTestId('pnl-compute-prompt')).toBeInTheDocument()
      expect(screen.getByText('SOD baseline active')).toBeInTheDocument()
    })

    it('renders a Compute P&L button', () => {
      render(
        <PnlSummaryCard {...defaultProps} sodStatus={sodStatusExists} pnlData={null} />,
      )

      expect(screen.getByRole('button', { name: /Compute P&L/i })).toBeInTheDocument()
    })

    it('calls onComputePnl when Compute P&L button is clicked', async () => {
      const user = userEvent.setup()
      const onComputePnl = vi.fn()

      render(
        <PnlSummaryCard
          {...defaultProps}
          sodStatus={sodStatusExists}
          pnlData={null}
          onComputePnl={onComputePnl}
        />,
      )

      await user.click(screen.getByRole('button', { name: /Compute P&L/i }))
      expect(onComputePnl).toHaveBeenCalledOnce()
    })

    it('shows spinner when computing', () => {
      render(
        <PnlSummaryCard
          {...defaultProps}
          sodStatus={sodStatusExists}
          pnlData={null}
          computing={true}
        />,
      )

      expect(screen.getByTestId('pnl-compute-prompt')).toBeInTheDocument()
    })
  })

  describe('State C: Attribution available', () => {
    it('renders pnl-summary-data when pnlData is present', () => {
      render(<PnlSummaryCard {...defaultProps} pnlData={pnlData} />)

      expect(screen.getByTestId('pnl-summary-data')).toBeInTheDocument()
    })

    it('displays the total P&L value', () => {
      render(<PnlSummaryCard {...defaultProps} pnlData={pnlData} />)

      expect(screen.getByTestId('pnl-total-value')).toHaveTextContent('12,500.50')
    })

    it('prefixes a + on a positive total P&L value', () => {
      render(<PnlSummaryCard {...defaultProps} pnlData={pnlData} />)

      expect(screen.getByTestId('pnl-total-value').textContent).toBe('+12,500.50')
    })

    it('does not prefix a + on a negative total P&L value', () => {
      const negativeData: PnlAttributionDto = { ...pnlData, totalPnl: '-5000.00' }
      render(<PnlSummaryCard {...defaultProps} pnlData={negativeData} />)

      expect(screen.getByTestId('pnl-total-value').textContent).toBe('-5,000.00')
    })

    it('prefixes a + on positive factor values and not on negatives', () => {
      render(<PnlSummaryCard {...defaultProps} pnlData={pnlData} />)

      // deltaPnl = 8000.00 (positive) — should carry a + prefix
      expect(screen.getByTestId('pnl-factor-delta').textContent).toBe('+8,000.00')
      // gammaPnl = -1200.00 (negative) — should keep its minus sign without +
      expect(screen.getByTestId('pnl-factor-gamma').textContent).toBe('-1,200.00')
    })

    it('applies green colour to positive total P&L', () => {
      render(<PnlSummaryCard {...defaultProps} pnlData={pnlData} />)

      expect(screen.getByTestId('pnl-total-value').className).toContain('text-green-600')
    })

    it('applies red colour to negative total P&L', () => {
      const negativeData: PnlAttributionDto = { ...pnlData, totalPnl: '-5000.00' }
      render(<PnlSummaryCard {...defaultProps} pnlData={negativeData} />)

      expect(screen.getByTestId('pnl-total-value').className).toContain('text-red-600')
    })

    it('displays all factor values in the breakdown', () => {
      render(<PnlSummaryCard {...defaultProps} pnlData={pnlData} />)

      expect(screen.getByText('Delta')).toBeInTheDocument()
      expect(screen.getByText('Gamma')).toBeInTheDocument()
      expect(screen.getByText('Vega')).toBeInTheDocument()
      expect(screen.getByText('Theta')).toBeInTheDocument()
      expect(screen.getByText('Rho')).toBeInTheDocument()
      expect(screen.getByText('Unexplained')).toBeInTheDocument()
    })

    it('applies green colour to positive factor values', () => {
      render(<PnlSummaryCard {...defaultProps} pnlData={pnlData} />)

      const deltaValue = screen.getByTestId('pnl-factor-delta')
      expect(deltaValue.className).toContain('text-green-600')
    })

    it('applies red colour to negative factor values', () => {
      render(<PnlSummaryCard {...defaultProps} pnlData={pnlData} />)

      const gammaValue = screen.getByTestId('pnl-factor-gamma')
      expect(gammaValue.className).toContain('text-red-600')
    })

    it('renders View Full Attribution link when callback is provided', () => {
      render(
        <PnlSummaryCard {...defaultProps} pnlData={pnlData} onViewFullAttribution={vi.fn()} />,
      )

      expect(screen.getByText('View Full Attribution')).toBeInTheDocument()
    })

    it('calls onViewFullAttribution when View Full Attribution is clicked', async () => {
      const user = userEvent.setup()
      const onViewFullAttribution = vi.fn()

      render(
        <PnlSummaryCard
          {...defaultProps}
          pnlData={pnlData}
          onViewFullAttribution={onViewFullAttribution}
        />,
      )

      await user.click(screen.getByTestId('pnl-view-full-attribution'))
      expect(onViewFullAttribution).toHaveBeenCalledOnce()
    })

    it('does not render View Full Attribution link when callback is not provided', () => {
      render(<PnlSummaryCard {...defaultProps} pnlData={pnlData} />)

      expect(screen.queryByTestId('pnl-view-full-attribution')).not.toBeInTheDocument()
    })
  })
})
