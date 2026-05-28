package com.kinetix.price.liquidity

/**
 * Per-instrument routing tier used by the price-service tick pipeline.
 *
 * This is a price-pipeline-internal routing taxonomy, NOT the canonical
 * market-tradability classification (`com.kinetix.common.model.LiquidityTier`,
 * derived from ADV + bid-ask spread). The tier selects which routing
 * policy the price pipeline applies to a tick — TIER_1 names pass any
 * tick straight through; lower tiers attract progressively tighter
 * stale-quote / dead-band checks; ILLIQUID names get the multi-hour
 * staleness threshold and the strictest tick filtering. See
 * `PriceStalenessMetric` for the alerting thresholds wired off this tier.
 */
enum class PriceRoutingTier { TIER_1, TIER_2, TIER_3, ILLIQUID }
