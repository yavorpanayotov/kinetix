import { Fragment, useEffect, useState } from 'react'
import { Info, Search, Star, RefreshCw } from 'lucide-react'
import type { ValuationJobSummaryDto, ValuationJobDetailDto } from '../types'
import { Badge, Spinner } from './ui'
import { ConfirmDialog } from './ui/ConfirmDialog'
import { JobTimeline } from './JobTimeline'
import { PhaseStepperMini } from './PhaseStepperMini'
import { ReplayPanel } from './ReplayPanel'
import { formatTimeOnly, formatDuration, formatMoney } from '../utils/format'
import { dedupeValuationJobs } from '../utils/dedupeValuationJobs'
import { useEodPromotion } from '../hooks/useEodPromotion'

interface JobHistoryTableProps {
  runs: ValuationJobSummaryDto[]
  expandedJobs: Record<string, ValuationJobDetailDto>
  loadingJobIds: Set<string>
  onSelectJob: (jobId: string) => void
  onCloseJob: (jobId: string) => void
  selectedForCompare?: Set<string>
  onToggleCompareSelection?: (jobId: string) => void
  onJobPromoted?: () => void
}

const STATUS_VARIANT: Record<string, 'success' | 'critical' | 'info' | 'neutral'> = {
  COMPLETED: 'success',
  FAILED: 'critical',
  RUNNING: 'info',
}

const TRIGGER_VARIANT: Record<string, 'info' | 'neutral' | 'warning'> = {
  ON_DEMAND: 'info',
  SCHEDULED: 'neutral',
  TRADE_EVENT: 'warning',
  PRICE_EVENT: 'warning',
}

function ElapsedDuration({ startedAt }: { startedAt: string }) {
  const [elapsedMs, setElapsedMs] = useState(0)

  useEffect(() => {
    const startMs = new Date(startedAt).getTime()
    const tick = () => setElapsedMs(Date.now() - startMs)
    tick()
    const id = setInterval(tick, 1000)
    return () => clearInterval(id)
  }, [startedAt])

  return <>{formatDuration(Math.max(0, elapsedMs))}</>
}

export function JobHistoryTable({ runs, expandedJobs, loadingJobIds, onSelectJob, onCloseJob, selectedForCompare, onToggleCompareSelection, onJobPromoted }: JobHistoryTableProps) {
  const [searchTerms, setSearchTerms] = useState<Record<string, string>>({})
  const [promoteTarget, setPromoteTarget] = useState<string | null>(null)
  const [demoteTarget, setDemoteTarget] = useState<string | null>(null)
  const [replayOpenFor, setReplayOpenFor] = useState<string | null>(null)
  const { state: promoteState, error: promoteError, promote, demote, reset: resetPromotion } = useEodPromotion()

  if (runs.length === 0) {
    return (
      <div data-testid="job-history-empty" className="text-sm text-slate-400 py-4 text-center">
        No valuation jobs yet.
      </div>
    )
  }

  return (
    <div data-testid="job-history-table">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-xs text-slate-500 border-b border-slate-200">
            {onToggleCompareSelection && (
              <th className="py-2 pr-2 w-8">
                <span className="sr-only">Select for compare</span>
              </th>
            )}
            <th className="py-2 pr-3">Job ID</th>
            <th className="py-2 pr-3">Time</th>
            <th className="py-2 pr-3">Trigger</th>
            <th className="py-2 pr-3">Status</th>
            <th className="py-2 pr-3">Duration</th>
            <th className="py-2 pr-3">
              <span className="relative inline-flex items-center gap-1 group">
                VaR <Info className="h-3 w-3 text-slate-400" />
                <span className="absolute left-1/2 -translate-x-1/2 top-full mt-1 z-10 hidden group-hover:block w-64 p-2 text-xs font-normal text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-800 rounded shadow-lg border border-slate-200 dark:border-slate-700">
                  VaR (Value at Risk) estimates the maximum potential loss over a given time period at a specific confidence level. For example, a 95% VaR of $5,000 means there is a 95% chance that losses will not exceed $5,000.
                </span>
              </span>
            </th>
            <th className="py-2 pr-3">
              <span className="relative inline-flex items-center gap-1 group">
                ES <Info className="h-3 w-3 text-slate-400" />
                <span className="absolute right-0 top-full mt-1 z-10 hidden group-hover:block w-64 p-2 text-xs font-normal text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-800 rounded shadow-lg border border-slate-200 dark:border-slate-700">
                  ES (Expected Shortfall) estimates the average loss in the worst-case scenarios beyond the VaR (Value at Risk) threshold. For example, if VaR at 95% confidence is $5,000, the ES tells you the average loss you'd expect in that worst 5% of cases.
                </span>
              </span>
            </th>
            <th className="py-2">
              <span className="relative inline-flex items-center gap-1 group">
                PV <Info className="h-3 w-3 text-slate-400" />
                <span className="absolute right-0 top-full mt-1 z-10 hidden group-hover:block w-64 p-2 text-xs font-normal text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-800 rounded shadow-lg border border-slate-200 dark:border-slate-700">
                  PV (Present Value) is the total current market value of the portfolio, calculated by summing the mark-to-market values of all positions.
                </span>
              </span>
            </th>
          </tr>
        </thead>
        <tbody>
          {dedupeValuationJobs(runs).map(({ run, count }) => {
            const isExpanded = run.jobId in expandedJobs
            const isLoading = loadingJobIds.has(run.jobId)
            const detail = expandedJobs[run.jobId]

            return (
              <Fragment key={run.jobId}>
                <tr
                  data-testid={`job-row-${run.jobId}`}
                  onClick={() => onSelectJob(run.jobId)}
                  className={`cursor-pointer hover:bg-slate-50 border-b border-slate-100 ${
                    isExpanded || isLoading ? 'bg-primary-50' : ''
                  } ${selectedForCompare?.has(run.jobId) ? 'bg-indigo-50 dark:bg-indigo-900/20' : ''} ${
                    run.runLabel === 'OFFICIAL_EOD' ? 'border-l-2 border-l-amber-400' : run.runLabel === 'SOD' ? 'border-l-2 border-l-sky-400' : ''
                  }`}
                >
                  {onToggleCompareSelection && (
                    <td className="py-2 pr-2">
                      <input
                        data-testid={`compare-select-${run.jobId}`}
                        type="checkbox"
                        checked={selectedForCompare?.has(run.jobId) ?? false}
                        disabled={run.status !== 'COMPLETED'}
                        onChange={(e) => {
                          e.stopPropagation()
                          onToggleCompareSelection(run.jobId)
                        }}
                        onClick={(e) => e.stopPropagation()}
                        className="h-3.5 w-3.5 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500 disabled:opacity-40"
                        aria-label={`Select job ${run.jobId.slice(0, 8)} for comparison`}
                      />
                    </td>
                  )}
                  <td
                    data-testid={`job-id-${run.jobId}`}
                    title={run.jobId}
                    className="py-2 pr-3 font-mono text-slate-500"
                  >
                    {run.jobId.slice(0, 8)}
                    {count > 1 && (
                      <span
                        data-testid={`dedupe-count-${run.jobId}`}
                        title={`${count} identical jobs collapsed into this row`}
                        className="ml-1.5 inline-flex items-center rounded bg-slate-200 dark:bg-slate-700 px-1 text-[10px] font-semibold text-slate-600 dark:text-slate-300"
                      >
                        (x{count})
                      </span>
                    )}
                  </td>
                  <td className="py-2 pr-3 text-slate-600">{formatTimeOnly(run.startedAt)}</td>
                  <td className="py-2 pr-3">
                    <Badge variant={TRIGGER_VARIANT[run.triggerType] ?? 'neutral'}>{run.triggerType}</Badge>
                  </td>
                  <td className="py-2 pr-3">
                    <span className="inline-flex items-center gap-1">
                      <Badge variant={STATUS_VARIANT[run.status] ?? 'neutral'}>{run.status}</Badge>
                      {run.runLabel === 'OFFICIAL_EOD' && (
                        <Badge variant="eod" data-testid={`eod-badge-${run.jobId}`}>
                          <Star className="h-3 w-3 mr-0.5 inline" />EOD
                        </Badge>
                      )}
                      {run.runLabel === 'PRE_CLOSE' && (
                        <Badge variant="preclose" data-testid={`preclose-badge-${run.jobId}`}>
                          Pre-Close
                        </Badge>
                      )}
                      {run.runLabel === 'SUPERSEDED_EOD' && (
                        <Badge variant="neutral" data-testid={`superseded-badge-${run.jobId}`}>
                          Superseded
                        </Badge>
                      )}
                      {run.runLabel === 'SOD' && (
                        <Badge variant="sod" data-testid={`sod-badge-${run.jobId}`}>
                          SOD
                        </Badge>
                      )}
                    </span>
                    {run.status === 'RUNNING' && run.currentPhase && (
                      <PhaseStepperMini currentPhase={run.currentPhase} />
                    )}
                  </td>
                  <td data-testid={`duration-${run.jobId}`} className="py-2 pr-3 text-slate-600">
                    {run.status === 'RUNNING'
                      ? <ElapsedDuration startedAt={run.startedAt} />
                      : run.durationMs != null ? formatDuration(run.durationMs) : '-'}
                  </td>
                  <td className="py-2 pr-3 text-slate-700 font-mono">
                    {run.varValue != null ? formatMoney(run.varValue.toFixed(2), 'USD') : '-'}
                  </td>
                  <td className="py-2 pr-3 text-slate-700 font-mono">
                    {run.expectedShortfall != null
                      ? formatMoney(run.expectedShortfall.toFixed(2), 'USD')
                      : '-'}
                  </td>
                  <td className="py-2 text-slate-700 font-mono">
                    {run.pvValue != null
                      ? formatMoney(run.pvValue.toFixed(2), 'USD')
                      : '-'}
                  </td>
                </tr>
                {(isExpanded || isLoading) && (
                  <tr data-testid={`job-detail-row-${run.jobId}`}>
                    <td colSpan={onToggleCompareSelection ? 9 : 8} className="p-0">
                      <div data-testid="job-detail-panel" className="px-4 py-3 bg-slate-50 border-b border-slate-200">
                        <div className="flex items-center justify-between mb-2">
                          <h4 className="text-sm font-semibold text-slate-700">Job Details</h4>
                          <div className="flex items-center gap-3">
                            {detail && !isLoading && (
                              <div className="relative">
                                <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3 w-3 text-slate-400" />
                                <input
                                  data-testid={`detail-search-${run.jobId}`}
                                  type="text"
                                  value={searchTerms[run.jobId] ?? ''}
                                  onChange={(e) => setSearchTerms((prev) => ({ ...prev, [run.jobId]: e.target.value }))}
                                  placeholder="Search…"
                                  className="pl-7 pr-2 py-1 text-xs rounded border border-slate-200 bg-white focus:outline-none focus:border-primary-300 w-48"
                                  onClick={(e) => e.stopPropagation()}
                                />
                              </div>
                            )}
                            <button
                              data-testid={`close-detail-${run.jobId}`}
                              onClick={(e) => {
                                e.stopPropagation()
                                onCloseJob(run.jobId)
                              }}
                              className="text-xs text-slate-400 hover:text-slate-600"
                            >
                              Close
                            </button>
                          </div>
                        </div>
                        <div data-testid="detail-job-id" className="font-mono text-xs text-slate-500 mb-2">{run.jobId}</div>
                        {isLoading && (
                          <div data-testid="detail-loading" className="flex items-center gap-2 text-sm text-slate-500 py-2">
                            <Spinner size="sm" />
                            Loading job details...
                          </div>
                        )}
                        {detail && !isLoading && (
                          <>
                            <JobTimeline phases={detail.phases} search={searchTerms[run.jobId] ?? ''} />
                            {detail.error && (
                              <p className="mt-2 text-xs text-red-600">Error: {detail.error}</p>
                            )}
                            {run.status === 'COMPLETED' && run.runLabel !== 'OFFICIAL_EOD' && (
                              <button
                                data-testid={`promote-eod-${run.jobId}`}
                                onClick={(e) => {
                                  e.stopPropagation()
                                  setPromoteTarget(run.jobId)
                                }}
                                className="mt-2 px-3 py-1 text-xs font-medium text-amber-700 border border-amber-300 rounded hover:bg-amber-50 transition-colors"
                              >
                                <Star className="h-3 w-3 mr-1 inline" />
                                Promote to Official EOD
                              </button>
                            )}
                            {run.runLabel === 'OFFICIAL_EOD' && (
                              <div data-testid={`eod-info-${run.jobId}`} className="mt-2 flex items-center gap-3 text-xs text-amber-700">
                                <span>
                                  <Star className="h-3 w-3 mr-1 inline" />
                                  Official EOD — promoted by {run.promotedBy}
                                  {run.promotedAt && ` at ${new Date(run.promotedAt).toLocaleTimeString()}`}
                                </span>
                                <button
                                  data-testid={`demote-eod-${run.jobId}`}
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    setDemoteTarget(run.jobId)
                                  }}
                                  className="text-red-500 hover:text-red-700 underline"
                                >
                                  Remove designation
                                </button>
                              </div>
                            )}
                            {run.status === 'COMPLETED' && run.manifestId && (
                              <div className="mt-3">
                                {replayOpenFor === run.jobId ? (
                                  <div className="border-t border-slate-200 pt-3">
                                    <ReplayPanel
                                      jobId={run.jobId}
                                      onClose={() => setReplayOpenFor(null)}
                                    />
                                  </div>
                                ) : (
                                  <button
                                    data-testid={`replay-toggle-${run.jobId}`}
                                    onClick={(e) => {
                                      e.stopPropagation()
                                      setReplayOpenFor(run.jobId)
                                    }}
                                    className="px-3 py-1 text-xs font-medium text-slate-600 border border-slate-300 rounded hover:bg-slate-100 transition-colors inline-flex items-center gap-1.5"
                                  >
                                    <RefreshCw className="h-3 w-3" />
                                    Reproducibility
                                  </button>
                                )}
                              </div>
                            )}
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            )
          })}
        </tbody>
      </table>
      <ConfirmDialog
        open={promoteTarget !== null}
        title="Promote to Official EOD"
        message={
          <>
            <p>Are you sure you want to designate this run as the Official End-of-Day calculation?</p>
            <p className="mt-2 text-xs text-slate-500">This action will mark the run as the authoritative EOD result for regulatory reporting.</p>
            {promoteError && (
              <p className="mt-2 text-sm text-red-600" data-testid="promote-error">{promoteError}</p>
            )}
          </>
        }
        confirmLabel="Promote"
        variant="primary"
        loading={promoteState === 'loading'}
        onConfirm={async () => {
          if (!promoteTarget) return
          const result = await promote(promoteTarget, 'current-user')
          if (result) {
            setPromoteTarget(null)
            resetPromotion()
            onJobPromoted?.()
          }
        }}
        onCancel={() => {
          setPromoteTarget(null)
          resetPromotion()
        }}
      />
      <ConfirmDialog
        open={demoteTarget !== null}
        title="Remove Official EOD Designation"
        message={
          <>
            <p>Are you sure you want to remove the Official EOD designation from this run?</p>
            <p className="mt-2 text-xs text-slate-500">This will clear the authoritative EOD status. The run data will not be modified.</p>
            {promoteError && (
              <p className="mt-2 text-sm text-red-600" data-testid="demote-error">{promoteError}</p>
            )}
          </>
        }
        confirmLabel="Remove Designation"
        variant="danger"
        loading={promoteState === 'loading'}
        onConfirm={async () => {
          if (!demoteTarget) return
          const result = await demote(demoteTarget, 'current-user')
          if (result) {
            setDemoteTarget(null)
            resetPromotion()
            onJobPromoted?.()
          }
        }}
        onCancel={() => {
          setDemoteTarget(null)
          resetPromotion()
        }}
      />
    </div>
  )
}
