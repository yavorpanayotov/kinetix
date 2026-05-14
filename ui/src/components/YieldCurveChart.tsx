import { useEffect, useRef, useState } from 'react'
import { useYieldCurve } from '../hooks/useYieldCurve'
import type { YieldCurvePoint } from '../api/yieldCurve'

interface YieldCurveChartProps {
  currency: string | null
}

const PADDING = { top: 32, right: 24, bottom: 36, left: 56 }
const CHART_HEIGHT = 260
const DEFAULT_WIDTH = 600
const LINE_COLOUR = '#10b981'
const MARKER_RADIUS = 5
const INTERPOLATED_TOOLTIP = 'Interpolated — source node unavailable'

function computeNiceGridLines(min: number, max: number, count: number): number[] {
  const range = max - min
  if (range === 0) return [min]
  const rough = range / count
  const magnitude = Math.pow(10, Math.floor(Math.log10(rough)))
  const candidates = [1, 2, 2.5, 5, 10]
  let step = candidates[candidates.length - 1] * magnitude
  for (const c of candidates) {
    if (c * magnitude >= rough) { step = c * magnitude; break }
  }
  const lines: number[] = []
  const start = Math.ceil(min / step) * step
  for (let v = start; v <= max + 1e-9; v += step) lines.push(v)
  if (lines.length < 2 && range > 0) {
    lines.length = 0
    const s = range / count
    for (let i = 1; i <= count; i++) lines.push(min + s * i)
  }
  return lines
}

interface RenderPoint {
  point: YieldCurvePoint
  x: number
  y: number
  ratePct: number
}

interface HoverState {
  point: RenderPoint
  pageX: number
  pageY: number
}

export function YieldCurveChart({ currency }: YieldCurveChartProps) {
  const { curve, loading, error } = useYieldCurve(currency)
  const containerRef = useRef<HTMLDivElement>(null)
  const [containerWidth, setContainerWidth] = useState(DEFAULT_WIDTH)
  const [hover, setHover] = useState<HoverState | null>(null)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) setContainerWidth(entry.contentRect.width)
    })
    observer.observe(el)
    setContainerWidth(el.clientWidth)
    return () => observer.disconnect()
  }, [curve?.points.length])

  const plotWidth = containerWidth - PADDING.left - PADDING.right
  const plotHeight = CHART_HEIGHT - PADDING.top - PADDING.bottom

  if (!currency) {
    return (
      <div data-testid="yield-curve-chart" className="rounded bg-slate-800 p-4">
        <h3 className="text-sm font-semibold text-slate-300 mb-3">Yield Curve</h3>
        <div className="flex items-center justify-center text-sm text-slate-400" style={{ height: CHART_HEIGHT }}>
          Select a currency to view the yield curve.
        </div>
      </div>
    )
  }

  if (loading) {
    return (
      <div data-testid="yield-curve-chart" className="rounded bg-slate-800 p-4">
        <h3 className="text-sm font-semibold text-slate-300 mb-3">Yield Curve · {currency}</h3>
        <div role="status" aria-label="Loading yield curve" className="space-y-3 animate-pulse" style={{ height: CHART_HEIGHT }}>
          <div className="h-2 bg-slate-700 rounded w-full" />
          <div className="h-2 bg-slate-700 rounded w-3/4" />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div data-testid="yield-curve-chart" className="rounded bg-slate-800 p-4">
        <h3 className="text-sm font-semibold text-slate-300 mb-3">Yield Curve · {currency}</h3>
        <div data-testid="yield-curve-error" className="text-sm text-red-400 py-4">{error}</div>
      </div>
    )
  }

  if (!curve || curve.points.length < 2) {
    return (
      <div data-testid="yield-curve-chart" className="rounded bg-slate-800 p-4">
        <h3 className="text-sm font-semibold text-slate-300 mb-3">Yield Curve · {currency}</h3>
        <div className="flex items-center justify-center text-sm text-slate-400" style={{ height: CHART_HEIGHT }}>
          No yield curve available for {currency}.
        </div>
      </div>
    )
  }

  const points = [...curve.points].sort((a, b) => a.days - b.days)
  const ratesPct = points.map((p) => Number(p.rate) * 100)
  const xMin = points[0].days
  const xMax = points[points.length - 1].days
  const yMinRaw = Math.min(...ratesPct)
  const yMaxRaw = Math.max(...ratesPct)
  const yPad = (yMaxRaw - yMinRaw) * 0.15 || 0.1
  const yMin = yMinRaw - yPad
  const yMax = yMaxRaw + yPad

  // Use a log-ish scale on days so short tenors don't crowd the left edge.
  const toX = (days: number) => {
    const t = (Math.log(days) - Math.log(xMin)) / (Math.log(xMax) - Math.log(xMin) || 1)
    return PADDING.left + t * plotWidth
  }
  const toY = (ratePct: number) => {
    const range = yMax - yMin || 1
    return PADDING.top + (1 - (ratePct - yMin) / range) * plotHeight
  }

  const rendered: RenderPoint[] = points.map((p, i) => ({
    point: p,
    x: toX(p.days),
    y: toY(ratesPct[i]),
    ratePct: ratesPct[i],
  }))

  const polyline = rendered.map((r) => `${r.x.toFixed(1)},${r.y.toFixed(1)}`).join(' ')
  const gridLines = computeNiceGridLines(yMin, yMax, 4)

  return (
    <div ref={containerRef} data-testid="yield-curve-chart" className="relative rounded bg-slate-800 p-4">
      <div className="flex items-center gap-3 mb-2">
        <h3 className="text-sm font-semibold text-slate-300">Yield Curve · {currency}</h3>
        <span className="flex items-center gap-1 text-xs text-slate-400">
          <span className="inline-block w-2 h-2 rounded-full" style={{ backgroundColor: LINE_COLOUR }} />
          Observed
        </span>
        <span className="flex items-center gap-1 text-xs text-slate-400">
          <span
            className="inline-block w-2 h-2 rounded-full border-2"
            style={{ borderColor: LINE_COLOUR, backgroundColor: 'transparent' }}
          />
          Interpolated
        </span>
      </div>

      <svg
        width="100%"
        height={CHART_HEIGHT}
        className="select-none"
        aria-label={`Yield curve chart for ${currency}`}
        role="img"
      >
        {/* Y-axis grid lines */}
        {gridLines.map((v) => {
          const y = toY(v)
          return (
            <g key={v}>
              <line
                x1={PADDING.left}
                y1={y}
                x2={containerWidth - PADDING.right}
                y2={y}
                stroke="#334155"
                strokeDasharray="4 2"
              />
              <text
                x={PADDING.left - 6}
                y={y + 3}
                textAnchor="end"
                fill="#94a3b8"
                fontSize={10}
                className="font-mono tabular-nums"
              >
                {v.toFixed(2)}%
              </text>
            </g>
          )
        })}

        {/* X-axis labels: tenor labels under each point */}
        {rendered.map((r) => (
          <text
            key={`xlbl-${r.point.label}`}
            x={r.x}
            y={CHART_HEIGHT - 8}
            textAnchor="middle"
            fill="#94a3b8"
            fontSize={10}
          >
            {r.point.label}
          </text>
        ))}

        {/* Curve polyline */}
        <polyline
          points={polyline}
          fill="none"
          stroke={LINE_COLOUR}
          strokeWidth={2}
          strokeLinejoin="round"
        />

        {/* Markers — hollow for interpolated, solid otherwise */}
        {rendered.map((r) => {
          const interp = r.point.interpolated
          const testId = `yield-curve-marker-${r.point.label}`
          const ariaLabel = interp
            ? `${r.point.label} ${r.ratePct.toFixed(3)}% (interpolated)`
            : `${r.point.label} ${r.ratePct.toFixed(3)}%`
          return (
            <g key={`marker-${r.point.label}`}>
              <circle
                cx={r.x}
                cy={r.y}
                r={MARKER_RADIUS}
                fill={interp ? 'transparent' : LINE_COLOUR}
                stroke={LINE_COLOUR}
                strokeWidth={2}
                data-testid={testId}
                data-interpolated={interp ? 'true' : 'false'}
                aria-label={ariaLabel}
                role="img"
              >
                {interp ? <title>{INTERPOLATED_TOOLTIP}</title> : <title>{`${r.point.label}: ${r.ratePct.toFixed(3)}%`}</title>}
              </circle>
              {/* Larger transparent hit area for hover */}
              <circle
                cx={r.x}
                cy={r.y}
                r={MARKER_RADIUS + 8}
                fill="transparent"
                onMouseEnter={(e) => setHover({ point: r, pageX: e.clientX, pageY: e.clientY })}
                onMouseLeave={() => setHover((h) => (h?.point === r ? null : h))}
                style={{ cursor: 'pointer' }}
              />
            </g>
          )
        })}
      </svg>

      {hover && (
        <div
          data-testid={hover.point.point.interpolated ? 'yield-curve-tooltip-interpolated' : 'yield-curve-tooltip'}
          className="absolute pointer-events-none bg-slate-900 border border-slate-700 text-slate-100 text-xs rounded shadow-lg px-3 py-2 whitespace-nowrap"
          style={{
            left: Math.min(Math.max(hover.point.x - 80, 4), containerWidth - 200),
            top: Math.max(hover.point.y - 56, 4),
          }}
        >
          <div className="font-semibold">{hover.point.point.label}</div>
          <div className="font-mono tabular-nums">{hover.point.ratePct.toFixed(3)}%</div>
          {hover.point.point.interpolated && (
            <div className="mt-1 text-amber-300">{INTERPOLATED_TOOLTIP}</div>
          )}
        </div>
      )}

      <p className="mt-2 text-xs text-slate-500 font-mono tabular-nums">
        As of {curve.asOfDate} · Source: {curve.source}
      </p>
    </div>
  )
}
