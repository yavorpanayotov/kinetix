import { Zap } from 'lucide-react'
import type { CannedStressResultDto } from '../types'
import { formatCurrency } from '../utils/format'
import { Card } from './ui'

interface StressScenarioTileProps {
  /** Latest canned stress-scenario tile result, or `null` when nothing has been seeded yet. */
  result: CannedStressResultDto | null
  loading: boolean
}

const SCENARIO_LABELS: Record<string, string> = {
  '+100BPS_PARALLEL': '+100bps parallel rates shock',
}

function scenarioLabel(scenario: string): string {
  return SCENARIO_LABELS[scenario] ?? scenario.replace(/_/g, ' ')
}

function formatAsOf(asOf: string): string {
  try {
    const d = new Date(asOf)
    if (Number.isNaN(d.getTime())) return asOf
    return d.toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      timeZoneName: 'short',
    })
  } catch {
    return asOf
  }
}

/**
 * Surface a canned stress-scenario result on the Risk overview (issue kx-wxy).
 * Renders a small tile with the scenario name, delta-PV impact, and the as-of
 * timestamp of the most recent server-side run.
 *
 * The demo orchestrator's `StressScenarioSeedJob` populates this tile by
 * POSTing the canned scenario through risk-orchestrator on bootstrap and at
 * SOD; the UI reads the cached result via
 * `GET /api/v1/risk/stress/{bookId}/canned`.
 */
export function StressScenarioTile({ result, loading }: StressScenarioTileProps) {
  return (
    <Card
      data-testid="stress-scenario-tile"
      header={
        <div className="flex items-center gap-1.5">
          <Zap className="h-4 w-4" />
          Stress Scenario
        </div>
      }
    >
      {loading && !result && (
        <p className="text-sm text-slate-500">Loading canned stress scenario…</p>
      )}

      {!loading && !result && (
        <p className="text-sm text-slate-500">
          No canned stress scenario has been seeded for this book yet.
        </p>
      )}

      {result && (
        <div className="flex flex-col gap-2">
          <div>
            <div data-testid="stress-scenario-name" className="text-sm font-semibold text-slate-700 dark:text-slate-200">
              {result.scenario}
            </div>
            <div className="text-xs text-slate-500 dark:text-slate-400">
              {scenarioLabel(result.scenario)}
            </div>
          </div>

          <div className="flex items-baseline justify-between gap-2">
            <span className="text-xs uppercase tracking-wide text-slate-500">
              Δ PV
            </span>
            <span
              data-testid="stress-scenario-delta-pv"
              className={`text-xl font-bold ${
                Number(result.deltaPv) < 0 ? 'text-red-600' : 'text-slate-700 dark:text-slate-200'
              }`}
              title={result.deltaPv}
            >
              {formatCurrency(result.deltaPv)}
            </span>
          </div>

          <div
            data-testid="stress-scenario-as-of"
            className="text-xs text-slate-400 dark:text-slate-500"
          >
            As of {formatAsOf(result.asOf)}
          </div>
        </div>
      )}
    </Card>
  )
}
