import { Card, Spinner } from './ui'
import type { BacktestComparisonDto } from '../types'

interface BacktestComparisonViewProps {
  comparison: BacktestComparisonDto | null
  loading: boolean
}

function TrafficLightBadge({ zone }: { zone: string }) {
  const colors: Record<string, string> = {
    GREEN: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
    YELLOW: 'bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400',
    RED: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  }
  const colorClass = colors[zone] ?? 'bg-slate-100 dark:bg-surface-700 text-slate-500 dark:text-slate-400'

  return (
    <span className={`inline-block px-2 py-0.5 text-xs font-medium rounded ${colorClass}`}>
      {zone}
    </span>
  )
}

export function BacktestComparisonView({ comparison, loading }: BacktestComparisonViewProps) {
  if (!comparison) {
    return (
      <Card data-testid="backtest-comparison-view">
        {loading ? (
          <div className="flex items-center justify-center gap-2 text-sm text-slate-500 dark:text-slate-400 py-4">
            <Spinner size="sm" />
            Loading comparison...
          </div>
        ) : (
          <p className="text-sm text-slate-500 dark:text-slate-400 text-center py-4">
            Select two backtest results to compare
          </p>
        )}
      </Card>
    )
  }

  const metrics = [
    {
      label: 'Calculation Type',
      base: comparison.baseCalculationType,
      target: comparison.targetCalculationType,
    },
    {
      label: 'Confidence Level',
      base: comparison.baseConfidenceLevel,
      target: comparison.targetConfidenceLevel,
    },
    {
      label: 'Total Days',
      base: String(comparison.baseTotalDays),
      target: String(comparison.targetTotalDays),
    },
    {
      label: 'Violations',
      base: String(comparison.baseViolationCount),
      target: String(comparison.targetViolationCount),
    },
    {
      label: 'Violation Rate',
      base: comparison.baseViolationRate,
      target: comparison.targetViolationRate,
    },
    {
      label: 'Kupiec p-value',
      base: comparison.baseKupiecPValue,
      target: comparison.targetKupiecPValue,
    },
    {
      label: 'Christoffersen p-value',
      base: comparison.baseChristoffersenPValue,
      target: comparison.targetChristoffersenPValue,
    },
  ]

  return (
    <Card data-testid="backtest-comparison-view">
      <div className="space-y-3">
        <h3 className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
          Backtest Comparison
        </h3>

        <div className="flex items-center gap-3 flex-wrap mb-2">
          <span className="text-sm text-slate-600 dark:text-slate-300">Traffic Light:</span>
          <TrafficLightBadge zone={comparison.baseTrafficLightZone} />
          <span className="text-slate-400 dark:text-slate-500" aria-hidden="true">→</span>
          <TrafficLightBadge zone={comparison.targetTrafficLightZone} />
          {comparison.trafficLightChanged && (
            <span
              data-testid="traffic-light-changed"
              className="text-xs text-amber-600 dark:text-amber-400 font-medium"
              aria-label="Traffic light zone has changed"
            >
              Changed
            </span>
          )}
        </div>

        <table className="w-full text-sm">
          <thead>
            <tr className="text-xs text-slate-500 dark:text-slate-400">
              <th className="text-left py-1 font-medium">Metric</th>
              <th className="text-right py-1 font-medium">Base</th>
              <th className="text-right py-1 font-medium">Target</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 dark:divide-surface-700">
            {metrics.map((m) => (
              <tr key={m.label}>
                <td className="py-1.5 text-slate-700 dark:text-slate-200 font-medium">{m.label}</td>
                <td className="py-1.5 text-right text-slate-700 dark:text-slate-200">{m.base}</td>
                <td className="py-1.5 text-right text-slate-700 dark:text-slate-200">{m.target}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}
