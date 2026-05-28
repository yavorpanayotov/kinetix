package com.kinetix.rates.curve

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Different currencies anchor on different risk-free rates: USD on
 * SOFR, GBP on SONIA, EUR on ESTR, JPY on TONA. A null risk-free rate
 * for a known currency is bad data; a null for an unknown currency
 * needs to fail closed (no silent default to USD's curve). The
 * resolver pins down the contract.
 */
class RiskFreeRateNullHandlingTest : FunSpec({

    test("known currency with a valid rate returns that rate") {
        resolveRiskFreeRate(currency = "USD", rate = 0.0525) shouldBe 0.0525
    }

    test("known currency with a null rate throws a domain-specific error") {
        val ex = shouldThrow<IllegalStateException> {
            resolveRiskFreeRate("USD", rate = null)
        }
        ex.message!!.contains("USD") shouldBe true
    }

    test("known currency with NaN throws") {
        shouldThrow<IllegalStateException> {
            resolveRiskFreeRate("EUR", Double.NaN)
        }
    }

    test("unknown currency throws fail-closed (no silent default)") {
        shouldThrow<IllegalArgumentException> {
            resolveRiskFreeRate("MNT", 0.05)
        }
    }

    test("error message for unknown currency lists supported currencies") {
        val ex = shouldThrow<IllegalArgumentException> {
            resolveRiskFreeRate("MNT", 0.05)
        }
        ex.message!!.contains("USD") shouldBe true
        ex.message!!.contains("GBP") shouldBe true
    }
})
