import { Sunrise } from 'lucide-react'
import { formatRelativeTime } from '../utils/format'
import type { BriefSection, MorningBrief } from '../api/brief'
import { CitationList } from './CitationList'

/**
 * Plan §6.10 — morning-brief card.
 *
 * A pure-presentational card rendered at the top of the notification
 * inbox (<NotificationInbox>). The parent owns fetching via
 * `fetchTodayBrief`; this component only lays out a fetched
 * <MorningBrief>: a header (book + generation time), an optional
 * "Demo mode" badge for canned briefs, and one block per section with
 * a severity-coloured accent, narrative, bullet list, and — when the
 * section carries provenance — a <CitationList> footer.
 */

export interface MorningBriefCardProps {
  brief: MorningBrief
}

/** Section accent colour classes keyed by severity. */
const ACCENT_CLASS: Record<BriefSection['severity'], string> = {
  info: 'bg-slate-300 dark:bg-slate-600',
  warning: 'bg-amber-400 dark:bg-amber-500',
  critical: 'bg-red-500 dark:bg-red-500',
}

export function MorningBriefCard({ brief }: MorningBriefCardProps) {
  return (
    <section
      data-testid="morning-brief-card"
      aria-label="Morning brief"
      className="border-b border-slate-200 dark:border-surface-700 bg-white dark:bg-surface-800 p-3"
    >
      <header className="flex items-center gap-2">
        <Sunrise
          className="h-4 w-4 flex-shrink-0 text-amber-500"
          aria-hidden="true"
        />
        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100">
          Morning Brief
        </h3>
        <span className="text-xs font-medium text-slate-500 dark:text-slate-400">
          {brief.book_id}
        </span>
        <span className="ml-auto text-[10px] font-mono text-slate-400 dark:text-slate-500">
          {formatRelativeTime(brief.generated_at)}
        </span>
        {brief.mode === 'canned' && (
          <span
            data-testid="morning-brief-demo-badge"
            className="rounded bg-amber-100 dark:bg-amber-900/30 px-2 py-0.5 text-[10px] font-medium text-amber-800 dark:text-amber-300"
          >
            Demo mode
          </span>
        )}
      </header>

      <div className="mt-2 space-y-3">
        {brief.sections.map((section, index) => (
          <div
            key={`${section.title}-${index}`}
            data-testid={`brief-section-${index}`}
            className="flex gap-2"
          >
            <span
              data-testid="brief-section-accent"
              aria-hidden="true"
              className={`mt-0.5 w-1 flex-shrink-0 rounded-full ${ACCENT_CLASS[section.severity]}`}
            />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <h4 className="text-xs font-semibold text-slate-800 dark:text-slate-100">
                  {section.title}
                </h4>
                {section.status !== 'ok' && (
                  <span
                    data-testid="brief-section-status"
                    className="rounded bg-slate-200 dark:bg-surface-700 px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400"
                  >
                    {section.status}
                  </span>
                )}
              </div>
              <p className="mt-0.5 text-xs leading-relaxed text-slate-600 dark:text-slate-300">
                {section.narrative}
              </p>
              {section.bullets.length > 0 && (
                <ul className="mt-1 list-disc pl-4 space-y-0.5 text-xs text-slate-600 dark:text-slate-300">
                  {section.bullets.map((bullet, i) => (
                    <li key={i}>{bullet}</li>
                  ))}
                </ul>
              )}
              {section.sources.length > 0 && (
                <CitationList citations={section.sources} />
              )}
            </div>
          </div>
        ))}
      </div>
      <p className="mt-2 text-xs text-slate-400">
        Dashboards remain the source of truth. The Copilot narrates them, it doesn&apos;t replace them.
      </p>
    </section>
  )
}

export default MorningBriefCard
