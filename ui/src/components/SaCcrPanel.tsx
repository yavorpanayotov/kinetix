import type { SaCcrResultDto } from '../types'
import { formatCurrency } from '../utils/format'
import { EmptyState, ErrorCard, Spinner } from './ui'

interface SaCcrPanelProps {
  result: SaCcrResultDto | null
  loading: boolean
  error: string | null
}

export function SaCcrPanel({ result, loading, error }: SaCcrPanelProps) {
  if (loading) {
    return (
      <div data-testid="sa-ccr-loading" className="flex items-center gap-2 text-sm text-slate-500">
        <Spinner size="sm" />
        Loading SA-CCR...
      </div>
    )
  }
  if (error) return <ErrorCard message={error} data-testid="sa-ccr-error" />
  if (!result) {
    return (
      <div data-testid="sa-ccr-empty">
        <EmptyState title="No SA-CCR data available." />
      </div>
    )
  }

  return (
    <div data-testid="sa-ccr-panel" className="bg-white dark:bg-surface-800 border border-slate-200 dark:border-surface-700 rounded-lg p-4">
      <div className="flex items-center justify-between mb-3">
        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Regulatory Capital (SA-CCR, BCBS 279)
          </h3>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
            Deterministic regulatory EAD. Distinct from internal Monte Carlo PFE above.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div data-testid="sa-ccr-ead">
          <p className="text-xs text-slate-500 dark:text-slate-400">EAD (α={result.alpha})</p>
          <p className="font-mono tabular-nums text-lg font-semibold text-slate-900 dark:text-slate-100">
            {formatCurrency(result.ead)}
          </p>
        </div>
        <div data-testid="sa-ccr-rc">
          <p className="text-xs text-slate-500 dark:text-slate-400">Replacement Cost</p>
          <p className="font-mono tabular-nums text-sm text-slate-700 dark:text-slate-300">
            {formatCurrency(result.replacementCost)}
          </p>
        </div>
        <div data-testid="sa-ccr-pfe">
          <p className="text-xs text-slate-500 dark:text-slate-400">SA-CCR PFE Add-on</p>
          <p className="font-mono tabular-nums text-sm text-slate-700 dark:text-slate-300">
            {formatCurrency(result.pfeAddon)}
          </p>
        </div>
        <div data-testid="sa-ccr-multiplier">
          <p className="text-xs text-slate-500 dark:text-slate-400">Multiplier</p>
          <p className="font-mono tabular-nums text-sm text-slate-700 dark:text-slate-300">
            {result.multiplier.toFixed(4)}
          </p>
        </div>
      </div>
    </div>
  )
}
