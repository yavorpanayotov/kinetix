package com.kinetix.refdata.instrument

/**
 * Pre-trade liquidity classification for an instrument.
 *
 * The four tiers map ADV (average daily volume in USD) to a coarse
 * blocking decision: TIER_1 trades without hesitation, TIER_2 has
 * mild market impact at block size, TIER_3 requires a quote
 * (request-for-quote), ILLIQUID requires desk approval.
 *
 * The `blockSizeWarningThresholdUsd()` lets pre-trade controls flag
 * an order whose size exceeds the tier's typical absorbable block;
 * downstream services consume this rather than re-deriving the
 * thresholds.
 */
enum class LiquidityTier {
    TIER_1,
    TIER_2,
    TIER_3,
    ILLIQUID;

    fun isIlliquid(): Boolean = this == ILLIQUID

    fun blockSizeWarningThresholdUsd(): Double = when (this) {
        TIER_1 -> 100_000_000.0
        TIER_2 -> 10_000_000.0
        TIER_3 -> 1_000_000.0
        ILLIQUID -> 100_000.0
    }

    companion object {
        /** Threshold boundaries in ADV USD. */
        private const val TIER_1_FROM = 1_000_000_000.0
        private const val TIER_2_FROM = 100_000_000.0
        private const val TIER_3_FROM = 1_000_000.0

        fun classify(averageDailyVolumeUsd: Double): LiquidityTier {
            if (averageDailyVolumeUsd >= TIER_1_FROM) return TIER_1
            if (averageDailyVolumeUsd >= TIER_2_FROM) return TIER_2
            if (averageDailyVolumeUsd >= TIER_3_FROM) return TIER_3
            return ILLIQUID
        }
    }
}
