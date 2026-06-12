import { ChevronLeft, ChevronRight } from 'lucide-react'

interface PaginationControlsProps {
  currentPage: number
  totalPages: number
  onPageChange: (page: number) => void
}

/**
 * Prev/next pager for long tables. Render only when totalPages > 1.
 * Mirrors the PositionGrid pager (same testids) so e2e selectors stay uniform.
 */
export function PaginationControls({ currentPage, totalPages, onPageChange }: PaginationControlsProps) {
  return (
    <div data-testid="pagination-controls" className="flex items-center justify-center gap-3 mt-3">
      <button
        data-testid="pagination-prev"
        disabled={currentPage === 1}
        onClick={() => onPageChange(currentPage - 1)}
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
        onClick={() => onPageChange(currentPage + 1)}
        className="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium rounded-md border border-slate-300 dark:border-surface-600 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-surface-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
      >
        Next
        <ChevronRight className="h-4 w-4" />
      </button>
    </div>
  )
}
