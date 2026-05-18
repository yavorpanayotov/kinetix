import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PnlWaterfallChart } from './PnlWaterfallChart'
import type { PnlAttributionDto } from '../types'

const attribution: PnlAttributionDto = {
  bookId: 'book-1',
  date: '2025-01-15',
  totalPnl: '15000.00',
  deltaPnl: '8000.00',
  gammaPnl: '2500.00',
  vegaPnl: '3000.00',
  thetaPnl: '-1500.00',
  rhoPnl: '500.00',
  unexplainedPnl: '2500.00',
  positionAttributions: [],
  calculatedAt: '2025-01-15T10:30:00Z',
}

describe('PnlWaterfallChart', () => {
  it('renders all factor bars plus total', () => {
    render(<PnlWaterfallChart data={attribution} />)

    expect(screen.getByTestId('waterfall-chart')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-delta')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-gamma')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-vega')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-theta')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-rho')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-unexplained')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-total')).toBeInTheDocument()
  })

  it('displays factor labels', () => {
    render(<PnlWaterfallChart data={attribution} />)

    expect(screen.getByText('Delta')).toBeInTheDocument()
    expect(screen.getByText('Gamma')).toBeInTheDocument()
    expect(screen.getByText('Vega')).toBeInTheDocument()
    expect(screen.getByText('Theta')).toBeInTheDocument()
    expect(screen.getByText('Rho')).toBeInTheDocument()
    expect(screen.getByText('Unexplained')).toBeInTheDocument()
    expect(screen.getByText('Total')).toBeInTheDocument()
  })

  it('displays formatted amounts for each factor', () => {
    render(<PnlWaterfallChart data={attribution} />)

    expect(screen.getByTestId('waterfall-value-delta')).toHaveTextContent('8,000.00')
    expect(screen.getByTestId('waterfall-value-theta')).toHaveTextContent('-1,500.00')
    expect(screen.getByTestId('waterfall-value-total')).toHaveTextContent('15,000.00')
  })

  it('prefixes a + on positive factor values and not on negative or zero', () => {
    render(<PnlWaterfallChart data={attribution} />)

    expect(screen.getByTestId('waterfall-value-delta').textContent).toBe('+8,000.00')
    expect(screen.getByTestId('waterfall-value-total').textContent).toBe('+15,000.00')
    expect(screen.getByTestId('waterfall-value-theta').textContent).toBe('-1,500.00')
  })

  it('applies green color class to positive values and red to negative', () => {
    render(<PnlWaterfallChart data={attribution} />)

    const deltaValue = screen.getByTestId('waterfall-value-delta')
    expect(deltaValue.className).toContain('text-green-600')

    const thetaValue = screen.getByTestId('waterfall-value-theta')
    expect(thetaValue.className).toContain('text-red-600')
  })
})
