package com.kinetix.common.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

/**
 * Property-based tests for [VolSurface.volAt].
 *
 * For any valid surface, an interpolated vol at a strike/maturity inside the
 * knot grid must fall within the (min, max) range of the bracketing knots.
 * This is bilinear interpolation's defining invariant — a property that holds
 * for every interior point, not just a finite set of examples.
 */
class VolSurfacePropertyTest : FunSpec({

    PropertyTesting.defaultSeed = 4_242L

    val instrument = InstrumentId("AAPL")
    val asOf = Instant.parse("2026-01-15T10:00:00Z")
    val volArb = Arb.bigDecimal(BigDecimal("0.05"), BigDecimal("1.50"))
    val maturityArb = Arb.int(7..365)

    test("volAt at a knot returns that knot's vol exactly") {
        checkAll(volArb, volArb, volArb, volArb) { v00, v01, v10, v11 ->
            val surface = VolSurface(
                instrumentId = instrument,
                asOf = asOf,
                points = listOf(
                    VolPoint(BigDecimal("100"), 30, v00),
                    VolPoint(BigDecimal("100"), 90, v01),
                    VolPoint(BigDecimal("110"), 30, v10),
                    VolPoint(BigDecimal("110"), 90, v11),
                ),
            )

            surface.volAt(BigDecimal("100"), 30).compareTo(v00) shouldBe 0
            surface.volAt(BigDecimal("100"), 90).compareTo(v01) shouldBe 0
            surface.volAt(BigDecimal("110"), 30).compareTo(v10) shouldBe 0
            surface.volAt(BigDecimal("110"), 90).compareTo(v11) shouldBe 0
        }
    }

    test("interpolated vol inside the grid lies within the min/max of the four bounding knots") {
        checkAll(volArb, volArb, volArb, volArb, maturityArb) { v00, v01, v10, v11, interiorMaturity ->
            // Restrict the interior maturity to the bounding (30, 90) range.
            val maturityInside = interiorMaturity.coerceIn(30, 90)
            val surface = VolSurface(
                instrumentId = instrument,
                asOf = asOf,
                points = listOf(
                    VolPoint(BigDecimal("100"), 30, v00),
                    VolPoint(BigDecimal("100"), 90, v01),
                    VolPoint(BigDecimal("110"), 30, v10),
                    VolPoint(BigDecimal("110"), 90, v11),
                ),
            )

            val minVol = listOf(v00, v01, v10, v11).minOrNull()!!
            val maxVol = listOf(v00, v01, v10, v11).maxOrNull()!!

            // Interior strike halfway between knots.
            val interpolated = surface.volAt(BigDecimal("105"), maturityInside)
            interpolated.shouldBeGreaterThanOrEqualTo(
                minVol.subtract(BigDecimal("1E-9"), MathContext.DECIMAL128)
            )
            interpolated.shouldBeLessThanOrEqualTo(
                maxVol.add(BigDecimal("1E-9"), MathContext.DECIMAL128)
            )
        }
    }

    test("flat surface returns the same vol for any (strike, maturity) inside the grid") {
        checkAll(volArb) { flatVol ->
            val surface = VolSurface.flat(instrument, asOf, flatVol)
            // flat() uses knots at strikes {100, 200} and maturities {30, 365}.
            surface.volAt(BigDecimal("150"), 100).compareTo(flatVol) shouldBe 0
            surface.volAt(BigDecimal("100"), 30).compareTo(flatVol) shouldBe 0
            surface.volAt(BigDecimal("200"), 365).compareTo(flatVol) shouldBe 0
        }
    }
})
