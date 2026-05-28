package com.kinetix.risk.orchestrator.`var`

private val SUPPORTED_CONFIDENCE = setOf(0.95, 0.99, 0.999)

/**
 * Validate that a VaR confidence level matches one of the three
 * supported values (0.95, 0.99, 0.999). Anything else — a typo, a
 * fat-finger like 9.9, or a non-standard 0.997 — would yield a
 * number that no operator could interpret correctly, so the call
 * fails fast instead.
 */
fun validateVaRConfidence(confidence: Double): Double {
    require(confidence in SUPPORTED_CONFIDENCE) {
        "VaR confidence $confidence is not in the supported set $SUPPORTED_CONFIDENCE"
    }
    return confidence
}
