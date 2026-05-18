import { Layers } from 'lucide-react'

interface ScenarioIndicatorProps {
  scenario: string | null
  loading: boolean
}

const SCENARIO_LABELS: Record<string, string> = {
  'multi-asset': 'Multi-Asset',
  'equity-ls': 'Equity L/S',
  'options-book': 'Options Book',
  'stress': 'Stress',
  'regulatory': 'Regulatory',
}

function labelFor(scenario: string): string {
  return SCENARIO_LABELS[scenario] ?? scenario
}

export function ScenarioIndicator({ scenario, loading }: ScenarioIndicatorProps) {
  if (loading && scenario === null) {
    return (
      <div
        data-testid="scenario-indicator-loading"
        className="inline-flex items-center gap-1.5 px-2 py-1 text-[11px] tracking-wider rounded border border-slate-700/50 bg-slate-800/40 text-slate-500"
        aria-label="Active scenario loading"
      >
        <Layers className="h-3 w-3" />
        Scenario...
      </div>
    )
  }

  if (scenario === null) {
    return null
  }

  return (
    <div
      data-testid="scenario-indicator"
      data-scenario={scenario}
      className="inline-flex items-center gap-1.5 px-2 py-1 text-[11px] tracking-wider rounded border border-indigo-500/30 bg-indigo-500/10 text-indigo-200"
      aria-label={`Active scenario: ${labelFor(scenario)}`}
      title={`Active demo scenario: ${labelFor(scenario)}`}
    >
      <Layers className="h-3 w-3" />
      {labelFor(scenario)}
    </div>
  )
}
