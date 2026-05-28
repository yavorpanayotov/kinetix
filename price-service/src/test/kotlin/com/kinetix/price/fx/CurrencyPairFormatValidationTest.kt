package com.kinetix.price.fx

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Currency pairs in FX market data are conventionally "BASE/QUOTE"
 * with both legs as ISO 4217 alpha-3 codes — EUR/USD, GBP/JPY,
 * etc. A malformed pair propagates into the cross-rate calculator
 * and silently picks the wrong leg, producing inverted FX
 * conversions that don't reconcile against the desk's blotter.
 */
class CurrencyPairFormatValidationTest : FunSpec({

    test("accepts canonical EUR/USD") {
        validateCurrencyPair("EUR/USD") shouldBe ("EUR" to "USD")
    }

    test("accepts GBP/JPY and other major pairs") {
        validateCurrencyPair("GBP/JPY") shouldBe ("GBP" to "JPY")
        validateCurrencyPair("USD/CHF") shouldBe ("USD" to "CHF")
    }

    test("rejects missing separator") {
        shouldThrow<IllegalArgumentException> { validateCurrencyPair("EURUSD") }
    }

    test("rejects lowercase legs") {
        shouldThrow<IllegalArgumentException> { validateCurrencyPair("eur/usd") }
    }

    test("rejects same-currency self-pair") {
        shouldThrow<IllegalArgumentException> { validateCurrencyPair("USD/USD") }
    }

    test("rejects 4-letter legs") {
        shouldThrow<IllegalArgumentException> { validateCurrencyPair("EURO/USD") }
    }

    test("rejects empty") {
        shouldThrow<IllegalArgumentException> { validateCurrencyPair("") }
    }

    test("rejects extra separators") {
        shouldThrow<IllegalArgumentException> { validateCurrencyPair("EUR//USD") }
        shouldThrow<IllegalArgumentException> { validateCurrencyPair("EUR/USD/JPY") }
    }
})
