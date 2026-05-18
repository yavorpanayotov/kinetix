import type { PnlAttributionDto } from '../types'
import { formatSignedNum, pnlColorClass } from '../utils/format'
import { PNL_FACTOR_COLORS } from '../utils/factorColors'

interface PnlWaterfallChartProps {
  data: PnlAttributionDto
}

interface FactorEntry {
  key: string
  label: string
  value: number
  color: string
}

const FACTOR_COLORS = PNL_FACTOR_COLORS

export function PnlWaterfallChart({ data }: PnlWaterfallChartProps) {
  const factors: FactorEntry[] = [
    { key: 'delta', label: 'Delta', value: Number(data.deltaPnl), color: FACTOR_COLORS.delta },
    { key: 'gamma', label: 'Gamma', value: Number(data.gammaPnl), color: FACTOR_COLORS.gamma },
    { key: 'vega', label: 'Vega', value: Number(data.vegaPnl), color: FACTOR_COLORS.vega },
    { key: 'theta', label: 'Theta', value: Number(data.thetaPnl), color: FACTOR_COLORS.theta },
    { key: 'rho', label: 'Rho', value: Number(data.rhoPnl), color: FACTOR_COLORS.rho },
    { key: 'unexplained', label: 'Unexplained', value: Number(data.unexplainedPnl), color: FACTOR_COLORS.unexplained },
    { key: 'total', label: 'Total', value: Number(data.totalPnl), color: FACTOR_COLORS.total },
  ]

  const absValues = factors.map((f) => Math.abs(f.value))
  const maxAbsValue = Math.max(...absValues, 1)

  return (
    <div data-testid="waterfall-chart" className="space-y-2 overflow-hidden">
      {factors.map((factor) => {
        const barWidthPercent = (Math.abs(factor.value) / maxAbsValue) * 50
        const isPositive = factor.value >= 0
        const valueStr = factor.value.toString()

        return (
          <div
            key={factor.key}
            data-testid={`waterfall-bar-${factor.key}`}
            className="flex items-center gap-3"
          >
            <span className="w-24 text-right text-sm font-medium text-slate-600 shrink-0">
              {factor.label}
            </span>

            <div className="flex-1 min-w-0 relative h-7">
              <div className="absolute inset-0 flex items-center">
                {/* Zero line in the center */}
                <div className="absolute left-1/2 top-0 bottom-0 w-px bg-slate-300" />

                {/* Bar */}
                {isPositive ? (
                  <div
                    className="absolute h-5 rounded-r"
                    style={{
                      left: '50%',
                      width: `${barWidthPercent}%`,
                      backgroundColor: factor.color,
                    }}
                  />
                ) : (
                  <div
                    className="absolute h-5 rounded-l"
                    style={{
                      right: '50%',
                      width: `${barWidthPercent}%`,
                      backgroundColor: factor.color,
                    }}
                  />
                )}
              </div>
            </div>

            <span
              data-testid={`waterfall-value-${factor.key}`}
              className={`w-36 text-right text-sm font-mono tabular-nums whitespace-nowrap shrink-0 ${pnlColorClass(valueStr)}`}
            >
              {formatSignedNum(factor.value)}
            </span>
          </div>
        )
      })}
    </div>
  )
}
