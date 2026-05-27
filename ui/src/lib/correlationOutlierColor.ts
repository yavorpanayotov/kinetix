// Correlation heatmap outlier highlight helper (kx-44pj).
//
// The correlation matrix view shows hundreds of pairwise values; the human eye
// can spot a single bright cell but glazes over a dense grid of near-mean
// numbers. To draw attention to *unusual* correlations — pairs that suddenly
// decorrelate, or two normally-independent series that have started moving
// together — we flag any cell whose value sits more than 2σ from the
// population mean.
//
// The threshold of 2σ corresponds to the conventional ~5% tail under a normal
// approximation. Standard deviation is computed with Bessel's correction
// (n − 1 in the denominator) because the values passed in are treated as a
// *sample* of the matrix, not the entire population — this matches how the
// rest of the analytics in the platform compute realised vol and tracking
// error from observed series.
//
// Positive outliers are flagged warm red (#ef4444) and negative outliers cool
// blue (#3b82f6). Values inside the 2σ band — or where the helper cannot
// form a meaningful judgement (non-finite input, empty population, zero
// variance with the value matching the constant mean) — return `null` so
// callers can omit the highlight and let the default heatmap colour through.

const POSITIVE_OUTLIER = '#ef4444'
const NEGATIVE_OUTLIER = '#3b82f6'
const SIGMA_THRESHOLD = 2

function mean(values: readonly number[]): number {
  let sum = 0
  for (const v of values) sum += v
  return sum / values.length
}

function sampleStdDev(values: readonly number[], mu: number): number {
  if (values.length < 2) return 0
  let sumSq = 0
  for (const v of values) {
    const d = v - mu
    sumSq += d * d
  }
  return Math.sqrt(sumSq / (values.length - 1))
}

/**
 * Returns a highlight colour for `value` when it is more than 2 sample
 * standard deviations from the mean of `population`. Returns `null` when
 * the value is inside the band, the population is too small to form a
 * statistic, the population has zero variance and `value` equals the mean,
 * or `value` itself is non-finite.
 *
 * Positive outliers return the platform's warm red; negative outliers
 * return the cool blue used for "below normal" cues elsewhere.
 */
export function correlationOutlierColor(
  value: number,
  population: readonly number[],
): string | null {
  if (!Number.isFinite(value)) return null
  if (population.length === 0) return null

  const mu = mean(population)
  const sigma = sampleStdDev(population, mu)

  if (sigma === 0) {
    // Constant population — there's no "spread" to compare against. If the
    // incoming value matches the constant, nothing to highlight; otherwise
    // any deviation is by definition an outlier.
    if (value === mu) return null
    return value > mu ? POSITIVE_OUTLIER : NEGATIVE_OUTLIER
  }

  const distance = Math.abs(value - mu)
  if (distance <= SIGMA_THRESHOLD * sigma) return null
  return value > mu ? POSITIVE_OUTLIER : NEGATIVE_OUTLIER
}
