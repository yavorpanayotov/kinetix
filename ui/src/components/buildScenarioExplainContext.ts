import type { StressTestResultDto } from '../types'

/**
 * How many of a scenario's stressed positions to attach to the inline
 * explainer `page_context`. The copilot only needs the worst offenders to
 * speak to *why* a scenario hurt — sending every position would bloat the
 * request for no analytical gain.
 */
const TOP_STRESSED_POSITIONS = 5

/** A stressed position trimmed to the fields the explainer needs. */
export interface ScenarioTopPosition {
  instrumentId: string
  assetClass: string
  pnlImpact: string
  percentageOfTotal: string
}

/**
 * Build the `page_context` for a scenario-row inline explainer `/chat`
 * call (plan §9.4 — inline explainer on the Stress / Scenarios panel).
 *
 * Extends the ambient copilot context (`useCopilotContext()`) with the
 * clicked scenario's payload — scenario name, stressed P&L, and the
 * top-N stressed positions derived from the result's `positionImpacts`
 * — so the model can speak to *that* specific scenario.
 *
 * "Top stressed positions" are the positions with the largest absolute
 * P&L impact under the scenario; nothing is fetched — the ranking is
 * derived from the `positionImpacts` the result already carries.
 */
export function buildScenarioExplainContext(
  base: Record<string, unknown>,
  result: StressTestResultDto,
): Record<string, unknown> {
  return {
    ...base,
    page: 'scenarios',
    scenario_name: result.scenarioName,
    stressed_pnl: result.pnlImpact,
    stressed_var: result.stressedVar,
    top_stressed_positions: topStressedPositions(result),
  }
}

/**
 * The scenario's stressed positions, ranked by absolute P&L impact
 * (worst first) and capped at {@link TOP_STRESSED_POSITIONS}. Returns an
 * empty array when the result carries no `positionImpacts`.
 */
export function topStressedPositions(
  result: StressTestResultDto,
): ScenarioTopPosition[] {
  const impacts = result.positionImpacts ?? []
  return [...impacts]
    .sort((a, b) => Math.abs(Number(b.pnlImpact)) - Math.abs(Number(a.pnlImpact)))
    .slice(0, TOP_STRESSED_POSITIONS)
    .map((p) => ({
      instrumentId: p.instrumentId,
      assetClass: p.assetClass,
      pnlImpact: p.pnlImpact,
      percentageOfTotal: p.percentageOfTotal,
    }))
}
