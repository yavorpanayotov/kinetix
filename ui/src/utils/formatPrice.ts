/**
 * Asset-class-aware price formatter (kx-m2v).
 *
 * The "obvious right" precision differs by asset class. A trader who sees
 *   `EUR/USD 1.09` thinks "synthetic". A trader who sees `EUR/USD 1.08543`
 * thinks "wire price". Equity prices to the cent, bond prices to fractional
 * cents, FX prices to four or five decimals — the precision IS the signal.
 *
 * Decimals chosen per asset class:
 *   - FX: 4 decimals (5 if minor pair, kept simple here at 4)
 *   - BOND / FIXED_INCOME: 3 decimals
 *   - EQUITY / COMMODITY / OPTION / default: 2 decimals
 */
export function priceDecimals(assetClass: string | undefined): number {
  if (!assetClass) return 2
  const upper = assetClass.toUpperCase()
  if (upper === 'FX' || upper === 'FX_SPOT' || upper === 'FOREX') return 4
  if (upper === 'BOND' || upper === 'FIXED_INCOME') return 3
  return 2
}

/**
 * Render an amount as a USD-formatted price string with precision driven by
 * `assetClass`. Falls back to `${amount} ${currency}` for non-USD currencies,
 * matching the convention used by {@link import('./format').formatMoney}.
 */
export function formatPrice(
  amount: string | number | null | undefined,
  currency: string,
  assetClass: string | undefined,
): string {
  if (amount === null || amount === undefined || amount === '') return '—'
  const numeric = typeof amount === 'string' ? Number(amount) : amount
  if (!Number.isFinite(numeric)) return String(amount)
  const decimals = priceDecimals(assetClass)
  try {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency,
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals,
    }).format(numeric)
  } catch {
    // Unknown currency code — Intl will throw. Fall back to a numeric string
    // tagged with the raw currency so the column never renders blank.
    return `${numeric.toFixed(decimals)} ${currency}`
  }
}
