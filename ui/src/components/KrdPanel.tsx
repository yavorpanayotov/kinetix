import { useState } from 'react'
import type { KrdBucketDto, InstrumentKrdResultDto, MarketRegime } from '../types'
import { formatCurrency } from '../utils/format'
import { ScenarioBadge } from './ScenarioBadge'
import { EmptyState, ErrorCard, Spinner } from './ui'

interface KrdPanelProps {
  aggregated: KrdBucketDto[]
  instruments: InstrumentKrdResultDto[]
  loading: boolean
  error: string | null
  /** Active scenario context — annotates the Total DV01 summary (plan §1.2). */
  activeScenario?: string | null
  /** Market regime — DV01 inputs reflect the regime-adjusted rate moves. */
  marketRegime?: MarketRegime | null
}

const BAR_HEIGHT = 24
const MAX_BAR_WIDTH = 200
const LABEL_WIDTH = 40
const VALUE_WIDTH = 90

function DV01Bar({ dv01, maxAbsDv01 }: { dv01: number; maxAbsDv01: number }) {
  const barWidth = maxAbsDv01 > 0 ? Math.abs(dv01) / maxAbsDv01 * MAX_BAR_WIDTH : 0
  const isPositive = dv01 >= 0
  return (
    <svg width={MAX_BAR_WIDTH} height={BAR_HEIGHT} aria-label={`DV01 ${dv01 >= 0 ? 'positive' : 'negative'}`}>
      <title>{`DV01: ${formatCurrency(dv01)}`}</title>
      {isPositive ? (
        <rect x={0} y={4} width={barWidth} height={BAR_HEIGHT - 8} rx={2} className="fill-blue-500 dark:fill-blue-400" />
      ) : (
        <rect x={MAX_BAR_WIDTH - barWidth} y={4} width={barWidth} height={BAR_HEIGHT - 8} rx={2} className="fill-amber-500 dark:fill-amber-400" />
      )}
    </svg>
  )
}

export function KrdPanel({ aggregated, instruments, loading, error, activeScenario = null, marketRegime = null }: KrdPanelProps) {
  const [expanded, setExpanded] = useState(false)

  if (loading) {
    return (
      <div data-testid="krd-loading" className="flex items-center gap-2 text-sm text-slate-500">
        <Spinner size="sm" />
        Loading key rate durations...
      </div>
    )
  }
  if (error) return <ErrorCard message={error} data-testid="krd-error" />
  if (aggregated.length === 0) {
    return (
      <div data-testid="krd-empty">
        <EmptyState title="No fixed-income positions in this book." />
      </div>
    )
  }

  const totalDv01 = aggregated.reduce((sum, b) => sum + Number(b.dv01), 0)
  const maxAbsDv01 = Math.max(...aggregated.map((b) => Math.abs(Number(b.dv01))), 1)

  return (
    <div data-testid="krd-panel" className="bg-white dark:bg-surface-800 border border-slate-200 dark:border-surface-700 rounded-lg p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">Key Rate Durations</h3>
        <span className="inline-flex items-center gap-1.5">
          <span className="font-mono tabular-nums text-sm text-slate-600 dark:text-slate-300" data-testid="krd-total-dv01">
            Total DV01: {formatCurrency(totalDv01)}
          </span>
          <ScenarioBadge scenario={activeScenario} regime={marketRegime} />
        </span>
      </div>

      <div className="space-y-1" data-testid="krd-buckets">
        {aggregated.map((bucket) => (
          <div key={bucket.tenorLabel} className="flex items-center gap-2" data-testid={`krd-bucket-${bucket.tenorLabel}`}>
            <span className="font-mono text-xs text-slate-500 dark:text-slate-400" style={{ width: LABEL_WIDTH }}>{bucket.tenorLabel}</span>
            <DV01Bar dv01={Number(bucket.dv01)} maxAbsDv01={maxAbsDv01} />
            <span className="font-mono tabular-nums text-xs text-slate-600 dark:text-slate-300 text-right" style={{ width: VALUE_WIDTH }}>
              {formatCurrency(Number(bucket.dv01))}
            </span>
          </div>
        ))}
      </div>

      {instruments.length > 0 && (
        <button
          data-testid="krd-expand-toggle"
          onClick={() => setExpanded(!expanded)}
          className="mt-3 text-xs text-primary-600 dark:text-primary-400 hover:underline"
        >
          {expanded ? 'Hide instrument detail' : `Show ${instruments.length} instrument(s)`}
        </button>
      )}

      {expanded && (
        <div className="mt-2 space-y-2 border-t border-slate-200 dark:border-surface-700 pt-2" data-testid="krd-instrument-detail">
          {instruments.map((inst) => (
            <div key={inst.instrumentId} className="pl-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-slate-600 dark:text-slate-300">{inst.instrumentId}</span>
                <span className="font-mono tabular-nums text-xs text-slate-500">{formatCurrency(Number(inst.totalDv01))}</span>
              </div>
              <div className="space-y-0.5 mt-1">
                {inst.krdBuckets.map((b) => (
                  <div key={b.tenorLabel} className="flex items-center gap-2 pl-2">
                    <span className="font-mono text-xs text-slate-400" style={{ width: LABEL_WIDTH }}>{b.tenorLabel}</span>
                    <span className="font-mono tabular-nums text-xs text-slate-500 text-right" style={{ width: VALUE_WIDTH }}>
                      {formatCurrency(Number(b.dv01))}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
