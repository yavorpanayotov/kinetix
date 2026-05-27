// Greeks chart with a faded-axes loading state (kx-156m).
//
// Quant dashboards compute Greeks server-side and stream them to the UI. The
// computation takes anywhere from milliseconds (cached) to several seconds
// (fresh Monte Carlo run). Hiding the entire chart while it computes makes
// the page jitter; showing stale values without indication misleads the
// trader. Industry-standard treatment is to keep the chart frame visible at
// reduced opacity, replace the data series with a "Computing…" placeholder,
// and announce the state to assistive technology via role="status" with
// aria-busy.

import type { CSSProperties } from 'react'

export interface GreeksDatum {
  tenor: string
  delta: number
  gamma: number
  vega: number
  theta: number
}

export interface GreeksChartProps {
  data: GreeksDatum[]
  loading: boolean
}

const axesStyleReady: CSSProperties = {
  opacity: 1,
  transition: 'opacity 200ms ease',
}

const axesStyleLoading: CSSProperties = {
  // 0.4 keeps the gridlines and tenor labels readable as a visual anchor
  // while still clearly signalling "data not yet here."
  opacity: 0.4,
  transition: 'opacity 200ms ease',
}

function Axes({ tenors, loading }: { tenors: string[]; loading: boolean }) {
  return (
    <g
      data-testid="greeks-chart-axes"
      data-state={loading ? 'loading' : 'ready'}
      style={loading ? axesStyleLoading : axesStyleReady}
    >
      {tenors.map((tenor, idx) => (
        <text key={tenor} x={idx * 60 + 30} y={180} textAnchor="middle">
          {tenor}
        </text>
      ))}
      {/* Horizontal axis */}
      <line x1={0} y1={170} x2={Math.max(60, tenors.length * 60)} y2={170} stroke="currentColor" />
      {/* Vertical axis */}
      <line x1={0} y1={0} x2={0} y2={170} stroke="currentColor" />
    </g>
  )
}

function Placeholder() {
  return (
    <g
      data-testid="greeks-chart-placeholder"
      role="status"
      aria-busy="true"
      aria-live="polite"
    >
      <rect x={10} y={20} width={200} height={140} fill="currentColor" opacity={0.05} />
      <text x={110} y={95} textAnchor="middle" opacity={0.6}>
        Computing Greeks…
      </text>
    </g>
  )
}

function EmptyState() {
  return (
    <g data-testid="greeks-chart-empty">
      <text x={110} y={95} textAnchor="middle" opacity={0.6}>
        No Greeks for this position
      </text>
    </g>
  )
}

function DataSeries({ data }: { data: GreeksDatum[] }) {
  // Simple delta polyline — the visual is illustrative rather than
  // production-grade charting; the loading/empty/ready states are the
  // testable behaviour.
  const points = data
    .map((d, idx) => `${idx * 60 + 30},${170 - d.delta * 150}`)
    .join(' ')
  return (
    <g data-testid="greeks-chart-data">
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth={2} />
    </g>
  )
}

export function GreeksChart({ data, loading }: GreeksChartProps) {
  const tenors = data.length > 0 ? data.map((d) => d.tenor) : ['1M', '3M', '6M']
  return (
    <svg
      data-testid="greeks-chart"
      width={Math.max(240, tenors.length * 60)}
      height={200}
      role="img"
      aria-label="Greeks by tenor"
    >
      <Axes tenors={tenors} loading={loading} />
      {loading ? (
        <Placeholder />
      ) : data.length === 0 ? (
        <EmptyState />
      ) : (
        <DataSeries data={data} />
      )}
    </svg>
  )
}
