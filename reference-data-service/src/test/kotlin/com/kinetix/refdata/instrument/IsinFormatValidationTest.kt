package com.kinetix.refdata.instrument

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * ISIN is the canonical 12-character security identifier (ISO 6166):
 * 2 ASCII country code, 9 alphanumeric national identifier, 1 numeric
 * check digit computed via the Luhn algorithm. A mis-formed ISIN
 * silently collides with another security in the master database;
 * the validator catches the format issues before the record reaches
 * the trade book.
 */
class IsinFormatValidationTest : FunSpec({

    // Known valid ISINs from public examples.
    test("accepts a valid US-domiciled ISIN") {
        validateIsin("US0378331005") shouldBe "US0378331005"   // Apple Inc.
    }
    test("accepts a valid German-domiciled ISIN") {
        validateIsin("DE000BAY0017") shouldBe "DE000BAY0017"   // Bayer AG
    }
    test("rejects shorter-than-12") {
        shouldThrow<IllegalArgumentException> { validateIsin("US037833100") }
    }
    test("rejects longer-than-12") {
        shouldThrow<IllegalArgumentException> { validateIsin("US03783310050") }
    }
    test("rejects lowercase country code") {
        shouldThrow<IllegalArgumentException> { validateIsin("us0378331005") }
    }
    test("rejects with non-numeric check digit") {
        shouldThrow<IllegalArgumentException> { validateIsin("US037833100X") }
    }
    test("rejects with bad check digit (Luhn mismatch)") {
        // Same ISIN but with check digit 1 instead of 5.
        shouldThrow<IllegalArgumentException> { validateIsin("US0378331001") }
    }
    test("rejects empty") {
        shouldThrow<IllegalArgumentException> { validateIsin("") }
    }
    test("rejects punctuation") {
        shouldThrow<IllegalArgumentException> { validateIsin("US-0378331005") }
    }
})
