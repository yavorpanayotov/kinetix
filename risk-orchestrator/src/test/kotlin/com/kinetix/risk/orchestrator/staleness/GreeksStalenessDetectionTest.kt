package com.kinetix.risk.orchestrator.staleness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Greeks are computed off the latest market data; once the underlying
 * moves the Greeks go stale. The orchestrator schedules a recalc
 * whenever the Greeks are older than five minutes; before that it
 * happily returns the cached value (avoids hammering the risk engine
 * on every UI poll). This test pins the threshold contract.
 */
class GreeksStalenessDetectionTest : FunSpec({

    val now = Instant.parse("2026-05-28T12:00:00Z")
    val staleAfter = 300L  // 5 minutes

    test("Greeks computed just now are fresh") {
        isGreeksStale(computedAt = now, now = now, staleAfterSeconds = staleAfter) shouldBe false
    }

    test("Greeks computed 4m59s ago are fresh") {
        isGreeksStale(
            computedAt = now.minusSeconds(299),
            now = now,
            staleAfterSeconds = staleAfter,
        ) shouldBe false
    }

    test("Greeks computed at exactly 5m old are stale (>= threshold)") {
        isGreeksStale(
            computedAt = now.minusSeconds(staleAfter),
            now = now,
            staleAfterSeconds = staleAfter,
        ) shouldBe true
    }

    test("Greeks computed 5m1s ago are stale") {
        isGreeksStale(
            computedAt = now.minusSeconds(301),
            now = now,
            staleAfterSeconds = staleAfter,
        ) shouldBe true
    }

    test("Greeks computed in the future (clock skew) are treated as fresh") {
        isGreeksStale(
            computedAt = now.plusSeconds(60),
            now = now,
            staleAfterSeconds = staleAfter,
        ) shouldBe false
    }

    test("null computedAt is treated as stale (no prior recalc)") {
        isGreeksStale(
            computedAt = null,
            now = now,
            staleAfterSeconds = staleAfter,
        ) shouldBe true
    }

    test("custom threshold honoured (1-minute window flags after 60s)") {
        isGreeksStale(
            computedAt = now.minusSeconds(61),
            now = now,
            staleAfterSeconds = 60L,
        ) shouldBe true
    }
})
