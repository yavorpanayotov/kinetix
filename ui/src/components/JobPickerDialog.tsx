import { useEffect, useCallback, useRef } from 'react'
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, Search } from 'lucide-react'
import { Button } from './ui/Button'
import { Spinner } from './ui/Spinner'
import { EmptyState } from './ui/EmptyState'
import { ErrorCard } from './ui/ErrorCard'
import { TimeRangeSelector } from './TimeRangeSelector'
import { useJobPicker } from '../hooks/useJobPicker'
import { formatTimestamp } from '../utils/format'

interface JobPickerDialogProps {
  open: boolean
  bookId: string
  onSelect: (jobId: string) => void
  onCancel: () => void
}

export function JobPickerDialog({
  open,
  bookId,
  onSelect,
  onCancel,
}: JobPickerDialogProps) {
  const {
    jobs,
    loading,
    error,
    timeRange,
    setTimeRange,
    search,
    setSearch,
    page,
    totalPages,
    totalCount,
    hasNextPage,
    nextPage,
    prevPage,
    firstPage,
    lastPage,
  } = useJobPicker(bookId, open)

  const searchRef = useRef<HTMLInputElement>(null)

  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
    },
    [onCancel],
  )

  useEffect(() => {
    if (open) {
      document.addEventListener('keydown', handleKeyDown)
      return () => document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open, handleKeyDown])

  useEffect(() => {
    if (open) {
      // Defer focus to after the dialog renders
      const timer = setTimeout(() => searchRef.current?.focus(), 0)
      return () => clearTimeout(timer)
    }
  }, [open])

  if (!open) return null

  const hasActiveFilters = search.trim().length > 0 || timeRange.label !== 'Today'

  return (
    <div
      data-testid="job-picker-overlay"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={onCancel}
      role="dialog"
      aria-modal="true"
      aria-labelledby="job-picker-title"
    >
      <div
        data-testid="job-picker-dialog"
        className="bg-white dark:bg-slate-800 rounded-lg shadow-xl max-w-3xl w-full mx-4 p-6 max-h-[80vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 id="job-picker-title" className="text-lg font-semibold text-slate-800 dark:text-slate-100">
          Select VaR Calculation
        </h3>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          Choose a completed VaR calculation to use as the SOD baseline.
        </p>

        <div className="mt-4" data-testid="job-picker-time-range">
          <TimeRangeSelector value={timeRange} onChange={setTimeRange} />
        </div>

        <div className="mb-3">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
            <input
              ref={searchRef}
              data-testid="job-picker-search"
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search by ID or keyword..."
              aria-label="Search jobs"
              className="w-full pl-9 pr-3 py-2 text-sm rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 focus:outline-none focus:border-primary-300 focus:ring-1 focus:ring-primary-300"
            />
          </div>
        </div>

        <div className="flex-1 min-h-0 overflow-y-auto max-h-[360px]">
          {loading && (
            <div data-testid="job-picker-loading" className="flex justify-center py-8">
              <Spinner />
            </div>
          )}

          {error && (
            <div className="py-4">
              <ErrorCard message={error} data-testid="job-picker-error" />
            </div>
          )}

          {!loading && !error && jobs.length === 0 && (
            <div data-testid="job-picker-empty">
              <EmptyState
                title={
                  hasActiveFilters
                    ? 'No completed calculations match your search.'
                    : 'No completed VaR calculations found.'
                }
              />
            </div>
          )}

          {!loading && !error && jobs.length > 0 && (
            <table data-testid="job-picker-table" className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200 dark:border-slate-700 text-left text-xs text-slate-500 dark:text-slate-400">
                  <th className="pb-2 pr-3">Job ID</th>
                  <th className="pb-2 pr-3">Time</th>
                  <th className="pb-2 pr-3">Type</th>
                  <th className="pb-2 pr-3">Trigger</th>
                  <th className="pb-2 pr-3 text-right">VaR</th>
                  <th className="pb-2 pr-3 text-right">ES</th>
                  <th className="pb-2 pr-3">Duration</th>
                  <th className="pb-2"></th>
                </tr>
              </thead>
              <tbody>
                {jobs.map((job) => (
                  <tr
                    key={job.jobId}
                    data-testid="job-picker-row"
                    className="border-b border-slate-100 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700 cursor-pointer"
                    onClick={() => onSelect(job.jobId)}
                    tabIndex={0}
                    onKeyDown={(e) => { if (e.key === 'Enter') onSelect(job.jobId) }}
                  >
                    <td className="py-2 pr-3 font-mono text-xs" title={job.jobId}>
                      {job.jobId.slice(0, 8)}
                    </td>
                    <td className="py-2 pr-3 text-xs">{formatTimestamp(job.startedAt)}</td>
                    <td className="py-2 pr-3">{job.calculationType ?? '—'}</td>
                    <td className="py-2 pr-3 text-xs">{job.triggerType ?? '—'}</td>
                    <td className="py-2 pr-3 text-right font-mono text-xs">
                      {job.varValue != null ? job.varValue.toFixed(2) : '—'}
                    </td>
                    <td className="py-2 pr-3 text-right font-mono text-xs">
                      {job.expectedShortfall != null ? job.expectedShortfall.toFixed(2) : '—'}
                    </td>
                    <td className="py-2 pr-3 text-xs">
                      {job.durationMs != null ? `${(job.durationMs / 1000).toFixed(1)}s` : '—'}
                    </td>
                    <td className="py-2">
                      <Button
                        variant="primary"
                        size="sm"
                        onClick={(e) => {
                          e.stopPropagation()
                          onSelect(job.jobId)
                        }}
                        data-testid="job-picker-select"
                      >
                        Use
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {totalPages > 1 && (
          <div data-testid="job-picker-pagination" className="flex items-center justify-center gap-2 mt-3 pt-3 border-t border-slate-200 dark:border-slate-700">
            <button
              data-testid="job-picker-first"
              onClick={firstPage}
              disabled={page === 0}
              aria-label="First page"
              className="inline-flex items-center px-1.5 py-1 text-xs font-medium rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-600 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <ChevronsLeft className="h-3 w-3" />
            </button>
            <button
              data-testid="job-picker-prev"
              onClick={prevPage}
              disabled={page === 0}
              aria-label="Previous page"
              className="inline-flex items-center px-1.5 py-1 text-xs font-medium rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-600 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <ChevronLeft className="h-3 w-3" />
            </button>
            <span data-testid="job-picker-page-info" className="text-xs text-slate-500 dark:text-slate-400 mx-1">
              Page {page + 1} of {totalPages}
            </span>
            <button
              data-testid="job-picker-next"
              onClick={nextPage}
              disabled={!hasNextPage}
              aria-label="Next page"
              className="inline-flex items-center px-1.5 py-1 text-xs font-medium rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-600 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <ChevronRight className="h-3 w-3" />
            </button>
            <button
              data-testid="job-picker-last"
              onClick={lastPage}
              disabled={!hasNextPage}
              aria-label="Last page"
              className="inline-flex items-center px-1.5 py-1 text-xs font-medium rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-600 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <ChevronsRight className="h-3 w-3" />
            </button>
            <span className="mx-1 border-l border-slate-200 dark:border-slate-600 h-4" />
            <span data-testid="job-picker-total" className="text-xs text-slate-400">Total: {totalCount}</span>
          </div>
        )}

        <div className="mt-4 flex justify-end">
          <Button
            variant="secondary"
            onClick={onCancel}
            data-testid="job-picker-cancel"
          >
            Cancel
          </Button>
        </div>
      </div>
    </div>
  )
}
