import React, { useMemo, useState } from 'react'
import { ChevronDown, ChevronUp, Download, RefreshCw } from 'lucide-react'
import type { MarketRegime, PositionRiskDto } from '../types'
import { formatNum } from '../utils/format'
import { formatAssetClassLabel } from '../utils/formatAssetClass'
import { exportToCsv } from '../utils/exportCsv'
import { Card, Spinner } from './ui'
import { ScenarioBadge } from './ScenarioBadge'

type SortField =
  | 'marketValue'
  | 'delta'
  | 'gamma'
  | 'vega'
  | 'theta'
  | 'rho'
  | 'varContribution'
  | 'esContribution'
  | 'percentageOfTotal'
type SortDirection = 'asc' | 'desc'

interface PositionRiskTableProps {
  data: PositionRiskDto[]
  loading: boolean
  error?: string | null
  onRetry?: () => void
  /** Active scenario context — annotates the table header (plan §1.2). */
  activeScenario?: string | null
  /** Market regime — VaR / ES contributions carry a regime-adj badge when non-NORMAL. */
  marketRegime?: MarketRegime | null
}

function numericValue(row: PositionRiskDto, field: SortField, useAbsolute: boolean): number {
  const raw = row[field]
  if (raw == null) return -Infinity
  const num = Number(raw)
  return useAbsolute ? Math.abs(num) : num
}

function pctColorClass(pct: number): string {
  if (pct > 30) return 'text-red-600'
  if (pct > 15) return 'text-amber-600'
  return ''
}

const COLUMNS: { label: string; tooltip?: string; field: SortField; sortable: true }[] = [
  { label: 'Mkt Value', field: 'marketValue', sortable: true },
  { label: 'Delta', tooltip: '$/1% move', field: 'delta', sortable: true },
  { label: 'Gamma', field: 'gamma', sortable: true },
  { label: 'Vega', tooltip: '$/1pp vol', field: 'vega', sortable: true },
  { label: 'Theta', tooltip: '$/day', field: 'theta', sortable: true },
  { label: 'Rho', tooltip: '$/bp', field: 'rho', sortable: true },
  { label: 'VaR Contrib', field: 'varContribution', sortable: true },
  { label: 'ES Contrib', field: 'esContribution', sortable: true },
  { label: '% Total', field: 'percentageOfTotal', sortable: true },
]

export function PositionRiskTable({ data, loading, error, onRetry, activeScenario = null, marketRegime = null }: PositionRiskTableProps) {
  const [expanded, setExpanded] = useState(true)
  const [sortField, setSortField] = useState<SortField>('varContribution')
  const [sortDir, setSortDir] = useState<SortDirection>('desc')
  const [useAbsoluteSort, setUseAbsoluteSort] = useState(true)
  const [expandedRow, setExpandedRow] = useState<string | null>(null)

  const sorted = useMemo(() => {
    return [...data].sort((a, b) => {
      const valA = numericValue(a, sortField, useAbsoluteSort)
      const valB = numericValue(b, sortField, useAbsoluteSort)
      return sortDir === 'desc' ? valB - valA : valA - valB
    })
  }, [data, sortField, sortDir, useAbsoluteSort])

  const handleSort = (field: SortField) => {
    if (sortField === field) {
      setSortDir((prev) => (prev === 'desc' ? 'asc' : 'desc'))
    } else {
      setSortField(field)
      setSortDir('desc')
      setUseAbsoluteSort(field === 'varContribution')
    }
  }

  const sortIcon = (field: SortField) => {
    if (sortField !== field) return null
    return sortDir === 'desc'
      ? <ChevronDown className="inline h-3 w-3" />
      : <ChevronUp className="inline h-3 w-3" />
  }

  const handleExportCsv = () => {
    const headers = ['Instrument', 'Asset Class', ...COLUMNS.map((c) => c.label)]
    const rows = sorted.map((row) => [
      row.instrumentId,
      formatAssetClassLabel(row.assetClass),
      row.marketValue,
      row.delta ?? '',
      row.gamma ?? '',
      row.vega ?? '',
      row.theta ?? '',
      row.rho ?? '',
      row.varContribution,
      row.esContribution,
      `${row.percentageOfTotal}%`,
    ])
    exportToCsv('position-risk.csv', headers, rows)
  }

  return (
    <Card data-testid="position-risk-section">
      <div className="-mx-4 -my-4">
        <div className="flex items-center justify-between px-4 py-3">
          <div className="flex items-center gap-2">
            <button
              data-testid="position-risk-toggle"
              onClick={() => setExpanded((prev) => !prev)}
              className="flex items-center gap-2 text-sm font-semibold text-slate-700 hover:text-slate-900 transition-colors"
            >
              <span>Position Risk Breakdown</span>
              {expanded
                ? <ChevronUp className="h-4 w-4 text-slate-400" />
                : <ChevronDown className="h-4 w-4 text-slate-400" />}
            </button>
            <ScenarioBadge scenario={activeScenario} regime={marketRegime} />
          </div>
          {data.length > 0 && (
            <button
              data-testid="risk-csv-export"
              onClick={handleExportCsv}
              className="inline-flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 border border-slate-300 rounded hover:bg-slate-50 transition-colors"
            >
              <Download className="h-3.5 w-3.5" />
              Export CSV
            </button>
          )}
        </div>

        {loading && data.length === 0 && (
          <div data-testid="position-risk-loading" className="flex items-center justify-center py-8">
            <Spinner />
          </div>
        )}

        {!loading && error && (
          <div
            data-testid="position-risk-error"
            role="alert"
            className="flex items-center justify-between gap-4 text-sm text-red-600 py-6 px-4"
          >
            <span>Unable to load position risk — {error}</span>
            {onRetry && (
              <button
                data-testid="position-risk-retry"
                onClick={onRetry}
                className="flex-shrink-0 inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-red-600 border border-red-300 rounded-md hover:bg-red-50 transition-colors"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                Retry
              </button>
            )}
          </div>
        )}

        {!loading && !error && data.length === 0 && (
          <div data-testid="position-risk-empty" className="text-sm text-slate-400 py-6 text-center">
            No position risk data — Positions will appear after the next VaR calculation.
          </div>
        )}

        {data.length > 0 && expanded && (
          <div data-testid="position-risk-table" className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-sm">
              <thead>
                <tr className="text-left text-xs text-slate-500 border-b border-slate-200">
                  <th className="py-2 pr-3 pl-4">Instrument</th>
                  <th className="py-2 pr-3">Asset Class</th>
                  {COLUMNS.map((col) => (
                    <th
                      key={col.field}
                      data-testid={`sort-${col.field}`}
                      className="py-2 pr-3 text-right cursor-pointer select-none whitespace-nowrap"
                      onClick={() => handleSort(col.field)}
                      title={col.tooltip}
                    >
                      {col.label} {sortIcon(col.field)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {sorted.map((row) => {
                  const pct = Number(row.percentageOfTotal)
                  const isExpanded = expandedRow === row.instrumentId
                  return (
                    <React.Fragment key={row.instrumentId}>
                      <tr
                        data-testid={`position-risk-row-${row.instrumentId}`}
                        className={`hover:bg-slate-50 transition-colors border-b border-slate-100 cursor-pointer ${isExpanded ? 'bg-slate-50' : ''}`}
                        onClick={() => setExpandedRow(isExpanded ? null : row.instrumentId)}
                      >
                        <td className="py-2 pr-3 pl-4 font-medium">{row.instrumentId}</td>
                        <td className="py-2 pr-3 text-slate-600">{formatAssetClassLabel(row.assetClass)}</td>
                        <td className="py-2 pr-3 text-right font-mono">{formatNum(row.marketValue)}</td>
                        <td className="py-2 pr-3 text-right font-mono">
                          {row.delta != null ? formatNum(row.delta) : '\u2014'}
                        </td>
                        <td className="py-2 pr-3 text-right font-mono">
                          {row.gamma != null ? formatNum(row.gamma) : '\u2014'}
                        </td>
                        <td className="py-2 pr-3 text-right font-mono">
                          {row.vega != null ? formatNum(row.vega) : '\u2014'}
                        </td>
                        <td className="py-2 pr-3 text-right font-mono">
                          {row.theta != null ? formatNum(row.theta) : '\u2014'}
                        </td>
                        <td className="py-2 pr-3 text-right font-mono">
                          {row.rho != null ? formatNum(row.rho) : '\u2014'}
                        </td>
                        <td className="py-2 pr-3 text-right font-mono">{formatNum(row.varContribution)}</td>
                        <td className="py-2 pr-3 text-right font-mono">{formatNum(row.esContribution)}</td>
                        <td
                          data-testid={`pct-total-${row.instrumentId}`}
                          className={`py-2 pr-3 text-right font-mono font-medium ${pctColorClass(pct)}`}
                        >
                          {formatNum(row.percentageOfTotal)}%
                        </td>
                      </tr>
                      {isExpanded && (
                        <tr data-testid={`position-risk-detail-${row.instrumentId}`}>
                          <td colSpan={11} className="bg-slate-50 px-4 py-3 border-b border-slate-200">
                            <div className="grid grid-cols-4 gap-4 text-xs">
                              <div>
                                <span className="text-slate-500">Market Value</span>
                                <p className="font-mono font-medium">{formatNum(row.marketValue)}</p>
                              </div>
                              <div>
                                <span className="text-slate-500">VaR Contribution</span>
                                <p className="font-mono font-medium">{formatNum(row.varContribution)}</p>
                              </div>
                              <div>
                                <span className="text-slate-500">ES Contribution</span>
                                <p className="font-mono font-medium">{formatNum(row.esContribution)}</p>
                              </div>
                              <div>
                                <span className="text-slate-500">% of Total</span>
                                <p className="font-mono font-medium">{formatNum(row.percentageOfTotal)}%</p>
                              </div>
                              <div>
                                <span className="text-slate-500">Delta</span>
                                <p className="font-mono font-medium">{row.delta != null ? formatNum(row.delta) : '\u2014'}</p>
                              </div>
                              <div>
                                <span className="text-slate-500">Gamma</span>
                                <p className="font-mono font-medium">{row.gamma != null ? formatNum(row.gamma) : '\u2014'}</p>
                              </div>
                              <div>
                                <span className="text-slate-500">Vega</span>
                                <p className="font-mono font-medium">{row.vega != null ? formatNum(row.vega) : '\u2014'}</p>
                              </div>
                              <div>
                                <span className="text-slate-500">Theta</span>
                                <p className="font-mono font-medium">{row.theta != null ? formatNum(row.theta) : '\u2014'}</p>
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Card>
  )
}
