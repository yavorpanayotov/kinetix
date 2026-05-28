package com.kinetix.risk.orchestrator.greeks

/** Five-Greek result computed by the risk-engine for a portfolio. */
data class GreeksResult(
    val delta: Double,
    val gamma: Double,
    val vega: Double,
    val theta: Double,
    val rho: Double,
)

/**
 * Validate a [GreeksResult] against the finiteness contract: every
 * Greek must be a finite Double. NaN or +/-Infinity indicates the
 * upstream calc broke (a vol of zero, a Black-Scholes input out of
 * domain, etc.) and silently propagating those values corrupts every
 * downstream number derived from them.
 *
 * Negative values are intentionally allowed: short positions have
 * negative delta and vega, and short-option positions have negative
 * gamma. Rejecting negatives wholesale would block the most common
 * short-vol books the platform serves.
 *
 * @throws IllegalArgumentException if any Greek is non-finite, naming
 * the offending Greek in the message.
 */
fun validateGreeksResult(result: GreeksResult): GreeksResult {
    requireFinite(result.delta, "delta")
    requireFinite(result.gamma, "gamma")
    requireFinite(result.vega, "vega")
    requireFinite(result.theta, "theta")
    requireFinite(result.rho, "rho")
    return result
}

private fun requireFinite(value: Double, name: String) {
    require(value.isFinite()) {
        "GreeksResult: $name is non-finite ($value); upstream calc likely broke"
    }
}
