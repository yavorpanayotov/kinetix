import { useEffect, useMemo, useState } from 'react'
import { ChevronDown, ChevronRight, Copy, Download, Inbox } from 'lucide-react'
import { useTradeHistory } from '../hooks/useTradeHistory'
import { formatMoney, formatQuantity, formatTimestamp } from '../utils/format'
import { formatPrice } from '../utils/formatPrice'
import { formatCompactCurrency } from '../utils/formatCompactCurrency'
import { Card, EmptyState, ErrorCard, Spinner } from './ui'
import type { TradeHistoryDto } from '../types'
import { InstrumentTypeBadge } from './InstrumentTypeBadge'
import { INSTRUMENT_TYPE_OPTIONS, formatInstrumentTypeLabel } from '../utils/instrumentTypes'
import { VenueRoutingStatusDot } from './VenueRoutingStatusDot'
import { TradeDetailPanel } from './TradeDetailPanel'
import { fillStatus, notional, qtyFilledOf, qtyOpenOf } from '../utils/tradeProjections'

interface TradeBlotterProps {
  bookId: string | null
  /**
   * Initial value for the counterparty filter — used by cross-tab jumps
   * from the Counterparty Risk dashboard (plan §2.4). The user can still
   * edit or clear the field after it's been pre-populated.
   */
  initialCounterpartyFilter?: string
}

function statusBadgeClass(status: string): string {
  switch (status) {
    case 'CANCELLED':
    case 'REJECTED':
      return 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300'
    case 'AMENDED':
    case 'PENDING_FAILED':
    case 'PARTIAL':
      return 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-300'
    case 'PENDING':
    case 'EXPIRED':
    case 'WORKING':
      return 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300'
    default:
      return 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300'
  }
}

function exportToCsv(trades: TradeHistoryDto[]) {
  const header = 'Time,Instrument,Name,Type,Side,Qty,QtyFilled,QtyOpen,Price,Currency,Notional,FillStatus,Venue,VenueOrderId'
  const rows = trades.map((t) => {
    const n = notional(t)
    const name = (t.displayName || t.instrumentId).replace(/,/g, ' ')
    return `${t.tradedAt},${t.instrumentId},${name},${t.instrumentType || ''},${t.side},${t.quantity},${qtyFilledOf(t)},${qtyOpenOf(t)},${t.price.amount},${t.price.currency},${n.toFixed(2)},${fillStatus(t)},${t.venue ?? ''},${t.venueOrderId ?? ''}`
  })
  const csv = [header, ...rows].join('\n')
  const blob = new Blob([csv], { type: 'text/csv' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'trades.csv'
  a.click()
  URL.revokeObjectURL(url)
}

async function copyVenueOrderId(value: string | undefined): Promise<void> {
  if (!value || typeof navigator === 'undefined' || !navigator.clipboard) return
  await navigator.clipboard.writeText(value)
}

const PAGE_SIZE = 50

export function TradeBlotter({ bookId, initialCounterpartyFilter = '' }: TradeBlotterProps) {
  const [counterpartyFilter, setCounterpartyFilter] = useState<string>(initialCounterpartyFilter)
  // When the parent supplies a new initial filter (e.g. the user clicks a
  // different counterparty's "View trades" link), reflect that in the input.
  const [lastInitialFilter, setLastInitialFilter] = useState(initialCounterpartyFilter)
  if (initialCounterpartyFilter !== lastInitialFilter) {
    setLastInitialFilter(initialCounterpartyFilter)
    setCounterpartyFilter(initialCounterpartyFilter)
  }
  const {
    trades,
    loading,
    error,
    total,
    page: serverPage,
    totalPages: serverTotalPages,
    goToPage,
  } = useTradeHistory(bookId, {
    pageSize: PAGE_SIZE,
    counterpartyId: counterpartyFilter || null,
  })
  const [instrumentFilter, setInstrumentFilter] = useState('')
  const [sideFilter, setSideFilter] = useState<'' | 'BUY' | 'SELL'>('')
  const [instrumentTypeFilter, setInstrumentTypeFilter] = useState('')
  const [filterResetNotice, setFilterResetNotice] = useState<string | null>(null)
  const [showVenueOrderId, setShowVenueOrderId] = useState(false)
  const [showVenue, setShowVenue] = useState(false)
  const [expandedTradeId, setExpandedTradeId] = useState<string | null>(null)

  const instrumentTypeOptions = useMemo(() => {
    const counts = new Map<string, number>()
    for (const t of trades) {
      if (t.instrumentType) {
        counts.set(t.instrumentType, (counts.get(t.instrumentType) ?? 0) + 1)
      }
    }
    const sorted = [...counts.keys()].sort((a, b) => {
      const ai = INSTRUMENT_TYPE_OPTIONS.indexOf(a)
      const bi = INSTRUMENT_TYPE_OPTIONS.indexOf(b)
      return (ai === -1 ? Infinity : ai) - (bi === -1 ? Infinity : bi)
    })
    return sorted.map((type) => ({ value: type, label: formatInstrumentTypeLabel(type), count: counts.get(type)! }))
  }, [trades])

  const [prevTrades, setPrevTrades] = useState(trades)
  if (trades !== prevTrades) {
    setPrevTrades(trades)
    if (instrumentTypeFilter) {
      const stillValid = trades.some((t) => t.instrumentType === instrumentTypeFilter)
      if (!stillValid) {
        const label = formatInstrumentTypeLabel(instrumentTypeFilter)
        setInstrumentTypeFilter('')
        setFilterResetNotice(`${label} filter removed (no matching trades)`)
      }
    }
  }

  useEffect(() => {
    if (filterResetNotice) {
      const timer = setTimeout(() => setFilterResetNotice(null), 8000)
      return () => clearTimeout(timer)
    }
  }, [filterResetNotice])

  // Server returns the active page; instrument / side / instrument-type
  // filters are still applied client-side and operate on the visible page.
  // Counterparty filter is forwarded to the server as a query param.
  const filtered = useMemo(() => {
    let result = [...trades]

    if (instrumentFilter) {
      const upper = instrumentFilter.toUpperCase()
      result = result.filter((t) => t.instrumentId.toUpperCase().includes(upper))
    }

    if (sideFilter) {
      result = result.filter((t) => t.side === sideFilter)
    }

    if (instrumentTypeFilter) {
      result = result.filter((t) => t.instrumentType === instrumentTypeFilter)
    }

    result.sort((a, b) => new Date(b.tradedAt).getTime() - new Date(a.tradedAt).getTime())

    return result
  }, [trades, instrumentFilter, sideFilter, instrumentTypeFilter])

  const paginatedTrades = filtered

  // Fixed columns: expand toggle + Time, Instrument, Name, Type, Side, Qty,
  // Filled, Open, Price, Notional, Status = 12. The Venue and Venue Order ID
  // columns are optional and gated behind their respective toggles.
  const totalColumns = 12 + (showVenue ? 1 : 0) + (showVenueOrderId ? 1 : 0)

  const handleInstrumentFilter = (value: string) => {
    setInstrumentFilter(value)
  }

  const handleSideFilter = (value: '' | 'BUY' | 'SELL') => {
    setSideFilter(value)
  }

  const handleInstrumentTypeFilter = (value: string) => {
    setInstrumentTypeFilter(value)
  }

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-sm text-slate-500">
        <Spinner size="sm" />
        Loading trades...
      </div>
    )
  }

  if (error) {
    return <ErrorCard message={error} />
  }

  if (trades.length === 0) {
    return (
      <Card>
        <EmptyState
          icon={<Inbox className="h-10 w-10" />}
          title="No trades to display."
        />
      </Card>
    )
  }

  return (
    <div>
      <div className="flex items-center gap-3 mb-4">
        <span
          data-testid="venue-routing-status"
          className="inline-flex items-center gap-1.5 text-xs text-slate-500"
        >
          <span>Venue routing</span>
          <VenueRoutingStatusDot />
        </span>
        <input
          data-testid="filter-instrument"
          type="text"
          placeholder="Filter by instrument..."
          value={instrumentFilter}
          onChange={(e) => handleInstrumentFilter(e.target.value)}
          className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
        />
        <select
          data-testid="filter-side"
          value={sideFilter}
          onChange={(e) => handleSideFilter(e.target.value as '' | 'BUY' | 'SELL')}
          className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
        >
          <option value="">All Sides</option>
          <option value="BUY">BUY</option>
          <option value="SELL">SELL</option>
        </select>
        <input
          data-testid="filter-counterparty"
          type="text"
          placeholder="Counterparty (server-side)..."
          aria-label="Filter by counterparty"
          value={counterpartyFilter}
          onChange={(e) => setCounterpartyFilter(e.target.value)}
          className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
        />
        {instrumentTypeOptions.length > 1 && (
          <select
            data-testid="filter-instrument-type"
            aria-label="Filter by instrument type"
            value={instrumentTypeFilter}
            onChange={(e) => handleInstrumentTypeFilter(e.target.value)}
            className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
          >
            <option value="">All Types</option>
            {instrumentTypeOptions.map(({ value, label, count }) => (
              <option key={value} value={value}>{label} ({count})</option>
            ))}
          </select>
        )}
        {filterResetNotice && (
          <span
            role="status"
            data-testid="filter-reset-notice"
            className="text-sm text-amber-700 bg-amber-50 px-2 py-1 rounded"
          >
            {filterResetNotice}
            <button
              onClick={() => setFilterResetNotice(null)}
              className="ml-2 text-amber-500 hover:text-amber-700"
              aria-label="Dismiss notice"
            >
              &times;
            </button>
          </span>
        )}
        <div className="flex-1" />
        <button
          data-testid="toggle-venue-column"
          aria-pressed={showVenue}
          onClick={() => setShowVenue((v) => !v)}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-slate-600 dark:text-slate-400 border border-slate-300 dark:border-surface-600 rounded-md hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
        >
          {showVenue ? 'Hide' : 'Show'} Venue
        </button>
        <button
          data-testid="toggle-venue-order-id-column"
          aria-pressed={showVenueOrderId}
          onClick={() => setShowVenueOrderId((v) => !v)}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-slate-600 dark:text-slate-400 border border-slate-300 dark:border-surface-600 rounded-md hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
        >
          {showVenueOrderId ? 'Hide' : 'Show'} Venue Order ID
        </button>
        <button
          data-testid="csv-export-button"
          onClick={() => exportToCsv(filtered)}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-slate-600 dark:text-slate-400 border border-slate-300 dark:border-surface-600 rounded-md hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
        >
          <Download className="h-4 w-4" />
          Export CSV
        </button>
      </div>

      <Card>
        <div className="-mx-4 -my-4 overflow-x-auto">
          <table className="min-w-full divide-y divide-slate-200 dark:divide-surface-700">
            <thead>
              <tr className="bg-slate-50 dark:bg-surface-800">
                <th className="px-2 py-2 w-8" aria-label="Expand row" />
                <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Time</th>
                <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Instrument</th>
                <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Name</th>
                <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Type</th>
                <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Side</th>
                <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700 dark:text-slate-300">Qty</th>
                <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700 dark:text-slate-300">Filled</th>
                <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700 dark:text-slate-300">Open</th>
                <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700 dark:text-slate-300">Price</th>
                <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700 dark:text-slate-300">Notional</th>
                <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Status</th>
                {showVenue && (
                  <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Venue</th>
                )}
                {showVenueOrderId && (
                  <th className="px-4 py-2 text-right text-sm font-semibold text-slate-700 dark:text-slate-300">Venue Order ID</th>
                )}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-surface-700">
              {paginatedTrades.length === 0 && filtered.length === 0 && trades.length > 0 ? (
                <tr>
                  <td colSpan={totalColumns} className="px-4 py-8 text-center">
                    <EmptyState title="No trades match your filters." />
                  </td>
                </tr>
              ) : (
                paginatedTrades.flatMap((trade) => {
                  const expanded = expandedTradeId === trade.tradeId
                  const colSpan = totalColumns
                  const mainRow = (
                  <tr
                    key={trade.tradeId}
                    data-testid={`trade-row-${trade.tradeId}`}
                    className="hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
                  >
                    <td className="px-2 py-2 text-sm">
                      <button
                        data-testid={`expand-trade-row-${trade.tradeId}`}
                        aria-label={expanded ? 'Collapse order detail' : 'Expand order detail'}
                        aria-expanded={expanded}
                        onClick={() =>
                          setExpandedTradeId((current) =>
                            current === trade.tradeId ? null : trade.tradeId,
                          )
                        }
                        className="p-1 rounded hover:bg-slate-100 dark:hover:bg-surface-700"
                      >
                        {expanded ? (
                          <ChevronDown className="h-3.5 w-3.5 text-slate-500" />
                        ) : (
                          <ChevronRight className="h-3.5 w-3.5 text-slate-500" />
                        )}
                      </button>
                    </td>
                    <td className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                      {formatTimestamp(trade.tradedAt)}
                    </td>
                    <td className="px-4 py-2 text-sm font-medium">{trade.instrumentId}</td>
                    <td className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400">{trade.displayName || trade.instrumentId}</td>
                    <td className="px-4 py-2 text-sm">{trade.instrumentType ? <InstrumentTypeBadge instrumentType={trade.instrumentType} /> : '—'}</td>
                    <td
                      data-testid={`trade-side-${trade.tradeId}`}
                      className={`px-4 py-2 text-sm font-medium ${
                        trade.side === 'BUY' ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
                      }`}
                    >
                      {trade.side}
                    </td>
                    <td className="px-4 py-2 text-sm text-right">{formatQuantity(trade.quantity)}</td>
                    <td
                      data-testid={`trade-qty-filled-${trade.tradeId}`}
                      className="px-4 py-2 text-sm text-right whitespace-nowrap text-slate-700 dark:text-slate-300"
                    >
                      {formatQuantity(qtyFilledOf(trade))}
                    </td>
                    <td
                      data-testid={`trade-qty-open-${trade.tradeId}`}
                      className="px-4 py-2 text-sm text-right whitespace-nowrap text-slate-700 dark:text-slate-300"
                    >
                      {formatQuantity(qtyOpenOf(trade))}
                    </td>
                    <td
                      data-testid={`trade-price-${trade.tradeId}`}
                      className="px-4 py-2 text-sm text-right whitespace-nowrap"
                    >
                      {formatPrice(trade.price.amount, trade.price.currency, trade.assetClass)}
                    </td>
                    <td
                      data-testid={`trade-notional-${trade.tradeId}`}
                      className="px-4 py-2 text-sm text-right whitespace-nowrap"
                      title={formatMoney(String(notional(trade)), trade.price.currency)}
                    >
                      {formatCompactCurrency(notional(trade))}
                    </td>
                    <td className="px-4 py-2 text-sm">
                      <span
                        data-testid={`trade-status-${trade.tradeId}`}
                        className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${statusBadgeClass(fillStatus(trade))}`}
                      >
                        {fillStatus(trade)}
                      </span>
                    </td>
                    {showVenue && (
                      <td
                        data-testid={`trade-venue-${trade.tradeId}`}
                        className="px-4 py-2 text-sm text-slate-700 dark:text-slate-300"
                      >
                        {trade.venue ?? '—'}
                      </td>
                    )}
                    {showVenueOrderId && (
                      <td className="px-4 py-2 text-sm">
                        <div className="flex items-center justify-end gap-1.5">
                          <span
                            data-testid={`trade-venue-order-id-${trade.tradeId}`}
                            aria-label="Venue order ID"
                            className="font-mono text-right text-xs text-slate-700 dark:text-slate-300"
                          >
                            {trade.venueOrderId ?? '—'}
                          </span>
                          {trade.venueOrderId && (
                            <button
                              data-testid={`copy-venue-order-id-${trade.tradeId}`}
                              aria-label="Copy venue order ID"
                              onClick={() => copyVenueOrderId(trade.venueOrderId)}
                              className="p-1 rounded hover:bg-slate-100 dark:hover:bg-surface-700"
                            >
                              <Copy className="h-3 w-3 text-slate-500" />
                            </button>
                          )}
                        </div>
                      </td>
                    )}
                  </tr>
                  )
                  if (!expanded) return [mainRow]
                  return [
                    mainRow,
                    <tr
                      key={`${trade.tradeId}-detail`}
                      data-testid={`trade-row-detail-${trade.tradeId}`}
                      className="bg-slate-50 dark:bg-surface-800"
                    >
                      <td colSpan={colSpan} className="px-4 py-3">
                        <TradeDetailPanel trade={trade} />
                      </td>
                    </tr>,
                  ]
                })
              )}
            </tbody>
          </table>
        </div>
      </Card>

      {serverTotalPages > 1 && (
        <div
          data-testid="blotter-pagination-footer"
          className="flex items-center justify-between mt-3 text-sm text-slate-600 dark:text-slate-400"
        >
          <span>
            Showing {serverPage * PAGE_SIZE + 1}
            –{Math.min((serverPage + 1) * PAGE_SIZE, total)} of {total}
          </span>
          <div className="flex items-center gap-2">
            <button
              data-testid="blotter-prev-page"
              disabled={serverPage === 0}
              onClick={() => goToPage(serverPage - 1)}
              className="px-3 py-1 rounded border border-slate-300 dark:border-surface-600 disabled:opacity-40 hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
            >
              Previous
            </button>
            <span>Page {serverPage + 1} of {serverTotalPages}</span>
            <button
              data-testid="blotter-next-page"
              disabled={serverPage >= serverTotalPages - 1}
              onClick={() => goToPage(serverPage + 1)}
              className="px-3 py-1 rounded border border-slate-300 dark:border-surface-600 disabled:opacity-40 hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
