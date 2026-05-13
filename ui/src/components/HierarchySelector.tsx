import { useRef, useState } from 'react'
import { ChevronDown, Building2 } from 'lucide-react'
import { HierarchyBreadcrumb } from './HierarchyBreadcrumb'
import { Spinner } from './ui/Spinner'
import { useClickOutside } from '../hooks/useClickOutside'
import { useHierarchySummary } from '../hooks/useHierarchySummary'
import { formatMoney } from '../utils/format'
import type { UseHierarchySelectorResult, HierarchySelection } from '../hooks/useHierarchySelector'

interface HierarchySelectorProps {
  hierarchy: UseHierarchySelectorResult
}

export function HierarchySelector({ hierarchy }: HierarchySelectorProps) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  useClickOutside(containerRef, () => setOpen(false))

  const { selection, setSelection, breadcrumb, divisions, desks, books, loading } = hierarchy
  const { summary, loading: summaryLoading } = useHierarchySummary(selection)
  const showSummary = selection.level !== 'book' && (summaryLoading || summary !== null)

  const handleNavigate = (newSelection: HierarchySelection) => {
    setSelection(newSelection)
    if (newSelection.level === 'book') {
      setOpen(false)
    }
  }

  const handleDivisionClick = (divisionId: string) => {
    setSelection({
      level: 'division',
      divisionId,
      deskId: null,
      bookId: null,
    })
  }

  const handleDeskClick = (deskId: string) => {
    setSelection({
      level: 'desk',
      divisionId: selection.divisionId,
      deskId,
      bookId: null,
    })
  }

  const handleBookClick = (bookId: string) => {
    setSelection({
      level: 'book',
      divisionId: selection.divisionId,
      deskId: selection.deskId,
      bookId,
    })
    setOpen(false)
  }

  const handleFirmClick = () => {
    setSelection({ level: 'firm', divisionId: null, deskId: null, bookId: null })
    setOpen(false)
  }

  // Text-only breadcrumb for display inside the toggle button (no nested buttons)
  const breadcrumbLabel = breadcrumb.map((item) => item.label).join(' / ')

  return (
    <div ref={containerRef} className="relative" data-testid="hierarchy-selector">
      <button
        data-testid="hierarchy-selector-toggle"
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 bg-surface-800 border border-surface-700 text-white rounded-md px-3 py-1.5 text-sm hover:bg-surface-700 focus:ring-2 focus:ring-primary-500 transition-colors"
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={`Hierarchy: ${breadcrumbLabel}`}
      >
        <Building2 className="h-4 w-4 text-primary-400" />
        <span className="hidden md:inline text-sm text-white max-w-[10rem] xl:max-w-[18rem] truncate">{breadcrumbLabel}</span>
        <ChevronDown className={`h-4 w-4 text-slate-400 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div
          data-testid="hierarchy-panel"
          className="absolute left-0 mt-1 min-w-64 bg-white dark:bg-surface-800 border border-slate-200 dark:border-surface-700 rounded-lg shadow-lg z-20 text-slate-800 dark:text-slate-100"
        >
          {/* Clickable breadcrumb at top of panel */}
          {breadcrumb.length > 1 && (
            <div className="px-3 pt-2 pb-1 border-b border-slate-100 dark:border-surface-700">
              <HierarchyBreadcrumb breadcrumb={breadcrumb} onNavigate={handleNavigate} />
            </div>
          )}

          {/* Aggregated metrics summary row (firm / division / desk levels only) */}
          {showSummary && (
            <div
              data-testid="hierarchy-summary-row"
              className="px-3 py-2 border-b border-slate-100 dark:border-surface-700 bg-slate-50 dark:bg-surface-800/60"
            >
              {summaryLoading && !summary ? (
                <div data-testid="hierarchy-summary-loading" className="flex items-center gap-2">
                  <Spinner size="sm" />
                </div>
              ) : summary ? (
                <div className="flex items-center gap-4 text-xs text-slate-600 dark:text-slate-400">
                  <span>
                    <span className="font-medium text-slate-500 dark:text-slate-500 uppercase tracking-wide mr-1">NAV</span>
                    {formatMoney(summary.totalNav.amount, summary.totalNav.currency)}
                  </span>
                  <span>
                    <span className="font-medium text-slate-500 dark:text-slate-500 uppercase tracking-wide mr-1">P&L</span>
                    {formatMoney(summary.totalUnrealizedPnl.amount, summary.totalUnrealizedPnl.currency)}
                  </span>
                </div>
              ) : null}
            </div>
          )}

          <div className="py-1">
            {loading && (
              <div className="px-3 py-2 text-sm text-slate-500">Loading...</div>
            )}

            {!loading && (
              <>
                <button
                  data-testid="hierarchy-firm-option"
                  onClick={handleFirmClick}
                  className={`w-full text-left px-3 py-2 text-sm hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors ${
                    selection.level === 'firm' ? 'font-semibold text-primary-700 dark:text-primary-400' : ''
                  }`}
                >
                  Firm (All)
                </button>

                {selection.level === 'firm' && divisions.length > 0 && (
                  <div className="border-t border-slate-100 dark:border-surface-700 mt-1 pt-1">
                    <div className="px-3 py-1 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">
                      Divisions
                    </div>
                    {divisions.map((div) => (
                      <button
                        key={div.id}
                        data-testid={`hierarchy-division-${div.id}`}
                        onClick={() => handleDivisionClick(div.id)}
                        className="w-full text-left px-4 py-1.5 text-sm hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
                      >
                        {div.name}
                        <span className="ml-1 text-xs text-slate-400">
                          ({div.deskCount} {div.deskCount === 1 ? 'desk' : 'desks'})
                        </span>
                      </button>
                    ))}
                  </div>
                )}

                {selection.level === 'division' && desks.length > 0 && (
                  <div className="border-t border-slate-100 dark:border-surface-700 mt-1 pt-1">
                    <div className="px-3 py-1 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">
                      Desks
                    </div>
                    {desks.map((desk) => (
                      <button
                        key={desk.id}
                        data-testid={`hierarchy-desk-${desk.id}`}
                        onClick={() => handleDeskClick(desk.id)}
                        className={`w-full text-left px-4 py-1.5 text-sm hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors ${
                          selection.deskId === desk.id ? 'font-semibold text-primary-700 dark:text-primary-400' : ''
                        }`}
                      >
                        {desk.name}
                        <span className="ml-1 text-xs text-slate-400">
                          ({desk.bookCount} {desk.bookCount === 1 ? 'book' : 'books'})
                        </span>
                      </button>
                    ))}
                  </div>
                )}

                {(selection.level === 'desk' || selection.level === 'book') && books.length > 0 && (
                  <div className="border-t border-slate-100 dark:border-surface-700 mt-1 pt-1">
                    <div className="px-3 py-1 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">
                      Books
                    </div>
                    {books.map((book) => (
                      <button
                        key={book.bookId}
                        data-testid={`hierarchy-book-${book.bookId}`}
                        onClick={() => handleBookClick(book.bookId)}
                        className={`w-full text-left px-4 py-1.5 text-sm hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors ${
                          selection.bookId === book.bookId ? 'font-semibold text-primary-700 dark:text-primary-400' : ''
                        }`}
                      >
                        {book.bookId}
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
