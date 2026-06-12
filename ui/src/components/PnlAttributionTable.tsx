import { Fragment, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import type { PnlAttributionDto, PositionPnlAttributionDto } from '../types'
import { EM_DASH, formatPctOfTotal, formatSignedNum, pnlColorClass } from '../utils/format'
import { PNL_FACTOR_COLORS } from '../utils/factorColors'

interface PnlAttributionTableProps {
  data: PnlAttributionDto
}

interface FactorDef {
  key: string
  label: string
  portfolioField: keyof PnlAttributionDto
  positionField: keyof PositionPnlAttributionDto
}

const FIRST_ORDER_FACTORS: FactorDef[] = [
  { key: 'delta', label: 'Delta', portfolioField: 'deltaPnl', positionField: 'deltaPnl' },
  { key: 'gamma', label: 'Gamma', portfolioField: 'gammaPnl', positionField: 'gammaPnl' },
  { key: 'vega', label: 'Vega', portfolioField: 'vegaPnl', positionField: 'vegaPnl' },
  { key: 'theta', label: 'Theta', portfolioField: 'thetaPnl', positionField: 'thetaPnl' },
  { key: 'rho', label: 'Rho', portfolioField: 'rhoPnl', positionField: 'rhoPnl' },
  { key: 'unexplained', label: 'Unexplained', portfolioField: 'unexplainedPnl', positionField: 'unexplainedPnl' },
]

const CROSS_GREEK_FACTORS: FactorDef[] = [
  { key: 'vanna', label: 'Vanna', portfolioField: 'vannaPnl', positionField: 'vannaPnl' },
  { key: 'volga', label: 'Volga', portfolioField: 'volgaPnl', positionField: 'volgaPnl' },
  { key: 'charm', label: 'Charm', portfolioField: 'charmPnl', positionField: 'charmPnl' },
  { key: 'crossGamma', label: 'Cross-Gamma', portfolioField: 'crossGammaPnl', positionField: 'crossGammaPnl' },
]

const FACTOR_COLORS = PNL_FACTOR_COLORS

export function PnlAttributionTable({ data }: PnlAttributionTableProps) {
  const [expandedFactor, setExpandedFactor] = useState<string | null>(null)
  const [showCrossGreeks, setShowCrossGreeks] = useState(false)

  const totalPnl = Number(data.totalPnl)
  const factors = showCrossGreeks ? [...FIRST_ORDER_FACTORS.slice(0, -1), ...CROSS_GREEK_FACTORS, FIRST_ORDER_FACTORS[FIRST_ORDER_FACTORS.length - 1]] : FIRST_ORDER_FACTORS

  const toggleFactor = (key: string) => {
    setExpandedFactor((prev) => (prev === key ? null : key))
  }

  const dataQuality = data.dataQualityFlag

  return (
    <div data-testid="attribution-table">
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          {dataQuality === 'PRICE_ONLY' && (
            <span data-testid="attribution-quality-badge" className="text-xs px-2 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900 dark:text-amber-300">
              First-order only
            </span>
          )}
        </div>
        <button
          data-testid="expand-greeks-toggle"
          onClick={() => setShowCrossGreeks((prev) => !prev)}
          className="text-xs text-primary-600 dark:text-primary-400 hover:underline"
        >
          {showCrossGreeks ? 'Hide cross-Greeks' : 'Expand Greeks'}
        </button>
      </div>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-200">
            <th className="text-left py-2 px-3 font-medium text-slate-500 w-8" />
            <th className="text-left py-2 px-3 font-medium text-slate-500">Factor</th>
            <th className="text-right py-2 px-3 font-medium text-slate-500">Amount</th>
            <th className="text-right py-2 px-3 font-medium text-slate-500">% of Total</th>
          </tr>
        </thead>
        <tbody>
          {factors.map((factor) => {
            const amount = String(data[factor.portfolioField] ?? '0')
            const amountNum = Number(amount)
            const pctOfTotal = formatPctOfTotal(amountNum, totalPnl)
            const isExpanded = expandedFactor === factor.key

            return (
              <Fragment key={factor.key}>
                <tr
                  data-testid={`factor-row-${factor.key}`}
                  onClick={() => toggleFactor(factor.key)}
                  className="border-b border-slate-100 cursor-pointer hover:bg-slate-50 transition-colors"
                >
                  <td className="py-2 px-3">
                    {isExpanded ? (
                      <ChevronDown className="h-4 w-4 text-slate-400" />
                    ) : (
                      <ChevronRight className="h-4 w-4 text-slate-400" />
                    )}
                  </td>
                  <td className="py-2 px-3">
                    <div className="flex items-center gap-2">
                      <span
                        className="inline-block w-3 h-3 rounded-sm"
                        style={{ backgroundColor: FACTOR_COLORS[factor.key] }}
                      />
                      <span className="font-medium text-slate-700">{factor.label}</span>
                    </div>
                  </td>
                  <td className="py-2 px-3 text-right">
                    <span
                      data-testid={`factor-amount-${factor.key}`}
                      className={`font-mono tabular-nums ${pnlColorClass(amount)}`}
                    >
                      {formatSignedNum(amountNum)}
                    </span>
                  </td>
                  <td
                    className="py-2 px-3 text-right font-mono tabular-nums text-slate-600"
                    title={pctOfTotal === EM_DASH ? 'Not meaningful — factor amounts offset to a near-zero total' : undefined}
                  >
                    {pctOfTotal}
                  </td>
                </tr>

                {isExpanded && data.positionAttributions.map((pos) => {
                  const posAmount = String(pos[factor.positionField] ?? '0')
                  const posAmountNum = Number(posAmount)

                  return (
                    <tr
                      key={`${factor.key}-${pos.instrumentId}`}
                      data-testid={`position-detail-${factor.key}-${pos.instrumentId}`}
                      className="bg-slate-50 border-b border-slate-100"
                    >
                      <td className="py-1.5 px-3" />
                      <td className="py-1.5 px-3 pl-10 text-slate-500">
                        {pos.instrumentId}
                        <span className="ml-2 text-xs text-slate-400">{pos.assetClass}</span>
                      </td>
                      <td className={`py-1.5 px-3 text-right font-mono tabular-nums ${pnlColorClass(posAmount)}`}>
                        {formatSignedNum(posAmountNum)}
                      </td>
                      <td className="py-1.5 px-3 text-right font-mono tabular-nums text-slate-400">
                        {formatPctOfTotal(posAmountNum, totalPnl)}
                      </td>
                    </tr>
                  )
                })}
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
