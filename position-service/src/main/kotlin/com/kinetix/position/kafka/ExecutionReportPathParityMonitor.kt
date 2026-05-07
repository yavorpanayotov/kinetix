package com.kinetix.position.kafka

import com.kinetix.common.execution.ExecutionEventType
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Parity monitor for the dual-path migration in ADR-0035 phase 3 commit 3.
 *
 * fix-gateway publishes inbound 35=8 / 35=9 / 35=j events on Kafka topic
 * `execution.reports`; the legacy in-process FIXExecutionReportProcessor path
 * (kept dormant in production today, exercised only by dev local-acceptor
 * setups behind `EXECUTION_REPORTS_VIA_KAFKA=false`) was the previous source
 * of truth.
 *
 * The plan requires a Prometheus counter
 * `execution_report_path_divergence_total{venue, event_type, divergence_kind}`
 * to remain at ZERO for one full week before phase 3 commit 4 removes the
 * legacy wiring. Each path calls the corresponding `record*` method when it
 * processes an event; matching `(venue, execId)` pairs cancel each other out
 * within [window]. Mismatched content increments `content_mismatch` immediately,
 * pendings older than [window] sweep into `kafka_only` / `in_process_only`.
 *
 * Dev-only by design: production today only has the Kafka path active, so the
 * monitor sees Kafka entries and sweeps them as `kafka_only` — which is
 * expected. The metric becomes meaningful only when both paths are wired
 * (e.g. dev local-acceptor parity tests, see `DualPathParityAcceptanceTest`).
 */
class ExecutionReportPathParityMonitor(
    private val meterRegistry: MeterRegistry,
    private val window: Duration = Duration.ofSeconds(5),
    private val sweepInterval: Duration = Duration.ofMillis(500),
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(ExecutionReportPathParityMonitor::class.java)
    private val pending = ConcurrentHashMap<String, PendingEntry>()

    /**
     * Records that the Kafka consumer processed an event. If the in-process
     * path has already recorded a matching entry, the pair is reconciled here.
     */
    fun recordKafkaPath(
        at: Instant,
        venue: String,
        execId: String,
        eventType: ExecutionEventType,
        contentHash: String,
    ) = record(at, venue, execId, eventType, contentHash, Path.KAFKA)

    /** Sibling of [recordKafkaPath] for the legacy in-process path. */
    fun recordInProcessPath(
        at: Instant,
        venue: String,
        execId: String,
        eventType: ExecutionEventType,
        contentHash: String,
    ) = record(at, venue, execId, eventType, contentHash, Path.IN_PROCESS)

    /** Number of unmatched entries currently being held — exposed for tests. */
    fun pendingEntries(): Int = pending.size

    /**
     * Sweeps entries older than [window] at [now] and increments the
     * appropriate divergence counter.
     */
    fun sweep(now: Instant) {
        val cutoff = now.minus(window)
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.observedAt.isBefore(cutoff)) {
                val divergenceKind = when (entry.path) {
                    Path.KAFKA -> "kafka_only"
                    Path.IN_PROCESS -> "in_process_only"
                }
                meterRegistry.counter(
                    "execution_report_path_divergence_total",
                    "venue", entry.venue,
                    "event_type", entry.eventType.name,
                    "divergence_kind", divergenceKind,
                ).increment()
                logger.warn(
                    "Execution report path divergence detected: venue={} execId={} eventType={} divergenceKind={}",
                    entry.venue, entry.execId, entry.eventType, divergenceKind,
                )
                iterator.remove()
            }
        }
    }

    /** Sweeper coroutine. Loops on [sweepInterval] until cancelled. */
    suspend fun start() {
        logger.info(
            "ExecutionReportPathParityMonitor sweep loop started — window={}s, interval={}ms",
            window.seconds, sweepInterval.toMillis(),
        )
        try {
            while (currentCoroutineContext().isActive) {
                delay(sweepInterval.toMillis())
                sweep(clock())
            }
        } finally {
            withContext(NonCancellable) {
                logger.info("ExecutionReportPathParityMonitor sweep loop stopped")
            }
        }
    }

    private fun record(
        at: Instant,
        venue: String,
        execId: String,
        eventType: ExecutionEventType,
        contentHash: String,
        path: Path,
    ) {
        val key = "$venue:$execId"
        val incoming = PendingEntry(at, venue, execId, eventType, contentHash, path)

        // computeIfPresent is atomic — if both paths race to record the same
        // (venue, execId), the second call sees the first and reconciles.
        val reconciled = pending.compute(key) { _, existing ->
            if (existing == null) {
                incoming
            } else if (existing.path == path) {
                // Same path saw the event twice — keep the later observation,
                // do NOT increment any counter (the dispatcher's LRU should
                // have caught this; treat as benign).
                if (incoming.observedAt.isAfter(existing.observedAt)) incoming else existing
            } else {
                // Different paths met. Reconcile and remove the entry.
                if (existing.eventType != incoming.eventType || existing.contentHash != incoming.contentHash) {
                    meterRegistry.counter(
                        "execution_report_path_divergence_total",
                        "venue", venue,
                        "event_type", existing.eventType.name,
                        "divergence_kind", "content_mismatch",
                    ).increment()
                    logger.error(
                        "Execution report content mismatch: venue={} execId={} kafkaEventType={} inProcessEventType={}",
                        venue, execId,
                        if (existing.path == Path.KAFKA) existing.eventType else incoming.eventType,
                        if (existing.path == Path.IN_PROCESS) existing.eventType else incoming.eventType,
                    )
                }
                null  // remove from map
            }
        }
        if (reconciled == null) return  // already-reconciled or removed
    }

    private enum class Path { KAFKA, IN_PROCESS }

    private data class PendingEntry(
        val observedAt: Instant,
        val venue: String,
        val execId: String,
        val eventType: ExecutionEventType,
        val contentHash: String,
        val path: Path,
    )
}
