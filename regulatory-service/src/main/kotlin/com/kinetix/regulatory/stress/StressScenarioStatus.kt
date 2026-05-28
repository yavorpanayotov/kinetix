package com.kinetix.regulatory.stress

/**
 * Run-state of a stress-scenario job.
 *
 *   PENDING -> RUNNING -> { COMPLETED | FAILED }
 *
 * COMPLETED and FAILED are terminal; the only legal entry into COMPLETED
 * or FAILED is through RUNNING. The transition guard prevents a stale
 * dashboard from "restarting" a run that already produced a regulatory
 * snapshot.
 *
 * Distinct from [ScenarioStatus], which tracks the *definition*
 * lifecycle (draft / approved / retired) rather than per-run execution
 * state.
 */
enum class StressScenarioStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED;

    fun transitionTo(next: StressScenarioStatus): StressScenarioStatus {
        check(next != this) {
            "StressScenarioStatus: refusing self-transition ${this} -> ${next}"
        }
        val allowed: Set<StressScenarioStatus> = when (this) {
            PENDING -> setOf(RUNNING)
            RUNNING -> setOf(COMPLETED, FAILED)
            COMPLETED -> emptySet()
            FAILED -> emptySet()
        }
        check(next in allowed) {
            "StressScenarioStatus: illegal transition ${this} -> ${next} (allowed: $allowed)"
        }
        return next
    }
}
