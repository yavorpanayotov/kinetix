package com.kinetix.demo.schedule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeBetween

/**
 * Reference values come from Hull's "Options, Futures, and Other
 * Derivatives" (10th ed.) — ATM European call worked example. We allow a
 * 1¢ tolerance to absorb the cumulative-normal approximation we use.
 */
class BlackScholesPricerTest : FunSpec({

    test("Hull's worked example — ATM European call matches textbook value within 1¢") {
        // S=42, K=40, r=10%, sigma=20%, T=0.5 → call ≈ 4.76
        val price = BlackScholesPricer.priceEuropeanCall(
            spot = 42.0,
            strike = 40.0,
            timeToExpiryYears = 0.5,
            riskFreeRate = 0.10,
            volatility = 0.20,
        )
        price.shouldBeBetween(4.75, 4.77, 0.0)
    }

    test("deep-OTM call decays to near zero") {
        val price = BlackScholesPricer.priceEuropeanCall(
            spot = 50.0,
            strike = 200.0,
            timeToExpiryYears = 0.05,
            riskFreeRate = 0.045,
            volatility = 0.18,
        )
        price.shouldBeBetween(0.0, 0.05, 0.0)
    }

    test("deep-ITM call approaches intrinsic value") {
        val price = BlackScholesPricer.priceEuropeanCall(
            spot = 200.0,
            strike = 50.0,
            timeToExpiryYears = 0.05,
            riskFreeRate = 0.045,
            volatility = 0.18,
        )
        // Intrinsic = 150; small carry adjustment → ~150.11
        price.shouldBeBetween(150.0, 150.5, 0.0)
    }

    test("expired or zero-volatility option returns intrinsic only") {
        val expired = BlackScholesPricer.priceEuropeanCall(
            spot = 100.0,
            strike = 90.0,
            timeToExpiryYears = 0.0,
            riskFreeRate = 0.045,
            volatility = 0.20,
        )
        expired.shouldBeBetween(10.0, 10.0, 0.0)

        val zeroVol = BlackScholesPricer.priceEuropeanCall(
            spot = 100.0,
            strike = 105.0,
            timeToExpiryYears = 0.5,
            riskFreeRate = 0.045,
            volatility = 0.0,
        )
        // Zero vol → deterministic forward = S * exp(rT) = 102.27, payoff = 0 (OTM)
        zeroVol.shouldBeBetween(0.0, 0.0, 0.0)
    }
})
