// Tests for the faded Greeks-chart loading state (kx-156m).
//
// While the risk engine is computing Greeks (delta/gamma/vega/theta), the
// chart should communicate that data is in-flight without hiding the axes —
// jumping from a populated chart to a blank panel and back disorients the
// user. Industry practice is to keep the axes visible at reduced opacity and
// overlay a "Computing Greeks…" placeholder where the data series would be,
// so the chart's shape remains as a stable visual anchor.

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import { GreeksChart } from './GreeksChart'

const SAMPLE_GREEKS = [
  { tenor: '1M', delta: 0.5, gamma: 0.02, vega: 0.15, theta: -0.01 },
  { tenor: '3M', delta: 0.48, gamma: 0.018, vega: 0.13, theta: -0.012 },
  { tenor: '6M', delta: 0.45, gamma: 0.015, vega: 0.12, theta: -0.014 },
]

describe('<GreeksChart />', () => {
  it('renders the data series when not loading', () => {
    render(<GreeksChart data={SAMPLE_GREEKS} loading={false} />)
    expect(screen.getByTestId('greeks-chart')).toBeInTheDocument()
    expect(screen.getByTestId('greeks-chart-data')).toBeInTheDocument()
    expect(screen.queryByTestId('greeks-chart-placeholder')).not.toBeInTheDocument()
  })

  it('renders the placeholder area when loading', () => {
    render(<GreeksChart data={[]} loading={true} />)
    expect(screen.getByTestId('greeks-chart-placeholder')).toBeInTheDocument()
    expect(screen.getByText('Computing Greeks…')).toBeInTheDocument()
  })

  it('keeps the axes visible while loading so the chart frame stays stable', () => {
    render(<GreeksChart data={[]} loading={true} />)
    expect(screen.getByTestId('greeks-chart-axes')).toBeInTheDocument()
  })

  it('fades the axes to reduced opacity while loading', () => {
    render(<GreeksChart data={[]} loading={true} />)
    const axes = screen.getByTestId('greeks-chart-axes')
    expect(axes).toHaveAttribute('data-state', 'loading')
    expect(axes).toHaveStyle({ opacity: '0.4' })
  })

  it('renders axes at full opacity when not loading', () => {
    render(<GreeksChart data={SAMPLE_GREEKS} loading={false} />)
    const axes = screen.getByTestId('greeks-chart-axes')
    expect(axes).toHaveAttribute('data-state', 'ready')
    expect(axes).toHaveStyle({ opacity: '1' })
  })

  it('exposes role="status" and aria-busy on the placeholder so AT users hear the state', () => {
    render(<GreeksChart data={[]} loading={true} />)
    const placeholder = screen.getByTestId('greeks-chart-placeholder')
    expect(placeholder).toHaveAttribute('role', 'status')
    expect(placeholder).toHaveAttribute('aria-busy', 'true')
    expect(placeholder).toHaveAttribute('aria-live', 'polite')
  })

  it('hides the data series while loading even if rows are present', () => {
    // Real-world scenario: a previous fetch's data is still cached but a
    // fresh request is in-flight. We hide it so the user doesn't compare
    // stale numbers without realising.
    render(<GreeksChart data={SAMPLE_GREEKS} loading={true} />)
    expect(screen.queryByTestId('greeks-chart-data')).not.toBeInTheDocument()
  })

  it('labels each tenor on the axis when ready', () => {
    render(<GreeksChart data={SAMPLE_GREEKS} loading={false} />)
    expect(screen.getByText('1M')).toBeInTheDocument()
    expect(screen.getByText('3M')).toBeInTheDocument()
    expect(screen.getByText('6M')).toBeInTheDocument()
  })

  it('falls back to the loading state when not loading but data is empty', () => {
    // No data and not loading should render an empty-state hint rather than
    // an empty chart frame that looks broken.
    render(<GreeksChart data={[]} loading={false} />)
    expect(screen.getByTestId('greeks-chart-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('greeks-chart-data')).not.toBeInTheDocument()
    expect(screen.queryByTestId('greeks-chart-placeholder')).not.toBeInTheDocument()
  })
})
