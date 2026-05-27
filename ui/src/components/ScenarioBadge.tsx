import { Layers, Loader2 } from 'lucide-react'
import type { MarketRegime } from '../types'

interface ScenarioBadgeProps {
  scenario: string | null
  regime: MarketRegime | null
  /**
   * Optional override for the visible label.
   * Defaults to a compact "scenario" / "regime-adj" indicator with a tooltip.
   */
  className?: string
  /**
   * Render an animated spinner instead of the scenario/regime annotation
   * while the scenario is being computed. Previously the badge fell back to
   * the plain word "Loading" which is both visually flat and silent for the
   * eye — operators couldn't tell whether the screen was frozen or working.
   * The spinner gives them obvious motion; an SR-only `<span role="status">`
   * preserves the announcement for assistive tech (kx-1crp).
   */
  loading?: boolean
}

const SCENARIO_LABELS: Record<string, string> = {
  'multi-asset': 'Multi-Asset',
  'equity-ls': 'Equity L/S',
  'options-book': 'Options Book',
  'stress': 'Stress',
  'regulatory': 'Regulatory',
}

function scenarioLabel(scenario: string): string {
  return SCENARIO_LABELS[scenario] ?? scenario
}

/**
 * A compact annotation chip that flags a risk number as having come out of a
 * scenario run or as being adjusted by the active market regime.
 *
 * Renders nothing when no scenario is active and the regime is null / NORMAL
 * — unless `loading` is true, in which case we render a spinner so the user
 * gets early feedback that a scenario is being computed.
 *
 * See plan §1.2: "the header pills tell you *that* a scenario is active, but
 * the numbers on screen don't say *whether* they reflect it."
 */
export function ScenarioBadge({
  scenario,
  regime,
  className,
  loading,
}: ScenarioBadgeProps) {
  const hasScenario = scenario !== null && scenario !== ''
  const hasAdjustedRegime = regime !== null && regime !== 'NORMAL'

  const containerClasses =
    'inline-flex items-center gap-1 px-1.5 py-0.5 text-[10px] font-medium tracking-wide rounded ' +
    'border border-indigo-300/60 bg-indigo-50 text-indigo-700 ' +
    'dark:border-indigo-500/30 dark:bg-indigo-500/10 dark:text-indigo-300 ' +
    'select-none align-middle ' +
    (className ?? '')

  if (loading) {
    // While loading we don't yet know which scenario will land, so the visible
    // payload is purely a spinner — no stale labels, no plain "Loading" text.
    return (
      <span
        data-testid="scenario-badge"
        data-loading="true"
        aria-label="Loading scenario"
        title="Loading scenario"
        className={containerClasses}
      >
        <Loader2
          data-testid="scenario-badge-spinner"
          className="h-2.5 w-2.5 animate-spin"
          aria-hidden="true"
        />
        {/* Visually hidden, still announced by screen readers. */}
        <span role="status" className="sr-only">
          Loading scenario
        </span>
      </span>
    )
  }

  if (!hasScenario && !hasAdjustedRegime) return null

  const parts: string[] = []
  if (hasScenario) parts.push(`Reflects active scenario: ${scenarioLabel(scenario)}`)
  if (hasAdjustedRegime) parts.push(`Regime-adjusted (${regime})`)
  const ariaLabel = parts.join(' — ')

  const visibleParts: string[] = []
  if (hasScenario) visibleParts.push('scenario')
  if (hasAdjustedRegime) visibleParts.push('regime-adj')
  const visibleText = visibleParts.join(' · ')

  const dataAttrs: Record<string, string> = {}
  if (hasScenario) dataAttrs['data-scenario'] = scenario
  if (hasAdjustedRegime) dataAttrs['data-regime'] = regime

  return (
    <span
      data-testid="scenario-badge"
      {...dataAttrs}
      aria-label={ariaLabel}
      title={ariaLabel}
      className={containerClasses}
    >
      <Layers className="h-2.5 w-2.5" aria-hidden="true" />
      {visibleText}
    </span>
  )
}
