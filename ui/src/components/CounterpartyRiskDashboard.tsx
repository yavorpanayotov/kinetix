import { useEffect, useMemo, useState } from 'react'
import { Users, AlertTriangle, RefreshCw, Activity, ArrowUp, ArrowDown, ArrowRight, Search, ShieldAlert } from 'lucide-react'
import { useCounterpartyRisk } from '../hooks/useCounterpartyRisk'
import type { CounterpartyExposureDto, ExposureAtTenorDto } from '../api/counterpartyRisk'
import { fetchSaCcr } from '../api/saCcr'
import type { SaCcrSummaryDto } from '../types'
import { formatCurrency } from '../utils/format'
import { SectionHeading, Spinner } from './ui'
import { SaCcrPanel } from './SaCcrPanel'
import { BlockTradesDialog } from './BlockTradesDialog'

type SortColumn = 'counterpartyId' | 'currentNetExposure' | 'peakPfe' | 'cva' | 'wwr'
type SortDirection = 'asc' | 'desc'

interface SortableHeaderProps {
  column: SortColumn
  label: string
  align: 'left' | 'right' | 'center'
  sortColumn: SortColumn
  sortDirection: SortDirection
  onSort: (column: SortColumn) => void
  /**
   * Extra utility classes applied to the `<th>`. Used to add light vertical
   * separators between column groups (plan §5.4 — decorative grouping via
   * borders, not colour).
   */
  className?: string
}

function SortableHeader({ column, label, align, sortColumn, sortDirection, onSort, className }: SortableHeaderProps) {
  const isActive = sortColumn === column
  const justify = align === 'right' ? 'justify-end' : align === 'center' ? 'justify-center' : 'justify-start'
  return (
    <th
      data-testid={`sort-header-${column}`}
      onClick={() => onSort(column)}
      className={`px-4 py-2.5 text-${align} text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wider cursor-pointer select-none hover:text-slate-700 dark:hover:text-slate-200${className ? ` ${className}` : ''}`}
      aria-sort={isActive ? (sortDirection === 'asc' ? 'ascending' : 'descending') : 'none'}
    >
      <span className={`inline-flex items-center gap-1 ${justify}`}>
        {label}
        {isActive && (sortDirection === 'asc'
          ? <ArrowUp data-testid={`sort-indicator-${column}-asc`} className="h-3 w-3" />
          : <ArrowDown data-testid={`sort-indicator-${column}-desc`} className="h-3 w-3" />)}
      </span>
    </th>
  )
}

// PFE methodology label (trader-review P2 #26). A bare "Peak PFE $7.2M" is
// uninterpretable — a credit officer needs to know the method, the confidence
// level and the horizon at which the peak occurs. Kinetix computes PFE with a
// Cholesky-based Monte Carlo engine at the 95th percentile
// (risk-engine/src/kinetix_risk/credit_exposure.py), so the method/confidence
// are static facts of the model. The horizon is data-derived: it is the tenor
// at which the 95th-percentile profile peaks. With no profile we fall back to
// the shortest standard horizon ("1Y").
const PFE_METHOD = 'Monte Carlo'
const PFE_CONFIDENCE_PCT = 95

interface PfeMethodology {
  /** Compact machine token, e.g. "MC_95_1Y". */
  token: string
  /** Human-readable label, e.g. "Monte Carlo · 95% · 1Y". */
  label: string
  horizon: string
}

function pfeMethodology(profile: ExposureAtTenorDto[]): PfeMethodology {
  const valid = profile.filter((p) => Number.isFinite(p.pfe95))
  let horizon = '1Y'
  if (valid.length > 0) {
    const peak = valid.reduce((max, p) => (p.pfe95 > max.pfe95 ? p : max), valid[0])
    if (peak.tenor) horizon = peak.tenor
  }
  return {
    token: `MC_${PFE_CONFIDENCE_PCT}_${horizon}`,
    label: `${PFE_METHOD} · ${PFE_CONFIDENCE_PCT}% · ${horizon}`,
    horizon,
  }
}

// Top-decile threshold: an exposure is flagged "high" if it is >= the 90th
// percentile of the universe. Below 10 counterparties the sample is too small
// to compute a meaningful percentile, so we fall back to disabling the flag.
function topDecileThreshold(exposures: CounterpartyExposureDto[]): number | null {
  if (exposures.length < 10) return null
  const sorted = exposures.map((e) => e.currentNetExposure).sort((a, b) => a - b)
  const idx = Math.floor(sorted.length * 0.9)
  return sorted[idx] ?? null
}

// ---------------------------------------------------------------------------
// PFE profile chart (SVG, no external chart library)
// ---------------------------------------------------------------------------

interface PfeChartProps {
  profile: ExposureAtTenorDto[]
}

function PfeChart({ profile }: PfeChartProps) {
  // Filter out any rows where either required numeric field is not a finite
  // number. This guards against partially-populated API responses where a
  // tenor entry is missing expectedExposure or pfe95 — without this filter,
  // undefined values propagate through the coordinate calculations and produce
  // NaN in SVG polyline points, which crashes real browser SVG renderers with:
  //   Cannot read properties of undefined (reading 'toFixed')
  const validProfile = profile.filter(
    (p) => Number.isFinite(p.pfe95) && Number.isFinite(p.expectedExposure),
  )

  if (validProfile.length === 0) {
    return (
      <div
        data-testid="pfe-chart-empty"
        className="flex items-center justify-center h-40 text-sm text-slate-500 dark:text-slate-400"
      >
        No PFE profile available. Run PFE computation to generate.
      </div>
    )
  }

  const PADDING = { top: 24, right: 16, bottom: 32, left: 64 }
  const CHART_HEIGHT = 180
  const PLOT_WIDTH = 480

  const maxValue = Math.max(...validProfile.flatMap((p) => [p.pfe95, p.expectedExposure])) * 1.1 || 1

  const toY = (value: number) =>
    PADDING.top + (1 - value / maxValue) * (CHART_HEIGHT - PADDING.top - PADDING.bottom)

  const xStep = (PLOT_WIDTH - PADDING.left - PADDING.right) / Math.max(validProfile.length - 1, 1)

  const toX = (i: number) => PADDING.left + i * xStep

  const pfe95Points = validProfile.map((p, i) => `${toX(i)},${toY(p.pfe95)}`).join(' ')
  const eePoints = validProfile.map((p, i) => `${toX(i)},${toY(p.expectedExposure)}`).join(' ')

  const gridLines = [0, 0.25, 0.5, 0.75, 1].map((frac) => frac * maxValue)

  return (
    <svg
      data-testid="pfe-chart"
      width="100%"
      viewBox={`0 0 ${PLOT_WIDTH} ${CHART_HEIGHT}`}
      className="overflow-visible"
    >
      {/* Grid lines */}
      {gridLines.map((v, i) => {
        const y = toY(v)
        return (
          <g key={i}>
            <line
              x1={PADDING.left}
              y1={y}
              x2={PLOT_WIDTH - PADDING.right}
              y2={y}
              stroke="#334155"
              strokeDasharray="4 2"
            />
            <text x={PADDING.left - 6} y={y + 4} textAnchor="end" fill="#94a3b8" fontSize={9}>
              {(v / 1_000_000).toFixed(1)}M
            </text>
          </g>
        )
      })}

      {/* X-axis labels */}
      {validProfile.map((p, i) => (
        <text
          key={i}
          x={toX(i)}
          y={CHART_HEIGHT - 4}
          textAnchor="middle"
          fill="#94a3b8"
          fontSize={9}
        >
          {p.tenor}
        </text>
      ))}

      {/* EE area fill */}
      <polyline
        points={eePoints}
        fill="none"
        stroke="#6366f1"
        strokeWidth={2}
        strokeLinejoin="round"
        strokeDasharray="5 3"
      />

      {/* PFE 95 line */}
      <polyline
        points={pfe95Points}
        fill="none"
        stroke="#f59e0b"
        strokeWidth={2}
        strokeLinejoin="round"
      />

      {/* Dots on PFE 95 */}
      {validProfile.map((p, i) => (
        <circle key={i} cx={toX(i)} cy={toY(p.pfe95)} r={3} fill="#f59e0b" />
      ))}

      {/* Legend */}
      <g>
        <line x1={PADDING.left} y1={10} x2={PADDING.left + 14} y2={10} stroke="#f59e0b" strokeWidth={2} />
        <text x={PADDING.left + 18} y={14} fill="#94a3b8" fontSize={9}>PFE 95</text>
        <line x1={PADDING.left + 60} y1={10} x2={PADDING.left + 74} y2={10} stroke="#6366f1" strokeWidth={2} strokeDasharray="5 3" />
        <text x={PADDING.left + 78} y={14} fill="#94a3b8" fontSize={9}>EE</text>
      </g>
    </svg>
  )
}

// ---------------------------------------------------------------------------
// Counterparty list row
// ---------------------------------------------------------------------------

interface CounterpartyRowProps {
  exposure: CounterpartyExposureDto
  isSelected: boolean
  highThreshold: number | null
  onSelect: () => void
  /** Optional cross-tab jump to the Trades blotter filtered to this counterparty (plan §2.4). */
  onJumpToTrades?: (counterpartyId: string) => void
  /**
   * Opens the "block new trades / open remediation ticket" workflow for a
   * counterparty whose agreement has expired (trader-review P2 #27). Only
   * invoked from the CTA rendered on expired rows.
   */
  onBlockTrades: (counterpartyId: string) => void
}

function CounterpartyRow({ exposure, isSelected, highThreshold, onSelect, onJumpToTrades, onBlockTrades }: CounterpartyRowProps) {
  const hasHighExposure = highThreshold !== null && exposure.currentNetExposure >= highThreshold
  const hasCva = exposure.cva !== null
  const isAgreementExpired = exposure.agreementStatus === 'EXPIRED'

  return (
    <tr
      data-testid={`counterparty-row-${exposure.counterpartyId}`}
      onClick={onSelect}
      className={`cursor-pointer border-b border-slate-200 dark:border-slate-700 transition-colors ${
        isSelected
          ? 'bg-indigo-900/30'
          : 'hover:bg-slate-100 dark:hover:bg-slate-700/40'
      }`}
    >
      <td className="px-4 py-2.5 text-sm font-mono text-slate-700 dark:text-slate-200">
        <div className="flex items-center gap-2">
          {hasHighExposure && (
            <AlertTriangle
              data-testid={`wwf-flag-${exposure.counterpartyId}`}
              className="h-3.5 w-3.5 text-amber-400 flex-shrink-0"
              aria-label="High exposure"
            />
          )}
          {exposure.counterpartyId}
          {isAgreementExpired && (
            <span
              data-testid={`agreement-expired-pill-${exposure.counterpartyId}`}
              aria-label="ISDA agreement expired"
              title="ISDA / netting agreement has expired"
              className="inline-flex items-center gap-1 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider rounded bg-red-900/40 text-red-300 border border-red-500/40"
            >
              <AlertTriangle className="h-2.5 w-2.5" />
              Agreement Expired
            </span>
          )}
          {isAgreementExpired && (
            <button
              type="button"
              data-testid={`block-trades-cta-${exposure.counterpartyId}`}
              onClick={(e) => {
                e.stopPropagation()
                onBlockTrades(exposure.counterpartyId)
              }}
              title={`Block new trades with ${exposure.counterpartyId} and open a remediation ticket`}
              aria-label={`Block new trades with ${exposure.counterpartyId} and open a remediation ticket`}
              className="inline-flex items-center gap-1 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider rounded bg-red-600 hover:bg-red-500 text-white transition-colors"
            >
              <ShieldAlert className="h-2.5 w-2.5" />
              Block new trades
            </button>
          )}
        </div>
      </td>
      <td className="px-4 py-2.5 text-sm text-right font-mono text-slate-700 dark:text-slate-200">
        {formatCurrency(exposure.currentNetExposure)}
      </td>
      {/*
       * Peak PFE marks the start of the credit-risk column group (Peak PFE
       * + CVA). The left border is a decorative-but-meaningful separator —
       * see plan §5.4: column identity is signalled by spacing / borders,
       * not by tint.
       */}
      <td className="px-4 py-2.5 text-sm text-right font-mono text-slate-700 dark:text-slate-200 border-l border-slate-200 dark:border-slate-700">
        {formatCurrency(exposure.peakPfe)}
      </td>
      <td className="px-4 py-2.5 text-sm text-right font-mono text-slate-700 dark:text-slate-200">
        {hasCva ? (
          <span className={exposure.cvaEstimated ? 'text-slate-500 dark:text-slate-400 italic' : ''}>
            {formatCurrency(exposure.cva!)}
            {exposure.cvaEstimated && ' *'}
          </span>
        ) : (
          <span className="text-slate-400 dark:text-slate-500">—</span>
        )}
      </td>
      <td className="px-4 py-2.5 text-sm text-center">
        <div className="flex items-center justify-center gap-2">
          {hasHighExposure ? (
            <span
              data-testid={`wwf-badge-${exposure.counterpartyId}`}
              className="inline-flex items-center gap-1 px-1.5 py-0.5 bg-amber-900/40 text-amber-400 text-xs rounded"
            >
              <AlertTriangle className="h-3 w-3" />
              High
            </span>
          ) : (
            <span className="text-slate-400 dark:text-slate-500 text-xs">Normal</span>
          )}
          {onJumpToTrades && (
            <button
              type="button"
              data-testid={`jump-to-trades-${exposure.counterpartyId}`}
              onClick={(e) => {
                e.stopPropagation()
                onJumpToTrades(exposure.counterpartyId)
              }}
              title={`View trades for ${exposure.counterpartyId}`}
              aria-label={`View trades for ${exposure.counterpartyId}`}
              className="inline-flex items-center gap-1 px-1.5 py-0.5 text-xs font-medium text-indigo-300 hover:text-indigo-200 hover:bg-indigo-900/30 rounded transition-colors"
            >
              <ArrowRight className="h-3 w-3" />
              Trades
            </button>
          )}
        </div>
      </td>
    </tr>
  )
}

// ---------------------------------------------------------------------------
// Detail panel for a selected counterparty
// ---------------------------------------------------------------------------

interface DetailPanelProps {
  exposure: CounterpartyExposureDto
  computing: boolean
  onComputePFE: () => void
  onComputeCVA: () => void
}

function DetailPanel({ exposure, computing, onComputePFE, onComputeCVA }: DetailPanelProps) {
  return (
    <div data-testid="counterparty-detail-panel" className="space-y-4">
      {/* Header */}
      <SectionHeading
        right={
          <>
            <button
              data-testid="compute-pfe-button"
              onClick={onComputePFE}
              disabled={computing}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded-md transition-colors"
            >
              {computing ? <Spinner size="sm" /> : <Activity className="h-3.5 w-3.5" />}
              Compute PFE
            </button>
            <button
              data-testid="compute-cva-button"
              onClick={onComputeCVA}
              disabled={computing || exposure.pfeProfile.length === 0}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium bg-amber-600 hover:bg-amber-500 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded-md transition-colors"
            >
              {computing ? <Spinner size="sm" /> : <Activity className="h-3.5 w-3.5" />}
              Compute CVA
            </button>
          </>
        }
      >
        {exposure.counterpartyId}
      </SectionHeading>

      {/* Metrics */}
      <div className="grid grid-cols-3 gap-3">
        <div className="rounded-lg bg-white dark:bg-slate-800 p-3">
          <div className="text-xs text-slate-500 dark:text-slate-400 mb-1">Net Exposure</div>
          <div
            data-testid="detail-net-exposure"
            className="text-lg font-mono font-semibold text-slate-900 dark:text-slate-100"
          >
            {formatCurrency(exposure.currentNetExposure)}
          </div>
          <div className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{exposure.currency}</div>
        </div>
        <div className="rounded-lg bg-white dark:bg-slate-800 p-3">
          <div className="text-xs text-slate-500 dark:text-slate-400 mb-1">Peak PFE</div>
          <div
            data-testid="detail-peak-pfe"
            className="text-lg font-mono font-semibold text-slate-900 dark:text-slate-100"
          >
            {formatCurrency(exposure.peakPfe)}
          </div>
          <div
            data-testid="pfe-methodology"
            title={`${pfeMethodology(exposure.pfeProfile).label} (potential future exposure)`}
            className="text-xs text-slate-400 dark:text-slate-500 mt-0.5"
          >
            <span className="font-mono">{pfeMethodology(exposure.pfeProfile).token}</span>
            {' · '}
            {pfeMethodology(exposure.pfeProfile).label}
          </div>
        </div>
        <div className="rounded-lg bg-white dark:bg-slate-800 p-3">
          <div className="text-xs text-slate-500 dark:text-slate-400 mb-1">CVA</div>
          <div
            data-testid="detail-cva"
            className="text-lg font-mono font-semibold text-slate-900 dark:text-slate-100"
          >
            {exposure.cva !== null ? (
              <>
                {formatCurrency(exposure.cva)}
                {exposure.cvaEstimated && (
                  <span className="text-xs text-slate-500 dark:text-slate-400 ml-1">(est.)</span>
                )}
              </>
            ) : (
              <span className="text-slate-400 dark:text-slate-500 text-base">Not computed</span>
            )}
          </div>
          <div className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">Credit Valuation Adj.</div>
        </div>
      </div>

      {/* PFE Chart */}
      <div className="rounded-lg bg-white dark:bg-slate-800 p-4">
        <h4 className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-3">PFE Profile</h4>
        <PfeChart profile={exposure.pfeProfile} />
      </div>

      {/* Calculation timestamp */}
      <div className="text-xs text-slate-400 dark:text-slate-500">
        Last calculated:{' '}
        {new Date(exposure.calculatedAt).toLocaleString(undefined, {
          dateStyle: 'medium',
          timeStyle: 'short',
        })}
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Main dashboard
// ---------------------------------------------------------------------------

interface CounterpartyRiskDashboardProps {
  /**
   * Cross-tab jump (plan §2.4): switch to the Trades tab with the blotter
   * pre-filtered to the chosen counterparty. Optional — omit for read-only
   * embeds.
   */
  onJumpToTrades?: (counterpartyId: string) => void
}

export function CounterpartyRiskDashboard({ onJumpToTrades }: CounterpartyRiskDashboardProps = {}) {
  const {
    exposures,
    selected,
    loading,
    computing,
    error,
    selectCounterparty,
    computePFE,
    computeCVA,
    refresh,
  } = useCounterpartyRisk()

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [saCcrResult, setSaCcrResult] = useState<SaCcrSummaryDto | null>(null)
  const [saCcrLoading, setSaCcrLoading] = useState(false)
  const [saCcrError, setSaCcrError] = useState<string | null>(null)
  const [prevSaCcrId, setPrevSaCcrId] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [sortColumn, setSortColumn] = useState<SortColumn>('currentNetExposure')
  const [sortDirection, setSortDirection] = useState<SortDirection>('desc')
  const [blockTradesFor, setBlockTradesFor] = useState<string | null>(null)
  // Hidden by default: a long tail of $0.00/$0.00 rows drowns the handful of
  // counterparties with live exposure (UX review).
  const [showZeroExposure, setShowZeroExposure] = useState(false)

  const highThreshold = useMemo(() => topDecileThreshold(exposures), [exposures])

  const zeroExposureCount = useMemo(
    () => exposures.filter((e) => e.currentNetExposure === 0 && e.peakPfe === 0).length,
    [exposures],
  )

  const displayedExposures = useMemo(() => {
    const trimmed = searchQuery.trim().toLowerCase()
    const nonZero = showZeroExposure
      ? exposures
      : exposures.filter((e) => e.currentNetExposure !== 0 || e.peakPfe !== 0)
    const filtered = trimmed.length === 0
      ? nonZero
      : nonZero.filter((e) => e.counterpartyId.toLowerCase().includes(trimmed))

    const sorted = [...filtered].sort((a, b) => {
      let cmp: number
      switch (sortColumn) {
        case 'counterpartyId':
          cmp = a.counterpartyId.localeCompare(b.counterpartyId)
          break
        case 'currentNetExposure':
          cmp = a.currentNetExposure - b.currentNetExposure
          break
        case 'peakPfe':
          cmp = a.peakPfe - b.peakPfe
          break
        case 'cva':
          cmp = (a.cva ?? -Infinity) - (b.cva ?? -Infinity)
          break
        case 'wwr': {
          const aHigh = highThreshold !== null && a.currentNetExposure >= highThreshold
          const bHigh = highThreshold !== null && b.currentNetExposure >= highThreshold
          cmp = (aHigh === bHigh) ? 0 : aHigh ? 1 : -1
          break
        }
      }
      return sortDirection === 'asc' ? cmp : -cmp
    })
    return sorted
  }, [exposures, searchQuery, sortColumn, sortDirection, highThreshold, showZeroExposure])

  const handleSort = (column: SortColumn) => {
    if (sortColumn === column) {
      setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc')
    } else {
      setSortColumn(column)
      setSortDirection(column === 'counterpartyId' ? 'asc' : 'desc')
    }
  }

  if (selectedId !== prevSaCcrId) {
    setPrevSaCcrId(selectedId)
    if (!selectedId) {
      setSaCcrResult(null)
      setSaCcrLoading(false)
      setSaCcrError(null)
    } else {
      setSaCcrLoading(true)
      setSaCcrError(null)
    }
  }

  useEffect(() => {
    if (!selectedId) return
    let cancelled = false
    fetchSaCcr(selectedId)
      .then((data) => { if (!cancelled) setSaCcrResult(data) })
      .catch((err: unknown) => { if (!cancelled) setSaCcrError(err instanceof Error ? err.message : String(err)) })
      .finally(() => { if (!cancelled) setSaCcrLoading(false) })
    return () => { cancelled = true }
  }, [selectedId])

  const handleSelect = (id: string) => {
    setSelectedId(id)
    void selectCounterparty(id)
  }

  // Pre-select the largest exposure on first load so the detail panel opens
  // with content instead of a "Select a counterparty" void (UX review).
  useEffect(() => {
    if (selectedId !== null || exposures.length === 0) return
    const top = exposures.reduce((a, b) =>
      Math.abs(a.currentNetExposure) >= Math.abs(b.currentNetExposure) ? a : b,
    )
    setSelectedId(top.counterpartyId)
    void selectCounterparty(top.counterpartyId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [exposures])

  return (
    <div data-testid="counterparty-risk-dashboard" className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Users className="h-5 w-5 text-indigo-400" />
          <SectionHeading as="h2">Counterparty Risk</SectionHeading>
          <span className="text-xs text-slate-400 dark:text-slate-500">
            {exposures.length} counterpart{exposures.length === 1 ? 'y' : 'ies'}
          </span>
        </div>
        <button
          data-testid="refresh-exposures-button"
          onClick={refresh}
          disabled={loading}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-600 rounded-md hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50 transition-colors"
          aria-label="Refresh counterparty exposures"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {/* Error */}
      {error && (
        <div
          data-testid="counterparty-error"
          className="rounded-lg bg-red-950 border border-red-800 px-4 py-3 text-sm text-red-300"
          role="alert"
        >
          {error}
        </div>
      )}

      {/* Loading */}
      {loading && exposures.length === 0 && (
        <div className="flex items-center justify-center py-12 text-slate-500 dark:text-slate-400 gap-2">
          <Spinner />
          Loading counterparty exposures...
        </div>
      )}

      {/* Empty state */}
      {!loading && exposures.length === 0 && !error && (
        <div
          data-testid="counterparty-empty-state"
          className="flex flex-col items-center justify-center py-16 text-slate-500 dark:text-slate-400 gap-2"
        >
          <Users className="h-10 w-10 text-slate-400 dark:text-slate-600" />
          <p className="text-sm">No counterparty exposures found.</p>
          <p className="text-xs text-slate-400 dark:text-slate-500">Book trades and run PFE computation to populate.</p>
        </div>
      )}

      {/* Content */}
      {exposures.length > 0 && (
        <>
        <div className="grid grid-cols-1 xl:grid-cols-5 gap-4">
          {/* Counterparty list */}
          <div className="xl:col-span-2 space-y-2">
            <div className="relative">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400 dark:text-slate-500" aria-hidden="true" />
              <input
                data-testid="counterparty-search"
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search counterparty..."
                aria-label="Search counterparty"
                className="w-full pl-8 pr-3 py-1.5 text-sm bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md text-slate-700 dark:text-slate-200 placeholder-slate-400 dark:placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-500"
              />
            </div>
            {zeroExposureCount > 0 && (
              <label
                data-testid="show-zero-exposure-toggle"
                className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400 cursor-pointer select-none"
              >
                <input
                  type="checkbox"
                  checked={showZeroExposure}
                  onChange={(e) => setShowZeroExposure(e.target.checked)}
                  className="h-3.5 w-3.5 rounded border-slate-300 dark:border-slate-600"
                />
                Show {zeroExposureCount} zero-exposure counterpart{zeroExposureCount === 1 ? 'y' : 'ies'}
              </label>
            )}
            <div className="rounded-lg bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 overflow-hidden">
              <table className="w-full" aria-label="Counterparty exposures">
                <thead>
                  <tr className="border-b border-slate-200 dark:border-slate-700">
                    <SortableHeader
                      column="counterpartyId"
                      label="Counterparty"
                      align="left"
                      sortColumn={sortColumn}
                      sortDirection={sortDirection}
                      onSort={handleSort}
                    />
                    <SortableHeader
                      column="currentNetExposure"
                      label="Net Exposure"
                      align="right"
                      sortColumn={sortColumn}
                      sortDirection={sortDirection}
                      onSort={handleSort}
                    />
                    <SortableHeader
                      column="peakPfe"
                      label="Peak PFE"
                      align="right"
                      sortColumn={sortColumn}
                      sortDirection={sortDirection}
                      onSort={handleSort}
                      // Visually group the credit-risk metrics (Peak PFE +
                      // CVA) apart from the exposure column to their left.
                      // Plan §5.4 — borders / spacing for decorative grouping.
                      className="border-l border-slate-200 dark:border-slate-700"
                    />
                    <SortableHeader
                      column="cva"
                      label="CVA"
                      align="right"
                      sortColumn={sortColumn}
                      sortDirection={sortDirection}
                      onSort={handleSort}
                    />
                    <SortableHeader
                      column="wwr"
                      label="WWR"
                      align="center"
                      sortColumn={sortColumn}
                      sortDirection={sortDirection}
                      onSort={handleSort}
                    />
                  </tr>
                </thead>
                <tbody>
                  {displayedExposures.length === 0 && searchQuery.trim().length > 0 && (
                    <tr>
                      <td
                        colSpan={5}
                        data-testid="counterparty-no-search-results"
                        className="px-4 py-6 text-center text-sm text-slate-400 dark:text-slate-500"
                      >
                        No counterparties match &quot;{searchQuery}&quot;
                      </td>
                    </tr>
                  )}
                  {displayedExposures.map((e) => (
                    <CounterpartyRow
                      key={e.counterpartyId}
                      exposure={e}
                      isSelected={selectedId === e.counterpartyId}
                      highThreshold={highThreshold}
                      onSelect={() => handleSelect(e.counterpartyId)}
                      onJumpToTrades={onJumpToTrades}
                      onBlockTrades={setBlockTradesFor}
                    />
                  ))}
                </tbody>
              </table>
              {selected?.cvaEstimated && (
                <div className="px-4 py-2 text-xs text-slate-400 dark:text-slate-500 border-t border-slate-200 dark:border-slate-700">
                  * CVA marked as estimated (no CDS spread available)
                </div>
              )}
            </div>
          </div>

          {/* Detail panel */}
          <div className="xl:col-span-3">
            {selected ? (
              <div className="rounded-lg bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 p-4">
                <DetailPanel
                  exposure={selected}
                  computing={computing}
                  onComputePFE={() => void computePFE(selected.counterpartyId)}
                  onComputeCVA={() => void computeCVA(selected.counterpartyId)}
                />
              </div>
            ) : (
              <div
                data-testid="detail-panel-placeholder"
                className="rounded-lg bg-slate-50 dark:bg-slate-800/30 border border-slate-200 dark:border-slate-700/50 h-full flex items-center justify-center min-h-48 text-slate-400 dark:text-slate-500 text-sm"
              >
                Select a counterparty to view details
              </div>
            )}
          </div>
        </div>

        {selectedId && (
          <div className="mt-4">
            <SaCcrPanel result={saCcrResult} loading={saCcrLoading} error={saCcrError} />
          </div>
        )}
        </>
      )}

      {blockTradesFor && (
        <BlockTradesDialog
          counterpartyId={blockTradesFor}
          onClose={() => setBlockTradesFor(null)}
        />
      )}
    </div>
  )
}
