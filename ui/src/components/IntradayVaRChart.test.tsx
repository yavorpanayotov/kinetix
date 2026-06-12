import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { IntradayVaRPointDto, TradeAnnotationDto } from '../types'
import { IntradayVaRChart } from './IntradayVaRChart'

const makePoint = (timestamp: string, overrides: Partial<IntradayVaRPointDto> = {}): IntradayVaRPointDto => ({
  timestamp,
  varValue: 12500.0,
  expectedShortfall: 15000.0,
  delta: 0.65,
  gamma: null,
  vega: null,
  ...overrides,
})

const makeAnnotation = (timestamp: string, overrides: Partial<TradeAnnotationDto> = {}): TradeAnnotationDto => ({
  timestamp,
  instrumentId: 'AAPL',
  side: 'BUY',
  quantity: '100',
  tradeId: 'T001',
  ...overrides,
})

const twoPoints = [
  makePoint('2026-03-25T09:00:00Z', { varValue: 10000.0 }),
  makePoint('2026-03-25T09:30:00Z', { varValue: 12500.0 }),
]

describe('IntradayVaRChart', () => {
  it('renders empty state when no varPoints are provided', () => {
    render(<IntradayVaRChart varPoints={[]} tradeAnnotations={[]} />)

    expect(screen.getByTestId('intraday-var-chart-empty')).toBeInTheDocument()
    expect(screen.getByText(/No intraday VaR data/)).toBeInTheDocument()
  })

  it('renders single-point notice when only one VaR point exists', () => {
    render(<IntradayVaRChart varPoints={[makePoint('2026-03-25T09:00:00Z')]} tradeAnnotations={[]} />)

    expect(screen.getByTestId('intraday-var-chart-single')).toBeInTheDocument()
  })

  it('renders the chart SVG when two or more points are present', () => {
    const { container } = render(<IntradayVaRChart varPoints={twoPoints} tradeAnnotations={[]} />)

    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('does not over-zoom the y-axis on near-flat data — enforces a minimum span around the level', () => {
    // UX review: VaR drifting 255.8K→256.2K (~0.15%) auto-scaled to fill the
    // plot and read as a cliff. With the ±2% minimum span the axis ticks must
    // reach well outside the data range.
    const flat = [
      makePoint('2026-03-25T09:00:00Z', { varValue: 256_200 }),
      makePoint('2026-03-25T12:00:00Z', { varValue: 255_800 }),
    ]
    render(<IntradayVaRChart varPoints={flat} tradeAnnotations={[]} />)

    // A $260K gridline only exists if the axis spans ≥ ~±2% of the level.
    expect(screen.getByText('$260K')).toBeInTheDocument()
  })

  it('renders VaR line path when multiple points are present', () => {
    const { container } = render(<IntradayVaRChart varPoints={twoPoints} tradeAnnotations={[]} />)

    const varPath = container.querySelector('path[data-series="var"]')
    expect(varPath).toBeInTheDocument()
  })

  it('renders trade annotation markers for each annotation', () => {
    const annotations = [
      makeAnnotation('2026-03-25T09:15:00Z', { tradeId: 'T001' }),
      makeAnnotation('2026-03-25T09:20:00Z', { tradeId: 'T002' }),
    ]
    const { container } = render(<IntradayVaRChart varPoints={twoPoints} tradeAnnotations={annotations} />)

    const markers = container.querySelectorAll('[data-testid="trade-marker"]')
    expect(markers.length).toBe(2)
  })

  it('renders no trade markers when tradeAnnotations is empty', () => {
    const { container } = render(<IntradayVaRChart varPoints={twoPoints} tradeAnnotations={[]} />)

    const markers = container.querySelectorAll('[data-testid="trade-marker"]')
    expect(markers.length).toBe(0)
  })

  it('displays the latest VaR value in the header', () => {
    render(<IntradayVaRChart varPoints={twoPoints} tradeAnnotations={[]} />)

    expect(screen.getByTestId('intraday-var-latest')).toBeInTheDocument()
  })

  it('renders the chart container with correct testid', () => {
    render(<IntradayVaRChart varPoints={twoPoints} tradeAnnotations={[]} />)

    expect(screen.getByTestId('intraday-var-chart')).toBeInTheDocument()
  })

  it('does not show a last-session indicator when sessionDate is not provided', () => {
    render(<IntradayVaRChart varPoints={twoPoints} tradeAnnotations={[]} />)

    expect(screen.queryByTestId('intraday-var-last-session')).not.toBeInTheDocument()
  })

  it('shows a last-session indicator with the date when sessionDate is provided', () => {
    render(<IntradayVaRChart varPoints={twoPoints} tradeAnnotations={[]} sessionDate="2026-05-30" />)

    const indicator = screen.getByTestId('intraday-var-last-session')
    expect(indicator).toBeInTheDocument()
    expect(indicator).toHaveTextContent('2026-05-30')
  })

  it('last-session indicator is accessible via aria attribute when sessionDate is provided', () => {
    render(<IntradayVaRChart varPoints={twoPoints} tradeAnnotations={[]} sessionDate="2026-05-30" />)

    const indicator = screen.getByTestId('intraday-var-last-session')
    expect(
      indicator.getAttribute('aria-label') || indicator.getAttribute('role'),
    ).toBeTruthy()
  })
})
