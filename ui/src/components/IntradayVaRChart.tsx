import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { RotateCcw } from 'lucide-react'
import type { IntradayVaRPointDto, TradeAnnotationDto } from '../types'
import { formatCompactNum, formatCurrency, formatTimeOnly } from '../utils/format'
import { formatCompactCurrency } from '../utils/formatCompactCurrency'
import { clampTooltipLeft } from '../utils/clampTooltipLeft'
import { useBrushSelection } from '../hooks/useBrushSelection'

interface IntradayVaRChartProps {
  varPoints: IntradayVaRPointDto[]
  tradeAnnotations: TradeAnnotationDto[]
  /**
   * When provided, the chart is displaying data from a past session rather
   * than today. The value is an ISO date string (e.g. "2026-05-30"). The
   * chart renders a subtle accessible indicator so the user knows the data
   * is not from the current trading day.
   */
  sessionDate?: string | null
}

const PADDING = { top: 32, right: 60, bottom: 28, left: 56 }
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

export function IntradayVaRChart({ varPoints, tradeAnnotations, sessionDate }: IntradayVaRChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const tooltipRef = useRef<HTMLDivElement>(null)
  const [containerWidth, setContainerWidth] = useState(DEFAULT_WIDTH)
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null)
  const [tooltipLeft, setTooltipLeft] = useState(0)
  const [zoomedRange, setZoomedRange] = useState<{ from: number; to: number } | null>(null)

  const plotWidth = containerWidth - PADDING.left - PADDING.right
  const plotHeight = CHART_HEIGHT - PADDING.top - PADDING.bottom

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
  }, [varPoints.length])

  // Time extent derived from zoomed range or full data
  const timeExtent = useMemo(() => {
    if (zoomedRange) return { fromMs: zoomedRange.from, toMs: zoomedRange.to, durationMs: zoomedRange.to - zoomedRange.from }
    if (varPoints.length < 2) return null
    const times = varPoints.map((p) => new Date(p.timestamp).getTime())
    const fromMs = Math.min(...times)
    const toMs = Math.max(...times)
    if (toMs <= fromMs) return null
    return { fromMs, toMs, durationMs: toMs - fromMs }
  }, [varPoints, zoomedRange])

  // Y scale for VaR values (left axis)
  const { varMin, varMax } = useMemo(() => {
    if (varPoints.length === 0) return { varMin: 0, varMax: 1 }
    const values = varPoints.map((p) => p.varValue)
    const min = Math.min(...values)
    const max = Math.max(...values)
    const range = max - min
    const pad = range * 0.15 || Math.abs(max) * 0.15 || 1
    // Enforce a minimum visible span of ±2% of the midpoint: a 0.15% intraday
    // drift auto-scaled to fill the plot reads as a cliff (UX review) — the
    // axis must give the eye a denominator.
    const mid = (min + max) / 2
    const minSpan = Math.abs(mid) * 0.04
    const span = Math.max(max - min + 2 * pad, minSpan)
    return { varMin: mid - span / 2, varMax: mid + span / 2 }
  }, [varPoints])

  // Y scale for delta values (right axis)
  const { deltaMin, deltaMax } = useMemo(() => {
    const deltas = varPoints.map((p) => p.delta).filter((d): d is number => d !== null)
    if (deltas.length === 0) return { deltaMin: -1, deltaMax: 1 }
    const min = Math.min(...deltas)
    const max = Math.max(...deltas)
    const range = max - min
    const pad = range * 0.15 || Math.abs(max) * 0.15 || 0.1
    return { deltaMin: min - pad, deltaMax: max + pad }
  }, [varPoints])

  const toX = useCallback(
    (timestampMs: number): number => {
      if (!timeExtent) return PADDING.left + plotWidth / 2
      const pct = Math.max(0, Math.min(1, (timestampMs - timeExtent.fromMs) / timeExtent.durationMs))
      return PADDING.left + pct * plotWidth
    },
    [timeExtent, plotWidth],
  )

  const toY = useCallback(
    (value: number): number => {
      const range = varMax - varMin || 1
      return PADDING.top + (1 - (value - varMin) / range) * plotHeight
    },
    [varMin, varMax, plotHeight],
  )

  const toDeltaY = useCallback(
    (value: number): number => {
      const range = deltaMax - deltaMin || 1
      return PADDING.top + (1 - (value - deltaMin) / range) * plotHeight
    },
    [deltaMin, deltaMax, plotHeight],
  )

  const varPlotPoints = useMemo(() => {
    if (!timeExtent || varPoints.length < 2) return []
    return varPoints.map((p) => ({
      x: toX(new Date(p.timestamp).getTime()),
      y: toY(p.varValue),
    }))
  }, [varPoints, timeExtent, toX, toY])

  const deltaPlotPoints = useMemo(() => {
    if (!timeExtent || varPoints.length < 2) return []
    return varPoints
      .filter((p) => p.delta !== null)
      .map((p) => ({
        x: toX(new Date(p.timestamp).getTime()),
        y: toDeltaY(p.delta as number),
      }))
  }, [varPoints, timeExtent, toX, toDeltaY])

  const varPolyline = varPlotPoints.map((p) => `${p.x},${p.y}`).join(' ')
  const deltaPolyline = deltaPlotPoints.map((p) => `${p.x},${p.y}`).join(' ')

  // Build path string with gap detection for VaR
  const gapRegions = useMemo(() => {
    if (varPoints.length < 3 || !timeExtent) return []
    const intervals: number[] = []
    for (let i = 1; i < varPoints.length; i++) {
      intervals.push(
        new Date(varPoints[i].timestamp).getTime() - new Date(varPoints[i - 1].timestamp).getTime(),
      )
    }
    intervals.sort((a, b) => a - b)
    const median = intervals[Math.floor(intervals.length / 2)]
    const threshold = median * 3
    const regions: { x1: number; x2: number }[] = []
    for (let i = 1; i < varPoints.length; i++) {
      const gap = new Date(varPoints[i].timestamp).getTime() - new Date(varPoints[i - 1].timestamp).getTime()
      if (gap > threshold) {
        regions.push({
          x1: toX(new Date(varPoints[i - 1].timestamp).getTime()),
          x2: toX(new Date(varPoints[i].timestamp).getTime()),
        })
      }
    }
    return regions
  }, [varPoints, timeExtent, toX])

  const varGridLines = useMemo(() => computeNiceGridLines(varMin, varMax, 4), [varMin, varMax])
  const deltaGridLines = useMemo(() => computeNiceGridLines(deltaMin, deltaMax, 4), [deltaMin, deltaMax])

  // X-axis labels
  const xLabels = useMemo(() => {
    if (!timeExtent || varPoints.length < 2) return []
    const count = 6
    return Array.from({ length: count + 1 }, (_, i) => {
      const t = timeExtent.fromMs + (i / count) * timeExtent.durationMs
      return { x: PADDING.left + (i / count) * plotWidth, text: formatTimeOnly(new Date(t).toISOString()) }
    })
  }, [varPoints.length, timeExtent, plotWidth])

  // Trade annotation positions (only those within time extent)
  const annotationPositions = useMemo(() => {
    if (!timeExtent || varPoints.length < 2) return []
    return tradeAnnotations
      .map((ann) => {
        const t = new Date(ann.timestamp).getTime()
        if (t < timeExtent.fromMs || t > timeExtent.toMs) return null
        const x = toX(t)
        return { x, annotation: ann }
      })
      .filter((a): a is { x: number; annotation: TradeAnnotationDto } => a !== null)
  }, [tradeAnnotations, timeExtent, toX, varPoints.length])

  const handleBrushEnd = useCallback(
    (startX: number, endX: number) => {
      if (!timeExtent) return
      const leftPct = (startX - PADDING.left) / plotWidth
      const rightPct = (endX - PADDING.left) / plotWidth
      const from = timeExtent.fromMs + leftPct * timeExtent.durationMs
      const to = timeExtent.fromMs + rightPct * timeExtent.durationMs
      setZoomedRange({ from, to })
    },
    [timeExtent, plotWidth],
  )

  const { brush, handlers: brushHandlers } = useBrushSelection({ onBrushEnd: handleBrushEnd })

  const handleMouseMove = useCallback(
    (e: React.MouseEvent<SVGSVGElement>) => {
      brushHandlers.onMouseMove(e)
      if (varPlotPoints.length === 0) return
      const el = containerRef.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      const mouseX = e.clientX - rect.left
      let closest = 0
      let closestDist = Infinity
      for (let i = 0; i < varPlotPoints.length; i++) {
        const dist = Math.abs(varPlotPoints[i].x - mouseX)
        if (dist < closestDist) {
          closestDist = dist
          closest = i
        }
      }
      setHoveredIndex(closest)
    },
    [varPlotPoints, brushHandlers],
  )

  const handleMouseLeave = useCallback(() => {
    brushHandlers.onMouseLeave()
    setHoveredIndex(null)
  }, [brushHandlers])

  useLayoutEffect(() => {
    if (hoveredIndex === null || !tooltipRef.current) return
    const tooltipWidth = tooltipRef.current.offsetWidth
    const pointX = varPlotPoints[hoveredIndex]?.x ?? 0
    setTooltipLeft(clampTooltipLeft(pointX, tooltipWidth, containerWidth))
  }, [hoveredIndex, varPlotPoints, containerWidth])

  const latestPoint = varPoints.length > 0 ? varPoints[varPoints.length - 1] : null

  // ---- Empty / single-point states ----
  if (varPoints.length === 0) {
    return (
      <div
        data-testid="intraday-var-chart"
        className="rounded bg-slate-800 p-4"
      >
        <h3 className="text-sm font-semibold text-slate-300 mb-2">Intraday VaR</h3>
        <div
          data-testid="intraday-var-chart-empty"
          className="flex items-center justify-center text-sm text-slate-400"
          style={{ height: CHART_HEIGHT }}
        >
          No intraday VaR data yet — points appear as valuation jobs complete.
        </div>
      </div>
    )
  }

  if (varPoints.length === 1) {
    return (
      <div
        data-testid="intraday-var-chart"
        className="rounded bg-slate-800 p-4"
      >
        <h3 className="text-sm font-semibold text-slate-300 mb-2">Intraday VaR</h3>
        <div
          data-testid="intraday-var-chart-single"
          className="flex items-center justify-center text-sm text-slate-400"
          style={{ height: CHART_HEIGHT }}
        >
          Only one VaR point — need at least two to draw a trend.
        </div>
      </div>
    )
  }

  const brushLeft = Math.min(brush.startX, brush.currentX)
  const brushWidth = Math.abs(brush.currentX - brush.startX)
  const hasDelta = varPoints.some((p) => p.delta !== null)

  return (
    <div
      ref={containerRef}
      data-testid="intraday-var-chart"
      className="relative rounded bg-slate-800 p-4 pb-14"
    >
      {zoomedRange && (
        <button
          data-testid="intraday-var-reset-zoom"
          onClick={() => setZoomedRange(null)}
          className="absolute top-2 right-2 z-10 flex items-center gap-1 px-2 py-1 text-xs text-slate-300 bg-slate-700 hover:bg-slate-600 rounded"
        >
          <RotateCcw className="h-3 w-3" />
          Reset zoom
        </button>
      )}

      <div className="flex items-center justify-between mb-1">
        <div className="flex items-center gap-3">
          <h3 className="text-sm font-semibold text-slate-300">Intraday VaR</h3>
          {sessionDate && (
            <span
              data-testid="intraday-var-last-session"
              role="status"
              aria-label={`Showing last available session: ${sessionDate}`}
              className="text-xs text-amber-400 bg-amber-900/30 border border-amber-700 rounded px-1.5 py-0.5"
            >
              Last session: {sessionDate}
            </span>
          )}
          <div className="flex items-center gap-2 text-xs text-slate-400">
            <span className="flex items-center gap-1">
              <span className="inline-block w-3 h-0.5 bg-indigo-500 rounded" />
              VaR
            </span>
            {hasDelta && (
              <span className="flex items-center gap-1">
                <span className="inline-block w-3 h-0.5 bg-emerald-500 rounded" />
                Delta
              </span>
            )}
          </div>
        </div>
        {latestPoint && (
          <span
            data-testid="intraday-var-latest"
            className="text-sm font-mono text-indigo-400 tabular-nums"
          >
            {formatCurrency(latestPoint.varValue)}
          </span>
        )}
      </div>

      <svg
        width="100%"
        height={CHART_HEIGHT}
        className="select-none cursor-crosshair"
        onMouseDown={brushHandlers.onMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={brushHandlers.onMouseUp}
        onMouseLeave={handleMouseLeave}
        aria-label="Intraday VaR chart"
        role="img"
      >
        {/* Left Y-axis grid lines (VaR) */}
        {varGridLines.map((v) => {
          const y = toY(v)
          return (
            <g key={`var-grid-${v}`}>
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

        {/* Right Y-axis labels (delta) */}
        {hasDelta && deltaGridLines.map((v) => {
          const y = toDeltaY(v)
          return (
            <text
              key={`delta-label-${v}`}
              x={containerWidth - PADDING.right + 6}
              y={y + 3}
              textAnchor="start"
              fill="#6ee7b7"
              fontSize={10}
            >
              {formatCompactNum(v)}
            </text>
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

        {/* Gap regions overlay */}
        {gapRegions.map((gap, i) => (
          <rect
            key={i}
            x={gap.x1}
            y={PADDING.top}
            width={gap.x2 - gap.x1}
            height={plotHeight}
            fill="#1e293b"
            opacity={0.6}
          />
        ))}

        {/* Delta line (right axis, emerald) */}
        {hasDelta && deltaPolyline && (
          <polyline
            data-series="delta"
            points={deltaPolyline}
            fill="none"
            stroke="#10b981"
            strokeWidth={1.5}
            strokeLinejoin="round"
            strokeDasharray="5 3"
            opacity={0.8}
          />
        )}

        {/* VaR line (indigo) */}
        {varPolyline && (
          <polyline
            data-series="var"
            points={varPolyline}
          />
        )}

        {/* Invisible wider VaR path for hit testing */}
        {varPolyline && (
          <path
            data-series="var"
            d={`M ${varPlotPoints.map((p) => `${p.x} ${p.y}`).join(' L ')}`}
            fill="none"
            stroke="#6366f1"
            strokeWidth={2.5}
            strokeLinejoin="round"
          />
        )}

        {/* Trade annotation markers (triangles pointing up) */}
        {annotationPositions.map(({ x, annotation }) => (
          <polygon
            key={annotation.tradeId}
            data-testid="trade-marker"
            points={`${x},${PADDING.top + plotHeight - MARKER_SIZE} ${x - MARKER_SIZE / 2},${PADDING.top + plotHeight} ${x + MARKER_SIZE / 2},${PADDING.top + plotHeight}`}
            fill={annotation.side === 'BUY' ? '#22c55e' : '#f43f5e'}
            opacity={0.9}
          />
        ))}

        {/* Brush selection overlay */}
        {brush.active && (
          <rect
            x={brushLeft}
            y={PADDING.top}
            width={brushWidth}
            height={plotHeight}
            fill="#6366f1"
            opacity={0.15}
          />
        )}

        {/* Hover crosshair */}
        {hoveredIndex !== null && varPlotPoints[hoveredIndex] && (
          <line
            data-testid="intraday-var-crosshair"
            x1={varPlotPoints[hoveredIndex].x}
            y1={PADDING.top}
            x2={varPlotPoints[hoveredIndex].x}
            y2={PADDING.top + plotHeight}
            stroke="#94a3b8"
            strokeDasharray="4 2"
            strokeWidth={1}
          />
        )}
      </svg>

      {/* Tooltip */}
      {hoveredIndex !== null && varPoints[hoveredIndex] && (
        <div className="relative">
          <div
            ref={tooltipRef}
            data-testid="intraday-var-tooltip"
            className="absolute top-0 bg-slate-900 text-white text-xs rounded shadow-lg px-3 py-2 pointer-events-none whitespace-nowrap border border-slate-600 z-10"
            style={{ left: `${tooltipLeft}px` }}
          >
            <div className="font-medium mb-1">{formatTimeOnly(varPoints[hoveredIndex].timestamp)}</div>
            <div className="space-y-0.5">
              <div className="text-indigo-400">
                VaR: {formatCurrency(varPoints[hoveredIndex].varValue)}
              </div>
              <div className="text-amber-400">
                ES: {formatCurrency(varPoints[hoveredIndex].expectedShortfall)}
              </div>
              {varPoints[hoveredIndex].delta !== null && (
                <div className="text-emerald-400">
                  Delta: {varPoints[hoveredIndex].delta?.toFixed(4)}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
