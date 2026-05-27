// Per-asset-class position-concentration colour helper (kx-7dbn).
//
// A 30% single-name weight means very different things in equities, FX, and
// crypto. This helper maps a (weight, asset-class) pair to a RAG colour by
// looking up the asset class's risk profile and comparing the weight against
// the class's warn / breach thresholds.
//
// Profiles are tuned to typical institutional concentration limits:
//
//   equity:    warn 25%, breach 40%
//   fx:        warn 15%, breach 30%
//   crypto:    warn 10%, breach 20%
//   rates:     warn 35%, breach 60%
//   commodity: warn 20%, breach 35%
//
// Unknown asset classes fall back to the strictest (crypto-style) profile so
// a brand-new instrument type never silently renders green.

const GREEN_HEX = '#22c55e'
const AMBER_HEX = '#f59e0b'
const RED_HEX = '#ef4444'

interface ConcentrationProfile {
  readonly warn: number
  readonly breach: number
}

const PROFILES: Record<string, ConcentrationProfile> = {
  equity: { warn: 25, breach: 40 },
  fx: { warn: 15, breach: 30 },
  crypto: { warn: 10, breach: 20 },
  rates: { warn: 35, breach: 60 },
  commodity: { warn: 20, breach: 35 },
}

// Conservative fallback used for asset classes the helper doesn't recognise.
// Picking the strictest known profile ensures a new instrument type can't
// silently render green at a weight that any sane risk team would flag.
const FALLBACK_PROFILE: ConcentrationProfile = PROFILES.crypto

/**
 * Returns a hex colour (e.g. "#22c55e") representing the concentration risk
 * of holding `weight` percent of a portfolio in a single position of the
 * given `assetClass`.
 *
 * Negative weights clamp to green (a short or zero position carries no
 * concentration risk by definition). Non-finite weights (NaN, Infinity)
 * return red so missing readings stay visible.
 */
export function concentrationColor(weight: number, assetClass: string): string {
  if (!Number.isFinite(weight)) return RED_HEX
  if (weight <= 0) return GREEN_HEX

  const profile = PROFILES[assetClass.toLowerCase()] ?? FALLBACK_PROFILE
  if (weight >= profile.breach) return RED_HEX
  if (weight >= profile.warn) return AMBER_HEX
  return GREEN_HEX
}
