package com.kinetix.testsupport.builders

import com.kinetix.common.model.VolSurface
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * Smoke test for [TestVolSurface]. Verifies the two named factory methods
 * produce well-formed, deterministic [VolSurface] instances and that the
 * smile shape behaves as documented.
 *
 * Vol comparisons use BigDecimal tolerance because the production type
 * stores impliedVol as BigDecimal and [VolSurface.volAt] performs
 * BigDecimal interpolation arithmetic — a flat surface returns the input
 * vol exactly, but a smile surface compared to a Double parameter needs
 * a tight numerical tolerance.
 */
class TestVolSurfaceTest : FunSpec({

    test("flatAt(0.20) produces a surface whose vol at every in-grid knot is 0.20") {
        val surface = TestVolSurface.flatAt(0.20)

        for (strike in TestVolSurface.DEFAULT_STRIKES) {
            for (days in TestVolSurface.DEFAULT_MATURITY_DAYS) {
                withClue("Vol at strike=$strike, days=$days") {
                    val v = surface.volAt(BigDecimal.valueOf(strike), days)
                    v.compareTo(BigDecimal("0.20")) shouldBe 0
                }
            }
        }
    }

    test("flatAt(0.20) populates the full strike × maturity grid") {
        val surface = TestVolSurface.flatAt(0.20)

        surface.points.size shouldBe
            TestVolSurface.DEFAULT_STRIKES.size * TestVolSurface.DEFAULT_MATURITY_DAYS.size
    }

    test("flatAt rejects zero vol — VolPoint requires strictly positive impliedVol") {
        // The production VolPoint type requires impliedVol > 0, so a zero-vol
        // fixture would either throw deep inside VolPoint or produce an
        // invalid surface. The factory rejects up front with a clear message.
        shouldThrow<IllegalArgumentException> {
            TestVolSurface.flatAt(0.0)
        }
    }

    test("flatAt rejects negative vol") {
        shouldThrow<IllegalArgumentException> {
            TestVolSurface.flatAt(-0.10)
        }
    }

    test("flatAt is deterministic — two calls with the same arg produce equal surfaces") {
        val a = TestVolSurface.flatAt(0.20)
        val b = TestVolSurface.flatAt(0.20)

        a shouldBe b
    }

    test("withSmile() default args produce ATM vol equal to the documented default 0.20") {
        val surface = TestVolSurface.withSmile()

        // ATM strike = 100 (median of default strikes). With skew=0 and
        // curvature=0.05 the ATM moneyness is 0, so vol(ATM) = atmVol exactly.
        val atmVol = surface.volAt(BigDecimal("100"), 365)
        atmVol.toDouble() shouldBeCloseTo 0.20
    }

    test("withSmile(curvature = 0.1) produces higher OTM vols than ATM (smile shape)") {
        val surface = TestVolSurface.withSmile(atmVol = 0.20, curvature = 0.1, skew = 0.0)

        val atmVol = surface.volAt(BigDecimal("100"), 365).toDouble()
        val otmCallVol = surface.volAt(BigDecimal("120"), 365).toDouble()
        val otmPutVol = surface.volAt(BigDecimal("80"), 365).toDouble()

        withClue("OTM call vol $otmCallVol must exceed ATM vol $atmVol") {
            (otmCallVol > atmVol) shouldBe true
        }
        withClue("OTM put vol $otmPutVol must exceed ATM vol $atmVol") {
            (otmPutVol > atmVol) shouldBe true
        }
        // Symmetric smile (skew=0) → OTM call vol equals OTM put vol.
        withClue("OTM call vol $otmCallVol must equal OTM put vol $otmPutVol (skew=0)") {
            (kotlin.math.abs(otmCallVol - otmPutVol) < 1e-12) shouldBe true
        }
    }

    test("withSmile(skew = -0.05) puts more vol on OTM puts than OTM calls (put skew)") {
        val surface = TestVolSurface.withSmile(atmVol = 0.20, curvature = 0.0, skew = -0.05)

        val otmCallVol = surface.volAt(BigDecimal("120"), 365).toDouble()
        val otmPutVol = surface.volAt(BigDecimal("80"), 365).toDouble()

        withClue("Put skew: OTM put vol $otmPutVol must exceed OTM call vol $otmCallVol") {
            (otmPutVol > otmCallVol) shouldBe true
        }
    }

    test("withSmile is deterministic — two calls with the same args produce equal surfaces") {
        val a = TestVolSurface.withSmile(atmVol = 0.20, curvature = 0.05, skew = -0.01)
        val b = TestVolSurface.withSmile(atmVol = 0.20, curvature = 0.05, skew = -0.01)

        a shouldBe b
    }

    test("withSmile rejects non-positive atmVol") {
        shouldThrow<IllegalArgumentException> {
            TestVolSurface.withSmile(atmVol = 0.0)
        }
        shouldThrow<IllegalArgumentException> {
            TestVolSurface.withSmile(atmVol = -0.10)
        }
    }

    test("withSmile rejects parameters that would produce a non-positive vol at any knot") {
        // Extreme negative skew at the lowest strike pushes vol below zero,
        // which the factory must reject up front rather than letting VolPoint
        // throw on a nested loop iteration.
        shouldThrow<IllegalArgumentException> {
            TestVolSurface.withSmile(atmVol = 0.20, curvature = 0.0, skew = 5.0)
        }
    }

    test("withSmile rejects an empty strike grid") {
        shouldThrow<IllegalArgumentException> {
            TestVolSurface.withSmile(strikes = emptyList())
        }
    }

    test("withSmile rejects an empty maturity grid") {
        shouldThrow<IllegalArgumentException> {
            TestVolSurface.withSmile(maturityDays = emptyList())
        }
    }
})

private infix fun Double.shouldBeCloseTo(expected: Double) {
    withClue("Expected $expected but was $this (tolerance 1e-9)") {
        (kotlin.math.abs(this - expected) < 1e-9) shouldBe true
    }
}
