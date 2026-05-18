import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { IntradayPnlSnapshotDto, TradeAnnotationDto } from '../types'
import { IntradayPnlChart } from './IntradayPnlChart'

const makeSnapshot = (
  snapshotAt: string,
  overrides: Partial<IntradayPnlSnapshotDto> = {},
): IntradayPnlSnapshotDto => ({
  snapshotAt,
  baseCurrency: 'USD',
  trigger: 'position_change',
  totalPnl: '1500.00',
  realisedPnl: '500.00',
  unrealisedPnl: '1000.00',
  deltaPnl: '1200.00',
  gammaPnl: '80.00',
  vegaPnl: '40.00',
  thetaPnl: '-15.00',
  rhoPnl: '7.00',
  unexplainedPnl: '188.00',
  highWaterMark: '1800.00',
  ...overrides,
})

const makeAnnotation = (
  timestamp: string,
  overrides: Partial<TradeAnnotationDto> = {},
): TradeAnnotationDto => ({
  timestamp,
  instrumentId: 'AAPL',
  side: 'BUY',
  quantity: '100',
  tradeId: 'T001',
  ...overrides,
})

const twoSnapshots = [
  makeSnapshot('2026-03-24T09:30:00Z', { totalPnl: '1000.00', realisedPnl: '200.00', unrealisedPnl: '800.00' }),
  makeSnapshot('2026-03-24T09:31:00Z', { totalPnl: '1500.00', realisedPnl: '500.00', unrealisedPnl: '1000.00' }),
]

describe('IntradayPnlChart', () => {
  it('renders empty state when no snapshots provided', () => {
    render(<IntradayPnlChart snapshots={[]} />)

    expect(screen.getByTestId('intraday-pnl-chart-empty')).toBeInTheDocument()
    expect(screen.getByText(/No intraday data/)).toBeInTheDocument()
  })

  it('renders single-point notice when only one snapshot exists', () => {
    render(<IntradayPnlChart snapshots={[makeSnapshot('2026-03-24T09:30:00Z')]} />)

    expect(screen.getByTestId('intraday-pnl-chart-single')).toBeInTheDocument()
  })

  it('renders the chart SVG when two or more snapshots are present', () => {
    const { container } = render(<IntradayPnlChart snapshots={twoSnapshots} />)

    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders total P&L line path when multiple snapshots are present', () => {
    const { container } = render(<IntradayPnlChart snapshots={twoSnapshots} />)

    // Total P&L line is rendered as a path element
    const paths = container.querySelectorAll('path[data-series="total"]')
    expect(paths.length).toBeGreaterThan(0)
  })

  it('renders realised and unrealised paths', () => {
    const { container } = render(<IntradayPnlChart snapshots={twoSnapshots} />)

    expect(container.querySelectorAll('path[data-series="realised"]').length).toBeGreaterThan(0)
    expect(container.querySelectorAll('path[data-series="unrealised"]').length).toBeGreaterThan(0)
  })

  it('displays the latest total P&L value in the header', () => {
    render(<IntradayPnlChart snapshots={twoSnapshots} />)

    expect(screen.getByTestId('intraday-chart-latest-total')).toHaveTextContent('1,500.00')
  })

  it('prefixes a + on a positive latest total P&L', () => {
    render(<IntradayPnlChart snapshots={twoSnapshots} />)

    expect(screen.getByTestId('intraday-chart-latest-total').textContent).toBe('+1,500.00')
  })

  it('does not prefix a + on a negative latest total P&L', () => {
    const snapshots = [
      makeSnapshot('2026-03-24T09:30:00Z', { totalPnl: '-500.00' }),
      makeSnapshot('2026-03-24T09:31:00Z', { totalPnl: '-750.00' }),
    ]
    render(<IntradayPnlChart snapshots={snapshots} />)

    expect(screen.getByTestId('intraday-chart-latest-total').textContent).toBe('-750.00')
  })

  it('applies green colour to positive latest total P&L', () => {
    render(<IntradayPnlChart snapshots={twoSnapshots} />)

    expect(screen.getByTestId('intraday-chart-latest-total').className).toContain('text-green-600')
  })

  it('applies red colour to negative latest total P&L', () => {
    const snapshots = [
      makeSnapshot('2026-03-24T09:30:00Z', { totalPnl: '-500.00' }),
      makeSnapshot('2026-03-24T09:31:00Z', { totalPnl: '-750.00' }),
    ]
    render(<IntradayPnlChart snapshots={snapshots} />)

    expect(screen.getByTestId('intraday-chart-latest-total').className).toContain('text-red-600')
  })

  it('renders the chart container with testid', () => {
    render(<IntradayPnlChart snapshots={twoSnapshots} />)

    expect(screen.getByTestId('intraday-pnl-chart')).toBeInTheDocument()
  })

  it('renders trade annotation markers for each annotation', () => {
    const annotations = [
      makeAnnotation('2026-03-24T09:30:15Z', { tradeId: 'T001' }),
      makeAnnotation('2026-03-24T09:30:45Z', { tradeId: 'T002', side: 'SELL' }),
    ]
    const { container } = render(
      <IntradayPnlChart snapshots={twoSnapshots} tradeAnnotations={annotations} />,
    )

    const markers = container.querySelectorAll('[data-testid="trade-marker"]')
    expect(markers.length).toBe(2)
  })

  it('renders no trade markers when tradeAnnotations is empty', () => {
    const { container } = render(
      <IntradayPnlChart snapshots={twoSnapshots} tradeAnnotations={[]} />,
    )

    const markers = container.querySelectorAll('[data-testid="trade-marker"]')
    expect(markers.length).toBe(0)
  })

  it('renders no trade markers when tradeAnnotations prop is omitted', () => {
    const { container } = render(<IntradayPnlChart snapshots={twoSnapshots} />)

    const markers = container.querySelectorAll('[data-testid="trade-marker"]')
    expect(markers.length).toBe(0)
  })
})
