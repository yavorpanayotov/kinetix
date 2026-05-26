import React, { useMemo, useState } from 'react'
import { ChevronDown, ChevronUp, Download, RefreshCw } from 'lucide-react'
import type { MarketRegime, PositionRiskDto } from '../types'
import { formatMoney, formatNum } from '../utils/format'
import { formatAssetClassLabel } from '../utils/formatAssetClass'
import { exportToCsv } from '../utils/exportCsv'
import { Card, Spinner } from './ui'
import { ScenarioBadge } from './ScenarioBadge'
import { ExplainButton } from './ExplainButton'
import { AIInsightPanel } from './AIInsightPanel'
import { chat, type ChatChunk, type ChatRequest } from '../api/copilot'

type SortField =
  | 'marketValue'
  | 'delta'
  | 'gamma'
  | 'vega'
  | 'theta'
  | 'rho'
  | 'dv01'
  | 'varContribution'
  | 'esContribution'
  | 'percentageOfTotal'
type SortDirection = 'asc' | 'desc'

/**
 * DV01 (dollar value of a basis point) is a rates-only risk metric. We
 * display it for FIXED_INCOME rows and render an em-dash for every other
 * asset class so the column is always present (consistent column count
 * across rows) without misleading traders into thinking equity/FX
 * instruments carry a meaningful DV01.
 */
function isRatesInstrument(assetClass: string): boolean {
  return assetClass === 'FIXED_INCOME'
}

/** Signature of the injectable `chatFn` — mirrors `chat` in `api/copilot`. */
type ChatFn = (
  request: ChatRequest,
  options?: { signal?: AbortSignal },
) => ReadableStream<ChatChunk>

/**
 * Which inline explainer (if any) is currently open. At most one is ever
 * open for the table — opening a new one replaces the previous (plan §9.1
 * "only one panel open"). `kind: 'portfolio'` is the table-header explain;
 * `kind: 'row'` is keyed by the row's `instrumentId`.
 */
type ExplainTarget =
  | { kind: 'portfolio' }
  | { kind: 'row'; instrumentId: string }

interface PositionRiskTableProps {
  data: PositionRiskDto[]
  loading: boolean
  error?: string | null
  onRetry?: () => void
  /** Active scenario context — annotates the table header (plan §1.2). */
  activeScenario?: string | null
  /** Market regime — VaR / ES contributions carry a regime-adj badge when non-NORMAL. */
  marketRegime?: MarketRegime | null
  /**
   * Book the table belongs to — stamped into the `page_context` of any
   * inline explainer `/chat` call (plan §9.1). Optional: the explainer
   * still works without it (the model just lacks a book scope).
   */
  bookId?: string | null
  /**
   * Dependency-injection seam for the streaming `chat()` client. Tests
   * substitute a fake; production callers leave it unset and the real
   * `chat` import is used.
   */
  chatFn?: ChatFn
}

/**
 * Build the `page_context` for an inline explainer `/chat` call.
 *
 * For a per-row explainer the row's full position payload is attached so
 * the model can speak to that specific instrument; for the header
 * explainer only the portfolio-level scope is attached.
 */
function buildExplainContext(
  target: ExplainTarget,
  bookId: string | null | undefined,
  rows: PositionRiskDto[],
): Record<string, unknown> {
  const context: Record<string, unknown> = { page: 'positions' }
  if (typeof bookId === 'string' && bookId.length > 0) {
    context.book_id = bookId
  }
  if (target.kind === 'row') {
    const row = rows.find((r) => r.instrumentId === target.instrumentId)
    context.instrument_id = target.instrumentId
    if (row) context.position = row
  } else {
    context.scope = 'portfolio'
    context.position_count = rows.length
  }
  return context
}

/** Stable equality for two explain targets (used for double-click guard). */
function sameTarget(a: ExplainTarget | null, b: ExplainTarget): boolean {
  if (a === null) return false
  if (a.kind !== b.kind) return false
  if (a.kind === 'row' && b.kind === 'row') {
    return a.instrumentId === b.instrumentId
  }
  return true
}

function numericValue(row: PositionRiskDto, field: SortField, useAbsolute: boolean): number {
  const raw = field === 'dv01' ? row.dv01 : row[field]
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
  { label: 'DV01', tooltip: 'Dollar value of a 1bp parallel rates shift (USD) — FIXED_INCOME only', field: 'dv01', sortable: true },
  { label: 'VaR Contrib', field: 'varContribution', sortable: true },
  { label: 'ES Contrib', field: 'esContribution', sortable: true },
  { label: '% Total', field: 'percentageOfTotal', sortable: true },
]

export function PositionRiskTable({ data, loading, error, onRetry, activeScenario = null, marketRegime = null, bookId = null, chatFn = chat }: PositionRiskTableProps) {
  const [expanded, setExpanded] = useState(true)
  const [sortField, setSortField] = useState<SortField>('varContribution')
  const [sortDir, setSortDir] = useState<SortDirection>('desc')
  const [useAbsoluteSort, setUseAbsoluteSort] = useState(true)
  const [expandedRow, setExpandedRow] = useState<string | null>(null)

  // Inline explainer state (plan §9.1). `explainTarget` identifies which
  // explainer (header or a specific row) is open — at most one at a time.
  // `explainStream` is the live `/chat` token stream feeding the panel.
  const [explainTarget, setExplainTarget] = useState<ExplainTarget | null>(null)
  const [explainStream, setExplainStream] = useState<ReadableStream<ChatChunk> | null>(null)

  /**
   * Open an inline explainer for `target`.
   *
   * Double-click protection: a second click on the explainer whose panel is
   * already open is a no-op — neither a duplicate panel nor a duplicate
   * `/chat` request is created. Opening a *different* explainer replaces the
   * current one ("only one panel open").
   */
  const handleExplain = (target: ExplainTarget) => {
    if (sameTarget(explainTarget, target)) return
    const message =
      target.kind === 'row'
        ? `Explain the risk contribution of position ${target.instrumentId}.`
        : 'Explain the portfolio risk in this position table.'
    const stream = chatFn({
      message,
      page_context: buildExplainContext(target, bookId, data),
    })
    setExplainTarget(target)
    setExplainStream(stream)
  }

  const closeExplain = () => {
    setExplainTarget(null)
    setExplainStream(null)
  }

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
      isRatesInstrument(row.assetClass) ? (row.dv01 ?? '') : '',
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
            <div className="flex items-center gap-2">
              <ExplainButton
                data-testid="explain-positions-portfolio"
                label="Explain"
                ariaLabel="Explain portfolio position risk"
                onClick={() => handleExplain({ kind: 'portfolio' })}
                className="px-2 py-1 text-xs"
              />
              <button
                data-testid="risk-csv-export"
                onClick={handleExportCsv}
                className="inline-flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 border border-slate-300 rounded hover:bg-slate-50 transition-colors"
              >
                <Download className="h-3.5 w-3.5" />
                Export CSV
              </button>
            </div>
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
                  {/* Rightmost action column — per-row inline explainer (plan §9.1). */}
                  <th
                    aria-label="Explain"
                    className="w-8 py-2 pr-4 text-right"
                  />
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
                        <td
                          data-testid={`dv01-${row.instrumentId}`}
                          className="py-2 pr-3 text-right font-mono"
                        >
                          {isRatesInstrument(row.assetClass) && row.dv01 != null
                            ? formatMoney(row.dv01, 'USD')
                            : '\u2014'}
                        </td>
                        <td className="py-2 pr-3 text-right font-mono">{formatNum(row.varContribution)}</td>
                        <td className="py-2 pr-3 text-right font-mono">{formatNum(row.esContribution)}</td>
                        <td
                          data-testid={`pct-total-${row.instrumentId}`}
                          className={`py-2 pr-3 text-right font-mono font-medium ${pctColorClass(pct)}`}
                        >
                          {formatNum(row.percentageOfTotal)}%
                        </td>
                        <td
                          className="w-8 py-2 pr-4 text-right"
                          onClick={(e) => {
                            // Don't toggle the row's expand state when the
                            // explainer is clicked.
                            e.stopPropagation()
                          }}
                        >
                          <ExplainButton
                            data-testid={`explain-position-${row.instrumentId}`}
                            // Label appears only when the row is focused /
                            // selected (expanded); icon-only otherwise so
                            // the 32px action column stays compact.
                            label={isExpanded ? 'Explain' : ''}
                            ariaLabel={`Explain ${row.instrumentId} position risk`}
                            onClick={() =>
                              handleExplain({
                                kind: 'row',
                                instrumentId: row.instrumentId,
                              })
                            }
                            className="px-1.5 py-1 text-xs"
                          />
                        </td>
                      </tr>
                      {isExpanded && (
                        <tr data-testid={`position-risk-detail-${row.instrumentId}`}>
                          <td colSpan={13} className="bg-slate-50 px-4 py-3 border-b border-slate-200">
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
                              {isRatesInstrument(row.assetClass) && (
                                <div>
                                  <span className="text-slate-500">DV01</span>
                                  <p
                                    data-testid={`dv01-detail-${row.instrumentId}`}
                                    className="font-mono font-medium"
                                  >
                                    {row.dv01 != null ? formatMoney(row.dv01, 'USD') : '\u2014'}
                                  </p>
                                </div>
                              )}
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

        {explainTarget && explainStream && (
          <div data-testid="position-explain-panel" className="px-4 pb-4 pt-2">
            <AIInsightPanel
              stream={explainStream}
              title={
                explainTarget.kind === 'row'
                  ? `Explain — ${explainTarget.instrumentId}`
                  : 'Explain — Portfolio Position Risk'
              }
              onClose={closeExplain}
            />
          </div>
        )}
      </div>
    </Card>
  )
}
