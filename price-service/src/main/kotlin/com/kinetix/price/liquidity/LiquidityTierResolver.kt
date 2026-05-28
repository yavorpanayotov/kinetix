package com.kinetix.price.liquidity

/**
 * Resolve a per-instrument liquidity tier, with a configurable
 * policy for the missing-tier case.
 *
 * Price-service routes ticks through this resolver — TIER_1 names
 * pass any tick; illiquid names get a stale-quote / dead-band check.
 * Missing tier (new listing, feed glitch) falls back to ILLIQUID by
 * default — losing a tick is worse than running extra checks, but
 * callers wanting fail-closed semantics can flip [rejectOnMissing].
 *
 * @throws IllegalStateException when [rejectOnMissing] is true and
 * the tier is missing.
 */
fun resolveLiquidityTier(
    instrumentId: String,
    knownTiers: Map<String, LiquidityTier>,
    rejectOnMissing: Boolean = false,
): LiquidityTier {
    val tier = knownTiers[instrumentId]
    if (tier != null) return tier
    check(!rejectOnMissing) {
        "no liquidity tier configured for instrument '$instrumentId'"
    }
    return LiquidityTier.ILLIQUID
}
