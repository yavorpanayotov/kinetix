package com.kinetix.testsupport.builders

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

/**
 * Test fixture factory for [VolSurface]. Two named constructors cover the
 * cases that come up overwhelmingly often in option-pricing tests: a flat
 * vol surface (constant vol across all strikes and maturities) and a
 * quadratic-in-moneyness smile (curvature + optional skew).
 *
 * Both factories return a fully valid [VolSurface] with sensible defaults
 * for the metadata fields ([instrumentId], [asOf]) so the dimension is
 * always obvious in test failures. The default knot grid mirrors a typical
 * equity option board — strikes 80/90/100/110/120 around an ATM of 100,
 * and three maturities (3M, 1Y, 2Y) expressed in days because [VolPoint]
 * stores maturity as `Int` days.
 *
 * Example:
 * ```
 * val flat   = TestVolSurface.flatAt(0.20)
 * val smile  = TestVolSurface.withSmile(atmVol = 0.20, curvature = 0.05)
 * val skewed = TestVolSurface.withSmile(atmVol = 0.20, skew = -0.05)
 * ```
 *
 * Vols produced by [withSmile] are guaranteed to be strictly positive — the
 * factory validates the computed grid and throws if any knot would have a
 * non-positive vol so the [VolPoint] constructor invariant cannot be
 * silently violated.
 */
object TestVolSurface {

    /** Default `as-of` instant for fixture surfaces (deterministic, stable). */
    val DEFAULT_AS_OF: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** Default instrument id for fixture surfaces. */
    val DEFAULT_INSTRUMENT_ID: InstrumentId = InstrumentId("AAPL")

    /**
     * Default strike grid for [flatAt] and [withSmile]. Five strikes around
     * an ATM of 100 keep moneyness within `[-0.20, +0.20]`, which is a
     * realistic equity option board.
     */
    val DEFAULT_STRIKES: List<Double> = listOf(80.0, 90.0, 100.0, 110.0, 120.0)

    /**
     * Default maturity grid for [flatAt] and [withSmile], in days. Roughly
     * 3M / 1Y / 2Y — covers the maturities used by the vast majority of
     * option-pricing tests.
     */
    val DEFAULT_MATURITY_DAYS: List<Int> = listOf(90, 365, 730)

    /**
     * Returns a flat [VolSurface] where every knot has the same vol. The
     * knot grid spans [DEFAULT_STRIKES] × [DEFAULT_MATURITY_DAYS], giving
     * 15 points by default.
     *
     * @throws IllegalArgumentException if [vol] is not strictly positive.
     */
    fun flatAt(vol: Double): VolSurface {
        require(vol > 0.0) { "Vol must be strictly positive, was $vol" }

        val impliedVol = BigDecimal.valueOf(vol)
        val points = DEFAULT_STRIKES.flatMap { strike ->
            DEFAULT_MATURITY_DAYS.map { days ->
                VolPoint(
                    strike = BigDecimal.valueOf(strike),
                    maturityDays = days,
                    impliedVol = impliedVol,
                )
            }
        }
        return VolSurface(
            instrumentId = DEFAULT_INSTRUMENT_ID,
            asOf = DEFAULT_AS_OF,
            points = points,
        )
    }

    /**
     * Returns a smile [VolSurface] where each strike's vol is a quadratic
     * function of moneyness around the ATM strike (the median of [strikes]):
     *
     * ```
     * vol(K) = atmVol + skew * m + curvature * m^2
     *   where m = (K - K_atm) / K_atm
     * ```
     *
     * The same vol(K) is applied at every maturity in [maturityDays] — i.e.
     * the surface has a constant term structure but a non-trivial strike
     * smile. This is the simplest shape that exercises smile-aware code
     * paths without dragging in term-structure complexity.
     *
     * @param atmVol vol at the ATM strike. Must be strictly positive.
     * @param curvature quadratic coefficient. Higher = more bowl-shaped
     *   smile. `0.0` collapses to a pure skew or flat surface.
     * @param skew linear coefficient. Negative = put skew (OTM puts more
     *   expensive), positive = call skew.
     * @param strikes strike grid. Must be non-empty and contain at least
     *   one positive strike.
     * @param maturityDays maturity grid in days. Must be non-empty and
     *   contain only positive values.
     *
     * @throws IllegalArgumentException if [atmVol] is not strictly positive,
     *   if either grid is empty, or if the computed smile would produce a
     *   non-positive vol at any knot (which would violate the [VolPoint]
     *   invariant).
     */
    fun withSmile(
        atmVol: Double = 0.20,
        curvature: Double = 0.05,
        skew: Double = 0.0,
        strikes: List<Double> = DEFAULT_STRIKES,
        maturityDays: List<Int> = DEFAULT_MATURITY_DAYS,
    ): VolSurface {
        require(atmVol > 0.0) { "atmVol must be strictly positive, was $atmVol" }
        require(strikes.isNotEmpty()) { "strikes must not be empty" }
        require(maturityDays.isNotEmpty()) { "maturityDays must not be empty" }
        require(strikes.all { it > 0.0 }) { "All strikes must be strictly positive, got $strikes" }
        require(maturityDays.all { it > 0 }) { "All maturityDays must be strictly positive, got $maturityDays" }

        // ATM = median of the strike grid. Using the median keeps the smile
        // symmetric around the centre of the grid even when the caller passes
        // an irregular number of strikes.
        val sortedStrikes = strikes.sorted()
        val atmStrike = sortedStrikes[sortedStrikes.size / 2]

        // Pre-compute the per-strike vol once, since the term structure is
        // flat. Reject the whole call if any computed vol is non-positive,
        // so the failure surfaces here rather than deep inside [VolPoint].
        val volByStrike: Map<Double, Double> = sortedStrikes.associateWith { strike ->
            val moneyness = (strike - atmStrike) / atmStrike
            val v = atmVol + skew * moneyness + curvature * moneyness * moneyness
            require(v > 0.0) {
                "Smile parameters produce non-positive vol $v at strike=$strike " +
                    "(atmVol=$atmVol, curvature=$curvature, skew=$skew)"
            }
            v
        }

        val points = sortedStrikes.flatMap { strike ->
            val impliedVol = BigDecimal.valueOf(volByStrike.getValue(strike))
                .round(MathContext.DECIMAL64)
            maturityDays.map { days ->
                VolPoint(
                    strike = BigDecimal.valueOf(strike),
                    maturityDays = days,
                    impliedVol = impliedVol,
                )
            }
        }

        return VolSurface(
            instrumentId = DEFAULT_INSTRUMENT_ID,
            asOf = DEFAULT_AS_OF,
            points = points,
        )
    }
}
