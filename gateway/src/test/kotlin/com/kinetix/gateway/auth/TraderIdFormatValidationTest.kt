package com.kinetix.gateway.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The gateway pulls a trader ID from the auth token on every request.
 * A malformed trader ID (whitespace, SQL-injection punctuation,
 * non-ASCII) propagates into audit events, book-access logs, and the
 * downstream services' attribution queries — every place that uses
 * the id assumes it is a safe alphanumeric token. The validator pins
 * the contract: non-empty, [A-Za-z0-9_.-] only, length 1-64.
 */
class TraderIdFormatValidationTest : FunSpec({

    test("accepts a typical alphanumeric trader id") {
        validateTraderId("alice42") shouldBe "alice42"
    }
    test("accepts mixed-case + dot/hyphen/underscore (auth providers use these)") {
        validateTraderId("alice.smith-42") shouldBe "alice.smith-42"
        validateTraderId("svc_risk_bot") shouldBe "svc_risk_bot"
    }
    test("rejects empty") {
        shouldThrow<IllegalArgumentException> { validateTraderId("") }
    }
    test("rejects whitespace-only") {
        shouldThrow<IllegalArgumentException> { validateTraderId("   ") }
    }
    test("rejects embedded whitespace") {
        shouldThrow<IllegalArgumentException> { validateTraderId("alice 42") }
    }
    test("rejects SQL-injection punctuation") {
        shouldThrow<IllegalArgumentException> { validateTraderId("alice;DROP") }
        shouldThrow<IllegalArgumentException> { validateTraderId("alice'OR'1") }
    }
    test("rejects non-ASCII characters") {
        shouldThrow<IllegalArgumentException> { validateTraderId("alïce") }
    }
    test("rejects over the 64-character maximum") {
        shouldThrow<IllegalArgumentException> { validateTraderId("a".repeat(65)) }
    }
    test("accepts at exactly 64 characters") {
        val maxLen = "a".repeat(64)
        validateTraderId(maxLen) shouldBe maxLen
    }
})
