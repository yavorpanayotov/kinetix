import type { FactorRiskDto, FactorContributionDto, MarketRegime } from '../types'
import { Card } from './ui'
import { Spinner } from './ui/Spinner'
import { ScenarioBadge } from './ScenarioBadge'

interface FactorDecompositionPanelProps {
  result: FactorRiskDto | null
  loading: boolean
  error: string | null
  /** Active scenario context for per-number annotation (plan §1.2). */
  activeScenario?: string | null
  /** Market regime — factor VaR carries a regime-adj badge when non-NORMAL. */
  marketRegime?: MarketRegime | null
}

const FACTOR_COLORS: Record<string, string> = {
  EQUITY_BETA: '#3b82f6',
  RATES_DURATION: '#22c55e',
  CREDIT_SPREAD: '#f59e0b',
  FX_DELTA: '#a855f7',
  VOL_EXPOSURE: '#ef4444',
}

const FACTOR_LABELS: Record<string, string> = {
  EQUITY_BETA: 'Equity Beta',
  RATES_DURATION: 'Rates Duration',
  CREDIT_SPREAD: 'Credit Spread',
  FX_DELTA: 'FX Delta',
  VOL_EXPOSURE: 'Vol Exposure',
}

const DEFAULT_COLOR = '#9ca3af'

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value)
}

function factorLabel(factorType: string): string {
  return FACTOR_LABELS[factorType] ?? factorType
}

function factorColor(factorType: string): string {
  return FACTOR_COLORS[factorType] ?? DEFAULT_COLOR
}

function StackedBar({ factors }: { factors: FactorContributionDto[] }) {
  const sorted = [...factors].sort((a, b) => b.pctOfTotal - a.pctOfTotal)

  return (
    <div
      data-testid="factor-stacked-bar"
      aria-label="Factor VaR breakdown"
      className="flex h-6 w-full overflow-hidden rounded"
    >
      {sorted.map((f) => (
        <div
          key={f.factorType}
          data-testid={`factor-bar-${f.factorType}`}
          title={`${factorLabel(f.factorType)}: ${f.pctOfTotal.toFixed(1)}%`}
          style={{
            width: `${f.pctOfTotal}%`,
            backgroundColor: factorColor(f.factorType),
          }}
        />
      ))}
    </div>
  )
}

export function FactorDecompositionPanel({
  result,
  loading,
  error,
  activeScenario = null,
  marketRegime = null,
}: FactorDecompositionPanelProps) {
  if (loading) {
    return (
      <Card className="p-6 flex items-center justify-center">
        <div data-testid="factor-risk-loading">
          <Spinner />
        </div>
      </Card>
    )
  }

  if (error) {
    return (
      <Card className="p-4">
        <p
          data-testid="factor-risk-error"
          className="text-sm text-red-600 dark:text-red-400"
        >
          {error}
        </p>
      </Card>
    )
  }

  if (!result) {
    return (
      <Card className="p-6 flex items-center justify-center">
        <p
          data-testid="factor-risk-empty"
          className="text-sm text-gray-500 dark:text-gray-400"
        >
          No factor decomposition available yet. Factor risk is computed
          automatically after each scheduled VaR run.
        </p>
      </Card>
    )
  }

  const systematicPct =
    result.totalVar > 0
      ? ((result.systematicVar / result.totalVar) * 100).toFixed(1)
      : '0.0'
  const idiosyncraticPct =
    result.totalVar > 0
      ? ((result.idiosyncraticVar / result.totalVar) * 100).toFixed(1)
      : '0.0'

  return (
    <Card className="p-4 flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">
          Factor Risk Decomposition
        </h3>
        {result.concentrationWarning && (
          <span
            data-testid="concentration-warning"
            className="rounded px-2 py-0.5 text-xs font-semibold text-yellow-700 bg-yellow-100 dark:text-yellow-300 dark:bg-yellow-900/30"
          >
            Concentration Warning
          </span>
        )}
      </div>

      {/* Summary metrics */}
      <div className="grid grid-cols-4 gap-3">
        <div className="flex flex-col">
          <span className="text-xs text-gray-500 dark:text-gray-400">
            Total VaR
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span
              data-testid="factor-total-var"
              className="text-lg font-bold text-gray-900 dark:text-gray-100"
            >
              {formatCurrency(result.totalVar)}
            </span>
            <ScenarioBadge scenario={activeScenario} regime={marketRegime} />
          </span>
        </div>
        <div className="flex flex-col">
          <span className="text-xs text-gray-500 dark:text-gray-400">
            Systematic ({systematicPct}%)
          </span>
          <span
            data-testid="factor-systematic-var"
            className="text-lg font-bold text-blue-600 dark:text-blue-400"
          >
            {formatCurrency(result.systematicVar)}
          </span>
        </div>
        <div className="flex flex-col">
          <span className="text-xs text-gray-500 dark:text-gray-400">
            Idiosyncratic ({idiosyncraticPct}%)
          </span>
          <span
            data-testid="factor-idiosyncratic-var"
            className="text-lg font-bold text-gray-700 dark:text-gray-300"
          >
            {formatCurrency(result.idiosyncraticVar)}
          </span>
        </div>
        <div className="flex flex-col">
          <span className="text-xs text-gray-500 dark:text-gray-400">
            R&#178;
          </span>
          <span
            data-testid="factor-r-squared"
            className="text-lg font-bold text-gray-900 dark:text-gray-100"
          >
            {(result.rSquared * 100).toFixed(1)}%
          </span>
        </div>
      </div>

      {/* Stacked bar */}
      {result.factors.length > 0 && (
        <StackedBar factors={result.factors} />
      )}

      {/* Legend */}
      {result.factors.length > 0 && (
        <div className="flex flex-wrap gap-3">
          {[...result.factors]
            .sort((a, b) => b.pctOfTotal - a.pctOfTotal)
            .map((f) => (
              <div key={f.factorType} className="flex items-center gap-1.5">
                <span
                  className="inline-block w-2.5 h-2.5 rounded-sm flex-shrink-0"
                  style={{ backgroundColor: factorColor(f.factorType) }}
                />
                <span className="text-xs text-gray-500 dark:text-gray-400">
                  {factorLabel(f.factorType)}
                </span>
              </div>
            ))}
        </div>
      )}

      {/* Factor table */}
      {result.factors.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-gray-200 dark:border-gray-700">
                <th className="pb-1 text-left font-medium text-gray-500 dark:text-gray-400">
                  Factor
                </th>
                <th className="pb-1 text-right font-medium text-gray-500 dark:text-gray-400">
                  VaR Contribution
                </th>
                <th className="pb-1 text-right font-medium text-gray-500 dark:text-gray-400">
                  % of Total
                </th>
                <th className="pb-1 text-right font-medium text-gray-500 dark:text-gray-400">
                  Loading
                </th>
                <th className="pb-1 text-left font-medium text-gray-500 dark:text-gray-400">
                  Method
                </th>
              </tr>
            </thead>
            <tbody>
              {[...result.factors]
                .sort((a, b) => b.varContribution - a.varContribution)
                .map((f) => (
                  <tr
                    key={f.factorType}
                    data-testid={`factor-row-${f.factorType}`}
                    className="border-b border-gray-100 dark:border-gray-800 last:border-0"
                  >
                    <td className="py-1.5 pr-2">
                      <div className="flex items-center gap-1.5">
                        <span
                          className="inline-block w-2 h-2 rounded-sm flex-shrink-0"
                          style={{ backgroundColor: factorColor(f.factorType) }}
                        />
                        <span className="font-medium text-gray-900 dark:text-gray-100">
                          {factorLabel(f.factorType)}
                        </span>
                      </div>
                    </td>
                    <td className="py-1.5 text-right text-gray-900 dark:text-gray-100">
                      {formatCurrency(f.varContribution)}
                    </td>
                    <td className="py-1.5 text-right text-gray-900 dark:text-gray-100">
                      {f.pctOfTotal.toFixed(1)}%
                    </td>
                    <td className="py-1.5 text-right text-gray-900 dark:text-gray-100">
                      {f.loading.toFixed(4)}
                    </td>
                    <td className="py-1.5 pl-2 text-gray-500 dark:text-gray-400">
                      {f.loadingMethod}
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="text-right text-xs text-gray-400 dark:text-gray-500">
        Calculated: {new Date(result.calculatedAt).toLocaleString()}
      </p>
    </Card>
  )
}
