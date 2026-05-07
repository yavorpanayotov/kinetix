package com.kinetix.position.kafka

import com.kinetix.common.execution.ExecutionEventType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant

/**
 * Parity-monitoring metric `execution_report_path_divergence_total` is required to be ZERO
 * during the soak window before phase 3 commit 4 lands. These tests pin the recorder/sweeper
 * contract.
 */
class ExecutionReportPathParityMonitorTest : FunSpec({

    test("matched event on both paths within window does NOT increment divergence") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-07T10:00:00Z")
        val monitor = ExecutionReportPathParityMonitor(
            meterRegistry = registry,
            window = Duration.ofSeconds(5),
        )

        monitor.recordKafkaPath(now, "NYSE", "exec-1", ExecutionEventType.FILL, "h1")
        monitor.recordInProcessPath(now.plusMillis(20), "NYSE", "exec-1", ExecutionEventType.FILL, "h1")

        registry.find("execution_report_path_divergence_total").counters().sumOf { it.count() } shouldBe 0.0
        monitor.pendingEntries() shouldBe 0
    }

    test("Kafka-only event sweeps as kafka_only after the window expires") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-07T10:00:00Z")
        val monitor = ExecutionReportPathParityMonitor(
            meterRegistry = registry,
            window = Duration.ofSeconds(5),
        )

        monitor.recordKafkaPath(now, "NYSE", "exec-orphan-k", ExecutionEventType.FILL, "h1")
        monitor.sweep(now.plusSeconds(6))

        registry.find("execution_report_path_divergence_total")
            .tag("venue", "NYSE")
            .tag("event_type", "FILL")
            .tag("divergence_kind", "kafka_only")
            .counter()!!.count() shouldBe 1.0
        monitor.pendingEntries() shouldBe 0
    }

    test("in-process-only event sweeps as in_process_only after the window expires") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-07T10:00:00Z")
        val monitor = ExecutionReportPathParityMonitor(
            meterRegistry = registry,
            window = Duration.ofSeconds(5),
        )

        monitor.recordInProcessPath(now, "LSE", "exec-orphan-ip", ExecutionEventType.PARTIAL_FILL, "h2")
        monitor.sweep(now.plusSeconds(6))

        registry.find("execution_report_path_divergence_total")
            .tag("venue", "LSE")
            .tag("event_type", "PARTIAL_FILL")
            .tag("divergence_kind", "in_process_only")
            .counter()!!.count() shouldBe 1.0
    }

    test("event still inside the window is NOT swept") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-07T10:00:00Z")
        val monitor = ExecutionReportPathParityMonitor(
            meterRegistry = registry,
            window = Duration.ofSeconds(5),
        )

        monitor.recordKafkaPath(now, "NYSE", "exec-pending", ExecutionEventType.FILL, "h")
        monitor.sweep(now.plusSeconds(2))

        registry.find("execution_report_path_divergence_total").counters().sumOf { it.count() } shouldBe 0.0
        monitor.pendingEntries() shouldBe 1
    }

    test("content mismatch increments content_mismatch immediately, regardless of arrival order") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-07T10:00:00Z")
        val monitor = ExecutionReportPathParityMonitor(
            meterRegistry = registry,
            window = Duration.ofSeconds(5),
        )

        monitor.recordKafkaPath(now, "NYSE", "exec-mm", ExecutionEventType.FILL, "kafka-hash")
        monitor.recordInProcessPath(now.plusMillis(50), "NYSE", "exec-mm", ExecutionEventType.FILL, "in-proc-hash")

        registry.find("execution_report_path_divergence_total")
            .tag("venue", "NYSE")
            .tag("event_type", "FILL")
            .tag("divergence_kind", "content_mismatch")
            .counter()!!.count() shouldBe 1.0
        monitor.pendingEntries() shouldBe 0
    }

    test("in-process arriving first then matching Kafka also clears the entry") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-07T10:00:00Z")
        val monitor = ExecutionReportPathParityMonitor(
            meterRegistry = registry,
            window = Duration.ofSeconds(5),
        )

        monitor.recordInProcessPath(now, "NYSE", "exec-rev", ExecutionEventType.CANCELLED, "h")
        monitor.recordKafkaPath(now.plusMillis(120), "NYSE", "exec-rev", ExecutionEventType.CANCELLED, "h")

        registry.find("execution_report_path_divergence_total").counters().sumOf { it.count() } shouldBe 0.0
        monitor.pendingEntries() shouldBe 0
    }

    test("event_type mismatch counts as content_mismatch — fix-gateway and legacy converter must agree on discriminator") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-07T10:00:00Z")
        val monitor = ExecutionReportPathParityMonitor(
            meterRegistry = registry,
            window = Duration.ofSeconds(5),
        )

        monitor.recordKafkaPath(now, "NYSE", "exec-typediff", ExecutionEventType.FILL, "h")
        monitor.recordInProcessPath(now.plusMillis(40), "NYSE", "exec-typediff", ExecutionEventType.PARTIAL_FILL, "h")

        registry.find("execution_report_path_divergence_total")
            .tag("venue", "NYSE")
            .tag("divergence_kind", "content_mismatch")
            .counters().sumOf { it.count() } shouldBe 1.0
    }
})
