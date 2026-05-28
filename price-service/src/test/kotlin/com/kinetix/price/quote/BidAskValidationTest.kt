package com.kinetix.price.quote

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Bid/ask quotes from a market data vendor occasionally arrive
 * mangled: an inverted pair (`bid > ask`), or a negative bid that
 * dropped a sign byte. The validator catches these at ingestion so
 * downstream pricers and spread calculators never see nonsense
 * values that would corrupt P&L attribution.
 */
class BidAskValidationTest : FunSpec({

    test("accepts a typical bid/ask spread") {
        validateBidAsk(bid = 100.5, ask = 100.7)
    }

    test("accepts equal bid and ask (locked market)") {
        validateBidAsk(100.5, 100.5)
    }

    test("rejects inverted (bid > ask)") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateBidAsk(bid = 100.7, ask = 100.5)
        }
        ex.message!!.contains("bid") shouldBe true
        ex.message!!.contains("ask") shouldBe true
    }

    test("rejects negative bid") {
        shouldThrow<IllegalArgumentException> { validateBidAsk(-0.01, 100.5) }
    }

    test("rejects negative ask") {
        shouldThrow<IllegalArgumentException> { validateBidAsk(100.5, -0.01) }
    }

    test("accepts zero bid (option struck deep ITM)") {
        validateBidAsk(0.0, 0.05)
    }

    test("rejects NaN") {
        shouldThrow<IllegalArgumentException> { validateBidAsk(Double.NaN, 100.5) }
        shouldThrow<IllegalArgumentException> { validateBidAsk(100.0, Double.NaN) }
    }
})
