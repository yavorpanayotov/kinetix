import { useEffect, useState } from 'react'
import { Inbox, AlertTriangle } from 'lucide-react'
import { fetchExecutionCosts } from '../api/execution'
import type { ExecutionCostDto } from '../types'
import { formatNum, formatQuantity, formatTimestamp } from '../utils/format'
import { Card, EmptyState, ErrorCard, Spinner } from './ui'

function SimulationModeBanner() {
  return (
    <div
      data-testid="simulation-mode-banner"
      role="status"
      aria-label="Order routing simulation mode notice"
      className="flex items-center gap-2 rounded-md bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700 px-3 py-2 text-sm text-amber-800 dark:text-amber-300 mb-4"
    >
      <AlertTriangle className="h-4 w-4 flex-shrink-0 text-amber-500" aria-hidden="true" />
      <span>Order routing in simulation mode — orders are logged but not transmitted to a broker.</span>
    </div>
  )
}

interface ExecutionCostPanelProps {
  bookId: string | null
}

export function ExecutionCostPanel({ bookId }: ExecutionCostPanelProps) {
  const [costs, setCosts] = useState<ExecutionCostDto[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!bookId) return

    let cancelled = false

    async function load() {
      setLoading(true)
      setError(null)
      try {
        const data = await fetchExecutionCosts(bookId!)
        if (!cancelled) setCosts(data)
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    load()

    return () => {
      cancelled = true
    }
  }, [bookId])

  if (!bookId) {
    return (
      <Card>
        <EmptyState icon={<Inbox className="h-10 w-10" />} title="Select a book to view execution costs." />
      </Card>
    )
  }

  if (loading) {
    return (
      <>
        <SimulationModeBanner />
        <div className="flex items-center gap-2 text-sm text-slate-500">
          <Spinner size="sm" />
          Loading execution costs...
        </div>
      </>
    )
  }

  if (error) {
    return (
      <>
        <SimulationModeBanner />
        <ErrorCard message={error} />
      </>
    )
  }

  if (costs.length === 0) {
    return (
      <>
        <SimulationModeBanner />
        <Card>
          <EmptyState
            icon={<Inbox className="h-10 w-10" />}
            title="No execution cost data for this book."
          />
        </Card>
      </>
    )
  }

  return (
    <>
      <SimulationModeBanner />
      <Card>
      <div className="-mx-4 -my-4 overflow-x-auto">
        <table className="min-w-full divide-y divide-slate-200" data-testid="execution-cost-table">
          <thead>
            <tr className="bg-slate-50">
              <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700">Order ID</th>
              <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700">Instrument</th>
              <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700">Side</th>
              <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700">Qty</th>
              <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700">Arrival Price</th>
              <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700">Avg Fill</th>
              <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700">Slippage (bps)</th>
              <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700">Total Cost (bps)</th>
              <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700">Completed</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {costs.map((cost) => {
              const slippage = parseFloat(cost.slippageBps)
              const isPositiveCost = slippage > 0
              return (
                <tr
                  key={cost.orderId}
                  data-testid={`cost-row-${cost.orderId}`}
                  className="hover:bg-slate-50 transition-colors"
                >
                  <td className="px-4 py-2 text-sm font-mono text-slate-600">{cost.orderId}</td>
                  <td className="px-4 py-2 text-sm font-medium">{cost.instrumentId}</td>
                  <td
                    data-testid={`side-${cost.orderId}`}
                    className={`px-4 py-2 text-sm font-medium ${cost.side === 'BUY' ? 'text-green-600' : 'text-red-600'}`}
                  >
                    {cost.side}
                  </td>
                  <td className="px-4 py-2 text-sm text-right tabular-nums">{formatQuantity(cost.totalQty)}</td>
                  <td className="px-4 py-2 text-sm text-right tabular-nums">{formatNum(cost.arrivalPrice)}</td>
                  <td className="px-4 py-2 text-sm text-right tabular-nums">{formatNum(cost.averageFillPrice)}</td>
                  <td
                    data-testid={`slippage-${cost.orderId}`}
                    className={`px-4 py-2 text-sm text-right font-medium tabular-nums ${isPositiveCost ? 'text-amber-600' : 'text-green-600'}`}
                  >
                    {formatNum(cost.slippageBps, 1)}
                  </td>
                  <td className="px-4 py-2 text-sm text-right tabular-nums">{formatNum(cost.totalCostBps, 1)}</td>
                  <td className="px-4 py-2 text-sm text-slate-500 tabular-nums">
                    {formatTimestamp(cost.completedAt)}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </Card>
    </>
  )
}
