import { Zap } from 'lucide-react'
import type { StressTestResultDto } from '../types'
import { formatCurrency } from '../utils/format'
import { Card, Button, Select, Spinner, ErrorCard } from './ui'

interface StressTestPanelProps {
  scenarios: string[]
  result: StressTestResultDto | null
  loading: boolean
  error: string | null
  selectedScenario: string
  onScenarioChange: (scenario: string) => void
  onRun: () => void
}

const ASSET_CLASS_COLORS: Record<string, string> = {
  EQUITY: 'bg-blue-500',
  FIXED_INCOME: 'bg-green-500',
  COMMODITY: 'bg-amber-500',
  FX: 'bg-purple-500',
  DERIVATIVE: 'bg-red-500',
}

export function StressTestPanel({
  scenarios,
  result,
  loading,
  error,
  selectedScenario,
  onScenarioChange,
  onRun,
}: StressTestPanelProps) {
  return (
    <Card
      data-testid="stress-test-panel"
      header={<span className="flex items-center gap-1.5"><Zap className="h-4 w-4" />Stress Testing</span>}
    >
      <div className="flex items-center gap-3 mb-4">
        <Select
          data-testid="scenario-dropdown"
          value={selectedScenario}
          onChange={(e) => onScenarioChange(e.target.value)}
        >
          {scenarios.map((s) => (
            <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>
          ))}
        </Select>
        <Button
          data-testid="stress-run-btn"
          variant="danger"
          size="md"
          icon={<Zap className="h-3.5 w-3.5" />}
          onClick={onRun}
          loading={loading}
        >
          {loading ? 'Running...' : 'Run Stress Test'}
        </Button>
      </div>

      {loading && (
        <div data-testid="stress-loading" className="flex items-center gap-2 text-slate-500 text-sm">
          <Spinner size="sm" />
          Running stress test...
        </div>
      )}

      {error && <ErrorCard message={error} data-testid="stress-error" />}

      {result && !loading && (
        <div data-testid="stress-results">
          <table data-testid="stress-results-table" className="w-full text-sm mb-4">
            <thead>
              <tr className="border-b text-left text-slate-600">
                <th className="py-2">Metric</th>
                <th className="py-2 text-right">Value</th>
              </tr>
            </thead>
            <tbody>
              <tr className="border-b hover:bg-slate-50 transition-colors">
                <td className="py-1.5">Scenario</td>
                <td className="py-1.5 text-right font-medium">{result.scenarioName}</td>
              </tr>
              <tr className="border-b hover:bg-slate-50 transition-colors">
                <td className="py-1.5">Base VaR</td>
                <td className="py-1.5 text-right">{formatCurrency(result.baseVar)}</td>
              </tr>
              <tr className="border-b hover:bg-slate-50 transition-colors">
                <td className="py-1.5">Stressed VaR</td>
                <td className="py-1.5 text-right text-red-600 font-medium">{formatCurrency(result.stressedVar)}</td>
              </tr>
              <tr className="border-b hover:bg-slate-50 transition-colors">
                <td className="py-1.5">P&L Impact</td>
                <td className="py-1.5 text-right text-red-600 font-medium">{formatCurrency(result.pnlImpact)}</td>
              </tr>
            </tbody>
          </table>

          <h3 className="text-sm font-semibold text-slate-700 mb-2">Impact by Asset Class</h3>
          <div data-testid="stress-impact-chart" className="space-y-2">
            {result.assetClassImpacts.map((impact) => {
              const baseExp = Number(impact.baseExposure)
              const stressedExp = Number(impact.stressedExposure)
              const maxExp = Math.max(baseExp, stressedExp, 1)
              return (
                <div key={impact.assetClass} className="flex items-center gap-2 text-xs">
                  <span className="w-24 text-slate-600">{impact.assetClass}</span>
                  <div className="flex-1 flex gap-1">
                    <div
                      className="h-4 bg-slate-300 rounded"
                      style={{ width: `${(baseExp / maxExp) * 100}%` }}
                      title={`Base: ${formatCurrency(baseExp)}`}
                    />
                    <div
                      className={`h-4 rounded ${ASSET_CLASS_COLORS[impact.assetClass] || 'bg-gray-400'}`}
                      style={{ width: `${(stressedExp / maxExp) * 100}%` }}
                      title={`Stressed: ${formatCurrency(stressedExp)}`}
                    />
                  </div>
                  <span className="w-28 text-right text-red-600">{formatCurrency(impact.pnlImpact)}</span>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </Card>
  )
}
