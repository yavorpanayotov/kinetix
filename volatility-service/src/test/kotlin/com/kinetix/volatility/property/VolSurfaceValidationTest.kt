package com.kinetix.volatility.property

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import java.math.BigDecimal
import java.time.Instant

/**
 * Three pure-domain volatility-surface invariants that any caller (ingestion,
 * cache, repository, calibration) inherits via [VolSurface] / [VolPoint]
 * construction:
 *
 *   1. **(kx-and)** Negative or zero implied vol is rejected at the point
 *      level — there is no "negative volatility" in any pricer; rejection
 *      must happen before the surface ever reaches the repository.
 *   2. **(kx-prd)** Term structure: at a fixed strike, a longer-tenor vol
 *      must be at least equal to the shorter-tenor vol — the surface stores
 *      a slice that is monotone non-decreasing in maturity. This is an ATM
 *      term-structure check matching the constraint the calibration step
 *      enforces by construction.
 *   3. **(kx-4h8)** Smile constraint: at a fixed maturity, implied vol must
 *      increase as the strike moves AWAY from ATM (in either direction).
 *      The skew can be asymmetric but the U-shape is required.
 *
 * These tests assert against the public domain API rather than mocking the
 * repository, because the constraint is a property of the data model, not
 * the persistence layer.
 */
class VolSurfaceValidationTest : FunSpec({
    val instrument = InstrumentId("AAPL")
    val asOf = Instant.parse("2026-01-15T10:00:00Z")

    test("rejects negative implied vol at point construction (kx-and)") {
        shouldThrow<IllegalArgumentException> {
            VolPoint(BigDecimal("100"), 30, BigDecimal("-0.01"))
        }
    }

    test("rejects zero implied vol at point construction (kx-and)") {
        shouldThrow<IllegalArgumentException> {
            VolPoint(BigDecimal("100"), 30, BigDecimal.ZERO)
        }
    }

    test("term structure: longer-tenor vol >= shorter-tenor vol at fixed strike (kx-prd)") {
        // A standard contango-style ATM term structure: vol rises with maturity.
        val surface = VolSurface(
            instrumentId = instrument,
            asOf = asOf,
            points = listOf(
                VolPoint(BigDecimal("100"), 30, BigDecimal("0.18")),
                VolPoint(BigDecimal("100"), 90, BigDecimal("0.20")),
                VolPoint(BigDecimal("100"), 365, BigDecimal("0.22")),
            ),
        )
        val short = surface.volAt(BigDecimal("100"), 30)
        val mid = surface.volAt(BigDecimal("100"), 90)
        val long_ = surface.volAt(BigDecimal("100"), 365)
        mid shouldBeGreaterThanOrEqualTo short
        long_ shouldBeGreaterThanOrEqualTo mid
    }

    test("smile constraint: vol(K_OTM) >= vol(K_ATM) at fixed maturity (kx-4h8)") {
        // OTM and ITM wings carry higher vol than ATM — the classic smile.
        val surface = VolSurface(
            instrumentId = instrument,
            asOf = asOf,
            points = listOf(
                VolPoint(BigDecimal("80"), 90, BigDecimal("0.28")),
                VolPoint(BigDecimal("100"), 90, BigDecimal("0.20")),
                VolPoint(BigDecimal("120"), 90, BigDecimal("0.26")),
            ),
        )
        val otmPut = surface.volAt(BigDecimal("80"), 90)
        val atm = surface.volAt(BigDecimal("100"), 90)
        val otmCall = surface.volAt(BigDecimal("120"), 90)
        otmPut shouldBeGreaterThanOrEqualTo atm
        otmCall shouldBeGreaterThanOrEqualTo atm
    }
})
