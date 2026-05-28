package com.kinetix.position.limit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalTime

/**
 * Traders run on two limit tiers: a generous intraday limit and a
 * tighter overnight limit. The intraday limit applies during market
 * hours (open through close); the overnight limit kicks in once the
 * exchange closes and stays in effect until the next open. Mis-tagging
 * a 5pm exposure as "intraday" lets a trader carry an oversized
 * overnight position the risk officer thinks they've capped.
 */
class IntradayLimitResetTest : FunSpec({

    fun limitAt(time: LocalTime): LimitTier =
        applicableLimitTier(time, marketOpen = LocalTime.of(9, 30), marketClose = LocalTime.of(16, 0))

    test("10:00 (during market) uses INTRADAY") {
        limitAt(LocalTime.of(10, 0)) shouldBe LimitTier.INTRADAY
    }

    test("15:59 (one minute before close) still INTRADAY") {
        limitAt(LocalTime.of(15, 59)) shouldBe LimitTier.INTRADAY
    }

    test("16:00 (exactly at close) flips to OVERNIGHT") {
        limitAt(LocalTime.of(16, 0)) shouldBe LimitTier.OVERNIGHT
    }

    test("17:00 (after close) is OVERNIGHT") {
        limitAt(LocalTime.of(17, 0)) shouldBe LimitTier.OVERNIGHT
    }

    test("09:29 (just before open, pre-market) is OVERNIGHT") {
        limitAt(LocalTime.of(9, 29)) shouldBe LimitTier.OVERNIGHT
    }

    test("09:30 (exactly at open) is INTRADAY") {
        limitAt(LocalTime.of(9, 30)) shouldBe LimitTier.INTRADAY
    }

    test("midnight is OVERNIGHT") {
        limitAt(LocalTime.MIDNIGHT) shouldBe LimitTier.OVERNIGHT
    }
})
