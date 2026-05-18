import { Calculator, RefreshCw } from 'lucide-react'
import { Card, EmptyState, Spinner } from './ui'
import { useMarginEstimate } from '../hooks/useMarginEstimate'

interface MarginPanelProps {
  bookId: string | null
}

function formatMoney(amount: string, currency: string): string {
  const numeric = Number(amount)
  if (!Number.isFinite(numeric)) return `${amount} ${currency}`
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
    maximumFractionDigits: 2,
  }).format(numeric)
}

export function MarginPanel({ bookId }: MarginPanelProps) {
  const { estimate, loading, error, refresh } = useMarginEstimate(bookId)

  if (!bookId) {
    return (
      <Card className="p-6">
        <EmptyState
          icon={<Calculator className="h-8 w-8" />}
          title="No book selected"
          description="Select a book to see its margin requirement."
        />
      </Card>
    )
  }

  if (loading) {
    return (
      <Card className="p-6 flex items-center justify-center">
        <div data-testid="margin-loading">
          <Spinner />
        </div>
      </Card>
    )
  }

  if (error) {
    return (
      <Card className="p-6">
        <div
          data-testid="margin-error"
          role="alert"
          className="flex items-center justify-between rounded-md bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 px-4 py-3 text-sm text-red-700 dark:text-red-400"
        >
          <span>{error}</span>
          <button
            data-testid="margin-retry-btn"
            onClick={refresh}
            className="ml-4 flex items-center gap-1 text-xs font-medium underline hover:no-underline"
          >
            <RefreshCw className="h-3 w-3" /> Retry
          </button>
        </div>
      </Card>
    )
  }

  if (!estimate) {
    return (
      <Card className="p-6">
        <EmptyState
          icon={<Calculator className="h-8 w-8" />}
          title="No margin data"
          description="The book has no positions or margin could not be calculated."
        />
      </Card>
    )
  }

  return (
    <Card className="p-6" data-testid="margin-panel">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
          <Calculator className="h-4 w-4 text-indigo-500" />
          Margin requirement
        </h3>
        <button
          data-testid="margin-refresh-btn"
          onClick={refresh}
          className="text-xs text-slate-500 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-400 flex items-center gap-1"
          aria-label="Refresh margin estimate"
        >
          <RefreshCw className="h-3 w-3" /> Refresh
        </button>
      </div>
      <div className="grid grid-cols-3 gap-3">
        <div data-testid="margin-initial" className="rounded-md border border-slate-200 dark:border-slate-700 p-3">
          <p className="text-xs uppercase text-slate-500 dark:text-slate-400">Initial margin</p>
          <p className="text-lg font-semibold text-slate-900 dark:text-slate-100">
            {formatMoney(estimate.initialMargin, estimate.currency)}
          </p>
        </div>
        <div data-testid="margin-variation" className="rounded-md border border-slate-200 dark:border-slate-700 p-3">
          <p className="text-xs uppercase text-slate-500 dark:text-slate-400">Variation margin</p>
          <p className="text-lg font-semibold text-slate-900 dark:text-slate-100">
            {formatMoney(estimate.variationMargin, estimate.currency)}
          </p>
        </div>
        <div data-testid="margin-total" className="rounded-md border border-slate-200 dark:border-slate-700 p-3 bg-indigo-50 dark:bg-indigo-900/20">
          <p className="text-xs uppercase text-slate-500 dark:text-slate-400">Total margin</p>
          <p className="text-lg font-semibold text-slate-900 dark:text-slate-100">
            {formatMoney(estimate.totalMargin, estimate.currency)}
          </p>
        </div>
      </div>
    </Card>
  )
}
