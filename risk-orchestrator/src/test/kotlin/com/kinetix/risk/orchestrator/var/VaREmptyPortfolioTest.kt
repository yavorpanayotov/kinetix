package com.kinetix.risk.orchestrator.`var`

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * An empty portfolio has zero risk. The orchestrator must return a
 * zero VaR for a book with no positions rather than throw — a freshly-
 * created book triggers an immediate risk calc, and the UX of crashing
 * on the first interaction is exactly the kind of paper cut that
 * sticks in a new user's memory. This test pins the contract.
 */
class VaREmptyPortfolioTest : FunSpec({

    test("VaR for an empty position list is exactly 0.0") {
        emptyPortfolioVaR() shouldBe 0.0
    }

    test("the result type is a finite Double (not NaN or Infinity)") {
        val v = emptyPortfolioVaR()
        v.isFinite() shouldBe true
    }

    test("the result is the same across multiple invocations (idempotent)") {
        emptyPortfolioVaR() shouldBe emptyPortfolioVaR()
    }

    test("the helper accepts the confidence level argument and ignores it for an empty book") {
        emptyPortfolioVaR(confidence = 0.99) shouldBe 0.0
        emptyPortfolioVaR(confidence = 0.95) shouldBe 0.0
    }
})
