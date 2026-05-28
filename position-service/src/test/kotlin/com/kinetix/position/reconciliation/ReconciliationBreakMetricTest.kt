package com.kinetix.position.reconciliation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * Reconciliation breaks (position-service vs prime broker / clearing
 * house) get sliced by severity: a $10 mismatch is a noise-level
 * cosmetic break; a $1M mismatch is an operational incident. Per-
 * severity counters let the platform alert without burying the
 * critical ones under the cosmetic noise.
 */
class ReconciliationBreakMetricTest : FunSpec({

    test("severity tag is carried on the counter") {
        val registry = SimpleMeterRegistry()
        val metric = ReconciliationBreakMetric(registry)
        metric.record("CRITICAL")
        registry.find("position.reconciliation.break.count")
            .tag("severity", "CRITICAL").counter()!!.count() shouldBe 1.0
    }

    test("repeated breaks of the same severity accumulate") {
        val registry = SimpleMeterRegistry()
        val metric = ReconciliationBreakMetric(registry)
        repeat(5) { metric.record("WARNING") }
        registry.find("position.reconciliation.break.count")
            .tag("severity", "WARNING").counter()!!.count() shouldBe 5.0
    }

    test("different severities register independently") {
        val registry = SimpleMeterRegistry()
        val metric = ReconciliationBreakMetric(registry)
        metric.record("CRITICAL")
        metric.record("WARNING")
        metric.record("INFO")
        registry.find("position.reconciliation.break.count").counters().size shouldBe 3
    }
})
