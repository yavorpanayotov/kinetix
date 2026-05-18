import { useEffect, useState } from 'react'
import { XCircle } from 'lucide-react'
import { fetchOrderGhostFills, type GhostFillDto } from '../api/ghostFills'
import { ErrorCard, Spinner } from './ui'

interface OrderGhostFillsProps {
  orderId: string
}

/**
 * Order detail panel section that renders ghost fills for an order. When the
 * order has any ghost fills attached, a CRITICAL banner explains that
 * Position has NOT been auto-updated and operator resolution is required —
 * the table below lists each fill so ops can match against the venue's
 * drop-copy or call the venue directly. ADR-0035 phase 2.
 */
export function OrderGhostFills({ orderId }: OrderGhostFillsProps) {
  const [fills, setFills] = useState<GhostFillDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    fetchOrderGhostFills(orderId)
      .then((data) => {
        if (!cancelled) setFills(data)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load ghost fills')
      })

    return () => {
      cancelled = true
    }
  }, [orderId])

  if (error) {
    return <ErrorCard message={error} data-testid="ghost-fills-error" />
  }

  if (fills === null) {
    return (
      <div
        data-testid="ghost-fills-loading"
        className="flex items-center gap-2 text-sm text-slate-500"
      >
        <Spinner size="sm" />
        Loading ghost fills…
      </div>
    )
  }

  if (fills.length === 0) {
    return null
  }

  return (
    <section data-testid="order-ghost-fills" className="mt-4 space-y-3">
      <div
        data-testid="ghost-fill-banner"
        role="alert"
        className="flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3"
      >
        <XCircle className="h-4 w-4 shrink-0 text-red-500" aria-hidden="true" />
        <div>
          <p className="text-sm font-semibold text-red-800">
            Fill received after cancel — contact ops
          </p>
          <p className="mt-1 text-xs text-red-700">
            {fills.length} fill{fills.length === 1 ? '' : 's'} arrived after the order
            reached terminal state. Position has NOT been auto-updated. Manual ops resolution
            is required — match against drop-copy or call the venue directly to confirm.
          </p>
        </div>
      </div>

      <table className="w-full text-sm" data-testid="ghost-fills-table">
        <thead>
          <tr className="text-left text-xs uppercase text-slate-500">
            <th className="px-2 py-1">Detected</th>
            <th className="px-2 py-1">Prior</th>
            <th className="px-2 py-1">Venue</th>
            <th className="px-2 py-1">Exec ID</th>
            <th className="px-2 py-1 text-right">Qty</th>
            <th className="px-2 py-1 text-right">Price</th>
          </tr>
        </thead>
        <tbody>
          {fills.map((fill) => (
            <tr
              key={fill.fixExecId}
              data-testid="ghost-fill-row"
              className="border-t border-slate-200"
            >
              <td className="px-2 py-1 font-mono text-xs">{fill.detectedAt}</td>
              <td className="px-2 py-1">
                <span className="rounded bg-slate-200 px-2 py-0.5 text-xs font-semibold uppercase text-slate-700">
                  {fill.priorStatus}
                </span>
              </td>
              <td className="px-2 py-1">{fill.venue}</td>
              <td className="px-2 py-1 font-mono text-xs">{fill.fixExecId}</td>
              <td className="px-2 py-1 text-right font-mono">{fill.fillQty}</td>
              <td className="px-2 py-1 text-right font-mono">{fill.fillPrice}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
