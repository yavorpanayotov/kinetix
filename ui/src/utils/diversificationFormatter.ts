import { formatMoney } from './format'

/**
 * Default epsilon below which a diversification benefit is treated as
 * rounding noise. Values like -$0.01 (0.00 %) carry no information for a
 * trader and only draw the eye, so anything with a magnitude under this
 * threshold collapses to `~$0`. Set above the cent so sub-cent and single-cent
 * noise both fold away, while a genuine $0.50 credit still renders.
 */
export const DEFAULT_DIVERSIFICATION_EPSILON = 0.05

/** Display string for a collapsed (rounding-noise) diversification value. */
export const NEAR_ZERO_LABEL = '~$0'

/**
 * Format a diversification benefit (a positive magnitude representing the VaR
 * reduction from diversification) for display.
 *
 * Diversification reduces portfolio VaR, so a real benefit is shown as a credit
 * (`-$X`). Values whose magnitude falls below `epsilon` — pure rounding noise —
 * collapse to `~$0` so the column does not draw the eye. Non-finite values
 * (NaN, Infinity) also collapse to `~$0` rather than leaking `$NaN`.
 *
 * @param value   the diversification benefit; sign is ignored (magnitude used).
 * @param epsilon collapse threshold; defaults to {@link DEFAULT_DIVERSIFICATION_EPSILON}.
 */
export function formatDiversificationBenefit(
  value: number,
  epsilon: number = DEFAULT_DIVERSIFICATION_EPSILON,
): string {
  if (!Number.isFinite(value) || Math.abs(value) < epsilon) {
    return NEAR_ZERO_LABEL
  }
  return `-${formatMoney(Math.abs(value).toFixed(2), 'USD')}`
}
