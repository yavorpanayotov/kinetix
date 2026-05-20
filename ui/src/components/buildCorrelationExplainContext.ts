/**
 * `page_context` builder for the correlation matrix inline explainer
 * (plans/ai-v2.md §9.5).
 *
 * The correlation matrix (`<CorrelationHeatmap>`) renders a symmetric
 * asset-class correlation grid. The matrix-level `<ExplainButton>` opens
 * an `<AIInsightPanel>` streaming `/chat` focused on **correlation
 * breaks** — the asset-class pairs whose correlation is far enough from
 * zero to materially affect diversification.
 *
 * The matrix component has no prior-day surface to diff against, so a
 * "break" here is derived from the matrix data already on screen: an
 * off-diagonal pair whose absolute correlation crosses
 * {@link CORRELATION_BREAK_THRESHOLD}. This keeps the helper purely
 * derivative — it fetches nothing new (plan §9.5).
 *
 * Kept in its own file (CLAUDE.md "Code Organisation": focused helper
 * files) and pure so it can be unit-tested in isolation.
 */

/**
 * Absolute-correlation magnitude at or above which an off-diagonal pair
 * is surfaced as a correlation "break" — a pair concentrated enough to
 * dent diversification benefit.
 */
export const CORRELATION_BREAK_THRESHOLD = 0.35

/** A single correlation pair surfaced to the explainer. */
export interface CorrelationBreak {
  /** First asset class in the pair. */
  a: string
  /** Second asset class in the pair. */
  b: string
  /** Correlation coefficient for the pair, in [-1, 1]. */
  correlation: number
}

/**
 * Derive the correlation "breaks" from the matrix already on screen.
 *
 * Walks the upper triangle (each unordered pair once, diagonal
 * excluded), keeps pairs whose absolute correlation is at or above
 * {@link CORRELATION_BREAK_THRESHOLD}, and ranks them most-extreme
 * first so the model leads with the dominant concentration.
 */
export function deriveCorrelationBreaks(
  classes: string[],
  matrix: Record<string, Record<string, number>>,
): CorrelationBreak[] {
  const breaks: CorrelationBreak[] = []
  for (let i = 0; i < classes.length; i += 1) {
    for (let j = i + 1; j < classes.length; j += 1) {
      const a = classes[i]
      const b = classes[j]
      const correlation = matrix[a]?.[b]
      if (correlation === undefined) continue
      if (Math.abs(correlation) >= CORRELATION_BREAK_THRESHOLD) {
        breaks.push({ a, b, correlation })
      }
    }
  }
  return breaks.sort(
    (x, y) => Math.abs(y.correlation) - Math.abs(x.correlation),
  )
}

/**
 * Build the `page_context` for the correlation matrix inline explainer.
 *
 * Extends the ambient copilot context with the matrix scope (the asset
 * classes shown) and the derived correlation breaks (plan §9.5).
 */
export function buildCorrelationExplainContext(
  base: Record<string, unknown>,
  classes: string[],
  matrix: Record<string, Record<string, number>>,
): Record<string, unknown> {
  return {
    ...base,
    page: 'correlation-matrix',
    asset_classes: classes,
    correlation_breaks: deriveCorrelationBreaks(classes, matrix),
  }
}
