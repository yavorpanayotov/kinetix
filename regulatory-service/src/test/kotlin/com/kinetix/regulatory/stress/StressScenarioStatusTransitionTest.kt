package com.kinetix.regulatory.stress

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A stress-scenario run is a discrete job kicked off by Compliance — the
 * scenario is loaded, the risk engine computes per-position impacts, the
 * results are persisted. The run goes through a small lifecycle:
 *
 *   PENDING -> RUNNING -> { COMPLETED | FAILED }
 *
 * Allowing arbitrary transitions (e.g. COMPLETED -> RUNNING) would let a
 * stale dashboard "restart" a run that already produced a regulatory
 * snapshot, silently overwriting it. The transition guard makes the
 * state machine explicit.
 */
class StressScenarioStatusTransitionTest : FunSpec({

    test("PENDING -> RUNNING is allowed") {
        StressScenarioStatus.PENDING.transitionTo(StressScenarioStatus.RUNNING) shouldBe
            StressScenarioStatus.RUNNING
    }

    test("RUNNING -> COMPLETED is allowed") {
        StressScenarioStatus.RUNNING.transitionTo(StressScenarioStatus.COMPLETED) shouldBe
            StressScenarioStatus.COMPLETED
    }

    test("RUNNING -> FAILED is allowed") {
        StressScenarioStatus.RUNNING.transitionTo(StressScenarioStatus.FAILED) shouldBe
            StressScenarioStatus.FAILED
    }

    test("PENDING -> COMPLETED is rejected (must go through RUNNING)") {
        shouldThrow<IllegalStateException> {
            StressScenarioStatus.PENDING.transitionTo(StressScenarioStatus.COMPLETED)
        }
    }

    test("PENDING -> FAILED is rejected (must go through RUNNING)") {
        shouldThrow<IllegalStateException> {
            StressScenarioStatus.PENDING.transitionTo(StressScenarioStatus.FAILED)
        }
    }

    test("COMPLETED is terminal — no further transitions") {
        for (next in StressScenarioStatus.entries) {
            if (next == StressScenarioStatus.COMPLETED) continue
            shouldThrow<IllegalStateException> {
                StressScenarioStatus.COMPLETED.transitionTo(next)
            }
        }
    }

    test("FAILED is terminal — no further transitions") {
        for (next in StressScenarioStatus.entries) {
            if (next == StressScenarioStatus.FAILED) continue
            shouldThrow<IllegalStateException> {
                StressScenarioStatus.FAILED.transitionTo(next)
            }
        }
    }

    test("self-transitions are rejected (avoid double-publishes)") {
        for (s in StressScenarioStatus.entries) {
            shouldThrow<IllegalStateException> { s.transitionTo(s) }
        }
    }

    test("the rejection message names the from-state and the to-state") {
        val ex = shouldThrow<IllegalStateException> {
            StressScenarioStatus.COMPLETED.transitionTo(StressScenarioStatus.RUNNING)
        }
        ex.message!!.contains("COMPLETED") shouldBe true
        ex.message!!.contains("RUNNING") shouldBe true
    }
})
