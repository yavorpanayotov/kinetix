package com.kinetix.risk.orchestrator.hedge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * A delta hedge is the canonical first-order risk neutralisation: take
 * an offsetting position in the underlying instrument equal in size to
 * the portfolio's net delta, and the combined book has zero (or near-
 * zero) instantaneous P&L sensitivity to small underlying moves.
 *
 * This test pins the contract on [applyDeltaHedge]: given a portfolio
 * net delta and a target reduction, the helper returns a hedge size
 * and a residual delta that, when summed, is at least 99% smaller than
 * the original. Over-hedging (a hedge larger than the portfolio delta)
 * is rejected as a fat-finger; under-hedging is allowed but flagged so
 * the trader knows the residual.
 */
class DeltaHedgeEffectivenessTest : FunSpec({

    test("a long-delta portfolio with a matching short hedge has near-zero residual delta") {
        val result = applyDeltaHedge(portfolioDelta = 1_000.0, hedgeSize = -1_000.0)
        result.residualDelta shouldBe 0.0
        result.reductionRatio shouldBe 1.0
    }

    test("a 50% hedge reduces residual delta by 50%") {
        val result = applyDeltaHedge(portfolioDelta = 1_000.0, hedgeSize = -500.0)
        result.residualDelta shouldBe 500.0
        result.reductionRatio shouldBe 0.5
    }

    test("over-hedging is rejected (hedge magnitude > portfolio magnitude)") {
        val result = runCatching { applyDeltaHedge(1_000.0, -1_500.0) }
        result.isFailure shouldBe true
    }

    test("hedging in the wrong direction (same sign as portfolio) is rejected") {
        runCatching { applyDeltaHedge(1_000.0, 500.0) }.isFailure shouldBe true
        runCatching { applyDeltaHedge(-1_000.0, -500.0) }.isFailure shouldBe true
    }

    test("a short-delta portfolio with a matching long hedge has near-zero residual delta") {
        val result = applyDeltaHedge(portfolioDelta = -1_000.0, hedgeSize = 1_000.0)
        abs(result.residualDelta) shouldBeLessThan 1e-9
    }

    test("zero portfolio delta needs no hedge") {
        val result = applyDeltaHedge(portfolioDelta = 0.0, hedgeSize = 0.0)
        result.residualDelta shouldBe 0.0
        result.reductionRatio shouldBe 1.0
    }

    test("zero portfolio delta with a non-zero hedge is rejected (would add risk)") {
        runCatching { applyDeltaHedge(0.0, 500.0) }.isFailure shouldBe true
    }

    test("reductionRatio is between 0 and 1 for any valid hedge") {
        for (deltaMag in listOf(100.0, 1_000.0, 1_000_000.0)) {
            for (fraction in listOf(0.1, 0.5, 0.9, 1.0)) {
                val result = applyDeltaHedge(deltaMag, -deltaMag * fraction)
                (result.reductionRatio in 0.0..1.0) shouldBe true
            }
        }
    }
})
