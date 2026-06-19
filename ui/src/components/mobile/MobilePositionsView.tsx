import { useEffect } from 'react'
import { usePositions } from '../../hooks/usePositions'
import { formatMoney, formatSignedMoney, pnlColorClass } from '../../utils/format'

// Plan §mobile — the phone-first Positions view. It distils the desktop
// PositionGrid's 11-column blotter down to a compact, read-only list of the
// book's TOP exposures: instrument, market value, and unrealised P&L coloured
// via pnlColorClass (the SAME formatter the grid uses, so the two surfaces
// never disagree on sign/magnitude). No editable notes, no what-if, no grid —
// just the headline figures a trader scans on a phone. Rows are sorted by
// absolute market value (largest exposure first) and capped so the screen
// stays glanceable.

// Cap the list so a phone screen stays glanceable — the largest exposures are
// what a trader checks on the move; the long tail belongs on the desktop grid.
const TOP_N = 15

interface MobilePositionsViewProps {
  // The selected book, or null when none is chosen (mirrors App.tsx's
  // effectiveBookId). Accepted for parity with the sibling mobile views; the
  // positions hook owns its own book selection.
  bookId: string | null
}

export function MobilePositionsView({ bookId }: MobilePositionsViewProps) {
  const { positions, bookId: activeBookId, selectBook, loading } = usePositions()

  // Honour the externally-selected book: when the parent picks a book that
  // differs from the hook's own default first-book selection, switch to it so
  // the list reflects the same book the rest of the mobile shell is showing.
  useEffect(() => {
    if (bookId && bookId !== activeBookId) {
      selectBook(bookId)
    }
  }, [bookId, activeBookId, selectBook])

  if (loading && positions.length === 0) {
    return (
      <div
        data-testid="mobile-positions-loading"
        className="flex items-center justify-center py-16 text-sm text-slate-500 dark:text-slate-400"
      >
        Loading positions…
      </div>
    )
  }

  if (positions.length === 0) {
    return (
      <div
        data-testid="mobile-positions-empty"
        className="flex flex-col items-center justify-center gap-1 py-16 text-center"
      >
        <p className="text-sm font-medium text-slate-600 dark:text-slate-300">
          No positions
        </p>
        <p className="text-xs text-slate-400 dark:text-slate-500">
          Select a book with open positions.
        </p>
      </div>
    )
  }

  // Sort by absolute market value (largest exposure first) and keep the top N.
  // A copy is sorted so the hook's array is never mutated in render.
  const topPositions = [...positions]
    .sort((a, b) => Math.abs(Number(b.marketValue.amount)) - Math.abs(Number(a.marketValue.amount)))
    .slice(0, TOP_N)

  return (
    <div data-testid="mobile-positions-view">
      <div className="-mx-4 -mt-4 mb-4">
        {/* The positions feed exposes no as-of timestamp — neither PositionDto
            nor the /positions endpoint carries one — so we can't drive the live
            MobileFreshnessBanner. Rather than render nothing (which would make a
            stale list look identical to a live one), we show a static banner
            that names the gap. We deliberately do NOT invent a timestamp. The
            amber palette signals "freshness undetermined" (caution) — distinct
            from the banner's neutral *fresh/OK* grey, which would wrongly read
            as "data is current" — while staying below the red stale (≥15 min)
            treatment. Kept consistent with MobilePnlView's fallback banner. */}
        <div
          data-testid="mobile-positions-freshness"
          role="status"
          aria-live="polite"
          className="w-full px-4 py-2 text-sm font-medium bg-amber-50 text-amber-800 dark:bg-amber-900/30 dark:text-amber-300"
        >
          Position data — freshness unknown, no timestamp available
        </div>
      </div>

      <ul className="space-y-2">
        {topPositions.map((p) => (
          <li
            key={p.instrumentId}
            data-testid={`mobile-position-row-${p.instrumentId}`}
            className="flex items-center justify-between gap-3 rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-surface-700 px-3 py-2"
          >
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold text-slate-900 dark:text-slate-100">
                {p.instrumentId}
              </p>
              <p className="text-xs text-slate-400 dark:text-slate-500">{p.bookId}</p>
            </div>
            <div className="text-right">
              <p
                data-testid={`mobile-position-mv-${p.instrumentId}`}
                className="font-mono tabular-nums text-sm font-semibold text-slate-900 dark:text-slate-100"
              >
                {formatMoney(p.marketValue.amount, p.marketValue.currency)}
              </p>
              <p
                data-testid={`mobile-position-pnl-${p.instrumentId}`}
                className={`font-mono tabular-nums text-xs ${pnlColorClass(p.unrealizedPnl.amount)}`}
              >
                {formatSignedMoney(p.unrealizedPnl.amount, p.unrealizedPnl.currency)}
              </p>
            </div>
          </li>
        ))}
      </ul>

      {/* When the book has more positions than the cap, the list is silently
          truncated. Without this line a trader could believe they're seeing
          every position. Name the cap (reusing TOP_N — no second copy of the
          number) and point to the full desktop blotter. */}
      {positions.length > TOP_N && (
        <p
          data-testid="mobile-positions-truncation"
          className="mt-3 text-xs text-slate-500 dark:text-slate-400"
        >
          Showing top {TOP_N} by market value — full blotter on desktop.
        </p>
      )}
    </div>
  )
}
