package com.kinetix.refdata.instrument

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Instrument currency must be a valid ISO 4217 alpha-3 code (USD, EUR,
 * JPY, etc.). The pricing engine routes through currency-specific rate
 * curves; a typo (USDS, U$D, "us dollar") would silently fall back to
 * the default curve and quietly mis-price the instrument. The
 * validator pins down the wire-format contract: exactly three
 * upper-case ASCII letters.
 */
class InstrumentCurrencyIsoValidationTest : FunSpec({

    test("accepts canonical USD") {
        validateIsoCurrency("USD") shouldBe "USD"
    }
    test("accepts EUR / JPY / GBP") {
        validateIsoCurrency("EUR") shouldBe "EUR"
        validateIsoCurrency("JPY") shouldBe "JPY"
        validateIsoCurrency("GBP") shouldBe "GBP"
    }
    test("rejects lower-case usd") {
        shouldThrow<IllegalArgumentException> { validateIsoCurrency("usd") }
    }
    test("rejects mixed-case Usd") {
        shouldThrow<IllegalArgumentException> { validateIsoCurrency("Usd") }
    }
    test("rejects four-letter codes") {
        shouldThrow<IllegalArgumentException> { validateIsoCurrency("USDS") }
    }
    test("rejects two-letter codes") {
        shouldThrow<IllegalArgumentException> { validateIsoCurrency("US") }
    }
    test("rejects empty") {
        shouldThrow<IllegalArgumentException> { validateIsoCurrency("") }
    }
    test("rejects whitespace-padded") {
        shouldThrow<IllegalArgumentException> { validateIsoCurrency(" USD") }
        shouldThrow<IllegalArgumentException> { validateIsoCurrency("USD ") }
    }
    test("rejects digits or punctuation") {
        shouldThrow<IllegalArgumentException> { validateIsoCurrency("US1") }
        shouldThrow<IllegalArgumentException> { validateIsoCurrency("U\$D") }
    }
})
