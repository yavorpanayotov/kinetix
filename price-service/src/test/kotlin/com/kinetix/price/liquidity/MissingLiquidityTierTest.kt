package com.kinetix.price.liquidity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Price-service routes new ticks through a liquidity-tier resolver
 * that decides whether the tick passes through immediately (TIER_1
 * liquid names — pass any tick) or needs a stale-quote / dead-band
 * check (illiquid names). When the tier is missing (a new instrument
 * just listed, or a feed glitch dropped the tier metadata), the
 * service has to choose between failing closed (reject the tick) or
 * falling back to the most conservative tier (treat as illiquid).
 * The default is fall back — losing a tick is worse than running
 * extra checks.
 */
class MissingLiquidityTierTest : FunSpec({

    test("returns the stored tier when present") {
        resolveLiquidityTier(
            instrumentId = "AAPL",
            knownTiers = mapOf("AAPL" to LiquidityTier.TIER_1),
        ) shouldBe LiquidityTier.TIER_1
    }

    test("missing tier falls back to ILLIQUID by default (conservative)") {
        resolveLiquidityTier(
            instrumentId = "NEW",
            knownTiers = emptyMap(),
        ) shouldBe LiquidityTier.ILLIQUID
    }

    test("missing tier with reject-on-missing throws") {
        shouldThrow<IllegalStateException> {
            resolveLiquidityTier(
                instrumentId = "NEW",
                knownTiers = emptyMap(),
                rejectOnMissing = true,
            )
        }
    }

    test("the rejection message names the offending instrument") {
        val ex = shouldThrow<IllegalStateException> {
            resolveLiquidityTier("OBSCURE", emptyMap(), rejectOnMissing = true)
        }
        ex.message!!.contains("OBSCURE") shouldBe true
    }
})
