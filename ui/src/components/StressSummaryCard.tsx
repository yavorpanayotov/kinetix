import { useMemo } from 'react'
import { Zap, ExternalLink } from 'lucide-react'
import type { CannedStressResultDto, StressTestResultDto } from '../types'
import { formatCurrency } from '../utils/format'
import { Card, Button } from './ui'
import { ScenarioBadge } from './ScenarioBadge'

interface StressSummaryCardProps {
  results: StressTestResultDto[]
  loading: boolean
  onRun: () => void
  onViewDetails: () => void
  /**
   * Active demo scenario context. When non-null, stress P&L impacts are computed
   * against the scenario-tilted book — annotated per plan §1.2.
   */
  activeScenario?: string | null
  /**
   * Optional canned-scenario tile result seeded server-side by the demo
   * orchestrator's `StressScenarioSeedJob`. When `results` is empty but a
   * canned result is present, the summary still has *something* to report —
   * so we surface that scenario as a single fallback row instead of the
   * "no stress test results yet" empty state, keeping this card consistent
   * with the inline `StressScenarioTile` rendered beside it
   * (trader-review P0 #10).
   */
  cannedResult?: CannedStressResultDto | null
}

const MAX_ROWS = 3

export function StressSummaryCard({
  results,
  loading,
  onRun,
  onViewDetails,
  activeScenario = null,
  cannedResult = null,
}: StressSummaryCardProps) {
  const sorted = useMemo(
    () => [...results].sort((a, b) => Math.abs(Number(b.pnlImpact)) - Math.abs(Number(a.pnlImpact))),
    [results],
  )

  // When the user hasn't kicked off a batch run yet but the orchestrator has
  // seeded a canned scenario, fall back to a single-row view derived from the
  // canned result. Base/stressed VaR are unknown for the canned payload — we
  // surface them as "—" rather than fabricating zeros.
  const cannedAsRow = useMemo<StressTestResultDto | null>(() => {
    if (!cannedResult) return null
    return {
      scenarioName: cannedResult.scenario,
      baseVar: '',
      stressedVar: '',
      pnlImpact: cannedResult.deltaPv,
      assetClassImpacts: [],
      positionImpacts: [],
      limitBreaches: [],
      calculatedAt: cannedResult.asOf,
    }
  }, [cannedResult])

  const effective: StressTestResultDto[] = sorted.length > 0 ? sorted : cannedAsRow ? [cannedAsRow] : []
  const visible = effective.slice(0, MAX_ROWS)
  const hasMore = effective.length > MAX_ROWS

  return (
    <Card
      data-testid="stress-summary-card"
      header={
        <div className="flex items-center justify-between w-full">
          <span className="flex items-center gap-1.5">
            <Zap className="h-4 w-4" />
            Stress Test Summary
          </span>
          <Button
            data-testid="stress-summary-run-btn"
            variant="danger"
            size="sm"
            icon={<Zap className="h-3.5 w-3.5" />}
            onClick={onRun}
            loading={loading}
          >
            {loading ? 'Running...' : 'Run Stress Tests'}
          </Button>
        </div>
      }
    >
      {effective.length === 0 && !loading && (
        <p className="text-sm text-slate-500">No stress test results yet. Run a stress test to see the summary.</p>
      )}

      {visible.length > 0 && !loading && (
        <>
          <table data-testid="stress-summary-table" className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-slate-600">
                <th className="py-2">Scenario</th>
                <th className="py-2 text-right">Base VaR</th>
                <th className="py-2 text-right">Stressed VaR</th>
                <th className="py-2 text-right">
                  <span className="inline-flex items-center gap-1.5 justify-end">
                    P&L Impact
                    <ScenarioBadge scenario={activeScenario} regime={null} />
                  </span>
                </th>
              </tr>
            </thead>
            <tbody>
              {visible.map((r) => {
                const pnlValue = Number(r.pnlImpact)
                const isLoss = pnlValue < 0
                return (
                  <tr
                    key={r.scenarioName}
                    data-testid="stress-summary-row"
                    className="border-b hover:bg-slate-50 transition-colors"
                  >
                    <td className="py-1.5 font-medium">{r.scenarioName.replace(/_/g, ' ')}</td>
                    <td className="py-1.5 text-right">{r.baseVar ? formatCurrency(r.baseVar) : '—'}</td>
                    <td className="py-1.5 text-right font-medium">{r.stressedVar ? formatCurrency(r.stressedVar) : '—'}</td>
                    <td
                      data-testid="stress-summary-pnl-impact"
                      className={`py-1.5 text-right font-medium ${isLoss ? 'text-red-600' : ''}`}
                    >
                      {formatCurrency(r.pnlImpact)}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>

          <div className="mt-3 flex items-center justify-between">
            {hasMore && (
              <button
                onClick={onViewDetails}
                className="text-xs text-slate-500 hover:text-slate-700"
              >
                View all {effective.length} scenarios
              </button>
            )}
            <div className={hasMore ? '' : 'ml-auto'}>
              <button
                data-testid="stress-summary-view-details"
                onClick={onViewDetails}
                className="inline-flex items-center gap-1 text-xs text-indigo-600 hover:text-indigo-800"
              >
                View Details
                <ExternalLink className="h-3 w-3" />
              </button>
            </div>
          </div>
        </>
      )}
    </Card>
  )
}
