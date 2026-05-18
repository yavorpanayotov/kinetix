import type { BookAggregationDto } from '../types'
import { formatMoney, formatSignedMoney, pnlColorClass } from '../utils/format'
import { Card, Spinner } from './ui'

const CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY']

interface BookSummaryCardProps {
  summary: BookAggregationDto | null
  baseCurrency: string
  onBaseCurrencyChange: (currency: string) => void
  loading?: boolean
  title?: string
}

export function BookSummaryCard({
  summary,
  baseCurrency,
  onBaseCurrencyChange,
  loading,
  title = 'Book Summary',
}: BookSummaryCardProps) {
  if (loading && !summary) {
    return (
      <Card data-testid="book-summary-loading">
        <div className="flex items-center justify-center py-4">
          <Spinner />
          <span className="ml-2 text-sm text-slate-500">Loading book summary...</span>
        </div>
      </Card>
    )
  }

  if (!summary) return null

  return (
    <Card data-testid="book-summary-card">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">{title}</h3>
        <select
          data-testid="base-currency-selector"
          value={baseCurrency}
          onChange={(e) => onBaseCurrencyChange(e.target.value)}
          className="bg-white dark:bg-surface-700 border border-slate-300 dark:border-surface-600 text-slate-700 dark:text-slate-200 rounded-md px-2 py-1 text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
        >
          {CURRENCIES.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
      </div>

      <div className="grid grid-cols-2 gap-4 mb-4">
        <div>
          <div className="text-xs text-slate-500">Total NAV</div>
          <div data-testid="total-nav" className="text-lg font-bold text-slate-800 dark:text-slate-100">
            {formatMoney(summary.totalNav.amount, summary.totalNav.currency)}
          </div>
        </div>
        <div>
          <div className="text-xs text-slate-500">Unrealized P&amp;L</div>
          <div
            data-testid="total-unrealized-pnl"
            className={`text-lg font-bold ${pnlColorClass(summary.totalUnrealizedPnl.amount)}`}
          >
            {formatSignedMoney(summary.totalUnrealizedPnl.amount, summary.totalUnrealizedPnl.currency)}
          </div>
        </div>
      </div>

      {summary.currencyBreakdown.length > 1 && (
        <div>
          <div className="text-xs text-slate-500 mb-2">Currency Breakdown</div>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs text-slate-500 border-b border-slate-100">
                <th className="text-left py-1">Currency</th>
                <th className="text-right py-1">Local Value</th>
                <th className="text-right py-1">Base Value</th>
                <th className="text-right py-1">FX Rate</th>
              </tr>
            </thead>
            <tbody>
              {summary.currencyBreakdown.map((exposure) => (
                <tr
                  key={exposure.currency}
                  data-testid={`currency-row-${exposure.currency}`}
                  className="border-b border-slate-50"
                >
                  <td className="py-1.5 font-medium">{exposure.currency}</td>
                  <td className="py-1.5 text-right">
                    {formatMoney(exposure.localValue.amount, exposure.localValue.currency)}
                  </td>
                  <td className="py-1.5 text-right">
                    {formatMoney(exposure.baseValue.amount, exposure.baseValue.currency)}
                  </td>
                  <td className="py-1.5 text-right text-slate-500">{exposure.fxRate}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}
