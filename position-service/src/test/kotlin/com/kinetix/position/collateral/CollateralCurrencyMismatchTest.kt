package com.kinetix.position.collateral

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Posted collateral has a currency; positions have a currency. They
 * match if the trader posts cash in the same currency as the exposure,
 * but with FX swaps and cross-currency books they often diverge — at
 * which point the collateral haircut needs an FX-conversion step. The
 * mismatch detector reports the pair so downstream services know
 * whether to apply the haircut as-is or convert first.
 */
class CollateralCurrencyMismatchTest : FunSpec({

    test("matching currencies report no mismatch") {
        val result = detectCollateralCurrencyMismatch(
            positionCurrency = "USD",
            collateralCurrency = "USD",
        )
        result shouldBe CollateralCurrencyCheck.Match
    }

    test("mismatched currencies report the pair") {
        val result = detectCollateralCurrencyMismatch("USD", "EUR")
        result shouldBe CollateralCurrencyCheck.Mismatch(
            positionCurrency = "USD",
            collateralCurrency = "EUR",
        )
    }

    test("case-sensitive comparison (USD != usd)") {
        val result = detectCollateralCurrencyMismatch("USD", "usd")
        result shouldBe CollateralCurrencyCheck.Mismatch(
            positionCurrency = "USD",
            collateralCurrency = "usd",
        )
    }

    test("empty currencies report a mismatch (one or both unset)") {
        detectCollateralCurrencyMismatch("USD", "") shouldBe CollateralCurrencyCheck.Mismatch("USD", "")
        detectCollateralCurrencyMismatch("", "USD") shouldBe CollateralCurrencyCheck.Mismatch("", "USD")
    }
})
