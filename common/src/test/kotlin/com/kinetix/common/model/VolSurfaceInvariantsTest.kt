package com.kinetix.common.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

/**
 * Invariants that volAt() must preserve regardless of surface shape:
 *
 * - Interpolated points always fall within the convex bound of bracketing knots
 * - Inverted term structure (short-dated vol > long-dated vol) is preserved
 * - Smile asymmetry (typical equity skew) is preserved
 * - Total variance σ²·T is monotone non-decreasing in maturity (calendar
 *   spread invariant) at any fixed strike where the input surface satisfies it
 *
 * These complement [VolSurfaceTest] which covers specific arithmetic outcomes
 * for known inputs. The tests here verify *properties* that should hold for
 * many inputs.
 */
class VolSurfaceInvariantsTest : FunSpec({

    val asOf = Instant.parse("2026-01-15T10:00:00Z")
    val instrument = InstrumentId("AAPL")

    fun strike(s: String) = BigDecimal(s)
    fun vol(v: String) = BigDecimal(v)

    fun makeSurface(points: List<Triple<String, Int, String>>): VolSurface =
        VolSurface(
            instrumentId = instrument,
            asOf = asOf,
            points = points.map { (s, m, v) -> VolPoint(strike(s), m, vol(v)) },
        )

    test("interpolated vol between two strike knots stays within their range") {
        val surface = makeSurface(
            listOf(
                Triple("90", 30, "0.30"),
                Triple("110", 30, "0.20"),
            )
        )
        val interpolated = surface.volAt(strike("100"), 30)
        interpolated.shouldBeGreaterThanOrEqualTo(vol("0.20"))
        interpolated.shouldBeLessThanOrEqualTo(vol("0.30"))
    }

    test("interpolated vol between two maturity knots stays within their range") {
        val surface = makeSurface(
            listOf(
                Triple("100", 30, "0.30"),
                Triple("100", 90, "0.20"),
            )
        )
        val interpolated = surface.volAt(strike("100"), 60)
        interpolated.shouldBeGreaterThanOrEqualTo(vol("0.20"))
        interpolated.shouldBeLessThanOrEqualTo(vol("0.30"))
    }

    test("inverted term structure (short > long) is preserved in interpolated values") {
        val surface = makeSurface(
            listOf(
                Triple("100", 30, "0.40"),
                Triple("100", 365, "0.20"),
            )
        )

        val shortVol = surface.volAt(strike("100"), 30)
        val midVol = surface.volAt(strike("100"), 90)
        val longVol = surface.volAt(strike("100"), 365)

        shortVol.shouldBeGreaterThan(midVol)
        midVol.shouldBeGreaterThan(longVol)
    }

    test("equity smile asymmetry: OTM put vol > ATM vol > OTM call vol is preserved") {
        // Typical equity skew shape: OTM puts (low strike) have higher implied vol
        // than ATM, which has higher vol than OTM calls (high strike).
        val surface = makeSurface(
            listOf(
                Triple("80", 30, "0.35"),
                Triple("100", 30, "0.25"),
                Triple("120", 30, "0.20"),
            )
        )

        val otmPut = surface.volAt(strike("85"), 30)
        val atm = surface.volAt(strike("100"), 30)
        val otmCall = surface.volAt(strike("115"), 30)

        otmPut.shouldBeGreaterThan(atm)
        atm.shouldBeGreaterThan(otmCall)
    }

    test("total variance is non-decreasing in maturity at a fixed strike (calendar spread)") {
        // Total variance v(T) = σ²·T should be monotone non-decreasing in T for an
        // arbitrage-free surface. Verify the input satisfies this and the interpolated
        // intermediate point preserves it.
        val surface = makeSurface(
            listOf(
                Triple("100", 30, "0.30"),
                Triple("100", 90, "0.28"),
                Triple("100", 365, "0.25"),
            )
        )

        fun totalVariance(maturityDays: Int): BigDecimal {
            val v = surface.volAt(strike("100"), maturityDays)
            return v.multiply(v, MathContext.DECIMAL128)
                .multiply(maturityDays.toBigDecimal(), MathContext.DECIMAL128)
        }

        val v30 = totalVariance(30)
        val v60 = totalVariance(60)
        val v90 = totalVariance(90)
        val v365 = totalVariance(365)

        v60.shouldBeGreaterThan(v30)
        v90.shouldBeGreaterThan(v60)
        v365.shouldBeGreaterThan(v90)
    }

    test("vol at a queried strike equal to a knot strike returns the knot exactly") {
        val surface = makeSurface(
            listOf(
                Triple("100", 30, "0.25"),
                Triple("110", 30, "0.20"),
            )
        )
        surface.volAt(strike("100"), 30).compareTo(vol("0.25")) shouldBe 0
        surface.volAt(strike("110"), 30).compareTo(vol("0.20")) shouldBe 0
    }

    test("flat surface returns the same vol regardless of strike or maturity (within grid)") {
        val flat = VolSurface.flat(instrument, asOf, vol("0.22"))

        flat.volAt(strike("100"), 30).compareTo(vol("0.22")) shouldBe 0
        flat.volAt(strike("100"), 365).compareTo(vol("0.22")) shouldBe 0
        flat.volAt(strike("150"), 100).compareTo(vol("0.22")) shouldBe 0
        flat.volAt(strike("200"), 365).compareTo(vol("0.22")) shouldBe 0
    }

    test("scaleAll preserves the shape of the surface (every point scales by the same factor)") {
        val original = makeSurface(
            listOf(
                Triple("90", 30, "0.30"),
                Triple("100", 30, "0.25"),
                Triple("110", 30, "0.20"),
            )
        )
        val scaled = original.scaleAll(vol("2.0"))

        original.points.zip(scaled.points).forEach { (o, s) ->
            (s.impliedVol.divide(o.impliedVol, MathContext.DECIMAL128))
                .compareTo(vol("2.0")) shouldBe 0
        }
    }
})
