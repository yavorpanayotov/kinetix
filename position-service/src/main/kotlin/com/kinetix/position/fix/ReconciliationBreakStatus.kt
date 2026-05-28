package com.kinetix.position.fix

/**
 * Lifecycle status of an individual reconciliation break.
 *
 * OPEN        — newly identified, not yet under investigation
 * INVESTIGATING — someone is actively researching the break
 * RESOLVED    — the break has been explained and closed
 *
 * Legal transitions (per specs/execution.allium UpdateReconciliationBreakStatus):
 *   OPEN          -> INVESTIGATING | RESOLVED
 *   INVESTIGATING -> RESOLVED | OPEN
 *   RESOLVED      -> (terminal — no transitions)
 */
enum class ReconciliationBreakStatus {
    OPEN,
    INVESTIGATING,
    RESOLVED;

    fun canTransitionTo(next: ReconciliationBreakStatus): Boolean = when (this) {
        OPEN -> next == INVESTIGATING || next == RESOLVED
        INVESTIGATING -> next == OPEN || next == RESOLVED
        RESOLVED -> false
    }
}
