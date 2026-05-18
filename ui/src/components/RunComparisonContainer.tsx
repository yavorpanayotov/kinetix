import { useEffect, useRef } from 'react'
import { ErrorCard, Spinner } from './ui'
import { useRunComparison } from '../hooks/useRunComparison'
import { DailyVarSelector } from './DailyVarSelector'
import { ModelComparisonSelector } from './ModelComparisonSelector'
import { BacktestComparisonView } from './BacktestComparisonView'
import { GenericRunComparisonPanel } from './GenericRunComparisonPanel'
import type { ComparisonMode } from '../types'

interface RunComparisonContainerProps {
  bookId: string | null
  initialJobIds?: { baseJobId: string; targetJobId: string } | null
}

const MODES: { key: ComparisonMode; label: string }[] = [
  { key: 'DAILY_VAR', label: 'Daily VaR' },
  { key: 'MODEL', label: 'Model' },
  { key: 'BACKTEST', label: 'Backtest' },
]

export function RunComparisonContainer({ bookId, initialJobIds }: RunComparisonContainerProps) {
  const {
    comparison,
    attribution,
    loading,
    attributionLoading,
    error,
    threshold,
    mode,
    setMode,
    setThreshold,
    loadDayOverDay,
    compareJobs,
    compareModels,
    loadAttribution,
    reset,
  } = useRunComparison()

  const processedJobIdsRef = useRef<string | null>(null)

  useEffect(() => {
    if (!initialJobIds || !bookId) return
    const key = `${initialJobIds.baseJobId}:${initialJobIds.targetJobId}`
    if (processedJobIdsRef.current === key) return
    processedJobIdsRef.current = key
    compareJobs(bookId, initialJobIds.baseJobId, initialJobIds.targetJobId)
  }, [initialJobIds, bookId, compareJobs])

  if (!bookId) {
    return (
      <p className="text-sm text-slate-500 dark:text-slate-400">
        Select a book to compare runs.
      </p>
    )
  }

  const handleModeChange = (m: ComparisonMode) => {
    setMode(m)
    reset()
  }

  return (
    <div data-testid="run-comparison-container" className="space-y-4">
      {/* Mode selector pills */}
      <div
        className="flex gap-1 bg-slate-100 dark:bg-surface-900 rounded-lg p-1 w-fit"
        role="tablist"
        aria-label="Comparison mode"
      >
        {MODES.map((m) => (
          <button
            key={m.key}
            data-testid={`mode-${m.key.toLowerCase().replace('_', '-')}`}
            role="tab"
            aria-selected={mode === m.key}
            onClick={() => handleModeChange(m.key)}
            className={`px-3 py-1.5 text-sm font-medium rounded-md transition-colors ${
              mode === m.key
                ? 'bg-white dark:bg-surface-700 text-slate-800 dark:text-slate-200 shadow-sm'
                : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'
            }`}
          >
            {m.label}
          </button>
        ))}
      </div>

      {/* Mode-specific selector */}
      {mode === 'DAILY_VAR' && (
        <DailyVarSelector
          loading={loading}
          onCompare={(targetDate, baseDate) => loadDayOverDay(bookId, targetDate, baseDate)}
        />
      )}
      {mode === 'MODEL' && (
        <ModelComparisonSelector
          loading={loading}
          onCompare={(request) => compareModels(bookId, request)}
        />
      )}
      {mode === 'BACKTEST' && (
        <BacktestComparisonView comparison={null} loading={loading} />
      )}

      {/* Error */}
      {error && <ErrorCard message={error} data-testid="comparison-error" />}

      {/* Loading */}
      {loading && (
        <div
          className="flex items-center justify-center py-8"
          aria-live="polite"
          aria-busy="true"
        >
          <Spinner size="sm" />
          <span className="ml-2 text-sm text-slate-500 dark:text-slate-400">
            Loading comparison...
          </span>
        </div>
      )}

      {/* Results */}
      {comparison && !loading && (
        <GenericRunComparisonPanel
          comparison={comparison}
          attribution={attribution}
          attributionLoading={attributionLoading}
          onRequestAttribution={() => loadAttribution(bookId)}
          threshold={threshold}
          onThresholdChange={setThreshold}
          showAttribution={mode === 'DAILY_VAR'}
        />
      )}
    </div>
  )
}
