import { useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp, ChevronsLeft, ChevronsRight, History, Search, Star } from 'lucide-react'

import type { ValuationJobSummaryDto } from '../types'
import { PHASE_LABELS } from '../constants/phaseLabels'
import { useJobHistory } from '../hooks/useJobHistory'
import { chartPointsToBuckets } from '../utils/timeBuckets'
import { jobMatchesSearch } from '../utils/jobSearch'
import { JobHistoryTable } from './JobHistoryTable'
import { JobTimechart } from './JobTimechart'
import { TimeRangeSelector } from './TimeRangeSelector'
import { Card, Badge, Spinner, ErrorCard } from './ui'

interface JobHistoryProps {
  bookId: string | null
  refreshSignal?: number
  onCompareJobs?: (baseJobId: string, targetJobId: string) => void
}

function formatJobTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
}


function formatElapsed(iso: string): string {
  const ms = Math.max(0, Date.now() - new Date(iso).getTime())
  const secs = Math.floor(ms / 1000)
  const mins = Math.floor(secs / 60)
  return `${mins}:${String(secs % 60).padStart(2, '0')}`
}

function buildSummaryText(runs: ValuationJobSummaryDto[], totalCount: number): string {
  if (runs.length === 0) return 'No calculations'
  const running = runs.find((r) => r.status === 'RUNNING')
  if (running && running.currentPhase) {
    const label = PHASE_LABELS[running.currentPhase] ?? running.currentPhase
    return `Running: ${label} (${formatElapsed(running.startedAt)}) · ${totalCount} job${totalCount !== 1 ? 's' : ''}`
  }
  const last = runs[0]
  const time = formatJobTime(last.startedAt)
  const status = last.status === 'COMPLETED' ? 'success' : last.status
  return `Last calc: ${time} (${status}) · ${totalCount} job${totalCount !== 1 ? 's' : ''}`
}

export function JobHistory({ bookId, refreshSignal = 0, onCompareJobs }: JobHistoryProps) {
  const [expanded, setExpanded] = useState(() => {
    try {
      return localStorage.getItem('kinetix:job-history-expanded') !== 'false'
    } catch {
      return true
    }
  })
  const [search, setSearch] = useState('')
  const [eodFilter, setEodFilter] = useState(false)
  const [selectedForCompare, setSelectedForCompare] = useState<Set<string>>(new Set())

  const toggleCompareSelection = (jobId: string) => {
    setSelectedForCompare((prev) => {
      const next = new Set(prev)
      if (next.has(jobId)) {
        next.delete(jobId)
      } else if (next.size < 2) {
        next.add(jobId)
      } else {
        // Replace the oldest selection with the new one
        const [first] = next
        next.delete(first)
        next.add(jobId)
      }
      return next
    })
  }
  const { runs, chartData, expandedJobs, loadingJobIds, loading, error, timeRange, setTimeRange, toggleJob, closeJob, refresh, zoomIn, resetZoom, zoomDepth, page, pageSize, setPageSize, totalCount, totalPages, hasNextPage, nextPage, prevPage, firstPage, lastPage, goToPage } = useJobHistory(
    bookId,
  )
  const [pageInput, setPageInput] = useState(String(page + 1))
  const [pageSizeInput, setPageSizeInput] = useState(String(pageSize))
  const [pageSizeOpen, setPageSizeOpen] = useState(false)
  const pageSizeRef = useRef<HTMLDivElement>(null)

  const prevSignalRef = useRef(refreshSignal)
  useEffect(() => {
    if (refreshSignal !== prevSignalRef.current) {
      prevSignalRef.current = refreshSignal
      refresh()
    }
  }, [refreshSignal, refresh])

  useEffect(() => {
    setPageInput(String(page + 1))
  }, [page])

  useEffect(() => {
    setPageSizeInput(String(pageSize))
  }, [pageSize])

  useEffect(() => {
    if (!pageSizeOpen) return
    const handleClickOutside = (e: MouseEvent) => {
      if (pageSizeRef.current && !pageSizeRef.current.contains(e.target as Node)) {
        setPageSizeOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [pageSizeOpen])

  const submitPageSizeInput = () => {
    const parsed = parseInt(pageSizeInput, 10)
    if (isNaN(parsed) || parsed < 1) {
      setPageSizeInput(String(pageSize))
      return
    }
    setPageSize(parsed)
  }

  const submitPageInput = () => {
    const parsed = parseInt(pageInput, 10)
    if (isNaN(parsed)) {
      setPageInput(String(page + 1))
      return
    }
    const clamped = Math.max(1, Math.min(parsed, totalPages))
    setPageInput(String(clamped))
    goToPage(clamped - 1)
  }

  const toggleExpanded = () => {
    setExpanded((prev) => {
      const next = !prev
      try { localStorage.setItem('kinetix:job-history-expanded', String(next)) } catch { /* noop */ }
      return next
    })
  }

  const buckets = useMemo(
    () => chartData ? chartPointsToBuckets(chartData.points, chartData.bucketSizeMs) : [],
    [chartData],
  )

  const runningJob = runs.find((r) => r.status === 'RUNNING')
  const runningPhaseLabel = runningJob?.currentPhase
    ? (PHASE_LABELS[runningJob.currentPhase] ?? runningJob.currentPhase)
    : ''
  const [liveText, setLiveText] = useState('')
  const prevPhaseRef = useRef(runningPhaseLabel)
  useEffect(() => {
    if (runningPhaseLabel && runningPhaseLabel !== prevPhaseRef.current) {
      prevPhaseRef.current = runningPhaseLabel
      setLiveText(runningJob ? `Job ${runningJob.jobId.slice(0, 8)}: ${runningPhaseLabel}` : '')
    } else if (!runningJob) {
      prevPhaseRef.current = ''
      setLiveText('')
    } else if (prevPhaseRef.current) {
      setLiveText(`Job ${runningJob.jobId.slice(0, 8)}: ${prevPhaseRef.current}`)
    } else {
      setLiveText('')
    }
  }, [runningJob, runningPhaseLabel])

  const filteredRuns = runs
    .filter((r) => !eodFilter || r.runLabel === 'OFFICIAL_EOD')
    .filter((r) => !search.trim() || jobMatchesSearch(r, search, expandedJobs[r.jobId]))

  return (
    <Card data-testid="job-history">
      <button
        data-testid="job-history-header"
        onClick={toggleExpanded}
        className="flex items-center gap-2 w-full text-left"
      >
        <History className="h-4 w-4 text-slate-500" />
        <span className="text-sm font-semibold text-slate-700">Valuation Jobs</span>
        {filteredRuns.length > 0 && (
          <Badge variant="neutral">{totalPages > 1 ? `Page ${page + 1} of ${totalPages}` : filteredRuns.length}</Badge>
        )}
        <span className="ml-auto">
          {expanded
            ? <ChevronUp className="h-4 w-4 text-slate-400" />
            : <ChevronDown className="h-4 w-4 text-slate-400" />}
        </span>
      </button>

      {loading && !runs.length && (
        <div data-testid="job-history-loading" className="flex items-center gap-2 text-sm text-slate-500 py-2 mt-2">
          <Spinner size="sm" />
          Loading jobs...
        </div>
      )}

      {error && (
        <div className="py-2 mt-2">
          <ErrorCard message={error} data-testid="job-history-error" />
        </div>
      )}

      {!expanded && !loading && !error && (
        <div data-testid="job-history-summary" className={`mt-2 text-xs ${runs.some((r) => r.status === 'RUNNING') ? 'text-blue-600 dark:text-blue-400' : 'text-slate-500'}`}>
          {buildSummaryText(runs, totalCount)}
        </div>
      )}

      {expanded && !error && !(loading && runs.length === 0) && <div className="mt-3">
            <>
              <TimeRangeSelector value={timeRange} onChange={setTimeRange} />
              {runs.length > 0 && (
                <JobTimechart
                  buckets={buckets}
                  timeRange={timeRange}
                  onZoom={zoomIn}
                  zoomDepth={zoomDepth}
                  onResetZoom={resetZoom}
                />
              )}
              {runs.length > 0 && (
                <div className="mb-2 flex items-center gap-2">
                  <div className="relative inline-block">
                    <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3 w-3 text-slate-400" />
                    <input
                      data-testid="job-history-search"
                      type="text"
                      value={search}
                      onChange={(e) => setSearch(e.target.value)}
                      placeholder="Search…"
                      className="pl-7 pr-2 py-1 text-xs rounded border border-slate-200 bg-white focus:outline-none focus:border-primary-300 w-48"
                      onClick={(e) => e.stopPropagation()}
                    />
                  </div>
                  <button
                    data-testid="eod-filter-chip"
                    onClick={(e) => { e.stopPropagation(); setEodFilter((prev) => !prev) }}
                    className={`inline-flex items-center gap-1 px-2 py-1 text-xs rounded-full border transition-colors ${
                      eodFilter
                        ? 'bg-amber-100 text-amber-800 border-amber-300'
                        : 'bg-white text-slate-500 border-slate-200 hover:border-amber-300 hover:text-amber-700'
                    }`}
                  >
                    <Star className="h-3 w-3" />
                    Official EOD
                  </button>
                </div>
              )}
              {onCompareJobs && selectedForCompare.size === 2 && (
                <div className="flex items-center gap-2 mb-2">
                  <button
                    data-testid="compare-selected-btn"
                    onClick={() => {
                      const ids = Array.from(selectedForCompare)
                      onCompareJobs(ids[0], ids[1])
                    }}
                    className="px-3 py-1.5 text-sm font-medium text-indigo-600 dark:text-indigo-400 border border-indigo-300 dark:border-indigo-600 rounded-md hover:bg-indigo-50 dark:hover:bg-indigo-900/20 transition-colors"
                  >
                    Compare Selected
                  </button>
                  <button
                    data-testid="clear-compare-selection"
                    onClick={() => setSelectedForCompare(new Set())}
                    className="text-xs text-slate-400 hover:text-slate-600"
                  >
                    Clear
                  </button>
                </div>
              )}
              <JobHistoryTable
                runs={filteredRuns}
                expandedJobs={expandedJobs}
                loadingJobIds={loadingJobIds}
                onSelectJob={toggleJob}
                onCloseJob={closeJob}
                selectedForCompare={onCompareJobs ? selectedForCompare : undefined}
                onToggleCompareSelection={onCompareJobs ? toggleCompareSelection : undefined}
              />
              {filteredRuns.length > 0 && (
                <div data-testid="pagination-bar" className="flex items-center justify-center gap-2 mt-2 py-2">
                  <button
                    data-testid="pagination-first"
                    onClick={firstPage}
                    disabled={page === 0}
                    className="inline-flex items-center px-1.5 py-1 text-xs font-medium rounded border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <ChevronsLeft className="h-3 w-3" />
                  </button>
                  <button
                    data-testid="pagination-prev"
                    onClick={prevPage}
                    disabled={page === 0}
                    className="inline-flex items-center px-1.5 py-1 text-xs font-medium rounded border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <ChevronLeft className="h-3 w-3" />
                  </button>
                  <span data-testid="pagination-info" className="text-xs text-slate-500 mx-1 inline-flex items-center gap-1">
                    {totalPages > 1 ? (
                      <>
                        <input
                          data-testid="pagination-page-input"
                          type="text"
                          value={pageInput}
                          onChange={(e) => setPageInput(e.target.value)}
                          onKeyDown={(e) => { if (e.key === 'Enter') submitPageInput() }}
                          onBlur={submitPageInput}
                          className="w-8 px-1 py-0.5 text-xs text-center rounded border border-slate-200 bg-white focus:outline-none focus:border-primary-300"
                        />
                        <span>of {totalPages}</span>
                      </>
                    ) : (
                      `Page ${page + 1}`
                    )}
                  </span>
                  <button
                    data-testid="pagination-next"
                    onClick={nextPage}
                    disabled={!hasNextPage}
                    className="inline-flex items-center px-1.5 py-1 text-xs font-medium rounded border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <ChevronRight className="h-3 w-3" />
                  </button>
                  <button
                    data-testid="pagination-last"
                    onClick={lastPage}
                    disabled={!hasNextPage}
                    className="inline-flex items-center px-1.5 py-1 text-xs font-medium rounded border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <ChevronsRight className="h-3 w-3" />
                  </button>
                  <span className="mx-1 border-l border-slate-200 h-4" />
                  <div data-testid="page-size-selector" className="inline-flex items-center gap-1 text-xs text-slate-500">
                    <span>Per page:</span>
                    <div ref={pageSizeRef} className="relative inline-flex">
                      <input
                        data-testid="page-size-input"
                        type="text"
                        value={pageSizeInput}
                        onChange={(e) => setPageSizeInput(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') {
                            submitPageSizeInput()
                            setPageSizeOpen(false)
                          }
                        }}
                        onBlur={submitPageSizeInput}
                        className="w-12 pl-1.5 pr-5 py-0.5 text-xs rounded border border-slate-200 bg-white text-slate-600 focus:outline-none focus:border-primary-300"
                      />
                      <button
                        data-testid="page-size-toggle"
                        type="button"
                        onClick={() => setPageSizeOpen((prev) => !prev)}
                        className="absolute right-0 top-0 bottom-0 flex items-center px-1 text-slate-400 hover:text-slate-600"
                      >
                        <ChevronDown className="h-3 w-3" />
                      </button>
                      {pageSizeOpen && (
                        <ul className="absolute right-full top-0 mr-0.5 flex items-center bg-white border border-slate-200 rounded shadow-sm z-10">
                          {[10, 20, 50].map((size) => (
                            <li key={size}>
                              <button
                                data-testid={`page-size-option-${size}`}
                                type="button"
                                onClick={() => {
                                  setPageSize(size)
                                  setPageSizeOpen(false)
                                }}
                                className={`px-2 py-0.5 text-xs whitespace-nowrap hover:bg-slate-50 ${pageSize === size ? 'font-semibold text-primary-600' : 'text-slate-600'}`}
                              >
                                {size}
                              </button>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  </div>
                  <span data-testid="total-count" className="text-xs text-slate-400">Total: {totalCount}</span>
                </div>
              )}
            </>
        </div>}
      <div role="status" aria-live="polite" aria-atomic="true" className="sr-only">
        {liveText}
      </div>
    </Card>
  )
}
