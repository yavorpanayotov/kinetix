import { useState } from 'react'
import { YieldCurveChart } from './YieldCurveChart'

interface YieldCurvePanelProps {
  currencies?: string[]
  defaultCurrency?: string
}

const DEFAULT_CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY']

/**
 * Yield curve panel for the Risk → Market Data sub-tab. Lets the user pick
 * a currency and renders the curve with hollow markers for any interpolated
 * tenor (e.g. the seeded GBP 5Y anomaly).
 */
export function YieldCurvePanel({
  currencies = DEFAULT_CURRENCIES,
  defaultCurrency = 'GBP',
}: YieldCurvePanelProps) {
  const [currency, setCurrency] = useState<string>(defaultCurrency)

  return (
    <div data-testid="yield-curve-panel" className="space-y-3">
      <div className="flex items-center gap-3">
        <label htmlFor="yield-curve-currency" className="text-xs text-slate-400">
          Currency:
        </label>
        <select
          id="yield-curve-currency"
          data-testid="yield-curve-currency-selector"
          value={currency}
          onChange={(e) => setCurrency(e.target.value)}
          className="bg-slate-700 border border-slate-600 text-slate-200 text-sm rounded px-2 py-1 focus:outline-none focus:ring-1 focus:ring-blue-500"
          aria-label="Select currency"
        >
          {currencies.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
      </div>
      <YieldCurveChart currency={currency} />
    </div>
  )
}
