package com.kinetix.volatility.property

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

/**
 * Out-of-grid extrapolation behaviour for [VolSurface.volAt].
 *
 * The production implementation handles out-of-grid queries by *clamping* the
 * requested strike and maturity to the grid boundary (see
 * `VolSurface.clampStrike` and the `coerceIn` of `maturityDays` in `volAt`).
 * It does NOT linearly extrapolate beyond the knots, and it does NOT throw
 * for valid (positive) strike/maturity inputs outside the knot range.
 *
 * These tests pin that clamping behaviour at extreme bounds — small strike
 * (1.0), large strike (10_000.0), short maturity (1 day) and long maturity
 * (10 years) — so a future change to the extrapolation policy will fail
 * loudly here and the failure name points clearly at extrapolation logic
 * rather than interior interpolation.
 *
 * Companion to [VolSurfaceButterflyTest] (price-space convexity inside the
 * grid) and the in-grid invariants in `common.model.VolSurfaceInvariantsTest`.
 *
 * No existing tests in `VolSurfaceInvariantsTest` exercised true out-of-grid
 * extrapolation — every query there was inside the configured knot grid — so
 * this file is entirely new coverage rather than a relocation.
 */
class VolSurfaceExtrapolationTest : FunSpec({

    val asOf = Instant.parse("2026-01-15T10:00:00Z")
    val instrument = InstrumentId("AAPL")

    fun strike(s: String) = BigDecimal(s)
    fun vol(v: String) = BigDecimal(v)

    // Standard 2x2 grid: strikes {90, 110}, maturities {30, 365}.
    // Vols are picked so each corner is distinct, which lets the assertions
    // below detect any accidental linear extrapolation (which would produce
    // values outside the knot vols).
    val gridSurface = VolSurface(
        instrumentId = instrument,
        asOf = asOf,
        points = listOf(
            VolPoint(strike("90"), 30, vol("0.40")),
            VolPoint(strike("90"), 365, vol("0.30")),
            VolPoint(strike("110"), 30, vol("0.25")),
            VolPoint(strike("110"), 365, vol("0.20")),
        ),
    )

    test("extrapolation: extreme out-of-grid low strike (1.0) clamps to lowest knot strike") {
        // At maturity = 30 the lowest-strike knot is 90 with vol 0.40.
        // Linear extrapolation toward strike=1 from (90, 0.40) and (110, 0.25)
        // would yield ~0.51 (much higher than any knot vol). Clamping returns
        // exactly the knot vol at strike=90.
        val volAtTinyStrike = gridSurface.volAt(strike("1.0"), 30)
        volAtTinyStrike.compareTo(vol("0.40")) shouldBe 0
    }

    test("extrapolation: extreme out-of-grid high strike (10000.0) clamps to highest knot strike") {
        // At maturity = 30 the highest-strike knot is 110 with vol 0.25.
        // Linear extrapolation toward strike=10_000 would diverge wildly;
        // clamping returns exactly the knot vol at strike=110.
        val volAtHugeStrike = gridSurface.volAt(strike("10000.0"), 30)
        volAtHugeStrike.compareTo(vol("0.25")) shouldBe 0
    }

    test("extrapolation: extreme out-of-grid short maturity (1 day) clamps to shortest knot maturity") {
        // At strike = 90 the shortest-maturity knot is 30 days with vol 0.40.
        // Maturity = 1 is well below the 30-day knot; clamping returns the
        // 30-day vol exactly.
        val volAtOneDay = gridSurface.volAt(strike("90"), 1)
        volAtOneDay.compareTo(vol("0.40")) shouldBe 0
    }

    test("extrapolation: extreme out-of-grid long maturity (10 years) clamps to longest knot maturity") {
        // 10 years = 3650 days; the longest-maturity knot at strike=90 is
        // 365 days with vol 0.30. Clamping returns the 365-day vol exactly.
        val volAtTenYears = gridSurface.volAt(strike("90"), 3650)
        volAtTenYears.compareTo(vol("0.30")) shouldBe 0
    }

    test("extrapolation: tiny strike with long maturity clamps in BOTH dimensions to (90, 365) knot") {
        // Strike below the low knot AND maturity above the high knot — both
        // dimensions clamp, landing exactly on the (strike=90, maturity=365)
        // corner with vol 0.30.
        val volAtCorner = gridSurface.volAt(strike("1.0"), 3650)
        volAtCorner.compareTo(vol("0.30")) shouldBe 0
    }

    test("extrapolation: huge strike with one-day maturity clamps in BOTH dimensions to (110, 30) knot") {
        // Strike above the high knot AND maturity below the low knot — both
        // dimensions clamp, landing exactly on the (strike=110, maturity=30)
        // corner with vol 0.25.
        val volAtCorner = gridSurface.volAt(strike("10000.0"), 1)
        volAtCorner.compareTo(vol("0.25")) shouldBe 0
    }
})
