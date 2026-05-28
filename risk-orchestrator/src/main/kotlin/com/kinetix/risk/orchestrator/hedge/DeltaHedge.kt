package com.kinetix.risk.orchestrator.hedge

import kotlin.math.abs

/**
 * Apply a delta hedge against [portfolioDelta] and report the residual.
 *
 * A valid hedge is opposite in sign to the portfolio delta and not
 * larger in magnitude (over-hedging would replace long-delta risk with
 * short-delta risk, which is rarely what the trader intended — if they
 * want that, they should book it as a separate position).
 *
 * Returns the residual delta after netting the hedge against the
 * portfolio, plus the fraction of the original magnitude that the
 * hedge eliminated. Throws [IllegalArgumentException] for an
 * over-hedge, a same-sign hedge, or a hedge against a zero-delta book
 * that would only add risk.
 */
fun applyDeltaHedge(portfolioDelta: Double, hedgeSize: Double): DeltaHedgeResult {
    if (portfolioDelta == 0.0) {
        require(hedgeSize == 0.0) {
            "applyDeltaHedge: portfolio delta is 0; non-zero hedge would add risk"
        }
        return DeltaHedgeResult(residualDelta = 0.0, reductionRatio = 1.0)
    }
    val magnitudePortfolio = abs(portfolioDelta)
    val magnitudeHedge = abs(hedgeSize)
    require(magnitudeHedge <= magnitudePortfolio) {
        "applyDeltaHedge: hedge magnitude $magnitudeHedge exceeds portfolio magnitude $magnitudePortfolio"
    }
    require(portfolioDelta * hedgeSize <= 0) {
        "applyDeltaHedge: hedge sign matches portfolio sign — would amplify risk"
    }
    val residual = portfolioDelta + hedgeSize
    val ratio = 1.0 - (abs(residual) / magnitudePortfolio)
    return DeltaHedgeResult(residualDelta = residual, reductionRatio = ratio)
}

/** Outcome of [applyDeltaHedge]. */
data class DeltaHedgeResult(
    val residualDelta: Double,
    val reductionRatio: Double,
)
