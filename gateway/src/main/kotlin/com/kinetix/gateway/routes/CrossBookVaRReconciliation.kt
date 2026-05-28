package com.kinetix.gateway.routes

import com.kinetix.gateway.client.CrossBookVaRResultSummary
import kotlin.math.abs

/**
 * Guards the Risk dashboard reconciliation invariants defined in
 * `specs/risk.allium` for cross-book valuation results.
 *
 * The trader-review (P0 #5, `plans/ui-trader-review.md`) found four
 * different "total VaR" numbers rendered on the same Risk tab for the same
 * scope. Root cause: the gateway passed through whatever the orchestrator
 * sent, with no check that:
 *
 *   - sum(bookContributions[].varContribution) == varValue (within rounding)
 *   - totalStandaloneVar - diversificationBenefit == varValue (within rounding)
 *
 * Those two algebraic facts come directly out of the spec — `varContribution`
 * is a decomposition of the DIVERSIFIED aggregate, and the diversification
 * benefit is by definition `sum(standalone) - aggregate`. When the
 * orchestrator returns a payload that violates them by orders of magnitude,
 * the gateway must not silently forward it.
 *
 * The check is intentionally tolerant: we only flag inconsistencies above a
 * floor of $1.00 OR 0.1 % of the headline VaR, whichever is larger. This
 * leaves room for the orchestrator's "%.2f" formatting and FP rounding while
 * still catching the production bug (off by ~$182M on a $1M headline).
 */
object CrossBookVaRReconciliation {

    /** Absolute floor for rounding tolerance, in absolute USD. */
    private const val ABSOLUTE_TOLERANCE_USD: Double = 1.00

    /** Relative tolerance — 0.1 % of the headline VaR. */
    private const val RELATIVE_TOLERANCE: Double = 0.001

    /**
     * @return null when the result satisfies the invariants; a human-readable
     *         description of the first violated invariant otherwise.
     */
    fun firstViolation(result: CrossBookVaRResultSummary): String? {
        val aggregate = result.varValue
        val tolerance = maxOf(ABSOLUTE_TOLERANCE_USD, abs(aggregate) * RELATIVE_TOLERANCE)

        // An empty `bookContributions` list means the orchestrator didn't
        // produce a per-book decomposition (e.g. single-book request, or a
        // legacy fixture). Skip the decomposition-sum check rather than
        // treating "no breakdown" as inconsistent — we still validate the
        // standalone / diversification algebra below, which is sufficient.
        if (result.bookContributions.isNotEmpty()) {
            val contributionsSum = result.bookContributions.sumOf { it.varContribution }
            if (abs(contributionsSum - aggregate) > tolerance) {
                return "sum(bookContributions.varContribution)=$contributionsSum does not reconcile to varValue=$aggregate (tolerance=$tolerance)"
            }
        }

        val derivedFromStandalone = result.totalStandaloneVar - result.diversificationBenefit
        if (abs(derivedFromStandalone - aggregate) > tolerance) {
            return "totalStandaloneVar - diversificationBenefit=$derivedFromStandalone does not reconcile to varValue=$aggregate (tolerance=$tolerance)"
        }

        return null
    }
}
