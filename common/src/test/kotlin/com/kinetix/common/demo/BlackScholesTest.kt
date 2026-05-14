package com.kinetix.common.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlin.math.abs

/**
 * Closed-form Black-Scholes Greeks are pinned against textbook values and
 * cross-checked via finite-difference bumps so a refactor of the formula
 * can't silently distort downstream P&L attribution numbers.
 */
class BlackScholesTest : FunSpec({

    test("CDF matches textbook reference values") {
        // Standard normal CDF at well-known points.
        BlackScholes.standardNormalCdf(0.0) shouldBeApprox 0.5
        BlackScholes.standardNormalCdf(1.0) shouldBeApprox 0.8413447
        BlackScholes.standardNormalCdf(-1.0) shouldBeApprox 0.1586553
        BlackScholes.standardNormalCdf(1.96) shouldBeApprox 0.9750021
    }

    test("ATM call price matches textbook Hull example: S=K=100, r=0.05, sigma=0.20, T=1") {
        val g = BlackScholes.greeks(
            spot = 100.0,
            strike = 100.0,
            timeToExpiry = 1.0,
            vol = 0.20,
            riskFreeRate = 0.05,
            type = BlackScholes.OptionType.CALL,
        )
        // Textbook value ≈ 10.4506 (Hull table).
        abs(g.price - 10.4506) shouldBeLessThan 0.01
        // ATM call delta ≈ N(d1) where d1 ≈ 0.35, so delta ≈ 0.636.
        abs(g.delta - 0.6368) shouldBeLessThan 0.01
    }

    test("put-call parity: C - P = S - K * exp(-rT)") {
        val s = 95.0
        val k = 100.0
        val t = 0.5
        val sigma = 0.30
        val r = 0.04
        val call = BlackScholes.price(s, k, t, sigma, r, BlackScholes.OptionType.CALL)
        val put = BlackScholes.price(s, k, t, sigma, r, BlackScholes.OptionType.PUT)
        val parityLhs = call - put
        val parityRhs = s - k * kotlin.math.exp(-r * t)
        abs(parityLhs - parityRhs) shouldBeLessThan 0.001
    }

    test("delta matches finite-difference bump") {
        val args = listOf(
            // spot, strike, t, vol, r, type
            BlackScholesArgs(100.0, 100.0, 1.0, 0.20, 0.05, BlackScholes.OptionType.CALL),
            BlackScholesArgs(100.0, 110.0, 0.5, 0.30, 0.03, BlackScholes.OptionType.CALL),
            BlackScholesArgs(95.0, 100.0, 0.25, 0.40, 0.05, BlackScholes.OptionType.PUT),
            BlackScholesArgs(150.0, 140.0, 0.75, 0.25, 0.045, BlackScholes.OptionType.PUT),
        )
        for (a in args) {
            val g = BlackScholes.greeks(a.s, a.k, a.t, a.sigma, a.r, a.type)
            val bump = a.s * 0.0001
            val pUp = BlackScholes.price(a.s + bump, a.k, a.t, a.sigma, a.r, a.type)
            val pDown = BlackScholes.price(a.s - bump, a.k, a.t, a.sigma, a.r, a.type)
            val numericDelta = (pUp - pDown) / (2 * bump)
            abs(g.delta - numericDelta) shouldBeLessThan 0.001
        }
    }

    test("gamma matches finite-difference second derivative") {
        val g = BlackScholes.greeks(100.0, 100.0, 0.5, 0.25, 0.04, BlackScholes.OptionType.CALL)
        val bump = 0.5
        val s = 100.0
        val pUp = BlackScholes.price(s + bump, 100.0, 0.5, 0.25, 0.04, BlackScholes.OptionType.CALL)
        val p = BlackScholes.price(s, 100.0, 0.5, 0.25, 0.04, BlackScholes.OptionType.CALL)
        val pDown = BlackScholes.price(s - bump, 100.0, 0.5, 0.25, 0.04, BlackScholes.OptionType.CALL)
        val numericGamma = (pUp - 2 * p + pDown) / (bump * bump)
        abs(g.gamma - numericGamma) shouldBeLessThan 0.001
    }

    test("vega matches finite-difference bump in vol") {
        val g = BlackScholes.greeks(100.0, 95.0, 0.75, 0.30, 0.045, BlackScholes.OptionType.CALL)
        val bump = 0.0001
        val pUp = BlackScholes.price(100.0, 95.0, 0.75, 0.30 + bump, 0.045, BlackScholes.OptionType.CALL)
        val pDown = BlackScholes.price(100.0, 95.0, 0.75, 0.30 - bump, 0.045, BlackScholes.OptionType.CALL)
        val numericVega = (pUp - pDown) / (2 * bump)
        abs(g.vega - numericVega) shouldBeLessThan 0.01
    }

    test("call delta in (0, 1) and put delta in (-1, 0)") {
        val call = BlackScholes.greeks(100.0, 100.0, 0.5, 0.2, 0.05, BlackScholes.OptionType.CALL)
        val put = BlackScholes.greeks(100.0, 100.0, 0.5, 0.2, 0.05, BlackScholes.OptionType.PUT)
        call.delta shouldBeGreaterThan 0.0
        call.delta shouldBeLessThan 1.0
        put.delta shouldBeGreaterThan -1.0
        put.delta shouldBeLessThan 0.0
    }

    test("gamma and vega are always non-negative for vanilla options") {
        val cases = listOf(
            BlackScholesArgs(100.0, 100.0, 0.5, 0.20, 0.05, BlackScholes.OptionType.CALL),
            BlackScholesArgs(50.0, 100.0, 0.1, 0.50, 0.05, BlackScholes.OptionType.PUT),
            BlackScholesArgs(200.0, 100.0, 2.0, 0.10, 0.05, BlackScholes.OptionType.CALL),
        )
        for (c in cases) {
            val g = BlackScholes.greeks(c.s, c.k, c.t, c.sigma, c.r, c.type)
            g.gamma shouldBeGreaterThan -1e-12
            g.vega shouldBeGreaterThan -1e-12
        }
    }
})

private data class BlackScholesArgs(
    val s: Double,
    val k: Double,
    val t: Double,
    val sigma: Double,
    val r: Double,
    val type: BlackScholes.OptionType,
)

private infix fun Double.shouldBeApprox(expected: Double) {
    require(abs(this - expected) < 1e-5) { "expected $expected, got $this" }
}
