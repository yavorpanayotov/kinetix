import { useHierarchySummary } from '../../hooks/useHierarchySummary'
import { useIntradayPnlStream } from '../../hooks/useIntradayPnlStream'
import type { HierarchySelection } from '../../hooks/useHierarchySelector'
import { formatMoney, formatSignedMoney, pnlColorClass, EM_DASH } from '../../utils/format'
import { MobileFreshnessBanner } from './MobileFreshnessBanner'

// Plan §mobile — the phone-first P&L view. It distils the desktop
// BookSummaryCard down to the three numbers a trader checks on a phone: the
// book NAV, unrealised P&L, and the live intraday P&L total. No waterfall, no
// chart, no position breakdown — just the headline figures, with P&L sign
// colours (pnlColorClass) so green/red reads at a glance and a "+" sign cue
// for colour-blind users (formatSignedMoney). The freshness banner up top is
// fed the intraday snapshot time so a stale phone screen can't pass for live.

interface MobilePnlViewProps {
  // The selected book, or null when none is chosen (mirrors App.tsx's
  // effectiveBookId). The view fetches its own summary + intraday stream.
  bookId: string | null
}

export function MobilePnlView({ bookId }: MobilePnlViewProps) {
  // useHierarchySummary takes a hierarchy selection; for a single book we build
  // the minimal book-level selection (same shape App.tsx passes via
  // hierarchy.selection).
  const selection: HierarchySelection = {
    level: 'book',
    divisionId: null,
    deskId: null,
    bookId,
  }
  const { summary, loading } = useHierarchySummary(selection)
  const { latest } = useIntradayPnlStream(bookId)

  if (loading && !summary) {
    return (
      <div
        data-testid="mobile-pnl-loading"
        className="flex items-center justify-center py-16 text-sm text-slate-500 dark:text-slate-400"
      >
        Loading P&L…
      </div>
    )
  }

  if (!summary) {
    return (
      <div
        data-testid="mobile-pnl-empty"
        className="flex flex-col items-center justify-center gap-1 py-16 text-center"
      >
        <p className="text-sm font-medium text-slate-600 dark:text-slate-300">
          No P&L available
        </p>
        <p className="text-xs text-slate-400 dark:text-slate-500">
          Select a book with a recent position summary.
        </p>
      </div>
    )
  }

  const nav = summary.totalNav
  const unrealised = summary.totalUnrealizedPnl
  const intradayPnl = latest?.totalPnl ?? null

  return (
    <div data-testid="mobile-pnl-view">
      <div className="-mx-4 -mt-4 mb-4">
        <MobileFreshnessBanner dataAsOf={latest?.snapshotAt ?? null} />
      </div>

      <section className="rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-surface-800 p-4">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          NAV
        </h2>
        <p
          data-testid="mobile-pnl-nav"
          className="mt-1 font-mono tabular-nums text-3xl font-bold text-slate-900 dark:text-slate-100"
        >
          {formatMoney(nav.amount, nav.currency)}
        </p>

        <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
          <div>
            <dt className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
              Unrealised P&L
            </dt>
            <dd
              data-testid="mobile-pnl-unrealised"
              className={`mt-0.5 font-mono tabular-nums text-lg font-semibold ${pnlColorClass(unrealised.amount)}`}
            >
              {formatSignedMoney(unrealised.amount, unrealised.currency)}
            </dd>
          </div>
          <div>
            <dt className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
              Intraday P&L
            </dt>
            <dd
              data-testid="mobile-pnl-intraday"
              className={`mt-0.5 font-mono tabular-nums text-lg font-semibold ${
                intradayPnl !== null
                  ? pnlColorClass(intradayPnl)
                  : 'text-slate-400 dark:text-slate-500'
              }`}
            >
              {intradayPnl !== null
                ? formatSignedMoney(intradayPnl, latest?.baseCurrency ?? nav.currency)
                : EM_DASH}
            </dd>
          </div>
        </dl>
      </section>
    </div>
  )
}
