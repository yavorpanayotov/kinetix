package com.kinetix.risk.orchestrator.`var`

/**
 * An empty portfolio has zero risk. This helper returns 0.0 explicitly
 * so the orchestrator can short-circuit a real VaR call (which would
 * involve a gRPC round-trip to the risk engine) when there are no
 * positions to value. Avoiding the round-trip is the difference
 * between an instant UI render and a 200ms+ delay.
 *
 * The [confidence] argument is accepted for API parity with the full
 * VaR path; it has no effect on the zero result.
 */
@Suppress("UNUSED_PARAMETER")
fun emptyPortfolioVaR(confidence: Double = 0.99): Double = 0.0
