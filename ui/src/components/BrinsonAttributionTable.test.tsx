import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { BrinsonAttributionTable } from './BrinsonAttributionTable'
import type { BrinsonAttributionDto } from '../api/benchmarkAttribution'

const sampleData: BrinsonAttributionDto = {
  bookId: 'BOOK-EQ-01',
  benchmarkId: 'SP500',
  asOfDate: '2026-03-25',
  sectors: [
    {
      sectorLabel: 'AAPL',
      portfolioWeight: 0.35,
      benchmarkWeight: 0.07,
      portfolioReturn: 0.12,
      benchmarkReturn: 0.10,
      allocationEffect: 0.028,
      selectionEffect: 0.014,
      interactionEffect: 0.004,
      totalActiveContribution: 0.046,
    },
    {
      sectorLabel: 'MSFT',
      portfolioWeight: 0.25,
      benchmarkWeight: 0.065,
      portfolioReturn: 0.08,
      benchmarkReturn: 0.09,
      allocationEffect: 0.010,
      selectionEffect: -0.0065,
      interactionEffect: -0.0019,
      totalActiveContribution: 0.0016,
    },
  ],
  totalActiveReturn: 0.0476,
  totalAllocationEffect: 0.038,
  totalSelectionEffect: 0.0075,
  totalInteractionEffect: 0.0021,
}

describe('BrinsonAttributionTable', () => {
  it('renders table with data-testid', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    expect(screen.getByTestId('brinson-attribution-table')).toBeInTheDocument()
  })

  it('renders a row for each sector', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    expect(screen.getByTestId('brinson-row-AAPL')).toBeInTheDocument()
    expect(screen.getByTestId('brinson-row-MSFT')).toBeInTheDocument()
  })

  it('displays sector label in each row', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    expect(screen.getByTestId('brinson-row-AAPL')).toHaveTextContent('AAPL')
    expect(screen.getByTestId('brinson-row-MSFT')).toHaveTextContent('MSFT')
  })

  it('displays portfolio and benchmark weights as percentages', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    const aaplRow = screen.getByTestId('brinson-row-AAPL')
    expect(aaplRow).toHaveTextContent('35.00%')
    expect(aaplRow).toHaveTextContent('7.00%')
  })

  it('displays allocation, selection, and interaction effects', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    const aaplRow = screen.getByTestId('brinson-row-AAPL')
    expect(aaplRow).toHaveTextContent('2.80%')  // allocationEffect 0.028
    expect(aaplRow).toHaveTextContent('1.40%')  // selectionEffect 0.014
    expect(aaplRow).toHaveTextContent('0.40%')  // interactionEffect 0.004
  })

  it('renders a totals row', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    expect(screen.getByTestId('brinson-totals-row')).toBeInTheDocument()
  })

  it('displays total active return in totals row', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    const totalsRow = screen.getByTestId('brinson-totals-row')
    expect(totalsRow).toHaveTextContent('4.76%')  // totalActiveReturn 0.0476
  })

  it('renders benchmark and as-of-date in header', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    expect(screen.getByTestId('brinson-attribution-table')).toHaveTextContent('SP500')
    expect(screen.getByTestId('brinson-attribution-table')).toHaveTextContent('2026-03-25')
  })

  it('applies positive color class to positive effects', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    const allocationCell = screen.getByTestId('brinson-allocation-AAPL')
    expect(allocationCell.className).toContain('text-green-600')
  })

  it('applies negative color class to negative effects', () => {
    render(<BrinsonAttributionTable data={sampleData} />)

    const selectionCell = screen.getByTestId('brinson-selection-MSFT')
    expect(selectionCell.className).toContain('text-red-600')
  })

  it('renders empty state when no sectors', () => {
    const emptyData = { ...sampleData, sectors: [] }
    render(<BrinsonAttributionTable data={emptyData} />)

    expect(screen.getByTestId('brinson-empty')).toBeInTheDocument()
  })

  it('renders a placeholder instead of crashing when a numeric field is missing or non-finite', () => {
    const dataWithGaps: BrinsonAttributionDto = {
      ...sampleData,
      sectors: [
        {
          ...sampleData.sectors[0],
          benchmarkReturn: undefined as unknown as number,
          allocationEffect: NaN,
        },
      ],
      totalActiveReturn: undefined as unknown as number,
    }

    // Must not throw "Cannot read properties of undefined (reading 'toFixed')".
    render(<BrinsonAttributionTable data={dataWithGaps} />)

    expect(screen.getByTestId('brinson-row-AAPL')).toHaveTextContent('—')
    expect(screen.getByTestId('brinson-totals-row')).toHaveTextContent('—')
  })
})
