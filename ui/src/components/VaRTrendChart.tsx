import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { RotateCcw } from 'lucide-react'
import type { VaRHistoryEntry } from '../hooks/useVaR'
import type { StressWindowDto } from '../api/stressWindows'
import type { TimeRange, TradeAnnotationDto } from '../types'
import { formatTimeOnly, formatChartTime, formatCurrency } from '../utils/format'
import { formatCompactCurrency } from '../utils/formatCompactCurrency'
import { clampTooltipLeft } from '../utils/clampTooltipLeft'
import { useBrushSelection } from '../hooks/useBrushSelection'
import { resolveTimeRange } from '../utils/resolveTimeRange'

interface VaRTrendChartProps {
  history: VaRHistoryEntry[]
  isLoading?: boolean
  timeRange?: TimeRange
  onZoom?: (range: TimeRange) => void
  zoomDepth?: number
  onResetZoom?: () => void
  stressWindows?: StressWindowDto[]
  tradeAnnotations?: TradeAnnotationDto[]
}

const PADDING = { top: 32, right: 16, bottom: 32, left: 56 }
const CHART_HEIGHT = 220
const DEFAULT_WIDTH = 600
const MARKER_SIZE = 6

function computeNiceGridLines(min: number, max: number, count: number): number[] {
  const range = max - min
  if (range === 0) return [min]

  const rough = range / count
  const magnitude = Math.pow(10, Math.floor(Math.log10(rough)))
  const candidates = [1, 2, 2.5, 5, 10]
  let step = candidates[candidates.length - 1] * magnitude
  for (const c of candidates) {
    if (c * magnitude >= rough) {
      step = c * magnitude
      break
    }
  }

  const lines: number[] = []
  const start = Math.ceil(min / step) * step
  for (let v = start; v <= max; v += step) {
    lines.push(v)
  }

  if (lines.length < 2 && range > 0) {
    lines.length = 0
    const simpleStep = range / count
    for (let i = 1; i <= count; i++) {
      lines.push(min + simpleStep * i)
    }
  }

  return lines
}

export function VaRTrendChart({ history, isLoading, timeRange, onZoom, zoomDepth = 0, onResetZoom, stressWindows = [], tradeAnnotations = [] }: VaRTrendChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const tooltipRef = useRef<HTMLDivElement>(null)
  const [containerWidth, setContainerWidth] = useState(DEFAULT_WIDTH)
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null)
  const [tooltipLeft, setTooltipLeft] = useState(0)
  const [hiddenSeries, setHiddenSeries] = useState<Set<string>>(new Set())

  const handleLegendClick = useCallback((seriesKey: string, e: React.MouseEvent) => {
    const isMultiSelect = e.ctrlKey || e.metaKey
    setHiddenSeries((prev) => {
      if (isMultiSelect) {
        const next = new Set(prev)
        if (next.has(seriesKey)) {
          next.delete(seriesKey)
        } else {
          const visibleAfter = ['var', 'es'].filter(k => k !== seriesKey && !next.has(k))
          if (visibleAfter.length > 0) next.add(seriesKey)
        }
        return next
      }
      const othersHidden = ['var', 'es'].filter(k => k !== seriesKey).every(k => prev.has(k))
      if (othersHidden && !prev.has(seriesKey)) return new Set()
      return new Set(['var', 'es'].filter(k => k !== seriesKey))
    })
  }, [])

  const varVisible = !hiddenSeries.has('var')
  const esVisible = !hiddenSeries.has('es')

  const hasChart = history.length >= 2

  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setContainerWidth(entry.contentRect.width)
      }
    })
    observer.observe(el)
    setContainerWidth(el.clientWidth)

    return () => observer.disconnect()
  }, [hasChart])

  const plotWidth = containerWidth - PADDING.left - PADDING.right
  const plotHeight = CHART_HEIGHT - PADDING.top - PADDING.bottom

  const { min, max } = useMemo(() => {
    // VaR and ES are conventionally non-negative. Zero-sentinel values
    // (e.g. useVaR fills missing ES with 0) would otherwise drag the
    // Y-domain bottom below the meaningful range and make the recent VaR
    // look like a flatline. Filter them out before fitting.
    const meaningful = (xs: number[]) => xs.filter((v) => Number.isFinite(v) && v > 0)
    const values = [
      ...(varVisible ? meaningful(history.map((e) => e.varValue)) : []),
      ...(esVisible ? meaningful(history.map((e) => e.expectedShortfall)) : []),
    ]
    if (values.length === 0) {
      const fallback = meaningful(history.flatMap((e) => [e.varValue, e.expectedShortfall]))
      if (fallback.length === 0) return { min: 0, max: 1 }
      const fMin = Math.min(...fallback)
      const fMax = Math.max(...fallback)
      const fRange = fMax - fMin
      const fPad = fRange * 0.1 || fMax * 0.1 || 1
      // Clamp top to 1.5x dataMax so padding never blows the axis open.
      return { min: Math.max(0, fMin - fPad), max: Math.min(fMax + fPad, fMax * 1.5) }
    }
    const minVal = Math.min(...values)
    const maxVal = Math.max(...values)
    const range = maxVal - minVal
    const padding = range * 0.1 || maxVal * 0.1 || 1
    // Clamp the domain: bottom >= 0 (VaR/ES are non-negative) and
    // top <= 1.5x maxVal so a single outlier can't push the axis into
    // a regime where typical values flatline visually.
    return {
      min: Math.max(0, minVal - padding),
      max: Math.min(maxVal + padding, maxVal * 1.5),
    }
  }, [history, varVisible, esVisible])

  const gridLines = useMemo(() => computeNiceGridLines(min, max, 4), [min, max])

  // Resolve the time range fresh (sliding presets use Date.now()) so X-axis
  // labels and data positions reflect the selected period, not stale timestamps.
  // history is included as a dep so the extent re-resolves on every poll cycle.
  const timeExtent = useMemo(() => {
    if (timeRange) {
      const { from, to } = resolveTimeRange(timeRange)
      const fromMs = new Date(from).getTime()
      const toMs = new Date(to).getTime()
      if (toMs > fromMs) return { fromMs, toMs, durationMs: toMs - fromMs }
    }
    // Fallback: derive extent from data timestamps
    if (history.length >= 2) {
      const times = history.map((e) => new Date(e.calculatedAt).getTime())
      const fromMs = Math.min(...times)
      const toMs = Math.max(...times)
      if (toMs > fromMs) return { fromMs, toMs, durationMs: toMs - fromMs }
    }
    return null
  }, [timeRange, history])

  const toX = useCallback(
    (timestampMs: number) => {
      if (timeExtent) {
        const pct = Math.max(0, Math.min(1, (timestampMs - timeExtent.fromMs) / timeExtent.durationMs))
        return PADDING.left + pct * plotWidth
      }
      return 0
    },
    [timeExtent, plotWidth],
  )

  const handleBrushEnd = useCallback(
    (startX: number, endX: number) => {
      if (!timeExtent || !onZoom) return

      const leftPct = (startX - PADDING.left) / plotWidth
      const rightPct = (endX - PADDING.left) / plotWidth

      const zoomFrom = new Date(timeExtent.fromMs + leftPct * timeExtent.durationMs)
      const zoomTo = new Date(timeExtent.fromMs + rightPct * timeExtent.durationMs)

      onZoom({
        from: zoomFrom.toISOString(),
        to: zoomTo.toISOString(),
        label: 'Custom',
      })
    },
    [timeExtent, onZoom, plotWidth],
  )

  const { brush, handlers: brushHandlers } = useBrushSelection({ onBrushEnd: handleBrushEnd })

  const xLabels = useMemo(() => {
    if (history.length < 2 || !timeExtent) return []

    const count = 6
    const rangeDays = timeExtent.durationMs / (24 * 60 * 60 * 1000)
    const labels: { x: number; text: string }[] = []
    for (let i = 0; i <= count; i++) {
      const t = timeExtent.fromMs + (i / count) * timeExtent.durationMs
      labels.push({
        x: PADDING.left + (i / count) * plotWidth,
        text: formatChartTime(new Date(t), rangeDays),
      })
    }
    return labels
  }, [history, plotWidth, timeExtent])

  const points = useMemo(() => {
    if (history.length < 2) return []
    const range = max - min || 1
    return history.map((entry) => ({
      x: timeExtent
        ? toX(new Date(entry.calculatedAt).getTime())
        : PADDING.left + plotWidth / 2,
      y: PADDING.top + (1 - (entry.varValue - min) / range) * plotHeight,
    }))
  }, [history, plotWidth, plotHeight, min, max, timeExtent, toX])

  // Leading void: detect when data starts after the time range begins
  const firstDataX = points.length > 0 ? points[0].x : null
  const hasLeadingVoid = firstDataX !== null && firstDataX > PADDING.left + 8

  // Mid-series gap detection: find stretches between data points that are
  // significantly larger than the typical interval
  const gapRegions = useMemo(() => {
    if (history.length < 3 || !timeExtent) return []

    const intervals: number[] = []
    for (let i = 1; i < history.length; i++) {
      intervals.push(
        new Date(history[i].calculatedAt).getTime() - new Date(history[i - 1].calculatedAt).getTime(),
      )
    }
    intervals.sort((a, b) => a - b)
    const median = intervals[Math.floor(intervals.length / 2)]
    const threshold = median * 3

    const regions: { x1: number; x2: number }[] = []
    for (let i = 1; i < history.length; i++) {
      const gap = new Date(history[i].calculatedAt).getTime() - new Date(history[i - 1].calculatedAt).getTime()
      if (gap > threshold) {
        regions.push({
          x1: toX(new Date(history[i - 1].calculatedAt).getTime()),
          x2: toX(new Date(history[i].calculatedAt).getTime()),
        })
      }
    }
    return regions
  }, [history, timeExtent, toX])

  // Stress-window bands: each window from the demo regime calendar renders
  // as a vertical red band with its label. Bands clip to the visible time
  // range; entirely-outside windows are skipped.
  const stressBands = useMemo(() => {
    if (!timeExtent || stressWindows.length === 0) return []
    return stressWindows.flatMap((window) => {
      const startMs = new Date(window.start).getTime()
      const endMs = new Date(window.end).getTime()
      const lo = Math.min(startMs, endMs)
      const hi = Math.max(startMs, endMs)
      if (hi < timeExtent.fromMs || lo > timeExtent.toMs) return []
      const visibleLo = Math.max(lo, timeExtent.fromMs)
      const visibleHi = Math.min(hi, timeExtent.toMs)
      return [{
        label: window.label,
        x1: toX(visibleLo),
        x2: toX(visibleHi),
      }]
    })
  }, [stressWindows, timeExtent, toX])

  // Trade annotation positions (only those within time extent)
  const annotationPositions = useMemo(() => {
    if (!timeExtent || tradeAnnotations.length === 0) return []
    return tradeAnnotations
      .map((ann) => {
        const t = new Date(ann.timestamp).getTime()
        if (t < timeExtent.fromMs || t > timeExtent.toMs) return null
        return { x: toX(t), annotation: ann }
      })
      .filter((a): a is { x: number; annotation: TradeAnnotationDto } => a !== null)
  }, [tradeAnnotations, timeExtent, toX])

  const polylinePoints = points.map((p) => `${p.x},${p.y}`).join(' ')

  const areaPoints = useMemo(() => {
    if (points.length === 0) return ''
    const baseY = PADDING.top + plotHeight
    const first = `${points[0].x},${baseY}`
    const last = `${points[points.length - 1].x},${baseY}`
    return `${first} ${polylinePoints} ${last}`
  }, [points, polylinePoints, plotHeight])

  const esPoints = useMemo(() => {
    if (history.length < 2) return []
    const range = max - min || 1
    return history.map((entry) => ({
      x: timeExtent
        ? toX(new Date(entry.calculatedAt).getTime())
        : PADDING.left + plotWidth / 2,
      y: PADDING.top + (1 - (entry.expectedShortfall - min) / range) * plotHeight,
    }))
  }, [history, plotWidth, plotHeight, min, max, timeExtent, toX])

  const esPolylinePoints = esPoints.map((p) => `${p.x},${p.y}`).join(' ')

  const esAreaPoints = useMemo(() => {
    if (esPoints.length === 0) return ''
    const baseY = PADDING.top + plotHeight
    const first = `${esPoints[0].x},${baseY}`
    const last = `${esPoints[esPoints.length - 1].x},${baseY}`
    return `${first} ${esPolylinePoints} ${last}`
  }, [esPoints, esPolylinePoints, plotHeight])

  const toY = useCallback(
    (value: number) => {
      const range = max - min || 1
      return PADDING.top + (1 - (value - min) / range) * plotHeight
    },
    [min, max, plotHeight],
  )

  const handleMouseMove = useCallback(
    (e: React.MouseEvent<SVGSVGElement>) => {
      brushHandlers.onMouseMove(e)

      if (points.length === 0) return

      const el = containerRef.current
      if (!el) return

      const rect = el.getBoundingClientRect()
      const mouseX = e.clientX - rect.left

      // Suppress tooltip in the leading void zone
      if (firstDataX !== null && mouseX < firstDataX) {
        setHoveredIndex(null)
        return
      }

      let closest = 0
      let closestDist = Infinity
      for (let i = 0; i < points.length; i++) {
        const dist = Math.abs(points[i].x - mouseX)
        if (dist < closestDist) {
          closestDist = dist
          closest = i
        }
      }

      setHoveredIndex(closest)
    },
    [points, brushHandlers, firstDataX],
  )

  const handleMouseLeave = useCallback(() => {
    brushHandlers.onMouseLeave()
    setHoveredIndex(null)
  }, [brushHandlers])

  useLayoutEffect(() => {
    if (hoveredIndex === null || !tooltipRef.current) return
    const tooltipWidth = tooltipRef.current.offsetWidth
    const pointX = points[hoveredIndex]?.x ?? 0
    setTooltipLeft(clampTooltipLeft(pointX, tooltipWidth, containerWidth))
  }, [hoveredIndex, points, containerWidth])

  const latestValue = history.length > 0 ? history[history.length - 1].varValue : 0
  const formattedLatest = formatCurrency(latestValue)
  const latestES = history.length > 0 ? history[history.length - 1].expectedShortfall : 0
  const formattedLatestES = formatCurrency(latestES)

  if (isLoading && history.length < 2) {
    return (
      <div data-testid="var-trend-chart" className="rounded bg-slate-800 p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-semibold text-slate-300">VaR Trend</h3>
        </div>
        <div
          role="status"
          aria-label="Loading chart data"
          className="space-y-3 animate-pulse"
          style={{ height: CHART_HEIGHT }}
        >
          <div className="h-2 bg-slate-700 rounded w-full" />
          <div className="h-2 bg-slate-700 rounded w-full" />
          <div className="h-2 bg-slate-700 rounded w-3/4" />
          <div className="h-2 bg-slate-700 rounded w-full" />
        </div>
      </div>
    )
  }

  if (history.length === 0) {
    return (
      <div data-testid="var-trend-chart" className="rounded bg-slate-800 p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-semibold text-slate-300">VaR Trend</h3>
        </div>
        <div className="flex items-center justify-center text-sm text-slate-400" style={{ height: CHART_HEIGHT }}>
          No calculations yet for this time range.
        </div>
      </div>
    )
  }

  if (history.length === 1) {
    return (
      <div data-testid="var-trend-chart" className="rounded bg-slate-800 p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-semibold text-slate-300">VaR Trend</h3>
          <span className="text-sm font-mono text-indigo-400">{formattedLatest}</span>
        </div>
        <div className="flex items-center justify-center text-sm text-slate-400" style={{ height: CHART_HEIGHT }}>
          Needs at least 2 calculations to draw a trend.
        </div>
      </div>
    )
  }

  return (
    <div ref={containerRef} data-testid="var-trend-chart" className="relative rounded bg-slate-800 p-4 pb-14">
      {zoomDepth > 0 && onResetZoom && (
        <button
          data-testid="reset-zoom"
          onClick={onResetZoom}
          className="absolute top-2 right-2 z-10 flex items-center gap-1 px-2 py-1 text-xs text-slate-300 bg-slate-700 hover:bg-slate-600 rounded"
        >
          <RotateCcw className="h-3 w-3" />
          Reset zoom
        </button>
      )}

      <div className="flex items-center justify-between mb-1">
        <h3 className="text-sm font-semibold text-slate-300">VaR Trend</h3>
        <div className="flex items-center gap-3">
          <span className="text-sm font-mono text-indigo-400">{formattedLatest}</span>
          <span className="text-sm font-mono text-amber-400">{formattedLatestES}</span>
        </div>
      </div>

      <div className="flex items-center gap-3 mb-1 text-xs text-slate-400">
        <button
          type="button"
          data-testid="legend-toggle-var"
          onClick={(e) => handleLegendClick('var', e)}
          className="flex items-center gap-1 bg-transparent border-0 p-0 text-xs text-slate-400"
          style={{ cursor: 'pointer', opacity: varVisible ? 1 : 0.35 }}
        >
          <span className="inline-block w-3 h-0.5 bg-indigo-500 rounded" />
          <span data-testid="legend-label-var" style={{ textDecoration: varVisible ? 'none' : 'line-through' }}>VaR</span>
        </button>
        <button
          type="button"
          data-testid="legend-toggle-es"
          onClick={(e) => handleLegendClick('es', e)}
          className="flex items-center gap-1 bg-transparent border-0 p-0 text-xs text-slate-400"
          style={{ cursor: 'pointer', opacity: esVisible ? 1 : 0.35 }}
        >
          <span className="inline-block w-3 h-0.5 bg-amber-500 rounded" />
          <span data-testid="legend-label-es" style={{ textDecoration: esVisible ? 'none' : 'line-through' }}>ES</span>
        </button>
      </div>

      {hasLeadingVoid && (
        <p data-testid="coverage-annotation" className="text-xs text-slate-500 mb-1">
          Data from {formatTimeOnly(history[0].calculatedAt)} ({history.length} calculations)
        </p>
      )}

      <svg
        width="100%"
        height={CHART_HEIGHT}
        className={`select-none ${onZoom ? 'cursor-crosshair' : ''}`}
        onMouseDown={onZoom ? brushHandlers.onMouseDown : undefined}
        onMouseMove={handleMouseMove}
        onMouseUp={onZoom ? brushHandlers.onMouseUp : undefined}
        onMouseLeave={handleMouseLeave}
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
              <text x={PADDING.left - 6} y={y + 3} textAnchor="end" fill="#94a3b8" fontSize={10}>
                {formatCompactCurrency(v)}
              </text>
            </g>
          )
        })}

        {/* X-axis labels */}
        {xLabels.map((label, i) => (
          <text
            key={i}
            x={label.x}
            y={CHART_HEIGHT - 6}
            textAnchor="middle"
            fill="#94a3b8"
            fontSize={10}
          >
            {label.text}
          </text>
        ))}

        {/* Leading void — no data before the first calculation */}
        {hasLeadingVoid && (
          <g data-testid="leading-void">
            <rect
              x={PADDING.left}
              y={PADDING.top}
              width={firstDataX! - PADDING.left}
              height={plotHeight}
              fill="rgba(15, 23, 42, 0.35)"
            />
            <line
              x1={firstDataX!}
              y1={PADDING.top}
              x2={firstDataX!}
              y2={PADDING.top + plotHeight}
              stroke="#475569"
              strokeWidth={1}
              strokeDasharray="3 3"
            />
            <text
              x={firstDataX! + 4}
              y={PADDING.top + 12}
              fill="#64748b"
              fontSize={9}
            >
              First calculation
            </text>
            {(firstDataX! - PADDING.left) / plotWidth > 0.3 && (
              <text
                x={PADDING.left + (firstDataX! - PADDING.left) / 2}
                y={PADDING.top + plotHeight / 2}
                textAnchor="middle"
                fill="#334155"
                fontSize={10}
              >
                No data
              </text>
            )}
          </g>
        )}

        {/* Mid-series gap regions */}
        {gapRegions.map((gap, i) => (
          <rect
            key={i}
            data-testid="gap-region"
            x={gap.x1}
            y={PADDING.top}
            width={gap.x2 - gap.x1}
            height={plotHeight}
            fill="rgba(71, 85, 105, 0.25)"
            stroke="#475569"
            strokeWidth={0.5}
            strokeDasharray="2 4"
          />
        ))}

        {/* Stress-window bands (regime annotations) */}
        {stressBands.map((band, i) => (
          <g key={`stress-${i}`} data-testid={`stress-band-${band.label}`}>
            <rect
              x={band.x1}
              y={PADDING.top}
              width={Math.max(band.x2 - band.x1, 1)}
              height={plotHeight}
              fill="rgba(220, 38, 38, 0.08)"
              stroke="#dc2626"
              strokeWidth={0.5}
              strokeDasharray="3 3"
            />
            <text
              x={band.x1 + 4}
              y={PADDING.top + 12}
              fill="#fca5a5"
              fontSize={9}
              data-testid={`stress-label-${band.label}`}
            >
              {band.label}
            </text>
          </g>
        ))}

        {/* ES area fill */}
        <polygon points={esAreaPoints} fill="rgba(245, 158, 11, 0.10)" opacity={esVisible ? 1 : 0.35} style={{ transition: 'opacity 150ms' }} />
        {/* ES line */}
        <polyline points={esPolylinePoints} fill="none" stroke="#f59e0b" strokeWidth={2} strokeLinejoin="round" opacity={esVisible ? 1 : 0.35} style={{ transition: 'opacity 150ms' }} />

        {/* Area fill */}
        <polygon points={areaPoints} fill="rgba(99, 102, 241, 0.15)" opacity={varVisible ? 1 : 0.35} style={{ transition: 'opacity 150ms' }} />

        {/* Line */}
        <polyline
          points={polylinePoints}
          fill="none"
          stroke="#6366f1"
          strokeWidth={2}
          strokeLinejoin="round"
          opacity={varVisible ? 1 : 0.35}
          style={{ transition: 'opacity 150ms' }}
        />

        {/* Trade annotation markers (triangles pointing up from the X-axis) */}
        {annotationPositions.map(({ x, annotation }) => (
          <polygon
            key={annotation.tradeId}
            data-testid="trade-marker"
            points={`${x},${PADDING.top + plotHeight - MARKER_SIZE} ${x - MARKER_SIZE / 2},${PADDING.top + plotHeight} ${x + MARKER_SIZE / 2},${PADDING.top + plotHeight}`}
            fill={annotation.side === 'BUY' ? '#22c55e' : '#f43f5e'}
            opacity={0.9}
          />
        ))}

        {/* Hover crosshair + dot */}
        {hoveredIndex !== null && points[hoveredIndex] && (
          <>
            <line
              data-testid="crosshair"
              x1={points[hoveredIndex].x}
              y1={PADDING.top}
              x2={points[hoveredIndex].x}
              y2={PADDING.top + plotHeight}
              stroke="#94a3b8"
              strokeDasharray="4 2"
              strokeWidth={1}
            />
            <circle
              data-testid="hover-dot"
              cx={points[hoveredIndex].x}
              cy={points[hoveredIndex].y}
              r={4}
              fill="#6366f1"
              stroke="white"
              strokeWidth={2}
            />
            {esPoints[hoveredIndex] && (
              <circle
                data-testid="hover-dot-es"
                cx={esPoints[hoveredIndex].x}
                cy={esPoints[hoveredIndex].y}
                r={4}
                fill="#f59e0b"
                stroke="white"
                strokeWidth={2}
              />
            )}
          </>
        )}

        {/* Brush selection overlay */}
        {brush.active && (
          <rect
            x={Math.min(brush.startX, brush.currentX)}
            y={PADDING.top}
            width={Math.abs(brush.currentX - brush.startX)}
            height={plotHeight}
            fill="rgba(99, 102, 241, 0.2)"
            stroke="#6366f1"
            strokeWidth={1}
          />
        )}
      </svg>

      {/* Tooltip */}
      {hoveredIndex !== null && history[hoveredIndex] && (
        <div className="relative">
          <div
            ref={tooltipRef}
            data-testid="var-trend-tooltip"
            className="absolute top-0 bg-slate-800 text-white text-xs rounded shadow-lg px-3 py-2 pointer-events-none whitespace-nowrap border border-slate-600"
            style={{ left: `${tooltipLeft}px` }}
          >
            <div className="font-medium mb-1">{formatTimeOnly(history[hoveredIndex].calculatedAt)}</div>
            <div className="flex gap-3">
              <span className="text-indigo-400">
                VaR: {formatCurrency(history[hoveredIndex].varValue)}
              </span>
              <span className="text-amber-400">
                ES: {formatCurrency(history[hoveredIndex].expectedShortfall)}
              </span>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
