import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { FactorRiskDto } from '../types'
import { Card, EmptyState, ErrorCard } from './ui'
import { Spinner } from './ui/Spinner'

interface FactorAttributionHistoryChartProps {
  history: FactorRiskDto[]
  loading: boolean
  error: string | null
}

const FACTOR_COLORS: Record<string, string> = {
  EQUITY_BETA: '#3b82f6',
  RATES_DURATION: '#22c55e',
  CREDIT_SPREAD: '#f59e0b',
  FX_DELTA: '#a855f7',
  VOL_EXPOSURE: '#ef4444',
}

const FACTOR_LABELS: Record<string, string> = {
  EQUITY_BETA: 'Equity Beta',
  RATES_DURATION: 'Rates Duration',
  CREDIT_SPREAD: 'Credit Spread',
  FX_DELTA: 'FX Delta',
  VOL_EXPOSURE: 'Vol Exposure',
}

const DEFAULT_COLOR = '#9ca3af'
const PADDING = { top: 16, right: 16, bottom: 36, left: 60 }
const CHART_HEIGHT = 200
const DEFAULT_WIDTH = 600

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value)
}

function formatDate(isoString: string): string {
  return new Date(isoString).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
  })
}

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
  for (let v = start; v <= max + step * 0.01; v += step) {
    lines.push(v)
  }
  return lines.length >= 2 ? lines : [min, max]
}

export function FactorAttributionHistoryChart({
  history,
  loading,
  error,
}: FactorAttributionHistoryChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [containerWidth, setContainerWidth] = useState(DEFAULT_WIDTH)
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null)

  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el) return
    const observer = new ResizeObserver(() => {
      setContainerWidth(el.getBoundingClientRect().width || DEFAULT_WIDTH)
    })
    observer.observe(el)
    setContainerWidth(el.getBoundingClientRect().width || DEFAULT_WIDTH)
    return () => observer.disconnect()
  }, [])

  const factorTypes = useMemo(() => {
    const types = new Set<string>()
    for (const snapshot of history) {
      for (const f of snapshot.factors) {
        types.add(f.factorType)
      }
    }
    return Array.from(types).sort()
  }, [history])

  const plotWidth = containerWidth - PADDING.left - PADDING.right
  const plotHeight = CHART_HEIGHT - PADDING.top - PADDING.bottom

  const allValues = useMemo(
    () =>
      history.flatMap((snap) => snap.factors.map((f) => f.varContribution)),
    [history],
  )

  const minY = 0
  const maxY = allValues.length > 0 ? Math.max(...allValues) * 1.1 : 1

  const xScale = (index: number) =>
    history.length <= 1
      ? PADDING.left + plotWidth / 2
      : PADDING.left + (index / (history.length - 1)) * plotWidth

  const yScale = (value: number) =>
    PADDING.top + plotHeight - ((value - minY) / (maxY - minY)) * plotHeight

  const gridLines = computeNiceGridLines(minY, maxY, 4)

  const seriesPoints = useMemo(
    () =>
      factorTypes.map((factorType) => ({
        factorType,
        points: history.map((snap, i) => {
          const factor = snap.factors.find((f) => f.factorType === factorType)
          return {
            x: xScale(i),
            y: yScale(factor?.varContribution ?? 0),
            value: factor?.varContribution ?? 0,
          }
        }),
      })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [history, factorTypes, containerWidth],
  )

  if (loading) {
    return (
      <Card className="p-6 flex items-center justify-center">
        <div data-testid="factor-history-loading">
          <Spinner />
        </div>
      </Card>
    )
  }

  if (error) {
    return (
      <Card className="p-4">
        <ErrorCard message={error} data-testid="factor-history-error" />
      </Card>
    )
  }

  if (history.length === 0) {
    return (
      <Card className="p-4">
        <div data-testid="factor-history-empty">
          <EmptyState title="No factor attribution history available yet." />
        </div>
      </Card>
    )
  }

  const xTickIndices = history.length <= 6
    ? history.map((_, i) => i)
    : [
        0,
        Math.floor(history.length / 4),
        Math.floor(history.length / 2),
        Math.floor((history.length * 3) / 4),
        history.length - 1,
      ]

  return (
    <Card className="p-4 flex flex-col gap-3">
      <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">
        Factor VaR Attribution History
      </h3>

      <div ref={containerRef} className="w-full">
        <svg
          data-testid="factor-history-chart"
          width={containerWidth}
          height={CHART_HEIGHT}
          aria-label="Factor VaR attribution over time"
        >
          {/* Grid lines */}
          {gridLines.map((v) => {
            const y = yScale(v)
            return (
              <g key={v}>
                <line
                  x1={PADDING.left}
                  y1={y}
                  x2={PADDING.left + plotWidth}
                  y2={y}
                  stroke="currentColor"
                  strokeOpacity={0.1}
                  strokeWidth={1}
                />
                <text
                  x={PADDING.left - 4}
                  y={y}
                  textAnchor="end"
                  dominantBaseline="middle"
                  fontSize={10}
                  className="fill-gray-400 dark:fill-gray-500"
                >
                  {formatCurrency(v)}
                </text>
              </g>
            )
          })}

          {/* X-axis ticks */}
          {xTickIndices.map((i) => (
            <text
              key={i}
              x={xScale(i)}
              y={PADDING.top + plotHeight + 16}
              textAnchor="middle"
              fontSize={10}
              className="fill-gray-400 dark:fill-gray-500"
            >
              {formatDate(history[i].calculatedAt)}
            </text>
          ))}

          {/* Series polylines */}
          {seriesPoints.map(({ factorType, points }) => (
            <polyline
              key={factorType}
              data-testid={`factor-history-line-${factorType}`}
              points={points.map((p) => `${p.x},${p.y}`).join(' ')}
              fill="none"
              stroke={FACTOR_COLORS[factorType] ?? DEFAULT_COLOR}
              strokeWidth={2}
              strokeLinejoin="round"
            />
          ))}

          {/* Hover overlay */}
          {history.map((_, i) => (
            <rect
              key={i}
              x={xScale(i) - (plotWidth / Math.max(history.length - 1, 1)) / 2}
              y={PADDING.top}
              width={plotWidth / Math.max(history.length - 1, 1)}
              height={plotHeight}
              fill="transparent"
              onMouseEnter={() => setHoveredIndex(i)}
              onMouseLeave={() => setHoveredIndex(null)}
            />
          ))}

          {/* Hover dots */}
          {hoveredIndex !== null &&
            seriesPoints.map(({ factorType, points }) => (
              <circle
                key={factorType}
                cx={points[hoveredIndex].x}
                cy={points[hoveredIndex].y}
                r={4}
                fill={FACTOR_COLORS[factorType] ?? DEFAULT_COLOR}
                stroke="white"
                strokeWidth={1.5}
              />
            ))}

          {/* Hover vertical line */}
          {hoveredIndex !== null && (
            <line
              x1={xScale(hoveredIndex)}
              y1={PADDING.top}
              x2={xScale(hoveredIndex)}
              y2={PADDING.top + plotHeight}
              stroke="currentColor"
              strokeOpacity={0.2}
              strokeWidth={1}
              strokeDasharray="4 2"
            />
          )}
        </svg>

        {/* Tooltip */}
        {hoveredIndex !== null && (
          <div
            data-testid="factor-history-tooltip"
            className="absolute z-10 rounded shadow-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-surface-800 p-2 text-xs pointer-events-none"
            style={{ left: xScale(hoveredIndex) + PADDING.left, top: PADDING.top + 8 }}
          >
            <div className="font-medium text-gray-700 dark:text-gray-200 mb-1">
              {formatDate(history[hoveredIndex].calculatedAt)}
            </div>
            {seriesPoints.map(({ factorType, points }) => (
              <div key={factorType} className="flex items-center gap-1.5">
                <span
                  className="inline-block w-2 h-2 rounded-sm"
                  style={{ backgroundColor: FACTOR_COLORS[factorType] ?? DEFAULT_COLOR }}
                />
                <span className="text-gray-500 dark:text-gray-400">
                  {FACTOR_LABELS[factorType] ?? factorType}:
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100">
                  {formatCurrency(points[hoveredIndex].value)}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Legend */}
      <div className="flex flex-wrap gap-3">
        {factorTypes.map((factorType) => (
          <div key={factorType} className="flex items-center gap-1.5">
            <span
              className="inline-block w-3 h-0.5 flex-shrink-0"
              style={{ backgroundColor: FACTOR_COLORS[factorType] ?? DEFAULT_COLOR }}
            />
            <span className="text-xs text-gray-500 dark:text-gray-400">
              {FACTOR_LABELS[factorType] ?? factorType}
            </span>
          </div>
        ))}
      </div>
    </Card>
  )
}
