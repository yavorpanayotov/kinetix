import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PnlAttributionTable } from './PnlAttributionTable'
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
  positionAttributions: [
    {
      instrumentId: 'AAPL',
      assetClass: 'EQUITY',
      totalPnl: '8000.00',
      deltaPnl: '5000.00',
      gammaPnl: '1200.00',
      vegaPnl: '1500.00',
      thetaPnl: '-800.00',
      rhoPnl: '300.00',
      unexplainedPnl: '800.00',
    },
    {
      instrumentId: 'MSFT',
      assetClass: 'EQUITY',
      totalPnl: '7000.00',
      deltaPnl: '3000.00',
      gammaPnl: '1300.00',
      vegaPnl: '1500.00',
      thetaPnl: '-700.00',
      rhoPnl: '200.00',
      unexplainedPnl: '1700.00',
    },
  ],
  calculatedAt: '2025-01-15T10:30:00Z',
}

describe('PnlAttributionTable', () => {
  it('renders a row for each factor', () => {
    render(<PnlAttributionTable data={attribution} />)

    expect(screen.getByTestId('attribution-table')).toBeInTheDocument()
    expect(screen.getByTestId('factor-row-delta')).toBeInTheDocument()
    expect(screen.getByTestId('factor-row-gamma')).toBeInTheDocument()
    expect(screen.getByTestId('factor-row-vega')).toBeInTheDocument()
    expect(screen.getByTestId('factor-row-theta')).toBeInTheDocument()
    expect(screen.getByTestId('factor-row-rho')).toBeInTheDocument()
    expect(screen.getByTestId('factor-row-unexplained')).toBeInTheDocument()
  })

  it('displays amount and percentage of total for each factor', () => {
    render(<PnlAttributionTable data={attribution} />)

    const deltaRow = screen.getByTestId('factor-row-delta')
    expect(deltaRow).toHaveTextContent('8,000.00')
    expect(deltaRow).toHaveTextContent('53.3%')
  })

  it('suppresses % of total when offsetting factors dwarf a near-zero total', () => {
    render(
      <PnlAttributionTable
        data={{
          ...attribution,
          totalPnl: '-77046.35',
          gammaPnl: '-12382262479.12',
          unexplainedPnl: '12384262713.22',
        }}
      />,
    )

    const gammaRow = screen.getByTestId('factor-row-gamma')
    expect(gammaRow).toHaveTextContent('—')
    expect(gammaRow).not.toHaveTextContent('%')
    const naCell = gammaRow.querySelector('td[title]')
    expect(naCell?.getAttribute('title')).toMatch(/not meaningful/i)
  })

  it('prefixes a + on positive factor amounts and not on negatives', () => {
    render(<PnlAttributionTable data={attribution} />)

    // deltaPnl = 8000.00 (positive) — should carry a + prefix
    expect(screen.getByTestId('factor-amount-delta').textContent).toBe('+8,000.00')
    // thetaPnl = -1500.00 (negative) — should keep its minus sign without +
    expect(screen.getByTestId('factor-amount-theta').textContent).toBe('-1,500.00')
  })

  it('expands position drill-down when a factor row is clicked', () => {
    render(<PnlAttributionTable data={attribution} />)

    expect(screen.queryByTestId('position-detail-delta-AAPL')).not.toBeInTheDocument()

    fireEvent.click(screen.getByTestId('factor-row-delta'))

    expect(screen.getByTestId('position-detail-delta-AAPL')).toBeInTheDocument()
    expect(screen.getByTestId('position-detail-delta-MSFT')).toBeInTheDocument()
  })

  it('collapses position drill-down when the same factor row is clicked again', () => {
    render(<PnlAttributionTable data={attribution} />)

    fireEvent.click(screen.getByTestId('factor-row-delta'))
    expect(screen.getByTestId('position-detail-delta-AAPL')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('factor-row-delta'))
    expect(screen.queryByTestId('position-detail-delta-AAPL')).not.toBeInTheDocument()
  })

  it('shows position instrument and amount in drill-down', () => {
    render(<PnlAttributionTable data={attribution} />)

    fireEvent.click(screen.getByTestId('factor-row-delta'))

    const aaplDetail = screen.getByTestId('position-detail-delta-AAPL')
    expect(aaplDetail).toHaveTextContent('AAPL')
    expect(aaplDetail).toHaveTextContent('5,000.00')
    // Positive position amount should be prefixed with +
    expect(aaplDetail).toHaveTextContent('+5,000.00')
  })

  it('applies green color class to positive amounts and red to negative', () => {
    render(<PnlAttributionTable data={attribution} />)

    const deltaRow = screen.getByTestId('factor-row-delta')
    const thetaRow = screen.getByTestId('factor-row-theta')

    expect(deltaRow.querySelector('[data-testid="factor-amount-delta"]')?.className).toContain('text-green-600')
    expect(thetaRow.querySelector('[data-testid="factor-amount-theta"]')?.className).toContain('text-red-600')
  })
})
