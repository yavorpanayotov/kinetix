package com.kinetix.volatility.property

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

/**
 * Property-based butterfly-convexity test for [VolSurface].
 *
 * For a single maturity slice with strikes K1 < K2 < K3, the implied-vol smile
 * stored on the surface must not violate the static no-arbitrage convexity
 * constraint: vol(K1) - 2*vol(K2) + vol(K3) >= 0.
 *
 * Equivalent in price space (the plan's "C(K1) - 2*C(K2) + C(K3) >= 0" hedge)
 * would require a `priceAt` API; [VolSurface] only exposes `volAt`, so the
 * assertion is in vol space — see the plan note "or, in vol terms, the
 * implied-vol curve does not violate static arbitrage".
 *
 * To keep the test about the surface's preservation of the invariant rather
 * than the bilinear interpolator's behaviour between knots, every query is
 * evaluated AT the knot strikes. The Arb generates input vols that satisfy
 * the convexity constraint by construction: pick v1 and v3 freely, then set
 *   v2 = (v1 + v3) / 2 - bow
 * for a non-negative `bow`. The discrete second difference is then
 *   v1 - 2*v2 + v3 = 2*bow >= 0
 * independent of strike spacing — this is what makes the property robust
 * for arbitrary K1 < K2 < K3.
 */
class VolSurfaceButterflyTest : FunSpec({

    PropertyTesting.defaultSeed = 4_242L
    // 1E-9 absorbs BigDecimal MathContext.DECIMAL128 float noise. Computed
    // vols round-trip through the surface as exact knot lookups, so 1E-9 is
    // a generous safety margin.
    val epsilon = BigDecimal("1E-9")
    val mc = MathContext.DECIMAL128

    val instrument = InstrumentId("AAPL")
    val asOf = Instant.parse("2026-01-15T10:00:00Z")

    // Three strictly-increasing strikes in [50, 200] are produced by drawing
    // a left strike and two positive gaps. Gaps stay big enough that the
    // BigDecimal compareTo cannot collapse the strikes.
    val leftStrikeArb = Arb.bigDecimal(BigDecimal("50"), BigDecimal("100"))
    val gapArb = Arb.bigDecimal(BigDecimal("1"), BigDecimal("40"))
    val maturityArb = Arb.int(7..1825) // ~0.02y to 5y, matching plan range
    // v1 and v3 are independent draws; v2 is constructed below to enforce
    // the convexity constraint. The lower bound 0.10 keeps v2 comfortably
    // above the VolPoint positivity requirement after subtracting `bow`.
    val sideVolArb = Arb.bigDecimal(BigDecimal("0.10"), BigDecimal("0.80"))
    // Non-negative bow. Capped well below 0.10 so v2 stays positive even
    // when v1 = v3 = 0.10.
    val bowArb = Arb.bigDecimal(BigDecimal("0.0"), BigDecimal("0.04"))

    test("butterfly convexity: vol(K1) - 2*vol(K2) + vol(K3) >= 0 at knot strikes") {
        checkAll(
            100,
            leftStrikeArb, gapArb, gapArb,
            maturityArb,
            sideVolArb, sideVolArb, bowArb,
        ) { k1, g1, g2, maturityDays, v1Side, v3Side, bow ->
            val strike1 = k1
            val strike2 = k1.add(g1, mc)
            val strike3 = strike2.add(g2, mc)

            val v1 = v1Side
            val v3 = v3Side
            // v2 = (v1 + v3) / 2 - bow. Discrete second difference is then
            // v1 - 2*v2 + v3 = 2*bow >= 0, regardless of strike spacing.
            val v2 = v1.add(v3, mc)
                .divide(BigDecimal("2"), mc)
                .subtract(bow, mc)

            // Sanity: v2 must be strictly positive for VolPoint to accept it.
            // The Arb bounds guarantee this (v1, v3 >= 0.10; bow <= 0.04 so
            // v2 >= 0.10 - 0.04 = 0.06).
            v2 shouldBeGreaterThanOrEqualTo BigDecimal("0.05")

            val surface = VolSurface(
                instrumentId = instrument,
                asOf = asOf,
                points = listOf(
                    VolPoint(strike1, maturityDays, v1),
                    VolPoint(strike2, maturityDays, v2),
                    VolPoint(strike3, maturityDays, v3),
                ),
            )

            val q1 = surface.volAt(strike1, maturityDays)
            val q2 = surface.volAt(strike2, maturityDays)
            val q3 = surface.volAt(strike3, maturityDays)

            // Sanity: round-trip at knots must match the input exactly.
            q1.compareTo(v1) shouldBe 0
            q2.compareTo(v2) shouldBe 0
            q3.compareTo(v3) shouldBe 0

            // Butterfly convexity in vol space — equals 2*bow at the input
            // level; the surface must preserve it after query.
            val secondDifference = q1.subtract(q2.multiply(BigDecimal("2"), mc), mc).add(q3, mc)
            secondDifference shouldBeGreaterThanOrEqualTo epsilon.negate()
        }
    }
})
