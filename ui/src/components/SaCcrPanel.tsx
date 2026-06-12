import type { SaCcrResultDto, SaCcrSummaryDto } from '../types'
import { formatCurrency } from '../utils/format'
import { EmptyState, ErrorCard, Spinner } from './ui'

interface SaCcrPanelProps {
  result: SaCcrSummaryDto | null
  loading: boolean
  error: string | null
}

const EM_DASH = '—'

// Defensive currency render: a partially-populated upstream response may omit a
// numeric field. Intl would format `undefined`/NaN as "$NaN"; show an em-dash
// instead so a missing aggregate is never confused with a real value.
function money(value: number | undefined | null): string {
  return typeof value === 'number' && Number.isFinite(value) ? formatCurrency(value) : EM_DASH
}

function multiplier(value: number | undefined | null): string {
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(4) : EM_DASH
}

function NettingSetRow({ set }: { set: SaCcrResultDto }) {
  return (
    <div
      data-testid={`sa-ccr-netting-set-${set.nettingSetId}`}
      className="rounded-md border border-slate-200 dark:border-surface-700 p-3"
    >
      <p className="font-mono text-xs text-slate-500 dark:text-slate-400 mb-2 truncate" title={set.nettingSetId}>
        {set.nettingSetId}
      </p>
      <div className="grid grid-cols-4 gap-3">
        <div data-testid={`sa-ccr-ead-${set.nettingSetId}`}>
          <p className="text-xs text-slate-500 dark:text-slate-400">EAD (α={set.alpha ?? EM_DASH})</p>
          <p className="font-mono tabular-nums text-base font-semibold text-slate-900 dark:text-slate-100">
            {money(set.ead)}
          </p>
        </div>
        <div data-testid={`sa-ccr-rc-${set.nettingSetId}`}>
          <p className="text-xs text-slate-500 dark:text-slate-400">Replacement Cost</p>
          <p className="font-mono tabular-nums text-sm text-slate-700 dark:text-slate-300">
            {money(set.replacementCost)}
          </p>
        </div>
        <div data-testid={`sa-ccr-pfe-${set.nettingSetId}`}>
          <p className="text-xs text-slate-500 dark:text-slate-400">SA-CCR PFE Add-on</p>
          <p className="font-mono tabular-nums text-sm text-slate-700 dark:text-slate-300">
            {money(set.pfeAddon)}
          </p>
        </div>
        <div data-testid={`sa-ccr-multiplier-${set.nettingSetId}`}>
          <p className="text-xs text-slate-500 dark:text-slate-400">Multiplier</p>
          <p className="font-mono tabular-nums text-sm text-slate-700 dark:text-slate-300">
            {multiplier(set.multiplier)}
          </p>
        </div>
      </div>
    </div>
  )
}

export function SaCcrPanel({ result, loading, error }: SaCcrPanelProps) {
  if (loading) {
    return (
      <div data-testid="sa-ccr-loading" className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
        <Spinner size="sm" />
        Loading SA-CCR...
      </div>
    )
  }
  if (error) return <ErrorCard message={error} data-testid="sa-ccr-error" />
  if (!result || result.nettingSets.length === 0) {
    return (
      <div data-testid="sa-ccr-empty">
        <EmptyState title="No SA-CCR data available." />
      </div>
    )
  }

  return (
    <div data-testid="sa-ccr-panel" className="bg-white dark:bg-surface-800 border border-slate-200 dark:border-surface-700 rounded-lg p-4">
      <div className="flex items-start justify-between mb-3 gap-4">
        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Regulatory Capital (SA-CCR, BCBS 279)
          </h3>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
            Deterministic regulatory EAD, per netting set. Distinct from internal Monte Carlo PFE above.
          </p>
        </div>
        <div data-testid="sa-ccr-total-ead" className="text-right shrink-0">
          <p className="text-xs text-slate-500 dark:text-slate-400">Total EAD</p>
          <p className="font-mono tabular-nums text-lg font-semibold text-slate-900 dark:text-slate-100">
            {money(result.totalEad)}
          </p>
        </div>
      </div>

      <div className="space-y-2">
        {result.nettingSets.map((set) => (
          <NettingSetRow key={set.nettingSetId} set={set} />
        ))}
      </div>
    </div>
  )
}
