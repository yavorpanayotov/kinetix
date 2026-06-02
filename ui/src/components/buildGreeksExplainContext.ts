import type { GreeksResultDto } from '../types'

/**
 * `page_context` builder for the Greeks panel inline explainer
 * (docs/plans/ai-v2.md §9.5).
 *
 * The aggregate Greeks card (`<RiskSensitivities>`) renders per-asset-class
 * delta / gamma / vega plus book-level theta / rho. The card-level
 * `<ExplainButton>` opens an `<AIInsightPanel>` streaming `/chat`; this
 * helper extends the ambient copilot context (`useCopilotContext()`) with
 * the *aggregate* Greeks figures so the model can speak to the book's net
 * sensitivity rather than picking through individual asset classes.
 *
 * Kept in its own file (CLAUDE.md "Code Organisation": focused helper
 * files) and pure so it can be unit-tested in isolation.
 */

/** Aggregate Greeks figures attached to the explainer's `page_context`. */
export interface AggregateGreeks {
  /** Net delta summed across all asset classes. */
  delta: number
  /** Net gamma summed across all asset classes. */
  gamma: number
  /** Net vega summed across all asset classes. */
  vega: number
  /** Book-level theta (time decay). */
  theta: number
  /** Book-level rho (rate sensitivity). */
  rho: number
}

/**
 * Sum the per-asset-class Greeks into book-level aggregates.
 *
 * Delta / gamma / vega are carried per asset class on `assetClassGreeks`;
 * theta / rho are already book-level scalars on the result.
 */
export function aggregateGreeks(greeks: GreeksResultDto): AggregateGreeks {
  let delta = 0
  let gamma = 0
  let vega = 0
  for (const g of greeks.assetClassGreeks) {
    delta += Number(g.delta)
    gamma += Number(g.gamma)
    vega += Number(g.vega)
  }
  return {
    delta,
    gamma,
    vega,
    theta: Number(greeks.theta),
    rho: Number(greeks.rho),
  }
}

/**
 * Build the `page_context` for the aggregate Greeks inline explainer.
 *
 * Extends the ambient copilot context with the book scope and the
 * aggregate Greeks payload (plan §9.5).
 */
export function buildGreeksExplainContext(
  base: Record<string, unknown>,
  greeks: GreeksResultDto,
): Record<string, unknown> {
  return {
    ...base,
    page: 'greeks',
    book_id: greeks.bookId,
    as_of: greeks.calculatedAt,
    aggregate_greeks: aggregateGreeks(greeks),
  }
}
