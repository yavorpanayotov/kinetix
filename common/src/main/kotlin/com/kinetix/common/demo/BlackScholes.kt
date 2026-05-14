package com.kinetix.common.demo

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Phase 0 closed-form Black-Scholes pricer and Greeks.
 *
 * Used by per-service demo seeders to derive option P&L attributions from the
 * deterministic price tape without calling out to risk-engine over gRPC.
 *
 * Conventions:
 *   - All prices in option's currency, per-unit (not per-contract).
 *   - vol is annualised; t in years (ACT/365 convention is the caller's responsibility).
 *   - r is the continuously-compounded risk-free rate.
 *   - Greeks are returned in their natural units (delta is dimensionless, vega is
 *     per unit of vol — multiply by 0.01 if the caller wants "per 1pp bump").
 *
 * No callers in production paths — replicating the risk-engine's Greeks would
 * require a gRPC round-trip during seed, which the plan explicitly defers as a
 * heavy lift. Closed-form is cheap, deterministic, and sufficient for demo data.
 */
object BlackScholes {

    enum class OptionType { CALL, PUT }

    data class Greeks(
        val price: Double,
        val delta: Double,
        val gamma: Double,
        val vega: Double,
        val theta: Double,
        val rho: Double,
    )

    /**
     * Compute price + first-order Greeks for a European vanilla option.
     *
     * @param spot underlying spot price
     * @param strike option strike
     * @param timeToExpiry years to expiry; clamped to >= 1/365 to avoid blow-ups
     * @param vol annualised volatility (e.g. 0.30 for 30%)
     * @param riskFreeRate continuously-compounded risk-free rate
     * @param type CALL or PUT
     */
    fun greeks(
        spot: Double,
        strike: Double,
        timeToExpiry: Double,
        vol: Double,
        riskFreeRate: Double,
        type: OptionType,
    ): Greeks {
        require(spot > 0.0) { "spot must be positive (got $spot)" }
        require(strike > 0.0) { "strike must be positive (got $strike)" }
        require(vol > 0.0) { "vol must be positive (got $vol)" }
        val t = max(timeToExpiry, 1.0 / 365.0)
        val sigmaSqrtT = vol * sqrt(t)
        val d1 = (ln(spot / strike) + (riskFreeRate + 0.5 * vol * vol) * t) / sigmaSqrtT
        val d2 = d1 - sigmaSqrtT
        val nd1 = standardNormalCdf(d1)
        val nd2 = standardNormalCdf(d2)
        val pd1 = standardNormalPdf(d1)
        val discount = exp(-riskFreeRate * t)

        return when (type) {
            OptionType.CALL -> {
                val price = spot * nd1 - strike * discount * nd2
                Greeks(
                    price = price,
                    delta = nd1,
                    gamma = pd1 / (spot * sigmaSqrtT),
                    vega = spot * pd1 * sqrt(t),
                    // Theta in price-per-year (negative for long options under normal conditions).
                    theta = -(spot * pd1 * vol) / (2.0 * sqrt(t)) - riskFreeRate * strike * discount * nd2,
                    rho = strike * t * discount * nd2,
                )
            }
            OptionType.PUT -> {
                val price = strike * discount * (1.0 - nd2) - spot * (1.0 - nd1)
                Greeks(
                    price = price,
                    delta = nd1 - 1.0,
                    gamma = pd1 / (spot * sigmaSqrtT),
                    vega = spot * pd1 * sqrt(t),
                    theta = -(spot * pd1 * vol) / (2.0 * sqrt(t)) + riskFreeRate * strike * discount * (1.0 - nd2),
                    rho = -strike * t * discount * (1.0 - nd2),
                )
            }
        }
    }

    /** Just the price — convenience wrapper. */
    fun price(
        spot: Double,
        strike: Double,
        timeToExpiry: Double,
        vol: Double,
        riskFreeRate: Double,
        type: OptionType,
    ): Double = greeks(spot, strike, timeToExpiry, vol, riskFreeRate, type).price

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Cumulative standard normal distribution function via Abramowitz-Stegun 26.2.17.
     * Max absolute error < 7.5e-8 — well within demo precision.
     */
    internal fun standardNormalCdf(x: Double): Double {
        val absX = if (x < 0) -x else x
        val k = 1.0 / (1.0 + 0.2316419 * absX)
        val a1 = 0.319381530
        val a2 = -0.356563782
        val a3 = 1.781477937
        val a4 = -1.821255978
        val a5 = 1.330274429
        val poly = a1 * k + a2 * k * k + a3 * k * k * k + a4 * k * k * k * k + a5 * k * k * k * k * k
        val cdf = 1.0 - standardNormalPdf(absX) * poly
        return if (x < 0) 1.0 - cdf else cdf
    }

    internal fun standardNormalPdf(x: Double): Double {
        val twoPi = 2.0 * Math.PI
        return exp(-0.5 * x * x) / sqrt(twoPi)
    }
}
