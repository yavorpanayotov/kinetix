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

/**
 * Tooltip-friendly Rho format that appends a "$/bp IR" unit suffix.
 *
 * Rho measures the change in option value for a 1 basis point shift in
 * the interest rate curve. Surfacing the unit alongside the number in
 * tooltips removes ambiguity — traders never have to guess whether a
 * value is per basis point, per percent, per dollar of notional, etc.
 *
 * Non-finite inputs collapse to the placeholder (em-dash) so a missing
 * Rho doesn't render a bare "$/bp IR" suffix on its own.
 */
export function formatRhoTooltip(
  value: number | null | undefined,
  options: FormatNumericOptions = {},
): string {
  const { fractionDigits = 2, placeholder = EM_DASH } = options
  if (value === null || value === undefined) return placeholder
  if (!Number.isFinite(value)) return placeholder
  return `${value.toFixed(fractionDigits)} $/bp IR`
}

/**
 * Tooltip-friendly Vega format that appends a "%/1pp vol" unit suffix.
 *
 * Vega measures the change in option value per 1 percentage-point shift
 * in implied volatility, expressed in the option's currency. The "1 vol
 * point" unit is the standard confusion — depending on the vendor it can
 * mean a basis point or a percentage point. Surfacing the explicit unit
 * alongside the number in tooltips removes that ambiguity.
 *
 * Non-finite inputs collapse to the placeholder (em-dash) so a missing
 * Vega doesn't render a bare "%/1pp vol" suffix on its own.
 */
export function formatVegaTooltip(
  value: number | null | undefined,
  options: FormatNumericOptions = {},
): string {
  const { fractionDigits = 2, placeholder = EM_DASH } = options
  if (value === null || value === undefined) return placeholder
  if (!Number.isFinite(value)) return placeholder
  return `${value.toFixed(fractionDigits)} %/1pp vol`
}

/**
 * Format a numeric value with zero-padding on the integer part so values
 * in a decimal column line up on the decimal point.
 *
 * Decimal columns in risk tables are read by scanning down a vertical
 * line — Δ values for one portfolio, then another, then a third. When the
 * integer widths vary (3.14, 11.2, 1234.5), the decimal points zig-zag
 * and the eye loses its place. Padding the integer part to a fixed width
 * with figure spaces (U+2007, the digit-width whitespace character that
 * does not collapse in HTML) keeps the column aligned without forcing
 * fixed-width fonts on the whole document.
 *
 * Non-finite inputs collapse to the placeholder (em-dash) so a missing
 * Greek does not render as "  —" or "0000.00".
 */
export interface FormatZeroPaddedOptions extends FormatNumericOptions {
  /** Number of digits to pad the integer part to (default 4). */
  integerWidth?: number
}

export function formatZeroPadded(
  value: number | null | undefined,
  options: FormatZeroPaddedOptions = {},
): string {
  const {
    fractionDigits = 2,
    placeholder = EM_DASH,
    integerWidth = 4,
  } = options
  if (value === null || value === undefined) return placeholder
  if (!Number.isFinite(value)) return placeholder
  const isNegative = value < 0
  const absolute = Math.abs(value)
  const fixed = absolute.toFixed(fractionDigits)
  const [intPart, fracPart] = fixed.split('.')
  // U+2007 figure space — same width as a digit and does not collapse.
  const FIGURE_SPACE = ' '
  const padded = intPart.padStart(integerWidth, FIGURE_SPACE)
  const body = fracPart === undefined ? padded : `${padded}.${fracPart}`
  // Always prefix with the sign slot so positives and negatives line up.
  // U+2212 minus sign for negatives; figure space for positives (same width).
  return `${isNegative ? '−' : FIGURE_SPACE}${body}`
}
