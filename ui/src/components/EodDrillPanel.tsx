import { useEffect, useRef } from 'react'
import { X } from 'lucide-react'
import type { EodTimelineEntryDto } from '../types'
import { formatCurrency } from '../utils/format'
import { usePositionRisk } from '../hooks/usePositionRisk'
import { PositionRiskTable } from './PositionRiskTable'
import { RunComparisonContainer } from './RunComparisonContainer'
import { Spinner } from './ui'

interface EodDrillPanelProps {
  bookId: string
  entry: EodTimelineEntryDto
  compareEntry?: EodTimelineEntryDto | null
  onClose: () => void
}

export function EodDrillPanel({
  bookId,
  entry,
  compareEntry,
  onClose,
}: EodDrillPanelProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  const { positionRisk, loading: riskLoading, error: riskError } = usePositionRisk(
    bookId,
    entry.valuationDate,
  )

  // Focus panel on open
  useEffect(() => {
    closeButtonRef.current?.focus()
  }, [])

  // Close on Escape
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  // Derive comparison job IDs if a compare entry is provided
  const compareJobIds =
    compareEntry && entry.jobId && compareEntry.jobId
      ? { baseJobId: entry.jobId, targetJobId: compareEntry.jobId }
      : null

  return (
    <>
      {/* Backdrop */}
      <div
        data-testid="eod-drill-backdrop"
        className="fixed inset-0 bg-black/20 dark:bg-black/40 z-30"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Panel */}
      <div
        ref={panelRef}
        data-testid="eod-drill-panel"
        role="dialog"
        aria-modal="true"
        aria-label={`EOD details for ${entry.valuationDate}`}
        className="fixed right-0 top-0 bottom-0 z-40 w-[480px] bg-white dark:bg-surface-800 border-l border-slate-200 dark:border-surface-700 shadow-xl flex flex-col overflow-y-auto"
      >
        {/* Header */}
        <div className="flex items-start justify-between px-5 py-4 border-b border-slate-200 dark:border-surface-700 flex-shrink-0">
          <div>
            <h2 className="text-sm font-semibold text-slate-800 dark:text-slate-100">
              EOD — {entry.valuationDate}
            </h2>
            <div className="mt-1 flex items-center gap-3 text-xs text-slate-500 dark:text-slate-400">
              <span>
                VaR:{' '}
                <span className="font-mono text-indigo-600 dark:text-indigo-400">
                  {entry.varValue !== null ? formatCurrency(entry.varValue) : '\u2014'}
                </span>
              </span>
              <span>
                ES:{' '}
                <span className="font-mono text-amber-600 dark:text-amber-400">
                  {entry.expectedShortfall !== null ? formatCurrency(entry.expectedShortfall) : '\u2014'}
                </span>
              </span>
            </div>
            {entry.promotedBy && (
              <p className="mt-1 text-xs text-slate-400 dark:text-slate-500">
                Promoted by{' '}
                <span className="font-medium text-slate-600 dark:text-slate-300">{entry.promotedBy}</span>
                {entry.promotedAt && (
                  <> at {new Date(entry.promotedAt).toLocaleString('en-GB')}</>
                )}
              </p>
            )}
          </div>
          <button
            ref={closeButtonRef}
            data-testid="eod-drill-close"
            onClick={onClose}
            aria-label="Close drill-down panel"
            className="ml-3 p-1.5 rounded-md text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-surface-700 transition-colors flex-shrink-0"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Summary metrics */}
        <div className="px-5 py-3 border-b border-slate-100 dark:border-surface-700 flex-shrink-0">
          <div className="grid grid-cols-3 gap-3 text-xs">
            <div>
              <p className="text-slate-500 dark:text-slate-400">PV</p>
              <p className="font-mono font-medium text-slate-800 dark:text-slate-200 mt-0.5">
                {entry.pvValue !== null ? formatCurrency(entry.pvValue) : '\u2014'}
              </p>
            </div>
            <div>
              <p className="text-slate-500 dark:text-slate-400">VaR DoD</p>
              <p className="font-mono font-medium mt-0.5">
                {entry.varChange !== null ? (
                  <span className={entry.varChange > 0 ? 'text-red-600 dark:text-red-400' : entry.varChange < 0 ? 'text-green-600 dark:text-green-400' : 'text-slate-500'}>
                    {entry.varChange > 0 ? '+' : ''}{formatCurrency(entry.varChange)}
                  </span>
                ) : (
                  <span className="text-slate-400">\u2014</span>
                )}
              </p>
            </div>
            <div>
              <p className="text-slate-500 dark:text-slate-400">Calc Type</p>
              <p className="font-medium text-slate-800 dark:text-slate-200 mt-0.5">
                {entry.calculationType ?? '\u2014'}
              </p>
            </div>
            {entry.delta !== null && (
              <div>
                <p className="text-slate-500 dark:text-slate-400">Delta</p>
                <p className="font-mono font-medium text-slate-800 dark:text-slate-200 mt-0.5">{entry.delta.toFixed(4)}</p>
              </div>
            )}
            {entry.gamma !== null && (
              <div>
                <p className="text-slate-500 dark:text-slate-400">Gamma</p>
                <p className="font-mono font-medium text-slate-800 dark:text-slate-200 mt-0.5">{entry.gamma.toFixed(4)}</p>
              </div>
            )}
            {entry.vega !== null && (
              <div>
                <p className="text-slate-500 dark:text-slate-400">Vega</p>
                <p className="font-mono font-medium text-slate-800 dark:text-slate-200 mt-0.5">{entry.vega.toFixed(2)}</p>
              </div>
            )}
          </div>
        </div>

        {/* Position risk */}
        <div className="flex-1 px-3 py-3 min-h-0">
          {riskLoading ? (
            <div className="flex items-center justify-center py-8">
              <Spinner size="sm" />
              <span className="ml-2 text-sm text-slate-500 dark:text-slate-400">Loading positions...</span>
            </div>
          ) : (
            <PositionRiskTable data={positionRisk} loading={riskLoading} error={riskError} />
          )}
        </div>

        {/* Comparison section — only when compare entry is present */}
        {compareJobIds && (
          <div className="px-3 py-3 border-t border-slate-200 dark:border-surface-700 flex-shrink-0">
            <h3 className="text-xs font-semibold text-slate-700 dark:text-slate-300 mb-3">
              Run Comparison: {entry.valuationDate} vs {compareEntry!.valuationDate}
            </h3>
            <RunComparisonContainer
              bookId={bookId}
              initialJobIds={compareJobIds}
            />
          </div>
        )}
      </div>
    </>
  )
}
