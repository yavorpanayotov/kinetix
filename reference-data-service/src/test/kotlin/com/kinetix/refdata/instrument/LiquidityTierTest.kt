package com.kinetix.refdata.instrument

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pre-trade controls need to know whether an instrument is liquid: a
 * trader trying to put up a $10M block in an illiquid name should see
 * a flag long before sending the order. The LiquidityTier classification
 * is derived from the average daily volume (ADV): TIER_1 is "trade
 * anything without hesitation"; TIER_2 is "expect mild market impact";
 * TIER_3 is "request quote, check liquidity"; ILLIQUID is "do not
 * book a block in this name without desk approval".
 */
class LiquidityTierTest : FunSpec({

    test("classify TIER_1 for very liquid names (ADV >= 1bn)") {
        LiquidityTier.classify(averageDailyVolumeUsd = 1_000_000_000.0) shouldBe LiquidityTier.TIER_1
        LiquidityTier.classify(50_000_000_000.0) shouldBe LiquidityTier.TIER_1
    }

    test("classify TIER_2 for moderately liquid names ($100M..$1B ADV)") {
        LiquidityTier.classify(100_000_000.0) shouldBe LiquidityTier.TIER_2
        LiquidityTier.classify(500_000_000.0) shouldBe LiquidityTier.TIER_2
    }

    test("classify TIER_3 for thin names ($1M..$100M ADV)") {
        LiquidityTier.classify(1_000_000.0) shouldBe LiquidityTier.TIER_3
        LiquidityTier.classify(50_000_000.0) shouldBe LiquidityTier.TIER_3
    }

    test("classify ILLIQUID for sub-million ADV") {
        LiquidityTier.classify(0.0) shouldBe LiquidityTier.ILLIQUID
        LiquidityTier.classify(500_000.0) shouldBe LiquidityTier.ILLIQUID
    }

    test("isIlliquid is true only for the ILLIQUID tier") {
        LiquidityTier.ILLIQUID.isIlliquid() shouldBe true
        LiquidityTier.TIER_3.isIlliquid() shouldBe false
        LiquidityTier.TIER_2.isIlliquid() shouldBe false
        LiquidityTier.TIER_1.isIlliquid() shouldBe false
    }

    test("blockSizeWarningThresholdUsd grows with tier liquidity") {
        val t1 = LiquidityTier.TIER_1.blockSizeWarningThresholdUsd()
        val t2 = LiquidityTier.TIER_2.blockSizeWarningThresholdUsd()
        val t3 = LiquidityTier.TIER_3.blockSizeWarningThresholdUsd()
        val illiq = LiquidityTier.ILLIQUID.blockSizeWarningThresholdUsd()
        // Liquid names tolerate much larger blocks before flagging.
        (t1 > t2) shouldBe true
        (t2 > t3) shouldBe true
        (t3 > illiq) shouldBe true
    }

    test("negative ADV (defensive) classifies as ILLIQUID") {
        LiquidityTier.classify(-1.0) shouldBe LiquidityTier.ILLIQUID
    }
})
