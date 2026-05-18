export interface SubTabBarTab {
  /** Stable identifier — passed back to `onSelect` when the tab is clicked. */
  id: string
  /** User-visible label rendered inside the tab button. */
  label: string
  /**
   * Optional count badge appended after the label. Rendered only when the
   * value is a positive number — `undefined`, `null`, or `0` render no badge.
   */
  count?: number
  /** Optional `data-testid` for end-to-end tests. */
  testId?: string
}

interface SubTabBarProps {
  /** Tabs rendered left-to-right. */
  tabs: SubTabBarTab[]
  /** Currently active tab id. The matching tab is rendered with the primary
   * underline / text colour and `aria-selected="true"`. */
  activeId: string
  /** Called with the clicked tab's id. The parent owns active-tab state. */
  onSelect: (id: string) => void
  /** Optional extra classes appended to the tablist container. */
  className?: string
  /** Accessible name for the tablist (e.g. "Trades sections"). */
  'aria-label'?: string
}

const TABLIST_CLASSES = 'flex gap-1 mb-4 border-b border-slate-200 dark:border-surface-700'

const BASE_TAB_CLASSES = 'px-4 py-2 text-sm font-medium border-b-2 transition-colors'

const ACTIVE_TAB_CLASSES = 'border-primary-500 text-primary-600 dark:text-primary-400'

const INACTIVE_TAB_CLASSES =
  'border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'

/**
 * Canonical sub-tab bar for a tab's inner sections (e.g. Trades > Blotter /
 * Place / Cost / Reconciliation, Risk > Dashboard / Intraday / Run Compare /
 * Market Data).
 *
 * Consolidates the two inline implementations in `App.tsx` (Trades) and
 * `RiskTab.tsx` so the active-state styling — including the dark-mode primary
 * text colour — stays in one place and cannot drift.
 */
export function SubTabBar({
  tabs,
  activeId,
  onSelect,
  className = '',
  'aria-label': ariaLabel,
}: SubTabBarProps) {
  const tablistClassName = `${TABLIST_CLASSES} ${className}`.trim()

  return (
    <div role="tablist" aria-label={ariaLabel} className={tablistClassName}>
      {tabs.map((tab) => {
        const isActive = tab.id === activeId
        const stateClasses = isActive ? ACTIVE_TAB_CLASSES : INACTIVE_TAB_CLASSES
        const hasCount = typeof tab.count === 'number' && tab.count > 0

        return (
          <button
            key={tab.id}
            role="tab"
            type="button"
            aria-selected={isActive}
            data-testid={tab.testId}
            onClick={() => onSelect(tab.id)}
            className={`${BASE_TAB_CLASSES} ${stateClasses}`}
          >
            {tab.label}
            {hasCount && (
              <span className="ml-2 inline-flex items-center justify-center rounded-full bg-slate-200 dark:bg-surface-700 px-2 py-0.5 text-xs font-semibold text-slate-700 dark:text-slate-200">
                {tab.count}
              </span>
            )}
          </button>
        )
      })}
    </div>
  )
}
