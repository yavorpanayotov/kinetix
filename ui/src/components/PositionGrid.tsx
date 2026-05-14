import { useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, ChevronUp, ChevronLeft, ChevronRight, Wifi, WifiOff, Inbox, Settings, Download } from 'lucide-react'
import type { PositionDto, PositionRiskDto } from '../types'
import { formatMoney, formatNum, formatQuantity, pnlColorClass } from '../utils/format'
import { formatCompactCurrency } from '../utils/formatCompactCurrency'
import { exportToCsv } from '../utils/exportCsv'
import { Card, EmptyState } from './ui'
import { InstrumentTypeBadge } from './InstrumentTypeBadge'
import { INSTRUMENT_TYPE_OPTIONS, formatInstrumentTypeLabel } from '../utils/instrumentTypes'
import { buildStrategyGroups } from '../utils/strategyGrouping'
import { StrategyGroupRow } from './StrategyGroupRow'

type SortField = 'delta' | 'gamma' | 'vega' | 'var-pct'
type SortDirection = 'asc' | 'desc'

interface PositionGridProps {
  positions: PositionDto[]
  connected?: boolean
  reconnecting?: boolean
  lastConnectedAt?: Date | null
  positionRisk?: PositionRiskDto[]
  showBookColumn?: boolean
}

function riskValue(risk: PositionRiskDto | undefined, field: SortField): number {
  if (!risk) return -Infinity
  switch (field) {
    case 'delta': return risk.delta != null ? Number(risk.delta) : -Infinity
    case 'gamma': return risk.gamma != null ? Number(risk.gamma) : -Infinity
    case 'vega': return risk.vega != null ? Number(risk.vega) : -Infinity
    case 'var-pct': return Number(risk.percentageOfTotal)
  }
}

const PAGE_SIZE = 50

const STORAGE_KEY = 'kinetix:column-visibility'

interface ColumnDef {
  key: string
  label: string
  align: 'left' | 'right'
}

const POSITION_COLUMNS: ColumnDef[] = [
  { key: 'instrument', label: 'Instrument', align: 'left' },
  { key: 'displayName', label: 'Name', align: 'left' },
  { key: 'instrumentType', label: 'Type', align: 'left' },
  { key: 'assetClass', label: 'Asset Class', align: 'left' },
  { key: 'quantity', label: 'Quantity', align: 'right' },
  { key: 'avgCost', label: 'Avg Cost', align: 'right' },
  { key: 'marketPrice', label: 'Market Price', align: 'right' },
  { key: 'marketValue', label: 'Market Value', align: 'right' },
  { key: 'unrealizedPnl', label: 'Unrealized P&L', align: 'right' },
  { key: 'realizedPnl', label: 'Realized P&L', align: 'right' },
]

function loadColumnVisibility(): Record<string, boolean> {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) return JSON.parse(stored)
  } catch { /* ignore */ }
  return {}
}

export function PositionGrid({ positions, connected, reconnecting, lastConnectedAt, positionRisk, showBookColumn = false }: PositionGridProps) {
  const [sortField, setSortField] = useState<SortField | null>(null)
  const [sortDir, setSortDir] = useState<SortDirection>('desc')
  const [currentPage, setCurrentPage] = useState(1)
  const [columnVisibility, setColumnVisibility] = useState<Record<string, boolean>>(loadColumnVisibility)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [instrumentTypeFilter, setInstrumentTypeFilter] = useState('')
  const [instrumentSearch, setInstrumentSearch] = useState('')
  const [filterResetNotice, setFilterResetNotice] = useState<string | null>(null)
  const settingsRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (settingsRef.current && !settingsRef.current.contains(e.target as Node)) {
        setSettingsOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const isColumnVisible = (key: string) => columnVisibility[key] !== false

  const toggleColumn = (key: string) => {
    const next = { ...columnVisibility, [key]: !isColumnVisible(key) }
    setColumnVisibility(next)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  }

  const hasRisk = positionRisk != null && positionRisk.length > 0

  const riskByInstrument = useMemo(() => {
    if (!positionRisk) return new Map<string, PositionRiskDto>()
    return new Map(positionRisk.map((r) => [r.instrumentId, r]))
  }, [positionRisk])

  const instrumentTypeOptions = useMemo(() => {
    const counts = new Map<string, number>()
    for (const p of positions) {
      if (p.instrumentType) {
        counts.set(p.instrumentType, (counts.get(p.instrumentType) ?? 0) + 1)
      }
    }
    const sorted = [...counts.keys()].sort((a, b) => {
      const ai = INSTRUMENT_TYPE_OPTIONS.indexOf(a)
      const bi = INSTRUMENT_TYPE_OPTIONS.indexOf(b)
      return (ai === -1 ? Infinity : ai) - (bi === -1 ? Infinity : bi)
    })
    return sorted.map((type) => ({ value: type, label: formatInstrumentTypeLabel(type), count: counts.get(type)! }))
  }, [positions])

  const [prevPositions, setPrevPositions] = useState(positions)
  if (positions !== prevPositions) {
    setPrevPositions(positions)
    if (instrumentTypeFilter) {
      const stillValid = positions.some((p) => p.instrumentType === instrumentTypeFilter)
      if (!stillValid) {
        const label = formatInstrumentTypeLabel(instrumentTypeFilter)
        setInstrumentTypeFilter('')
        setCurrentPage(1)
        setFilterResetNotice(`${label} filter removed (no matching positions)`)
      }
    }
  }

  useEffect(() => {
    if (filterResetNotice) {
      const timer = setTimeout(() => setFilterResetNotice(null), 8000)
      return () => clearTimeout(timer)
    }
  }, [filterResetNotice])

  const filteredPositions = useMemo(() => {
    let result = positions
    if (instrumentTypeFilter) {
      result = result.filter((p) => p.instrumentType === instrumentTypeFilter)
    }
    if (instrumentSearch) {
      const query = instrumentSearch.toLowerCase()
      result = result.filter((p) =>
        p.instrumentId.toLowerCase().includes(query) ||
        (p.displayName ?? '').toLowerCase().includes(query),
      )
    }
    return result
  }, [positions, instrumentTypeFilter, instrumentSearch])

  const { groups: strategyGroups, ungrouped: ungroupedPositions } = useMemo(
    () => buildStrategyGroups(filteredPositions, positionRisk),
    [filteredPositions, positionRisk],
  )

  const sortedPositions = useMemo(() => {
    if (!sortField || !hasRisk) return ungroupedPositions
    return [...ungroupedPositions].sort((a, b) => {
      const riskA = riskByInstrument.get(a.instrumentId)
      const riskB = riskByInstrument.get(b.instrumentId)
      const valA = riskValue(riskA, sortField)
      const valB = riskValue(riskB, sortField)
      return sortDir === 'desc' ? valB - valA : valA - valB
    })
  }, [ungroupedPositions, sortField, sortDir, hasRisk, riskByInstrument])

  const totalPages = Math.ceil(sortedPositions.length / PAGE_SIZE)
  const showPagination = totalPages > 1
  const paginatedPositions = useMemo(() => {
    const start = (currentPage - 1) * PAGE_SIZE
    return sortedPositions.slice(start, start + PAGE_SIZE)
  }, [sortedPositions, currentPage])

  const handleInstrumentTypeFilter = (value: string) => {
    setInstrumentTypeFilter(value)
    setCurrentPage(1)
  }

  const handleInstrumentSearch = (value: string) => {
    setInstrumentSearch(value)
    setCurrentPage(1)
  }

  if (positions.length === 0) {
    return (
      <Card>
        <EmptyState
          icon={<Inbox className="h-10 w-10" />}
          title="No positions to display."
        />
      </Card>
    )
  }

  const totalMarketValue = positions.reduce(
    (sum, pos) => sum + Number(pos.marketValue.amount),
    0,
  )
  const totalPnl = positions.reduce(
    (sum, pos) => sum + Number(pos.unrealizedPnl.amount),
    0,
  )
  const currency = positions[0].marketValue.currency

  const totalDelta = hasRisk
    ? positionRisk.reduce((sum, r) => sum + (r.delta != null ? Number(r.delta) : 0), 0)
    : null
  const totalVar = hasRisk
    ? positionRisk.reduce((sum, r) => sum + Number(r.varContribution), 0)
    : null

  const handleSort = (field: SortField) => {
    if (sortField === field) {
      setSortDir((prev) => (prev === 'desc' ? 'asc' : 'desc'))
    } else {
      setSortField(field)
      setSortDir('desc')
    }
  }

  const sortIcon = (field: SortField) => {
    if (sortField === field) {
      return sortDir === 'desc'
        ? <ChevronDown className="inline h-3 w-3" />
        : <ChevronUp className="inline h-3 w-3" />
    }
    return <ChevronDown className="inline h-3 w-3 opacity-0 group-hover/sort:opacity-40 transition-opacity" />
  }

  const BOOK_COLUMN: ColumnDef = { key: 'book', label: 'Book', align: 'left' }
  const allPositionCols = showBookColumn
    ? [BOOK_COLUMN, ...POSITION_COLUMNS]
    : POSITION_COLUMNS
  const visiblePositionCols = allPositionCols.filter((c) => isColumnVisible(c.key))
  const positionColCount = visiblePositionCols.length
  const riskColCount = 4

  const handleExportCsv = () => {
    const headers = visiblePositionCols.map((c) => c.label)
    if (hasRisk) headers.push('Delta', 'Gamma', 'Vega', 'VaR Contrib %')

    const rows = sortedPositions.map((pos) => {
      const risk = riskByInstrument.get(pos.instrumentId)
      const cellValues: Record<string, string> = {
        book: pos.bookId,
        instrument: pos.instrumentId,
        displayName: pos.displayName || '',
        instrumentType: pos.instrumentType || '',
        assetClass: pos.assetClass,
        quantity: pos.quantity,
        avgCost: pos.averageCost.amount,
        marketPrice: pos.marketPrice.amount,
        marketValue: pos.marketValue.amount,
        unrealizedPnl: pos.unrealizedPnl.amount,
        realizedPnl: pos.realizedPnl?.amount ?? '0',
      }
      const row = visiblePositionCols.map((c) => cellValues[c.key])
      if (hasRisk) {
        row.push(
          risk?.delta ?? '',
          risk?.gamma ?? '',
          risk?.vega ?? '',
          risk ? `${risk.percentageOfTotal}%` : '',
        )
      }
      return row
    })

    exportToCsv('positions.csv', headers, rows)
  }

  return (
    <div>
      {connected !== undefined && (
        <div data-testid="connection-status" aria-live="polite" className="mb-3 text-sm flex items-center gap-1.5">
          {connected ? (
            <>
              <Wifi className="h-4 w-4 text-green-600" />
              <span className="text-green-600 font-medium">Live</span>
            </>
          ) : (
            <>
              <WifiOff className="h-4 w-4 text-red-600" />
              <span className="text-red-600 font-medium">Disconnected</span>
              {lastConnectedAt && (
                <span data-testid="stale-timestamp" className="text-slate-500 ml-1">
                  as of {lastConnectedAt.toLocaleTimeString()}
                </span>
              )}
            </>
          )}
        </div>
      )}

      <div data-testid="book-summary" className={`grid gap-3 mb-4 ${hasRisk ? 'grid-cols-5' : 'grid-cols-3'}`}>
        <Card>
          <div className="text-center -my-1">
            <div className="text-xs text-slate-500">Positions</div>
            <div className="text-lg font-bold text-slate-800 dark:text-slate-100">{positions.length}</div>
          </div>
        </Card>
        <Card>
          <div className="text-center -my-1">
            <div className="text-xs text-slate-500">Market Value</div>
            <div className="text-lg font-bold text-slate-800 dark:text-slate-100" title={formatMoney(String(totalMarketValue), currency)}>
              {formatCompactCurrency(totalMarketValue)}
            </div>
          </div>
        </Card>
        <Card>
          <div className="text-center -my-1">
            <div className="text-xs text-slate-500">Unrealized P&amp;L</div>
            <div className={`text-lg font-bold ${pnlColorClass(String(totalPnl))}`} title={formatMoney(String(totalPnl), currency)}>
              {formatCompactCurrency(totalPnl)}
            </div>
          </div>
        </Card>
        {hasRisk && totalDelta != null && (
          <Card>
            <div data-testid="summary-book-delta" className="text-center -my-1">
              <div className="text-xs text-slate-500">Book Delta</div>
              <div className="text-lg font-bold text-slate-800 dark:text-slate-100">
                {formatCompactCurrency(totalDelta)}
              </div>
            </div>
          </Card>
        )}
        {hasRisk && totalVar != null && (
          <Card>
            <div data-testid="summary-book-var" className="text-center -my-1">
              <div className="text-xs text-slate-500">Book VaR</div>
              <div className="text-lg font-bold text-slate-800 dark:text-slate-100">
                {formatCompactCurrency(totalVar)}
              </div>
            </div>
          </Card>
        )}
      </div>

      <div className="flex items-center gap-3 mb-3">
        <input
          data-testid="instrument-search"
          type="text"
          placeholder="Search instrument…"
          value={instrumentSearch}
          onChange={(e) => handleInstrumentSearch(e.target.value)}
          aria-label="Search instruments"
          className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500 w-48"
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
      </div>

      <div className="flex justify-end gap-2 mb-2">
        <button
          data-testid="csv-export-button"
          onClick={handleExportCsv}
          className="inline-flex items-center gap-1.5 px-2.5 py-1.5 text-sm font-medium text-slate-600 dark:text-slate-400 border border-slate-300 dark:border-surface-600 rounded-md hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
        >
          <Download className="h-4 w-4" />
          Export CSV
        </button>
        <div ref={settingsRef} className="relative">
          <button
            data-testid="column-settings-button"
            onClick={() => setSettingsOpen((v) => !v)}
            className="inline-flex items-center gap-1.5 px-2.5 py-1.5 text-sm font-medium text-slate-600 dark:text-slate-400 border border-slate-300 dark:border-surface-600 rounded-md hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
          >
            <Settings className="h-4 w-4" />
            Columns
          </button>
          {settingsOpen && (
            <div data-testid="column-settings-dropdown" className="absolute right-0 mt-1 w-48 bg-white dark:bg-surface-800 border border-slate-200 dark:border-surface-700 rounded-lg shadow-lg z-10 py-1">
              {POSITION_COLUMNS.map((col) => (
                <label
                  key={col.key}
                  className="flex items-center gap-2 px-3 py-1.5 text-sm text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-surface-700 cursor-pointer"
                >
                  <input
                    type="checkbox"
                    data-testid={`column-toggle-${col.key}`}
                    checked={isColumnVisible(col.key)}
                    onChange={() => toggleColumn(col.key)}
                    className="rounded border-slate-300"
                  />
                  {col.label}
                </label>
              ))}
            </div>
          )}
        </div>
      </div>

      <Card>
        <div className="-mx-4 -my-4 overflow-x-auto">
          <table className="min-w-full divide-y divide-slate-200 dark:divide-surface-700">
            <thead>
              {hasRisk && (
                <tr>
                  <th
                    data-testid="header-group-position"
                    colSpan={positionColCount}
                    className="px-4 py-1.5 text-left text-xs font-semibold text-slate-500 dark:text-slate-400 bg-slate-50 dark:bg-surface-800 border-b border-slate-200 dark:border-surface-700"
                  >
                    Position Details
                  </th>
                  <th
                    data-testid="header-group-risk"
                    colSpan={riskColCount}
                    className="px-4 py-1.5 text-left text-xs font-semibold text-indigo-600 dark:text-indigo-400 bg-indigo-50 dark:bg-indigo-950/30 border-b border-slate-200 dark:border-surface-700"
                  >
                    Risk Metrics
                  </th>
                </tr>
              )}
              <tr className="bg-slate-50 dark:bg-surface-800">
                {visiblePositionCols.map((col) => (
                  <th
                    key={col.key}
                    className={`px-4 py-2 text-${col.align} text-sm font-semibold text-slate-700 dark:text-slate-300`}
                  >
                    {col.label}
                  </th>
                ))}
                {hasRisk && (
                  <>
                    <th
                      data-testid="sort-delta"
                      className="px-4 py-2 text-right text-sm font-semibold text-indigo-700 dark:text-indigo-400 bg-indigo-50/50 dark:bg-indigo-950/20 cursor-pointer select-none group/sort"
                      onClick={() => handleSort('delta')}
                    >
                      Delta {sortIcon('delta')}
                    </th>
                    <th
                      data-testid="sort-gamma"
                      className="px-4 py-2 text-right text-sm font-semibold text-indigo-700 dark:text-indigo-400 bg-indigo-50/50 dark:bg-indigo-950/20 cursor-pointer select-none group/sort"
                      onClick={() => handleSort('gamma')}
                    >
                      Gamma {sortIcon('gamma')}
                    </th>
                    <th
                      data-testid="sort-vega"
                      className="px-4 py-2 text-right text-sm font-semibold text-indigo-700 dark:text-indigo-400 bg-indigo-50/50 dark:bg-indigo-950/20 cursor-pointer select-none group/sort"
                      onClick={() => handleSort('vega')}
                    >
                      Vega {sortIcon('vega')}
                    </th>
                    <th
                      data-testid="sort-var-pct"
                      className="px-4 py-2 text-right text-sm font-semibold text-indigo-700 dark:text-indigo-400 bg-indigo-50/50 dark:bg-indigo-950/20 cursor-pointer select-none group/sort"
                      onClick={() => handleSort('var-pct')}
                    >
                      VaR Contrib % {sortIcon('var-pct')}
                    </th>
                  </>
                )}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-surface-700">
              {strategyGroups.map((strategy) => (
                <StrategyGroupRow
                  key={strategy.strategyId}
                  strategy={strategy}
                  colSpan={positionColCount + (hasRisk ? riskColCount : 0)}
                />
              ))}
              {sortedPositions.length === 0 && (instrumentTypeFilter || instrumentSearch) && (
                <tr>
                  <td colSpan={positionColCount + (hasRisk ? riskColCount : 0)} className="px-4 py-8 text-center text-sm text-slate-500">
                    No positions match the current filter.
                  </td>
                </tr>
              )}
              {paginatedPositions.map((pos) => {
                const risk = riskByInstrument.get(pos.instrumentId)
                const cellMap: Record<string, React.ReactNode> = {
                  book: <td key="book" data-testid={`book-${pos.instrumentId}`} className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400">{pos.bookId}</td>,
                  instrument: <td key="instrument" className="px-4 py-2 text-sm font-medium">{pos.instrumentId}</td>,
                  displayName: <td key="displayName" className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400">{pos.displayName || '—'}</td>,
                  instrumentType: <td key="instrumentType" className="px-4 py-2 text-sm">{pos.instrumentType ? <InstrumentTypeBadge instrumentType={pos.instrumentType} /> : '—'}</td>,
                  assetClass: <td key="assetClass" className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400">{pos.assetClass}</td>,
                  quantity: <td key="quantity" className="px-4 py-2 text-sm text-right">{formatQuantity(pos.quantity)}</td>,
                  avgCost: <td key="avgCost" className="px-4 py-2 text-sm text-right">{formatMoney(pos.averageCost.amount, pos.averageCost.currency)}</td>,
                  marketPrice: <td key="marketPrice" className={`px-4 py-2 text-sm text-right ${reconnecting ? 'opacity-60' : ''}`}>{formatMoney(pos.marketPrice.amount, pos.marketPrice.currency)}</td>,
                  marketValue: <td key="marketValue" className={`px-4 py-2 text-sm text-right ${reconnecting ? 'opacity-60' : ''}`}>{formatMoney(pos.marketValue.amount, pos.marketValue.currency)}</td>,
                  unrealizedPnl: (
                    <td
                      key="unrealizedPnl"
                      data-testid={`pnl-${pos.instrumentId}`}
                      className={`px-4 py-2 text-sm text-right ${pnlColorClass(pos.unrealizedPnl.amount)} ${reconnecting ? 'opacity-60' : ''}`}
                    >
                      {formatMoney(pos.unrealizedPnl.amount, pos.unrealizedPnl.currency)}
                    </td>
                  ),
                  realizedPnl: (
                    <td
                      key="realizedPnl"
                      className={`px-4 py-2 text-sm text-right ${pnlColorClass(pos.realizedPnl?.amount ?? '0')}`}
                    >
                      {formatMoney(pos.realizedPnl?.amount ?? '0', pos.realizedPnl?.currency ?? pos.unrealizedPnl.currency)}
                    </td>
                  ),
                }
                return (
                  <tr key={pos.instrumentId} data-testid={`position-row-${pos.instrumentId}`} className="hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors">
                    {visiblePositionCols.map((col) => cellMap[col.key])}
                    {hasRisk && (
                      <>
                        <td
                          data-testid={`delta-${pos.instrumentId}`}
                          className="px-4 py-2 text-sm text-right bg-indigo-50/30 dark:bg-indigo-950/10"
                        >
                          {risk?.delta != null ? formatNum(risk.delta) : 'N/A'}
                        </td>
                        <td
                          data-testid={`gamma-${pos.instrumentId}`}
                          className="px-4 py-2 text-sm text-right bg-indigo-50/30 dark:bg-indigo-950/10"
                        >
                          {risk?.gamma != null ? formatNum(risk.gamma) : 'N/A'}
                        </td>
                        <td
                          data-testid={`vega-${pos.instrumentId}`}
                          className="px-4 py-2 text-sm text-right bg-indigo-50/30 dark:bg-indigo-950/10"
                        >
                          {risk?.vega != null ? formatNum(risk.vega) : 'N/A'}
                        </td>
                        <td
                          data-testid={`var-pct-${pos.instrumentId}`}
                          className="px-4 py-2 text-sm text-right font-medium bg-indigo-50/30 dark:bg-indigo-950/10"
                        >
                          {risk ? `${formatNum(risk.percentageOfTotal)}%` : 'N/A'}
                        </td>
                      </>
                    )}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Card>

      {showPagination && (
        <div data-testid="pagination-controls" className="flex items-center justify-center gap-3 mt-3">
          <button
            data-testid="pagination-prev"
            disabled={currentPage === 1}
            onClick={() => setCurrentPage((p) => p - 1)}
            className="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium rounded-md border border-slate-300 dark:border-surface-600 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-surface-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            <ChevronLeft className="h-4 w-4" />
            Previous
          </button>
          <span data-testid="pagination-info" className="text-sm text-slate-600 dark:text-slate-400">
            Page {currentPage} of {totalPages}
          </span>
          <button
            data-testid="pagination-next"
            disabled={currentPage === totalPages}
            onClick={() => setCurrentPage((p) => p + 1)}
            className="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium rounded-md border border-slate-300 dark:border-surface-600 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-surface-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            Next
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      )}
    </div>
  )
}
