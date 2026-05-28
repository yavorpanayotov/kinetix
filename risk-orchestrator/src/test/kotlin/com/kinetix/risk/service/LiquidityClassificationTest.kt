package com.kinetix.risk.service

import com.kinetix.common.model.LiquidityTier
import com.kinetix.risk.client.dtos.InstrumentLiquidityDto
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Regression for the trader-review P0 bug: `Risk → Liquidity Risk` flagged
 * `JPM` (one of the largest US cash equities) as `ILLIQUID` with a 10-day
 * horizon, because the demo reference-data seed had no
 * [InstrumentLiquidityDto] entry for JPM (and several other major names).
 * When the orchestrator failed to find ADV data it forwarded `advMissing=true`
 * to the Python engine, which fail-safed to `ILLIQUID` per
 * `specs/liquidity.allium:79-80`.
 *
 * The classifier rule we pin here is the position-level
 * `adv_fraction = abs(market_value) / instrument_adv` rule documented in
 * `specs/liquidity.allium:71-74`:
 *
 *   adv_fraction < 0.10            → HIGH_LIQUID  (1-day horizon)
 *   0.10 <= adv_fraction < 0.25    → LIQUID       (3-day horizon)
 *   0.25 <= adv_fraction < 0.50    → SEMI_LIQUID  (5-day horizon)
 *   adv_fraction >= 0.50, or no ADV → ILLIQUID     (10-day horizon)
 *
 * The classifier MUST also keep `advDataMissing` explicit when the seed has
 * no ADV record — so the UI can distinguish "fail-safe default" from
 * "deeply illiquid name". Silently collapsing both to ILLIQUID is what made
 * the bug invisible.
 */
class LiquidityClassificationTest : FunSpec({

    fun jpmLiquidity(): InstrumentLiquidityDto = InstrumentLiquidityDto(
        instrumentId = "JPM",
        adv = 80_000_000.0,
        bidAskSpreadBps = 1.5,
        assetClass = "EQUITY",
        advUpdatedAt = "2026-05-28T09:00:00Z",
        advStale = false,
        advStalenessDays = 0,
        createdAt = "2026-05-28T09:00:00Z",
        updatedAt = "2026-05-28T09:00:00Z",
        liquidityTier = "HIGH_LIQUID",
    )

    test("JPM with full ADV reference data classifies as HIGH_LIQUID at a normal position size") {
        // Position 1 % of ADV: 800k / 80M = 0.01 < 0.10 → HIGH_LIQUID.
        val result = InstrumentLiquidityClassifier.classifyPosition(
            marketValue = 800_000.0,
            liquidity = jpmLiquidity(),
        )

        result.tier shouldBe LiquidityTier.HIGH_LIQUID
        result.horizonDays shouldBe 1
        result.advDataMissing shouldBe false
    }

    test("DE10Y (10Y Bund) with deep sovereign ADV classifies as HIGH_LIQUID") {
        // Bunds are among the deepest sovereign markets — adv >= $500M.
        val bund = InstrumentLiquidityDto(
            instrumentId = "DE10Y",
            adv = 500_000_000.0,
            bidAskSpreadBps = 0.5,
            assetClass = "FIXED_INCOME",
            advUpdatedAt = "2026-05-28T09:00:00Z",
            advStale = false,
            advStalenessDays = 0,
            createdAt = "2026-05-28T09:00:00Z",
            updatedAt = "2026-05-28T09:00:00Z",
            liquidityTier = "HIGH_LIQUID",
        )

        val result = InstrumentLiquidityClassifier.classifyPosition(
            marketValue = 1_000_000.0,
            liquidity = bund,
        )

        result.tier shouldBe LiquidityTier.HIGH_LIQUID
        result.horizonDays shouldBe 1
    }

    test("US30Y Treasury with deep sovereign ADV classifies as HIGH_LIQUID") {
        val ust30y = InstrumentLiquidityDto(
            instrumentId = "US30Y",
            adv = 300_000_000.0,
            bidAskSpreadBps = 0.5,
            assetClass = "FIXED_INCOME",
            advUpdatedAt = "2026-05-28T09:00:00Z",
            advStale = false,
            advStalenessDays = 0,
            createdAt = "2026-05-28T09:00:00Z",
            updatedAt = "2026-05-28T09:00:00Z",
            liquidityTier = "HIGH_LIQUID",
        )

        val result = InstrumentLiquidityClassifier.classifyPosition(
            marketValue = 500_000.0,
            liquidity = ust30y,
        )

        result.tier shouldBe LiquidityTier.HIGH_LIQUID
    }

    test("position above 50% of ADV classifies as ILLIQUID (forced liquidation regime)") {
        // 60% of ADV — a position too large to unwind without market impact.
        val result = InstrumentLiquidityClassifier.classifyPosition(
            marketValue = 48_000_000.0,
            liquidity = jpmLiquidity(),
        )

        result.tier shouldBe LiquidityTier.ILLIQUID
        result.horizonDays shouldBe 10
        result.advDataMissing shouldBe false
    }

    test("missing ADV data fails safe to ILLIQUID BUT keeps advDataMissing explicit") {
        // Trader-review bug regression: the UI must be able to distinguish
        // "we have no ADV data" from "this instrument is genuinely illiquid".
        // Silently collapsing the two is what made the seed gap invisible.
        val result = InstrumentLiquidityClassifier.classifyPosition(
            marketValue = 800_000.0,
            liquidity = null,
        )

        result.tier shouldBe LiquidityTier.ILLIQUID
        result.horizonDays shouldBe 10
        result.advDataMissing shouldBe true
    }
})
