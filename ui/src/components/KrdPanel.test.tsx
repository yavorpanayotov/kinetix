import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { KrdPanel } from './KrdPanel'

const TEST_AGGREGATED = [
  { tenorLabel: '2Y', tenorDays: 730, dv01: '120.50' },
  { tenorLabel: '5Y', tenorDays: 1825, dv01: '340.20' },
  { tenorLabel: '10Y', tenorDays: 3650, dv01: '680.75' },
  { tenorLabel: '30Y', tenorDays: 10950, dv01: '210.10' },
]

const TEST_INSTRUMENTS = [
  {
    instrumentId: 'UST-10Y',
    krdBuckets: [
      { tenorLabel: '2Y', tenorDays: 730, dv01: '20.00' },
      { tenorLabel: '5Y', tenorDays: 1825, dv01: '40.00' },
      { tenorLabel: '10Y', tenorDays: 3650, dv01: '580.00' },
      { tenorLabel: '30Y', tenorDays: 10950, dv01: '10.00' },
    ],
    totalDv01: '650.00',
  },
]

describe('KrdPanel', () => {
  it('renders loading state', () => {
    render(<KrdPanel aggregated={[]} instruments={[]} loading={true} error={null} />)
    expect(screen.getByTestId('krd-loading')).toBeVisible()
  })

  it('renders error state', () => {
    render(<KrdPanel aggregated={[]} instruments={[]} loading={false} error="Yield curve not found" />)
    expect(screen.getByTestId('krd-error')).toHaveTextContent('Yield curve not found')
  })

  it('renders empty state when no fixed-income positions', () => {
    render(<KrdPanel aggregated={[]} instruments={[]} loading={false} error={null} />)
    expect(screen.getByTestId('krd-empty')).toBeVisible()
    expect(screen.getByTestId('krd-empty')).toHaveTextContent(/no fixed-income positions/i)
  })

  it('does not claim "no fixed-income positions" when the book holds bonds — says KRD not computed instead', () => {
    // UX review: the panel said "No fixed-income positions in this book"
    // directly below a position table full of bonds. The panel can't infer
    // position composition from an empty KRD result.
    render(
      <KrdPanel
        aggregated={[]}
        instruments={[]}
        loading={false}
        error={null}
        hasFixedIncomePositions={true}
      />,
    )
    expect(screen.getByTestId('krd-empty')).toHaveTextContent(/not.*computed|run a risk calculation/i)
    expect(screen.getByTestId('krd-empty')).not.toHaveTextContent(/no fixed-income positions/i)
  })

  it('renders all four tenor buckets', () => {
    render(<KrdPanel aggregated={TEST_AGGREGATED} instruments={TEST_INSTRUMENTS} loading={false} error={null} />)
    expect(screen.getByTestId('krd-bucket-2Y')).toBeVisible()
    expect(screen.getByTestId('krd-bucket-5Y')).toBeVisible()
    expect(screen.getByTestId('krd-bucket-10Y')).toBeVisible()
    expect(screen.getByTestId('krd-bucket-30Y')).toBeVisible()
  })

  it('displays total DV01', () => {
    render(<KrdPanel aggregated={TEST_AGGREGATED} instruments={TEST_INSTRUMENTS} loading={false} error={null} />)
    expect(screen.getByTestId('krd-total-dv01')).toBeVisible()
  })

  it('expands to show instrument detail when toggled', () => {
    render(<KrdPanel aggregated={TEST_AGGREGATED} instruments={TEST_INSTRUMENTS} loading={false} error={null} />)
    expect(screen.queryByTestId('krd-instrument-detail')).not.toBeInTheDocument()

    fireEvent.click(screen.getByTestId('krd-expand-toggle'))
    expect(screen.getByTestId('krd-instrument-detail')).toBeVisible()
    expect(screen.getByText('UST-10Y')).toBeVisible()
  })

  it('renders SVG bars for each tenor bucket', () => {
    render(<KrdPanel aggregated={TEST_AGGREGATED} instruments={TEST_INSTRUMENTS} loading={false} error={null} />)
    const buckets = screen.getByTestId('krd-buckets')
    const svgs = buckets.querySelectorAll('svg')
    expect(svgs).toHaveLength(4)
  })
})
