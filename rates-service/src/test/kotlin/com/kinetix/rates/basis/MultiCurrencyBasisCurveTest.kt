package com.kinetix.rates.basis

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * A cross-currency basis curve captures the spread between an FX-
 * implied forward rate and the corresponding interest-rate-parity-
 * implied forward. EUR/USD basis has traded ±50bps in stress; pricing
 * a euro-denominated swap against a flat zero-basis curve produces
 * systematic error during those episodes. The basis lookup must be
 * symmetric (USD/EUR == -EUR/USD) and finite for every tenor present
 * on the underlying single-currency curves.
 */
class MultiCurrencyBasisCurveTest : FunSpec({

    val tol = 1e-12

    test("basis lookup returns the published basis for a known pair+tenor") {
        val curve = MultiCurrencyBasisCurve(
            quotes = listOf(
                BasisQuote("USD", "EUR", tenorDays = 90, basisBp = -25),
                BasisQuote("USD", "EUR", tenorDays = 180, basisBp = -30),
            ),
        )
        curve.basisBp("USD", "EUR", tenorDays = 90) shouldBe -25
    }

    test("symmetry: B/A basis equals negation of A/B basis") {
        val curve = MultiCurrencyBasisCurve(
            quotes = listOf(BasisQuote("USD", "EUR", 90, -25)),
        )
        curve.basisBp("USD", "EUR", 90) shouldBe -25
        curve.basisBp("EUR", "USD", 90) shouldBe 25
    }

    test("missing tenor returns null (caller may interpolate)") {
        val curve = MultiCurrencyBasisCurve(
            quotes = listOf(BasisQuote("USD", "EUR", 90, -25)),
        )
        curve.basisBp("USD", "EUR", 180) shouldBe null
    }

    test("missing currency pair returns null") {
        val curve = MultiCurrencyBasisCurve(
            quotes = listOf(BasisQuote("USD", "EUR", 90, -25)),
        )
        curve.basisBp("USD", "GBP", 90) shouldBe null
    }

    test("a same-currency lookup is always zero basis") {
        val curve = MultiCurrencyBasisCurve(quotes = emptyList())
        curve.basisBp("USD", "USD", 90) shouldBe 0
    }

    test("interpolated tenor between two quoted points returns linear interp") {
        val curve = MultiCurrencyBasisCurve(
            quotes = listOf(
                BasisQuote("USD", "EUR", tenorDays = 90, basisBp = -20),
                BasisQuote("USD", "EUR", tenorDays = 180, basisBp = -40),
            ),
        )
        // Mid-tenor 135 days -> halfway -> -30bps
        val result = curve.interpolatedBasisBp("USD", "EUR", tenorDays = 135)
        abs(result!! - (-30.0)) shouldBeLessThanOrEqual tol
    }

    test("interpolated basis honours the symmetry rule") {
        val curve = MultiCurrencyBasisCurve(
            quotes = listOf(
                BasisQuote("USD", "EUR", 90, -20),
                BasisQuote("USD", "EUR", 180, -40),
            ),
        )
        val ab = curve.interpolatedBasisBp("USD", "EUR", 135)!!
        val ba = curve.interpolatedBasisBp("EUR", "USD", 135)!!
        abs(ab + ba) shouldBeLessThanOrEqual tol
    }
})
