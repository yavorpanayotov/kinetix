package com.kinetix.refdata.dividend

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Dividend yields go stale faster than equity prices: corporate actions
 * happen on a quarterly cadence and a yield more than 30 days old is
 * likely from a previous quarter's announcement. Stale yields propagate
 * into the risk engine as wrong-sized dividend Greeks (rho is materially
 * affected by the dividend assumption). The warning flag lets the
 * platform either show a "stale yield" badge in the UI or skip the
 * yield in the calc and fall back to the curve-implied estimate.
 */
class StaleDividendYieldWarningTest : FunSpec({

    val now = Instant.parse("2026-05-28T12:00:00Z")
    val thirtyDays = 30L * 24 * 3600

    test("a yield updated 1 hour ago is fresh") {
        isDividendYieldStale(updatedAt = now.minusSeconds(3600), now = now) shouldBe false
    }

    test("a yield updated 29 days ago is fresh") {
        isDividendYieldStale(
            updatedAt = now.minusSeconds(29L * 24 * 3600),
            now = now,
        ) shouldBe false
    }

    test("a yield updated exactly 30 days ago is stale (>= threshold)") {
        isDividendYieldStale(updatedAt = now.minusSeconds(thirtyDays), now = now) shouldBe true
    }

    test("a yield updated 31 days ago is stale") {
        isDividendYieldStale(
            updatedAt = now.minusSeconds(31L * 24 * 3600),
            now = now,
        ) shouldBe true
    }

    test("a null updatedAt is treated as stale (no yield received)") {
        isDividendYieldStale(updatedAt = null, now = now) shouldBe true
    }

    test("future-dated updatedAt (clock skew) is treated as fresh") {
        isDividendYieldStale(updatedAt = now.plusSeconds(60), now = now) shouldBe false
    }

    test("custom thresholdDays honoured (7-day window flags at 8 days)") {
        isDividendYieldStale(
            updatedAt = now.minusSeconds(8L * 24 * 3600),
            now = now,
            thresholdDays = 7L,
        ) shouldBe true
    }
})
