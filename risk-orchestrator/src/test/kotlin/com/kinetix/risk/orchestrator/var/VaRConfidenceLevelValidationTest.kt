package com.kinetix.risk.orchestrator.`var`

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * VaR is reported at three standard confidence levels: 95%, 99%,
 * 99.9% — the Basel committee defines its own around 99% and 99.9%,
 * and 95% is the trader-internal "soft" view. Allowing arbitrary
 * confidence levels means a fat-fingered 9.9 (intended 99) silently
 * spits out a different number that nobody can interpret. The
 * validator pins the contract.
 */
class VaRConfidenceLevelValidationTest : FunSpec({

    test("accepts 0.95") { validateVaRConfidence(0.95) shouldBe 0.95 }
    test("accepts 0.99") { validateVaRConfidence(0.99) shouldBe 0.99 }
    test("accepts 0.999") { validateVaRConfidence(0.999) shouldBe 0.999 }

    test("rejects 0.90") { shouldThrow<IllegalArgumentException> { validateVaRConfidence(0.90) } }
    test("rejects 0.95 + 1e-3 (close but not exact)") {
        shouldThrow<IllegalArgumentException> { validateVaRConfidence(0.951) }
    }
    test("rejects 9.9 (fat-finger)") {
        shouldThrow<IllegalArgumentException> { validateVaRConfidence(9.9) }
    }
    test("rejects 0 / 1 / >1") {
        shouldThrow<IllegalArgumentException> { validateVaRConfidence(0.0) }
        shouldThrow<IllegalArgumentException> { validateVaRConfidence(1.0) }
        shouldThrow<IllegalArgumentException> { validateVaRConfidence(1.5) }
    }
    test("rejects negative") {
        shouldThrow<IllegalArgumentException> { validateVaRConfidence(-0.5) }
    }

    test("error message names the supported set") {
        val ex = shouldThrow<IllegalArgumentException> { validateVaRConfidence(0.5) }
        ex.message!!.contains("0.95") shouldBe true
        ex.message!!.contains("0.99") shouldBe true
        ex.message!!.contains("0.999") shouldBe true
    }
})
