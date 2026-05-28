package com.kinetix.price.ingestion

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A zero price tick is almost always bad data — a feed glitch
 * publishes 0.00 for an instrument that very rarely (or never)
 * actually trades at zero. Ingesting it would mis-mark the position
 * and trigger spurious P&L moves. The ingestion service rejects
 * zero (and negative) prices with a domain-specific error that the
 * audit-service can correlate.
 */
class ZeroPriceRejectionTest : FunSpec({

    test("accepts a typical positive price") {
        validateIngestedPrice("AAPL", 200.50)
    }

    test("rejects exact zero") {
        val ex = shouldThrow<InvalidPriceException> {
            validateIngestedPrice("AAPL", 0.0)
        }
        ex.message!!.contains("zero") shouldBe true
        ex.instrumentId shouldBe "AAPL"
    }

    test("rejects negative price") {
        shouldThrow<InvalidPriceException> { validateIngestedPrice("AAPL", -0.01) }
    }

    test("rejects NaN") {
        shouldThrow<InvalidPriceException> { validateIngestedPrice("AAPL", Double.NaN) }
    }

    test("rejects Infinity") {
        shouldThrow<InvalidPriceException> { validateIngestedPrice("AAPL", Double.POSITIVE_INFINITY) }
    }

    test("accepts a very small but positive price (penny stock floor)") {
        validateIngestedPrice("PINK", 0.01)
    }
})
