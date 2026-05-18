import { TrendingUp, ExternalLink } from 'lucide-react'
import type { PnlAttributionDto, SodBaselineStatusDto } from '../types'
import { Card, Button } from './ui'
import { ScenarioBadge } from './ScenarioBadge'
import { formatSignedNum, pnlColorClass } from '../utils/format'

interface PnlSummaryCardProps {
  sodStatus: SodBaselineStatusDto | null
  pnlData: PnlAttributionDto | null
  computing: boolean
  onComputePnl: () => void
  onViewFullAttribution?: () => void
  /** When a demo scenario is active, attribution P&L numbers are scenario-driven. */
  activeScenario?: string | null
}

interface Factor {
  key: string
  label: string
  value: string
}

export function PnlSummaryCard({ sodStatus, pnlData, computing, onComputePnl, onViewFullAttribution, activeScenario = null }: PnlSummaryCardProps) {
  const hasBaseline = sodStatus?.exists === true
  const showNoBaseline = !hasBaseline && !pnlData
  const showComputePrompt = hasBaseline && !pnlData
  const showData = pnlData != null

  const factors: Factor[] = pnlData
    ? [
        { key: 'delta', label: 'Delta', value: pnlData.deltaPnl },
        { key: 'gamma', label: 'Gamma', value: pnlData.gammaPnl },
        { key: 'vega', label: 'Vega', value: pnlData.vegaPnl },
        { key: 'theta', label: 'Theta', value: pnlData.thetaPnl },
        { key: 'rho', label: 'Rho', value: pnlData.rhoPnl },
        { key: 'unexplained', label: 'Unexplained', value: pnlData.unexplainedPnl },
      ]
    : []

  return (
    <Card
      data-testid="pnl-summary-card"
      header={
        <span className="flex items-center gap-1.5">
          <TrendingUp className="h-4 w-4" />
          P&L Attribution
        </span>
      }
    >
      {showNoBaseline && (
        <p data-testid="pnl-no-baseline" className="text-sm text-slate-500">
          No SOD baseline — set one on the P&L tab to enable attribution
        </p>
      )}

      {showComputePrompt && (
        <div data-testid="pnl-compute-prompt" className="text-center py-4">
          <p className="text-sm text-slate-600 mb-3">SOD baseline active</p>
          <Button
            variant="primary"
            size="sm"
            onClick={onComputePnl}
            loading={computing}
          >
            Compute P&L
          </Button>
        </div>
      )}

      {showData && (
        <div data-testid="pnl-summary-data">
          <div className="mb-3">
            <p className="text-xs text-slate-500 uppercase tracking-wide">Total P&L</p>
            <div className="flex items-center gap-2 flex-wrap">
              <p
                data-testid="pnl-total-value"
                className={`text-2xl font-bold tabular-nums ${pnlColorClass(pnlData.totalPnl)}`}
              >
                {formatSignedNum(pnlData.totalPnl)}
              </p>
              <ScenarioBadge scenario={activeScenario} regime={null} />
            </div>
          </div>

          <div className="grid grid-cols-3 gap-x-4 gap-y-2 text-sm">
            {factors.map((f) => (
              <div key={f.key}>
                <span className="text-slate-500">{f.label}</span>
                <span
                  data-testid={`pnl-factor-${f.key}`}
                  className={`ml-1 font-mono tabular-nums ${pnlColorClass(f.value)}`}
                >
                  {formatSignedNum(f.value)}
                </span>
              </div>
            ))}
          </div>

          {onViewFullAttribution && (
            <div className="mt-3 flex justify-end">
              <button
                data-testid="pnl-view-full-attribution"
                onClick={onViewFullAttribution}
                className="inline-flex items-center gap-1 text-xs text-indigo-600 hover:text-indigo-800"
              >
                View Full Attribution
                <ExternalLink className="h-3 w-3" />
              </button>
            </div>
          )}
        </div>
      )}
    </Card>
  )
}
