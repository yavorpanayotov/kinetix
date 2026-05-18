import { useCallback, useEffect, useRef, useState } from 'react'
import { Info, X } from 'lucide-react'
import { useClickOutside } from '../hooks/useClickOutside'
import { formatMoney } from '../utils/format'
import { formatCompactCurrency } from '../utils/formatCompactCurrency'
import { ScenarioBadge } from './ScenarioBadge'
import type { MarketRegime } from '../types'

type Metric = 'var' | 'es'

const metricDescriptions: Record<Metric, string> = {
  var: "Value at Risk (VaR) estimates the maximum expected loss over a given time horizon at the selected confidence level under normal market conditions.",
  es: "Expected Shortfall (ES) estimates the average loss in scenarios that exceed the VaR threshold, capturing the severity of tail-risk events beyond the confidence level.",
}

interface VaRGaugeProps {
  varValue: number
  expectedShortfall: number
  confidenceLevel: string
  varLimit?: number | null
  pvValue?: string
  previousVaR?: number | null
  onConfidenceLevelChange?: (level: string) => void
  disabled?: boolean
  totalStandaloneVar?: number | null
  diversificationBenefit?: number | null
  /** Active demo scenario, if any — annotated next to VaR and ES per plan §1.2. */
  activeScenario?: string | null
  /** Current market regime — VaR / ES carry a regime-adj badge when non-NORMAL. */
  marketRegime?: MarketRegime | null
}

function gaugeColorEsBased(ratio: number): string {
  if (ratio < 0.5) return '#22c55e'
  if (ratio < 0.8) return '#f59e0b'
  return '#ef4444'
}

function gaugeColorLimitBased(ratio: number): string {
  if (ratio < 0.6) return '#22c55e'
  if (ratio < 0.85) return '#f59e0b'
  return '#ef4444'
}

function confidenceLabel(level: string): string {
  if (level === 'CL_99') return 'VaR (99%)'
  if (level === 'CL_975') return 'VaR (97.5%)'
  return 'VaR (95%)'
}

export function VaRGauge({ varValue, expectedShortfall, confidenceLevel, varLimit, pvValue, previousVaR, onConfidenceLevelChange, disabled, totalStandaloneVar, diversificationBenefit, activeScenario = null, marketRegime = null }: VaRGaugeProps) {
  const [openPopover, setOpenPopover] = useState<Metric | null>(null)
  const gaugeRef = useRef<HTMLDivElement>(null)

  const closePopover = useCallback(() => setOpenPopover(null), [])
  useClickOutside(gaugeRef, closePopover)

  useEffect(() => {
    if (!openPopover) return
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpenPopover(null)
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [openPopover])

  const togglePopover = (metric: Metric) => {
    setOpenPopover(prev => prev === metric ? null : metric)
  }

  const hasLimit = varLimit != null && varLimit > 0
  const maxValue = hasLimit ? varLimit : expectedShortfall * 1.5
  const ratio = Math.min(varValue / maxValue, 1)
  const color = hasLimit ? gaugeColorLimitBased(ratio) : gaugeColorEsBased(ratio)
  const utilisationPct = Math.round(ratio * 100)

  return (
    <div data-testid="var-gauge" ref={gaugeRef} className="flex flex-col gap-1">
      <div className="relative">
        <div data-testid="var-confidence" className="text-xs text-slate-500 inline-flex items-center gap-1">
          {onConfidenceLevelChange ? (
            <>
              <span className="mr-0.5">VaR</span>
              <span className="inline-flex rounded border border-slate-200" role="group">
                <button
                  data-testid="confidence-toggle-95"
                  onClick={() => onConfidenceLevelChange('CL_95')}
                  disabled={disabled}
                  className={`px-1.5 py-0.5 text-xs font-medium rounded-l transition-colors ${confidenceLevel === 'CL_95' ? 'bg-primary-100 text-primary-700' : 'text-slate-500 hover:bg-slate-50'}`}
                >
                  95%
                </button>
                <button
                  data-testid="confidence-toggle-975"
                  onClick={() => onConfidenceLevelChange('CL_975')}
                  disabled={disabled}
                  className={`px-1.5 py-0.5 text-xs font-medium transition-colors ${confidenceLevel === 'CL_975' ? 'bg-primary-100 text-primary-700' : 'text-slate-500 hover:bg-slate-50'}`}
                >
                  97.5%
                </button>
                <button
                  data-testid="confidence-toggle-99"
                  onClick={() => onConfidenceLevelChange('CL_99')}
                  disabled={disabled}
                  className={`px-1.5 py-0.5 text-xs font-medium rounded-r transition-colors ${confidenceLevel === 'CL_99' ? 'bg-primary-100 text-primary-700' : 'text-slate-500 hover:bg-slate-50'}`}
                >
                  99%
                </button>
              </span>
            </>
          ) : (
            confidenceLabel(confidenceLevel)
          )}
          <Info
            data-testid="var-info"
            className="h-3 w-3 cursor-pointer text-slate-400 hover:text-slate-600 transition-colors"
            onClick={() => togglePopover('var')}
          />
        </div>
        {openPopover === 'var' && (
          <span
            data-testid="var-popover"
            className="absolute top-full left-1/2 -translate-x-1/2 mt-1 w-64 rounded bg-slate-800 px-3 py-2 text-xs font-normal text-white text-justify shadow-lg z-10"
          >
            <button data-testid="var-popover-close" className="float-right ml-2 text-slate-400 hover:text-white" onClick={closePopover}><X className="h-3 w-3" /></button>
            {metricDescriptions.var}
          </span>
        )}
      </div>

      <div className="flex items-center gap-2 flex-wrap">
        <div data-testid="var-value" className="text-2xl font-bold" title={formatMoney(varValue.toFixed(2), 'USD')}>
          {formatCompactCurrency(varValue)}
        </div>
        <span
          data-testid="var-status-dot"
          className="h-2.5 w-2.5 rounded-full"
          style={{ backgroundColor: color }}
        />
        <ScenarioBadge scenario={activeScenario} regime={marketRegime} />
      </div>

      {previousVaR != null && (() => {
        const change = varValue - previousVaR
        const absChange = Math.abs(change)
        const formattedChange = formatMoney(absChange.toFixed(2), 'USD')
        const hasPct = previousVaR !== 0
        const pct = hasPct ? (change / previousVaR) * 100 : 0
        const arrow = change > 0 ? '\u2191' : change < 0 ? '\u2193' : '\u2014'
        const colorClass = change > 0 ? 'text-red-600' : change < 0 ? 'text-green-600' : 'text-slate-400'
        const sign = change > 0 ? '+' : change < 0 ? '-' : ''

        return (
          <div data-testid="var-change" className={`text-xs ${colorClass}`}>
            {arrow} {formattedChange}{hasPct ? ` (${sign}${Math.abs(pct).toFixed(1)}%)` : ''}
          </div>
        )
      })()}

      <div className="relative">
        <div data-testid="es-value" className="text-sm text-slate-600 inline-flex items-center gap-1">
          ES
          <Info
            data-testid="es-info"
            className="h-3 w-3 cursor-pointer text-slate-400 hover:text-slate-600 transition-colors"
            onClick={() => togglePopover('es')}
          />
          <span title={formatMoney(expectedShortfall.toFixed(2), 'USD')}>{formatCompactCurrency(expectedShortfall)}</span>
          {varValue > 0 && (
            <span data-testid="es-var-ratio" className="text-xs text-slate-400">
              ({(expectedShortfall / varValue).toFixed(2)}x)
            </span>
          )}
          <ScenarioBadge scenario={activeScenario} regime={marketRegime} />
        </div>
        {openPopover === 'es' && (
          <span
            data-testid="es-popover"
            className="absolute top-full left-1/2 -translate-x-1/2 mt-1 w-64 rounded bg-slate-800 px-3 py-2 text-xs font-normal text-white text-justify shadow-lg z-10"
          >
            <button data-testid="es-popover-close" className="float-right ml-2 text-slate-400 hover:text-white" onClick={closePopover}><X className="h-3 w-3" /></button>
            {metricDescriptions.es}
          </span>
        )}
      </div>

      {totalStandaloneVar != null && diversificationBenefit != null && (
        <div data-testid="diversification-summary" className="border-t border-slate-100 mt-1 pt-1 space-y-0.5">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Sum of books</span>
            <span data-testid="sum-of-books-var" title={formatMoney(totalStandaloneVar.toFixed(2), 'USD')}>{formatCompactCurrency(totalStandaloneVar)}</span>
          </div>
          <div className="flex items-center justify-between text-xs text-green-600">
            <span>Diversification benefit</span>
            <span data-testid="diversification-benefit-value" title={formatMoney(diversificationBenefit.toFixed(2), 'USD')}>
              -{formatCompactCurrency(diversificationBenefit)}
              {totalStandaloneVar > 0 && ` (${((diversificationBenefit / totalStandaloneVar) * 100).toFixed(1)}%)`}
            </span>
          </div>
        </div>
      )}

      {pvValue && (
        <div data-testid="var-pv" className="text-sm text-slate-600">
          PV {formatCompactCurrency(Number(pvValue))}
        </div>
      )}

      {hasLimit && (
        <>
          <div className="border-t border-slate-100 mt-1 pt-1" />
          <div data-testid="var-limit" className="flex items-center justify-between text-xs text-slate-500">
            <span>Limit</span>
            <span>{utilisationPct}%</span>
          </div>
          <div data-testid="var-limit-bar" className="h-1.5 rounded-full bg-slate-200 overflow-hidden">
            <div
              data-testid="var-limit-bar-fill"
              className="h-full rounded-full transition-all"
              style={{ width: `${utilisationPct}%`, backgroundColor: color }}
            />
          </div>
        </>
      )}
    </div>
  )
}
