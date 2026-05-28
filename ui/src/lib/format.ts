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

/**
 * Format a currency amount with the symbol immediately preceding the
 * value, right-aligned for table cells.
 *
 * Risk tables show currency amounts across many rows — USD positions, EUR
 * positions, JPY positions — and the eye needs to scan the *amounts*
 * vertically. Putting the symbol immediately before the digits (no space)
 * lets the rendering engine right-align the cell on the last digit, which
 * keeps the decimal points lined up even when symbol widths differ
 * ($, €, ¥ all render at one glyph wide; £ is similar). The helper also
 * collapses non-finite inputs to the em-dash placeholder so a missing
 * value never renders as a bare currency symbol.
 */
export interface FormatCurrencyPrefixOptions extends FormatNumericOptions {
  /** Currency symbol or short code to prepend (e.g. "$", "€", "£", "kr"). */
  symbol: string
}

export function formatCurrencyPrefix(
  value: number | null | undefined,
  options: FormatCurrencyPrefixOptions,
): string {
  const { fractionDigits = 2, placeholder = EM_DASH, symbol } = options
  if (value === null || value === undefined) return placeholder
  if (!Number.isFinite(value)) return placeholder
  // Negatives render as "-$1234.56" so the sign reads before the symbol —
  // matches the accounting convention used in trading blotters.
  const isNegative = value < 0
  const absolute = Math.abs(value)
  return `${isNegative ? '-' : ''}${symbol}${absolute.toFixed(fractionDigits)}`
}

/**
 * Format a fractional ratio as a percentage with normalised precision.
 *
 * The platform was rendering rates inconsistently — "5.25%" in one cell,
 * "5.2500%" in another — because each callsite picked its own fraction
 * digit count. This helper standardises on 2 fraction digits (the common
 * trader convention for fixed-income coupons and IR-curve points) and
 * trims trailing zeros so 5% reads as "5%" and 5.5% reads as "5.5%",
 * never "5.0000%" or "5.5000%". Callers override [fractionDigits] when
 * they need more precision (e.g. spread tables in basis points).
 *
 * The input is the *fraction* (0.0525 = 5.25%), matching the convention
 * used by [formatRhoTooltip] and the upstream API types. Non-finite
 * inputs collapse to the em-dash placeholder.
 */
export function formatPercent(
  fraction: number | null | undefined,
  options: FormatNumericOptions = {},
): string {
  const { fractionDigits = 2, placeholder = EM_DASH } = options
  if (fraction === null || fraction === undefined) return placeholder
  if (!Number.isFinite(fraction)) return placeholder
  const fixed = (fraction * 100).toFixed(fractionDigits)
  // Trim trailing zeros after the decimal point so "5.0000" -> "5"
  // and "5.5000" -> "5.5", but leave integers like "5" alone.
  const trimmed = fixed.includes('.') ? fixed.replace(/\.?0+$/, '') : fixed
  return `${trimmed}%`
}

/**
 * Format a Greek value with an explicit "+" prefix for non-negatives.
 *
 * Greeks columns in the risk blotter render P&L sensitivities — delta,
 * vega, theta — and the sign is meaningful: positive vega means the book
 * benefits from a vol uptick, negative vega means it bleeds. A bare
 * "100" cell is ambiguous (is it +100 or -100?). The explicit "+"
 * prefix anchors the sign so the eye picks it up at the same place it
 * reads "-" for negatives. Zero renders without a sign prefix (a "+0"
 * suggests a rounding artefact). Non-finite values collapse to the
 * em-dash placeholder.
 */
export function formatGreekWithSign(
  value: number | null | undefined,
  options: FormatNumericOptions = {},
): string {
  const { fractionDigits = 2, placeholder = EM_DASH } = options
  if (value === null || value === undefined) return placeholder
  if (!Number.isFinite(value)) return placeholder
  if (value === 0) return value.toFixed(fractionDigits)
  return value > 0
    ? `+${value.toFixed(fractionDigits)}`
    : value.toFixed(fractionDigits)
}

/**
 * Format a numeric value with thousands separators for table cell
 * legibility (e.g. "1,234,567.89").
 *
 * Currency and notional columns in the position blotter run to seven or
 * eight digits — without a thousands separator the eye loses count and
 * mis-reads "1234567" as "12345" plus three trailing noise digits.
 * `Number.prototype.toLocaleString` is fast and locale-aware; this helper
 * pins the locale to `en-US` so the separator is always a comma
 * (matching Bloomberg / Reuters convention) regardless of the trader's
 * browser locale. Non-finite values collapse to the em-dash.
 */
export function formatWithThousands(
  value: number | null | undefined,
  options: FormatNumericOptions = {},
): string {
  const { fractionDigits = 2, placeholder = EM_DASH } = options
  if (value === null || value === undefined) return placeholder
  if (!Number.isFinite(value)) return placeholder
  return value.toLocaleString('en-US', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  })
}
