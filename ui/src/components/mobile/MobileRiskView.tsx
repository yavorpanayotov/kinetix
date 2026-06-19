import { useVaR } from '../../hooks/useVaR'
import { useVarLimit } from '../../hooks/useVarLimit'
import { formatMoney } from '../../utils/format'
import { freshnessLevel } from '../../utils/freshnessLevel'
import { VAR_BREACH_THRESHOLD } from '../RiskTickerStrip'
import { MobileFreshnessBanner } from './MobileFreshnessBanner'

// Plan §mobile — the phone-first Risk view. It distils the desktop
// RiskTickerStrip's VaR cell down to the one number a trader checks on a phone:
// VaR against limit, the utilisation between them, and a loud breach cue when
// utilisation crosses VAR_BREACH_THRESHOLD (the SAME constant the desktop strip
// uses, so the two surfaces never disagree on what "breach" means).

interface MobileRiskViewProps {
  // The selected book, or null when none is chosen (mirrors App.tsx's
  // effectiveBookId). The view fetches its own VaR + limit for this book.
  bookId: string | null
}

export function MobileRiskView({ bookId }: MobileRiskViewProps) {
  const { varResult, loading } = useVaR(bookId)
  const { varLimit } = useVarLimit()

  if (loading && !varResult) {
    return (
      <div
        data-testid="mobile-risk-loading"
        className="flex items-center justify-center py-16 text-sm text-slate-500 dark:text-slate-400"
      >
        Loading risk…
      </div>
    )
  }

  if (!varResult) {
    return (
      <div
        data-testid="mobile-risk-empty"
        className="flex flex-col items-center justify-center gap-1 py-16 text-center"
      >
        <p className="text-sm font-medium text-slate-600 dark:text-slate-300">
          No VaR available
        </p>
        <p className="text-xs text-slate-400 dark:text-slate-500">
          Select a book with a recent risk calculation.
        </p>
      </div>
    )
  }

  const varValueNumber = Number(varResult.varValue)
  const hasLimit = varLimit !== null && varLimit > 0
  const utilisation = hasLimit ? varValueNumber / varLimit : null
  const breach = utilisation !== null && utilisation > VAR_BREACH_THRESHOLD

  const varValueText = formatMoney(varResult.varValue, 'USD')
  const limitText = hasLimit ? formatMoney(String(varLimit), 'USD') : '—'
  const utilisationPctText =
    utilisation !== null ? `${(utilisation * 100).toFixed(1)}%` : '—'
  // Clamp the bar so a breach over 100% does not overflow its track.
  const barWidthPct =
    utilisation !== null ? Math.min(utilisation * 100, 100) : 0

  // At red staleness the banner alone isn't enough — a 520-day-stale number
  // renders identically to a live one. Dim the card content so a stale number
  // reads as visually subordinate to the warning strip above it.
  const redStale = freshnessLevel(varResult.calculatedAt) === 'red'

  return (
    <div data-testid="mobile-risk-view" data-breach={breach}>
      <div className="-mx-4 -mt-4 mb-4">
        <MobileFreshnessBanner dataAsOf={varResult.calculatedAt} />
      </div>

      <section
        data-testid="mobile-risk-card"
        className={`rounded-lg border p-4 ${
          breach
            ? // Full-card red rail so a breach reads from the corner of the eye,
              // matching MobileAlertsView's CRITICAL card treatment so the
              // visual language stays consistent across the mobile surface.
              'border-red-300 bg-red-50 dark:border-red-800 dark:bg-red-900/30'
            : 'border-slate-200 bg-white dark:border-slate-700 dark:bg-surface-800'
        } ${redStale ? 'opacity-50' : ''}`}
      >
        <div className="flex items-baseline justify-between">
          <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
            VaR 1d 95%
          </h2>
          {breach && (
            <span
              data-testid="mobile-risk-breach-badge"
              className="text-xs font-bold uppercase tracking-wide px-2 py-0.5 rounded bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300"
            >
              Limit breach
            </span>
          )}
        </div>

        <p
          data-testid="mobile-risk-var-value"
          className={`mt-1 font-mono tabular-nums text-3xl font-bold ${
            breach
              ? 'text-red-600 dark:text-red-400'
              : 'text-slate-900 dark:text-slate-100'
          }`}
        >
          {varValueText}
        </p>

        <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
          <div>
            <dt className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
              Limit
            </dt>
            {hasLimit ? (
              <dd
                data-testid="mobile-risk-var-limit"
                className="font-mono tabular-nums text-slate-700 dark:text-slate-200"
              >
                {limitText}
              </dd>
            ) : (
              // No limit configured: an empty grey bar plus a bare "—" reads as
              // "0% used / no risk" — the dangerous opposite of the truth. Say
              // so explicitly, in amber, so absence reads as a state not a zero.
              <dd
                data-testid="mobile-risk-no-limit"
                className="text-xs font-medium text-amber-700 dark:text-amber-300"
              >
                No limit configured
              </dd>
            )}
          </div>
          <div>
            <dt className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
              Utilisation
            </dt>
            <dd
              data-testid="mobile-risk-utilisation"
              className={`font-mono tabular-nums ${
                breach
                  ? 'text-red-600 dark:text-red-400 font-semibold'
                  : 'text-slate-700 dark:text-slate-200'
              }`}
            >
              {utilisationPctText}
            </dd>
          </div>
        </dl>

        {hasLimit && (
          <div
            className="mt-3 h-2 w-full rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden"
            role="progressbar"
            aria-label="VaR limit utilisation"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={
              utilisation !== null ? Math.round(utilisation * 100) : undefined
            }
          >
            <div
              data-testid="mobile-risk-utilisation-bar"
              className={`h-full rounded-full transition-all ${
                breach ? 'bg-red-500 dark:bg-red-400' : 'bg-primary-500'
              }`}
              style={{ width: `${barWidthPct}%` }}
            />
          </div>
        )}
      </section>

      {/* Wayfinding: a single VaR card over a tall empty surface reads as
          "unfinished" or "still loading". A muted one-liner makes the space
          feel intentional and points to where the full breakdown lives. */}
      <p
        data-testid="mobile-risk-note"
        className="mt-3 text-xs text-slate-500 dark:text-slate-400"
      >
        VaR shown for the selected book — switch to Positions for the full
        exposure breakdown.
      </p>
    </div>
  )
}
