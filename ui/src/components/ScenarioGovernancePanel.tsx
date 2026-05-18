import { Button } from './ui/Button'
import { Spinner, ErrorCard } from './ui'
import type { StressScenarioDto } from '../types'

const STATUS_COLORS: Record<string, string> = {
  DRAFT: 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-300',
  PENDING_APPROVAL: 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-300',
  APPROVED: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300',
  RETIRED: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300',
}

export interface ScenarioGovernancePanelProps {
  scenarios: StressScenarioDto[]
  onSubmit: (id: string) => void
  onApprove: (id: string) => void
  onRetire: (id: string) => void
  loading: boolean
  error?: string | null
}

export function ScenarioGovernancePanel({
  scenarios,
  onSubmit,
  onApprove,
  onRetire,
  loading,
  error,
}: ScenarioGovernancePanelProps) {
  return (
    <div className="mt-4 border-t pt-4" data-testid="governance-panel">
      <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-3">Scenario Governance</h3>

      {loading && (
        <div className="flex items-center gap-2 text-sm text-slate-500">
          <Spinner size="sm" />
          Loading scenarios...
        </div>
      )}

      {error && (
        <div className="mb-2">
          <ErrorCard message={error} data-testid="governance-error" />
        </div>
      )}

      {!loading && !error && scenarios.length === 0 && (
        <div className="text-sm text-slate-500 dark:text-slate-400" data-testid="governance-empty">
          No scenarios found. Use &quot;+ Custom Scenario&quot; to create one.
        </div>
      )}

      <div className="space-y-2">
      {scenarios.map((scenario) => (
        <div
          key={scenario.id}
          className="flex items-center justify-between p-3 rounded border border-slate-200 dark:border-slate-700"
        >
          <div className="flex items-center gap-3">
            <span className="text-sm font-medium text-slate-900 dark:text-slate-100">
              {scenario.name}
            </span>
            <span
              className={`text-xs font-medium px-2 py-0.5 rounded-full ${STATUS_COLORS[scenario.status] ?? ''}`}
            >
              {scenario.status}
            </span>
            {scenario.approvedBy && (
              <span className="text-xs text-slate-500">
                by {scenario.approvedBy}
              </span>
            )}
          </div>

          <div className="flex items-center gap-2">
            {scenario.status === 'DRAFT' && (
              <Button
                variant="primary"
                size="sm"
                onClick={() => onSubmit(scenario.id)}
              >
                Submit for Approval
              </Button>
            )}
            {scenario.status === 'PENDING_APPROVAL' && (
              <Button
                variant="success"
                size="sm"
                onClick={() => onApprove(scenario.id)}
              >
                Approve
              </Button>
            )}
            {scenario.status === 'APPROVED' && (
              <Button
                variant="danger"
                size="sm"
                onClick={() => onRetire(scenario.id)}
              >
                Retire
              </Button>
            )}
          </div>
        </div>
      ))}
      </div>
    </div>
  )
}
