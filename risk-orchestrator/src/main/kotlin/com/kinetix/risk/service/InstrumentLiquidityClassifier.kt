package com.kinetix.risk.service

import com.kinetix.common.model.LiquidityTier
import com.kinetix.risk.client.dtos.InstrumentLiquidityDto
import kotlin.math.abs

/**
 * Per-position liquidity-tier classifier.
 *
 * Mirrors the Python engine's `compute_liquidation_horizon` (see
 * `risk-engine/src/kinetix_risk/liquidity.py:122-163`) and the Allium rule
 * in `specs/liquidity.allium:65-80`:
 *
 *   adv_fraction = abs(market_value) / instrument_adv
 *     < 0.10            → HIGH_LIQUID  (1-day horizon)
 *     [0.10, 0.25)      → LIQUID       (3-day horizon)
 *     [0.25, 0.50)      → SEMI_LIQUID  (5-day horizon)
 *     >= 0.50           → ILLIQUID     (10-day horizon)
 *   missing ADV         → ILLIQUID     (10-day horizon, advDataMissing=true)
 *
 * The classifier is intentionally a thin Kotlin mirror of the canonical
 * rule. The Python engine remains the system of record at compute time
 * (the orchestrator does NOT pre-classify and send the tier on the wire);
 * this helper exists so unit tests can pin the rule against trader-review
 * regression cases (JPM, DE10Y, US30Y) without needing a gRPC dependency,
 * and so the orchestrator can sanity-check classifications surfaced by the
 * Python engine before publishing alerts.
 *
 * Bug fixed by `feat(refdata): seed liquidity data for missing major
 * names` — when reference-data has no [InstrumentLiquidityDto] for a name,
 * this classifier (and the Python engine it mirrors) MUST fail-safe to
 * ILLIQUID, but it MUST also keep [PositionLiquidityClassification.advDataMissing]
 * explicit so the UI can distinguish "we have no ADV data" from "this name
 * is genuinely illiquid".
 */
object InstrumentLiquidityClassifier {

    private const val HIGH_LIQUID_MAX_FRACTION = 0.10
    private const val LIQUID_MAX_FRACTION = 0.25
    private const val SEMI_LIQUID_MAX_FRACTION = 0.50

    private const val ILLIQUID_HORIZON_DAYS = 10
    private val TIER_TO_HORIZON: Map<LiquidityTier, Int> = mapOf(
        LiquidityTier.HIGH_LIQUID to 1,
        LiquidityTier.LIQUID to 3,
        LiquidityTier.SEMI_LIQUID to 5,
        LiquidityTier.ILLIQUID to ILLIQUID_HORIZON_DAYS,
    )

    fun classifyPosition(
        marketValue: Double,
        liquidity: InstrumentLiquidityDto?,
    ): PositionLiquidityClassification {
        if (liquidity == null || liquidity.adv <= 0.0) {
            return PositionLiquidityClassification(
                tier = LiquidityTier.ILLIQUID,
                horizonDays = ILLIQUID_HORIZON_DAYS,
                advDataMissing = true,
            )
        }

        val advFraction = abs(marketValue) / liquidity.adv
        val tier = when {
            advFraction < HIGH_LIQUID_MAX_FRACTION -> LiquidityTier.HIGH_LIQUID
            advFraction < LIQUID_MAX_FRACTION -> LiquidityTier.LIQUID
            advFraction < SEMI_LIQUID_MAX_FRACTION -> LiquidityTier.SEMI_LIQUID
            else -> LiquidityTier.ILLIQUID
        }

        return PositionLiquidityClassification(
            tier = tier,
            horizonDays = TIER_TO_HORIZON.getValue(tier),
            advDataMissing = false,
        )
    }
}
