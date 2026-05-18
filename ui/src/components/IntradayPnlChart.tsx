import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { IntradayPnlSnapshotDto, TradeAnnotationDto } from '../types'
import { formatNum, formatTimeOnly, pnlColorClass } from '../utils/format'
import { formatCompactCurrency } from '../utils/formatCompactCurrency'
import { clampTooltipLeft } from '../utils/clampTooltipLeft'

interface IntradayPnlChartProps {
  snapshots: IntradayPnlSnapshotDto[]
  tradeAnnotations?: TradeAnnotationDto[]
}

const PADDING = { top: 28, right: 16, bottom: 28, left: 56 }
const CHART_HEIGHT = 200
const DEFAULT_WIDTH = 600
const MARKER_SIZE = 6

function buildPath(points: Array<{ x: number; y: number } | null>): string {
  let path = ''
  for (const pt of points) {
    if (pt === null) continue
    path += path ? ` L ${pt.x} ${pt.y}` : `M ${pt.x} ${pt.y}`
  }
  return path
}

export function IntradayPnlChart({ snapshots, tradeAnnotations = [] }: IntradayPnlChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const tooltipRef = useRef<HTMLDivElement>(null)
  const [containerWidth, setContainerWidth] = useState(DEFAULT_WIDTH)
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null)
  const [tooltipLeft, setTooltipLeft] = useState(0)

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
  }, [snapshots.length])

  const { min, max } = useMemo(() => {
    if (snapshots.length === 0) return { min: 0, max: 1 }
    const values = snapshots.flatMap((s) => [
      Number(s.totalPnl),
      Number(s.realisedPnl),
      Number(s.unrealisedPnl),
    ])
    const minVal = Math.min(...values)
    const maxVal = Math.max(...values)
    const range = maxVal - minVal
    const pad = range * 0.1 || Math.abs(maxVal) * 0.1 || 1
    return { min: minVal - pad, max: maxVal + pad }
  }, [snapshots])

  const toX = useCallback(
    (index: number): number => {
      if (snapshots.length <= 1) return PADDING.left + plotWidth / 2
      return PADDING.left + (index / (snapshots.length - 1)) * plotWidth
    },
    [snapshots.length, plotWidth],
  )

  const toY = useCallback(
    (value: number): number => {
      const range = max - min || 1
      return PADDING.top + (1 - (value - min) / range) * plotHeight
    },
    [min, max, plotHeight],
  )

  const totalPoints = useMemo(
    () => snapshots.map((s, i) => ({ x: toX(i), y: toY(Number(s.totalPnl)) })),
    [snapshots, toX, toY],
  )

  const realisedPoints = useMemo(
    () => snapshots.map((s, i) => ({ x: toX(i), y: toY(Number(s.realisedPnl)) })),
    [snapshots, toX, toY],
  )

  const unrealisedPoints = useMemo(
    () => snapshots.map((s, i) => ({ x: toX(i), y: toY(Number(s.unrealisedPnl)) })),
    [snapshots, toX, toY],
  )

  const totalPath = useMemo(() => buildPath(totalPoints), [totalPoints])
  const realisedPath = useMemo(() => buildPath(realisedPoints), [realisedPoints])
  const unrealisedPath = useMemo(() => buildPath(unrealisedPoints), [unrealisedPoints])

  const zeroY = useMemo(() => toY(0), [toY])

  // Map each trade annotation's timestamp to an X coordinate by linear
  // interpolation between adjacent snapshot timestamps. Annotations whose
  // timestamps lie outside the snapshot window are skipped.
  const annotationPositions = useMemo(() => {
    if (snapshots.length < 2 || tradeAnnotations.length === 0) return []
    const snapshotMs = snapshots.map((s) => new Date(s.snapshotAt).getTime())
    const firstMs = snapshotMs[0]
    const lastMs = snapshotMs[snapshotMs.length - 1]
    return tradeAnnotations
      .map((ann) => {
        const t = new Date(ann.timestamp).getTime()
        if (t < firstMs || t > lastMs) return null
        // Find the segment [i, i+1] containing t.
        let i = 0
        for (let k = 0; k < snapshotMs.length - 1; k++) {
          if (snapshotMs[k] <= t && t <= snapshotMs[k + 1]) {
            i = k
            break
          }
        }
        const segStart = snapshotMs[i]
        const segEnd = snapshotMs[i + 1]
        const segSpan = segEnd - segStart
        const frac = segSpan > 0 ? (t - segStart) / segSpan : 0
        const fracIndex = i + frac
        const x = PADDING.left + (fracIndex / (snapshots.length - 1)) * plotWidth
        return { x, annotation: ann }
      })
      .filter((a): a is { x: number; annotation: TradeAnnotationDto } => a !== null)
  }, [tradeAnnotations, snapshots, plotWidth])

  // Up to 5 evenly spaced x-axis time labels
  const xLabels = useMemo(() => {
    if (snapshots.length < 2) return []
    const count = Math.min(5, snapshots.length)
    return Array.from({ length: count }, (_, i) => {
      const index = Math.round((i / (count - 1)) * (snapshots.length - 1))
      return { x: toX(index), text: formatTimeOnly(snapshots[index].snapshotAt) }
    })
  }, [snapshots, toX])

  // Y-axis grid lines: evenly spaced between min and max
  const gridLines = useMemo(() => {
    if (snapshots.length === 0) return []
    const step = (max - min) / 4
    return [1, 2, 3].map((i) => min + step * i)
  }, [min, max, snapshots.length])

  const handleMouseMove = useCallback(
    (e: React.MouseEvent<SVGSVGElement>) => {
      if (snapshots.length === 0) return
      const el = containerRef.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      const mouseX = e.clientX - rect.left
      let closest = 0
      let closestDist = Infinity
      for (let i = 0; i < snapshots.length; i++) {
        const dist = Math.abs(toX(i) - mouseX)
        if (dist < closestDist) {
          closestDist = dist
          closest = i
        }
      }
      setHoveredIndex(closest)
    },
    [snapshots.length, toX],
  )

  const handleMouseLeave = useCallback(() => setHoveredIndex(null), [])

  useLayoutEffect(() => {
    if (hoveredIndex === null || !tooltipRef.current) return
    const tooltipWidth = tooltipRef.current.offsetWidth
    const pointX = totalPoints[hoveredIndex]?.x ?? toX(hoveredIndex)
    setTooltipLeft(clampTooltipLeft(pointX, tooltipWidth, containerWidth))
  }, [hoveredIndex, totalPoints, containerWidth, toX])

  if (snapshots.length === 0) {
    return (
      <div
        data-testid="intraday-pnl-chart"
        className="rounded bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-4"
      >
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
          Intraday P&L
        </h3>
        <div
          data-testid="intraday-pnl-chart-empty"
          className="flex items-center justify-center text-sm text-slate-400"
          style={{ height: CHART_HEIGHT }}
        >
          No intraday data yet — snapshots will appear as prices update.
        </div>
      </div>
    )
  }

  if (snapshots.length === 1) {
    return (
      <div
        data-testid="intraday-pnl-chart"
        className="rounded bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-4"
      >
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
          Intraday P&L
        </h3>
        <div
          data-testid="intraday-pnl-chart-single"
          className="flex items-center justify-center text-sm text-slate-400"
          style={{ height: CHART_HEIGHT }}
        >
          Only one snapshot — need at least two to draw a trend.
        </div>
      </div>
    )
  }

  const latest = snapshots[snapshots.length - 1]

  return (
    <div
      ref={containerRef}
      data-testid="intraday-pnl-chart"
      className="relative rounded bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-4"
    >
      <div className="flex items-center justify-between mb-1">
        <div className="flex items-center gap-3">
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Intraday P&L</h3>
          <div className="flex items-center gap-2 text-xs text-slate-500">
            <span className="flex items-center gap-1">
              <span className="inline-block w-3 h-0.5 bg-indigo-500 rounded" />
              Total
            </span>
            <span className="flex items-center gap-1">
              <span className="inline-block w-3 h-0.5 bg-green-500 rounded" />
              Realised
            </span>
            <span className="flex items-center gap-1">
              <span className="inline-block w-3 h-0.5 bg-amber-500 rounded" />
              Unrealised
            </span>
          </div>
        </div>
        <span
          data-testid="intraday-chart-latest-total"
          className={`text-sm font-mono font-semibold tabular-nums ${pnlColorClass(latest.totalPnl)}`}
        >
          {formatNum(latest.totalPnl)}
        </span>
      </div>

      <svg
        width="100%"
        height={CHART_HEIGHT}
        className="select-none"
        onMouseMove={handleMouseMove}
        onMouseLeave={handleMouseLeave}
        aria-label="Intraday P&L trend chart"
        role="img"
      >
        {/* Grid lines */}
        {gridLines.map((v) => {
          const y = toY(v)
          return (
            <g key={v}>
              <line
                x1={PADDING.left}
                y1={y}
                x2={containerWidth - PADDING.right}
                y2={y}
                stroke="#e2e8f0"
                strokeDasharray="4 2"
              />
              <text x={PADDING.left - 6} y={y + 3} textAnchor="end" fill="#94a3b8" fontSize={10}>
                {formatCompactCurrency(v)}
              </text>
            </g>
          )
        })}

        {/* Zero line */}
        {zeroY >= PADDING.top && zeroY <= PADDING.top + plotHeight && (
          <line
            x1={PADDING.left}
            y1={zeroY}
            x2={containerWidth - PADDING.right}
            y2={zeroY}
            stroke="#94a3b8"
            strokeWidth={1}
          />
        )}

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

        {/* Unrealised line */}
        {unrealisedPath && (
          <path
            data-series="unrealised"
            d={unrealisedPath}
            fill="none"
            stroke="#f59e0b"
            strokeWidth={1.5}
            strokeLinejoin="round"
          />
        )}

        {/* Realised line */}
        {realisedPath && (
          <path
            data-series="realised"
            d={realisedPath}
            fill="none"
            stroke="#22c55e"
            strokeWidth={1.5}
            strokeLinejoin="round"
          />
        )}

        {/* Total P&L line */}
        {totalPath && (
          <path
            data-series="total"
            d={totalPath}
            fill="none"
            stroke="#6366f1"
            strokeWidth={2.5}
            strokeLinejoin="round"
          />
        )}

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

        {/* Hover crosshair */}
        {hoveredIndex !== null && totalPoints[hoveredIndex] && (
          <line
            data-testid="intraday-crosshair"
            x1={totalPoints[hoveredIndex].x}
            y1={PADDING.top}
            x2={totalPoints[hoveredIndex].x}
            y2={PADDING.top + plotHeight}
            stroke="#94a3b8"
            strokeDasharray="4 2"
            strokeWidth={1}
          />
        )}
      </svg>

      {/* Tooltip */}
      {hoveredIndex !== null && snapshots[hoveredIndex] && (
        <div className="relative">
          <div
            ref={tooltipRef}
            data-testid="intraday-chart-tooltip"
            className="absolute top-0 bg-slate-800 text-white text-xs rounded shadow-lg px-3 py-2 pointer-events-none whitespace-nowrap border border-slate-600 z-10"
            style={{ left: `${tooltipLeft}px` }}
          >
            <div className="font-medium mb-1">{formatTimeOnly(snapshots[hoveredIndex].snapshotAt)}</div>
            <div className="space-y-0.5">
              <div className="flex gap-3">
                <span className="text-indigo-400">
                  Total: {formatNum(snapshots[hoveredIndex].totalPnl)}
                </span>
              </div>
              <div className="flex gap-3">
                <span className="text-green-400">
                  Realised: {formatNum(snapshots[hoveredIndex].realisedPnl)}
                </span>
                <span className="text-amber-400">
                  Unrealised: {formatNum(snapshots[hoveredIndex].unrealisedPnl)}
                </span>
              </div>
              <div className="text-slate-400 text-xs">
                trigger: {snapshots[hoveredIndex].trigger}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
