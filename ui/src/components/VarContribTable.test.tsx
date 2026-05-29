import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { PositionDto, PositionRiskDto } from '../types'
import { PositionGrid } from './PositionGrid'

// VarContribTable footer row (Plan P3 #33): the VaR Contrib % column can sum to
// more than 100% because negative diversification contributors are netted out
// of the total. To make that discrepancy explicit rather than silent, the
// position grid renders a footer row showing the column sum of VaR Contrib %.

const makePosition = (overrides: Partial<PositionDto> = {}): PositionDto => ({
  bookId: 'book-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  quantity: '100',
  averageCost: { amount: '150.00', currency: 'USD' },
  marketPrice: { amount: '155.00', currency: 'USD' },
  marketValue: { amount: '15500.00', currency: 'USD' },
  unrealizedPnl: { amount: '500.00', currency: 'USD' },
  ...overrides,
})

const makeRisk = (overrides: Partial<PositionRiskDto> = {}): PositionRiskDto => ({
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
  ...overrides,
})

describe('VarContribTable footer', () => {
  it('renders a footer row showing the summed VaR Contrib %', () => {
    render(
      <PositionGrid
        positions={[
          makePosition({ instrumentId: 'AAPL' }),
          makePosition({ instrumentId: 'GOOGL' }),
        ]}
        positionRisk={[
          makeRisk({ instrumentId: 'AAPL', percentageOfTotal: '64.85' }),
          makeRisk({ instrumentId: 'GOOGL', percentageOfTotal: '40.00' }),
        ]}
      />,
    )

    const footer = screen.getByTestId('var-pct-footer')
    expect(footer).toBeInTheDocument()
    // 64.85 + 40.00 = 104.85 — explicitly surfaced even though it exceeds 100%.
    expect(footer).toHaveTextContent('104.85%')
  })

  it('sums correctly when negative diversification contributors net the total below the column sum', () => {
    render(
      <PositionGrid
        positions={[
          makePosition({ instrumentId: 'AAPL' }),
          makePosition({ instrumentId: 'GOOGL' }),
          makePosition({ instrumentId: 'TSLA' }),
        ]}
        positionRisk={[
          makeRisk({ instrumentId: 'AAPL', percentageOfTotal: '70.00' }),
          makeRisk({ instrumentId: 'GOOGL', percentageOfTotal: '50.00' }),
          makeRisk({ instrumentId: 'TSLA', percentageOfTotal: '-10.00' }),
        ]}
      />,
    )

    const footer = screen.getByTestId('var-pct-footer')
    // 70 + 50 + (-10) = 110.00%
    expect(footer).toHaveTextContent('110.00%')
  })

  it('does not render the footer row when no risk data is provided', () => {
    render(<PositionGrid positions={[makePosition()]} />)

    expect(screen.queryByTestId('var-pct-footer')).not.toBeInTheDocument()
  })

  it('labels the footer total so the sum is unambiguous', () => {
    const { container } = render(
      <PositionGrid
        positions={[makePosition({ instrumentId: 'AAPL' })]}
        positionRisk={[makeRisk({ instrumentId: 'AAPL', percentageOfTotal: '64.85' })]}
      />,
    )

    const tfoot = container.querySelector('tfoot')
    expect(tfoot).not.toBeNull()
    expect(within(tfoot as HTMLElement).getByText(/total/i)).toBeInTheDocument()
  })
})
