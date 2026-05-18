import { Download, Star } from 'lucide-react'
import { Button } from './ui'
import { RunSnapshotCard } from './RunSnapshotCard'
import { RunDiffSummary } from './RunDiffSummary'
import { ComponentDiffChart } from './ComponentDiffChart'
import { PositionDiffTable } from './PositionDiffTable'
import { VaRAttributionPanel } from './VaRAttributionPanel'
import { InputChangesPanel } from './InputChangesPanel'
import { exportToCsv } from '../utils/exportCsv'
import type { RunComparisonResponseDto, VaRAttributionDto } from '../types'

interface GenericRunComparisonPanelProps {
  comparison: RunComparisonResponseDto
  attribution: VaRAttributionDto | null
  attributionLoading: boolean
  onRequestAttribution: () => void
  threshold: number
  onThresholdChange: (threshold: number) => void
  showAttribution?: boolean
}

export function GenericRunComparisonPanel({
  comparison,
  attribution,
  attributionLoading,
  onRequestAttribution,
  threshold,
  onThresholdChange,
  showAttribution = true,
}: GenericRunComparisonPanelProps) {
  const handleExportCsv = () => {
    const headers = ['Metric', 'Base', 'Target', 'Change']
    const diff = comparison.bookDiff
    const rows: string[][] = [
      ['VaR', String(comparison.baseRun.varValue ?? ''), String(comparison.targetRun.varValue ?? ''), String(diff.varChange)],
      ['ES', String(comparison.baseRun.es ?? ''), String(comparison.targetRun.es ?? ''), String(diff.esChange)],
      ['PV', String(comparison.baseRun.pv ?? ''), String(comparison.targetRun.pv ?? ''), String(diff.pvChange)],
      ['Delta', String(comparison.baseRun.delta ?? ''), String(comparison.targetRun.delta ?? ''), String(diff.deltaChange)],
      ['Gamma', String(comparison.baseRun.gamma ?? ''), String(comparison.targetRun.gamma ?? ''), String(diff.gammaChange)],
      ['Vega', String(comparison.baseRun.vega ?? ''), String(comparison.targetRun.vega ?? ''), String(diff.vegaChange)],
      ['Theta', String(comparison.baseRun.theta ?? ''), String(comparison.targetRun.theta ?? ''), String(diff.thetaChange)],
      ['Rho', String(comparison.baseRun.rho ?? ''), String(comparison.targetRun.rho ?? ''), String(diff.rhoChange)],
    ]
    exportToCsv(`comparison-${comparison.comparisonId}.csv`, headers, rows)
  }

  const baseIsEod = comparison.baseRun.label.includes('Official EOD')
  const targetIsEod = comparison.targetRun.label.includes('Official EOD')

  return (
    <div data-testid="run-comparison-panel" className="space-y-4">
      {(baseIsEod || targetIsEod) && (
        <div
          data-testid="eod-auto-select-notice"
          className="flex items-center gap-2 text-xs text-amber-700 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-md px-3 py-2"
        >
          <Star className="h-3.5 w-3.5 flex-shrink-0" />
          <span>
            {baseIsEod && targetIsEod
              ? 'Official EOD auto-selected for both dates'
              : baseIsEod
                ? `Official EOD auto-selected for base date`
                : `Official EOD auto-selected for target date`}
          </span>
        </div>
      )}
      <div className="flex justify-end">
        <Button
          data-testid="export-comparison-csv"
          variant="secondary"
          onClick={handleExportCsv}
          aria-label="Export comparison to CSV"
        >
          <Download className="h-4 w-4 mr-1" aria-hidden="true" />
          Export CSV
        </Button>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <RunSnapshotCard snapshot={comparison.baseRun} title="Base" />
        <RunSnapshotCard snapshot={comparison.targetRun} title="Target" />
      </div>

      <InputChangesPanel
        inputChanges={comparison.inputChanges ?? null}
        parameterDiffs={comparison.parameterDiffs}
        bookId={comparison.bookId}
      />

      <RunDiffSummary diff={comparison.bookDiff} />

      {comparison.componentDiffs.length > 0 && (
        <ComponentDiffChart diffs={comparison.componentDiffs} />
      )}

      <PositionDiffTable
        diffs={comparison.positionDiffs}
        threshold={threshold}
        onThresholdChange={onThresholdChange}
      />

      {comparison.parameterDiffs.length > 0 && (
        <div data-testid="parameter-diffs" className="text-sm">
          <h3 className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-2">
            Parameter Differences
          </h3>
          <div className="space-y-1">
            {comparison.parameterDiffs.map((p) => (
              <div
                key={p.paramName}
                className="flex gap-2 text-slate-600 dark:text-slate-300"
              >
                <span className="font-medium">{p.paramName}:</span>
                <span className="text-slate-500 dark:text-slate-400">{p.baseValue ?? '—'}</span>
                <span aria-hidden="true">→</span>
                <span>{p.targetValue ?? '—'}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {showAttribution && (
        <VaRAttributionPanel
          attribution={attribution}
          loading={attributionLoading}
          onRequest={onRequestAttribution}
        />
      )}
    </div>
  )
}
