import { Layers } from 'lucide-react'
import type { MarketRegime } from '../types'

interface ScenarioBadgeProps {
  scenario: string | null
  regime: MarketRegime | null
  /**
   * Optional override for the visible label.
   * Defaults to a compact "scenario" / "regime-adj" indicator with a tooltip.
   */
  className?: string
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
 * Renders nothing when no scenario is active and the regime is null / NORMAL.
 *
 * See plan §1.2: "the header pills tell you *that* a scenario is active, but
 * the numbers on screen don't say *whether* they reflect it."
 */
export function ScenarioBadge({ scenario, regime, className }: ScenarioBadgeProps) {
  const hasScenario = scenario !== null && scenario !== ''
  const hasAdjustedRegime = regime !== null && regime !== 'NORMAL'

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
      className={
        'inline-flex items-center gap-1 px-1.5 py-0.5 text-[10px] font-medium tracking-wide rounded ' +
        'border border-indigo-300/60 bg-indigo-50 text-indigo-700 ' +
        'dark:border-indigo-500/30 dark:bg-indigo-500/10 dark:text-indigo-300 ' +
        'select-none align-middle ' +
        (className ?? '')
      }
    >
      <Layers className="h-2.5 w-2.5" aria-hidden="true" />
      {visibleText}
    </span>
  )
}
