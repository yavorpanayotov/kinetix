package com.kinetix.fix.canary

/**
 * Result of [CanaryGate.checkPromotion].
 *
 * [Allowed] — all three SLIs have been within threshold for at least
 * [SliThresholds.consecutiveHealthyMinutes]; the caller may advance
 * the canary percentage.
 *
 * [Blocked] — at least one SLI is breached or the consecutive-healthy window
 * has not yet elapsed.  [reason] is a machine-readable token suitable for
 * structured logging and metrics labels; [breachedSli] names the specific
 * metric that tripped the gate (null when the block reason is the window, not a
 * breach).
 */
sealed class PromotionDecision {

    data object Allowed : PromotionDecision()

    data class Blocked(
        val reason: String,
        val breachedSli: String? = null,
    ) : PromotionDecision()
}
