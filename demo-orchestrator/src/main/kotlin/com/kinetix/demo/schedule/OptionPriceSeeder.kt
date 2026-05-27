package com.kinetix.demo.schedule

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Derives Black-Scholes-consistent seed prices for the demo derivatives book
 * at startup. The output is layered on top of [SimulatedPriceBook.SEED_PRICES]
 * so that SPX-OPT-5000C and VIX-OPT-20C carry prices a vol PM can defend
 * (strike, expiry, IV, and underlying spot all flow into a real
 * closed-form valuation) rather than the round constants that the static
 * seed table would otherwise carry.
 *
 * The option specs duplicate the structured reference-data entries seeded
 * in `reference-data-service` for the same instrument ids; demo-orchestrator
 * does not currently hold a reference-data HTTP client and keeping the
 * duplication scoped here avoids a cross-module fetch on the demo hot path.
 */
object OptionPriceSeeder {

    private const val RISK_FREE_RATE: Double = 0.045
    private val DAYS_PER_YEAR: Double = 365.0

    /**
     * Per-option specs used to value each derivatives-book seed.
     *
     * Spot assumptions:
     *  * SPX-OPT-5000C: uses 5060 (matches the ES-FUT-MAR seed in
     *    [SimulatedPriceBook.SEED_PRICES], a reasonable cash-index proxy
     *    for this demo).
     *  * VIX-OPT-20C: uses 18.5 — typical VIX futures level. VIX itself is
     *    not separately simulated, so the future-level proxy keeps the
     *    derived price within the empirical $1–$5 band quoted in kx-bt2.
     */
    private val SPECS: List<OptionSpec> = listOf(
        OptionSpec(
            instrumentId = "SPX-OPT-5000C",
            spot = 5060.0,
            strike = 5000.0,
            expiry = LocalDate.of(2026, 6, 19),
            impliedVol = 0.18,
        ),
        OptionSpec(
            instrumentId = "VIX-OPT-20C",
            spot = 18.5,
            strike = 20.0,
            expiry = LocalDate.of(2026, 6, 18),
            impliedVol = 1.20,
        ),
    )

    fun computeSeeds(clock: Clock): Map<String, BigDecimal> {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        return SPECS.associate { spec ->
            val days = ChronoUnit.DAYS.between(today, spec.expiry).coerceAtLeast(0L)
            val timeToExpiry = days / DAYS_PER_YEAR
            val price = BlackScholesPricer.priceEuropeanCall(
                spot = spec.spot,
                strike = spec.strike,
                timeToExpiryYears = timeToExpiry,
                riskFreeRate = RISK_FREE_RATE,
                volatility = spec.impliedVol,
            )
            spec.instrumentId to BigDecimal(price).setScale(2, RoundingMode.HALF_UP)
        }
    }

    private data class OptionSpec(
        val instrumentId: String,
        val spot: Double,
        val strike: Double,
        val expiry: LocalDate,
        val impliedVol: Double,
    )
}
