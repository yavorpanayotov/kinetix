package com.kinetix.rates.curve

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Yield-curve feeds normally refresh every 5-15 minutes during market
 * hours. A curve that hasn't updated in over four hours is almost
 * certainly stuck — the upstream vendor's session dropped, or our
 * consumer's offset is behind. The detector flags the staleness so
 * the platform alerts before the desk acts on a stale curve.
 */
class CurveStaleDataDetectionTest : FunSpec({

    val now = Instant.parse("2026-05-28T13:00:00Z")

    test("a 1-minute-old curve is fresh") {
        isCurveStale(lastUpdate = now.minusSeconds(60), now = now) shouldBe false
    }

    test("a curve updated 3h59m ago is still fresh") {
        isCurveStale(now.minusSeconds(4 * 3600 - 60), now) shouldBe false
    }

    test("a curve updated exactly 4h ago is stale (>= threshold)") {
        isCurveStale(now.minusSeconds(4 * 3600), now) shouldBe true
    }

    test("a curve updated 4h+1s ago is stale") {
        isCurveStale(now.minusSeconds(4 * 3600 + 1), now) shouldBe true
    }

    test("a null lastUpdate is stale (curve never received)") {
        isCurveStale(lastUpdate = null, now = now) shouldBe true
    }

    test("a future-dated lastUpdate (clock skew) is fresh") {
        isCurveStale(now.plusSeconds(60), now) shouldBe false
    }

    test("custom thresholdHours honoured") {
        isCurveStale(now.minusSeconds(2 * 3600), now, thresholdHours = 1L) shouldBe true
    }
})
