// Em-dash placeholder formatter for null numeric cells (kx-c3az).
//
// Risk tables pull values from many sources — sometimes a Greek hasn't been
// computed yet, a curve point is missing, or an API call returned an error.
// Rendering "0", "NaN", or an empty cell in those situations is misleading:
// an empty cell looks like a layout bug, and "NaN"/"Infinity" looks like a
// calculation error that traders will chase down. The convention across the
// platform is to display an em-dash ("—") so the cell visually reads as
// "no value here yet" rather than "the value is bad".
//
// The helper accepts the value plus optional overrides for the number of
// fraction digits and the placeholder string. Non-finite inputs (null,
// undefined, NaN, ±Infinity) collapse to the placeholder; everything else
// is rendered via `Number.prototype.toFixed` so callers do not have to
// remember to round at every callsite.

export interface FormatNumericOptions {
  /** Number of digits after the decimal point. Defaults to 2. */
  fractionDigits?: number
  /** Replacement string for non-finite values. Defaults to an em-dash. */
  placeholder?: string
}

const EM_DASH = '—'

/**
 * Format a (possibly missing) numeric value for display in a table cell.
 *
 * Returns the configured placeholder (em-dash by default) when the value
 * is null, undefined, NaN, or ±Infinity. Otherwise the value is rounded
 * to the requested number of fraction digits (default 2).
 */
export function formatNumeric(
  value: number | null | undefined,
  options: FormatNumericOptions = {},
): string {
  const { fractionDigits = 2, placeholder = EM_DASH } = options
  if (value === null || value === undefined) return placeholder
  if (!Number.isFinite(value)) return placeholder
  return value.toFixed(fractionDigits)
}
