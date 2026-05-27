package com.kinetix.demo.schedule

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Closed-form Black-Scholes pricer for European calls. Used by
 * [OptionPriceSeeder] at demo-orchestrator startup to derive realistic
 * option seed prices for the [SimulatedPriceBook] instead of carrying
 * hardcoded constants that a vol PM would immediately recognise as
 * fabricated.
 *
 * The cumulative normal is approximated by Abramowitz & Stegun 26.2.17,
 * which has |error| < 7.5e-8 — far tighter than any demo seed needs but
 * cheap to compute and self-contained (no external math dependency).
 *
 * Boundary cases:
 *  * `timeToExpiryYears <= 0` → intrinsic value `max(S - K, 0)` (expired).
 *  * `volatility <= 0` → deterministic-forward payoff `max(S*exp(rT) - K, 0) * exp(-rT)`.
 */
object BlackScholesPricer {

    fun priceEuropeanCall(
        spot: Double,
        strike: Double,
        timeToExpiryYears: Double,
        riskFreeRate: Double,
        volatility: Double,
    ): Double {
        if (timeToExpiryYears <= 0.0) {
            return max(spot - strike, 0.0)
        }
        if (volatility <= 0.0) {
            val forward = spot * exp(riskFreeRate * timeToExpiryYears)
            return max(forward - strike, 0.0) * exp(-riskFreeRate * timeToExpiryYears)
        }
        val sigmaSqrtT = volatility * sqrt(timeToExpiryYears)
        val d1 = (ln(spot / strike) + (riskFreeRate + 0.5 * volatility * volatility) * timeToExpiryYears) / sigmaSqrtT
        val d2 = d1 - sigmaSqrtT
        return spot * cumulativeNormal(d1) - strike * exp(-riskFreeRate * timeToExpiryYears) * cumulativeNormal(d2)
    }

    /**
     * Cumulative normal distribution via Abramowitz & Stegun 26.2.17.
     * Sufficient precision for demo seed pricing.
     */
    private fun cumulativeNormal(x: Double): Double {
        val a1 = 0.254829592
        val a2 = -0.284496736
        val a3 = 1.421413741
        val a4 = -1.453152027
        val a5 = 1.061405429
        val p = 0.3275911

        val sign = if (x < 0.0) -1.0 else 1.0
        val absX = kotlin.math.abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + p * absX)
        val erf = 1.0 - ((((a5 * t + a4) * t + a3) * t + a2) * t + a1) * t * exp(-absX * absX)
        return 0.5 * (1.0 + sign * erf)
    }
}
