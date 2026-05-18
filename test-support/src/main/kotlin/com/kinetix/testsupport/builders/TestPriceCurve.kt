package com.kinetix.testsupport.builders

import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import java.time.Instant

/**
 * Test fixture factory for [ForwardCurve] — the production type that
 * represents a one-dimensional price curve over a tenor grid. Two named
 * constructors cover the cases that come up overwhelmingly often in pricing
 * and risk tests: a flat curve where every tenor has the same value, and a
 * linear curve where the value rises (or falls) by a fixed slope per tenor
 * step.
 *
 * Both factories return a fully valid [ForwardCurve] with sensible defaults
 * for the metadata fields ([instrumentId], [assetClass], [asOfDate],
 * [source]) and a five-tenor grid (1M / 3M / 6M / 1Y / 2Y) that mirrors the
 * grid used by `DevDataSeeder` and the rates-service feed simulator, so
 * fixtures stay consistent with the rest of the platform.
 *
 * The "linear" shape is over the tenor *index* — i.e. `value(t_i) = start +
 * slope * i` for `i` in `0..n-1`. This is the simplest interpretation of a
 * linear curve over a discrete grid and avoids smuggling in day-count
 * arithmetic that the [CurvePoint] type itself doesn't enforce.
 *
 * Example:
 * ```
 * val flat   = TestPriceCurve.constant(100.0)
 * val rising = TestPriceCurve.linear(start = 100.0, slope = 1.0)
 * val fall   = TestPriceCurve.linear(start = 100.0, slope = -0.5)
 * ```
 *
 * Values produced by [linear] are not constrained to be positive — the
 * [CurvePoint] type accepts any double — but tests that need a strictly
 * positive curve should pick a `(start, slope)` pair where `start + slope *
 * (n - 1) > 0` over the chosen grid.
 */
object TestPriceCurve {

    /** Default `as-of` instant for fixture curves (deterministic, stable). */
    val DEFAULT_AS_OF: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** Default instrument id for fixture curves. */
    val DEFAULT_INSTRUMENT_ID: InstrumentId = InstrumentId("AAPL")

    /** Default asset class for fixture curves. Matches the `DevDataSeeder` convention. */
    const val DEFAULT_ASSET_CLASS: String = "EQUITY"

    /** Default rate source for fixture curves. */
    val DEFAULT_SOURCE: RateSource = RateSource.INTERNAL

    /**
     * Default tenor grid for [constant] and [linear]. Five tenors spanning
     * 1M to 2Y — the same grid `DevDataSeeder.FORWARD_CURVE_TENORS` and the
     * rates-service feed simulator use, so fixtures look familiar wherever
     * they appear.
     */
    val DEFAULT_TENORS: List<String> = listOf("1M", "3M", "6M", "1Y", "2Y")

    /**
     * Returns a flat [ForwardCurve] where every tenor in [DEFAULT_TENORS]
     * has the same [price]. Useful as a no-op baseline curve for tests that
     * exercise downstream pricing without caring about curve shape.
     */
    fun constant(price: Double = 100.0): ForwardCurve {
        val points = DEFAULT_TENORS.map { tenor -> CurvePoint(tenor = tenor, value = price) }
        return ForwardCurve(
            instrumentId = DEFAULT_INSTRUMENT_ID,
            assetClass = DEFAULT_ASSET_CLASS,
            points = points,
            asOfDate = DEFAULT_AS_OF,
            source = DEFAULT_SOURCE,
        )
    }

    /**
     * Returns a linear [ForwardCurve] over [DEFAULT_TENORS] where the value
     * at tenor index `i` is `start + slope * i`. So with the default grid
     * (1M, 3M, 6M, 1Y, 2Y) and `linear(100.0, 1.0)` you get values
     * `[100.0, 101.0, 102.0, 103.0, 104.0]`.
     *
     * A positive [slope] gives an upward-sloping curve; a negative slope
     * gives a downward-sloping curve. `slope = 0.0` collapses to
     * [constant]`(start)`.
     */
    fun linear(start: Double = 100.0, slope: Double = 1.0): ForwardCurve {
        val points = DEFAULT_TENORS.mapIndexed { index, tenor ->
            CurvePoint(tenor = tenor, value = start + slope * index)
        }
        return ForwardCurve(
            instrumentId = DEFAULT_INSTRUMENT_ID,
            assetClass = DEFAULT_ASSET_CLASS,
            points = points,
            asOfDate = DEFAULT_AS_OF,
            source = DEFAULT_SOURCE,
        )
    }
}
