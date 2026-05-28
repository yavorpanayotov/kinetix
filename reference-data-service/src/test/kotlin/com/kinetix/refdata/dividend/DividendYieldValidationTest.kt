package com.kinetix.refdata.dividend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Dividend yield is by definition a non-negative percentage of share
 * price. A negative yield in the feed is bad data (the dividend was
 * mis-tagged as a split, or the price denominator went negative in a
 * corporate-action mid-window) and must be rejected so the wrong-sign
 * value cannot propagate into the dividend Greek and rho. Yields above
 * 50% are also rejected as a fat-finger guard — extreme but legitimate
 * yields (deep-distress equities) get a separate manual override.
 */
class DividendYieldValidationTest : FunSpec({

    test("accepts a typical positive yield (2.5%)") {
        validateDividendYield(0.025) shouldBe 0.025
    }

    test("accepts zero yield (no dividend)") {
        validateDividendYield(0.0) shouldBe 0.0
    }

    test("accepts a high but plausible yield (15%)") {
        validateDividendYield(0.15) shouldBe 0.15
    }

    test("rejects a negative yield") {
        shouldThrow<IllegalArgumentException> { validateDividendYield(-0.01) }
    }

    test("rejects yields above the 50% fat-finger guard") {
        shouldThrow<IllegalArgumentException> { validateDividendYield(0.51) }
    }

    test("rejects NaN") {
        shouldThrow<IllegalArgumentException> { validateDividendYield(Double.NaN) }
    }

    test("rejects Infinity") {
        shouldThrow<IllegalArgumentException> { validateDividendYield(Double.POSITIVE_INFINITY) }
    }
})
