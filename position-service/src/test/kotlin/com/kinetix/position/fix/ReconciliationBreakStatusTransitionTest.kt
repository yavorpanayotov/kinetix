package com.kinetix.position.fix

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Specifies the legal status transitions for a reconciliation break.
 *
 * Per specs/execution.allium UpdateReconciliationBreakStatus:
 *   open          -> investigating | resolved
 *   investigating -> resolved | open
 *   resolved      -> (terminal — no transitions)
 */
class ReconciliationBreakStatusTransitionTest : FunSpec({

    test("OPEN transitions to INVESTIGATING") {
        ReconciliationBreakStatus.OPEN.canTransitionTo(ReconciliationBreakStatus.INVESTIGATING) shouldBe true
    }

    test("OPEN transitions to RESOLVED") {
        ReconciliationBreakStatus.OPEN.canTransitionTo(ReconciliationBreakStatus.RESOLVED) shouldBe true
    }

    test("OPEN cannot transition to itself") {
        ReconciliationBreakStatus.OPEN.canTransitionTo(ReconciliationBreakStatus.OPEN) shouldBe false
    }

    test("INVESTIGATING transitions to RESOLVED") {
        ReconciliationBreakStatus.INVESTIGATING.canTransitionTo(ReconciliationBreakStatus.RESOLVED) shouldBe true
    }

    test("INVESTIGATING transitions back to OPEN") {
        ReconciliationBreakStatus.INVESTIGATING.canTransitionTo(ReconciliationBreakStatus.OPEN) shouldBe true
    }

    test("INVESTIGATING cannot transition to itself") {
        ReconciliationBreakStatus.INVESTIGATING.canTransitionTo(ReconciliationBreakStatus.INVESTIGATING) shouldBe false
    }

    test("RESOLVED is terminal and cannot transition to any status") {
        ReconciliationBreakStatus.RESOLVED.canTransitionTo(ReconciliationBreakStatus.OPEN) shouldBe false
        ReconciliationBreakStatus.RESOLVED.canTransitionTo(ReconciliationBreakStatus.INVESTIGATING) shouldBe false
        ReconciliationBreakStatus.RESOLVED.canTransitionTo(ReconciliationBreakStatus.RESOLVED) shouldBe false
    }
})
