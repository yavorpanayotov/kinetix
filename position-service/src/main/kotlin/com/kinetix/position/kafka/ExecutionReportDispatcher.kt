package com.kinetix.position.kafka

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import com.kinetix.position.fix.FIXExecutionReportProcessor
import com.kinetix.position.fix.FIXInboundFillEvent
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Maps wire-format [ExecutionReportEvent]s onto the in-process [FIXExecutionReportProcessor]
 * (ADR-0035 phase 3 commit 3).
 *
 * Lives separately from [ExecutionReportConsumer] so unit tests can drive the
 * dispatch logic without spinning up a Kafka container — the consumer concern
 * (poll loop, manual offset commits, deserialisation framing) is in the consumer.
 *
 * Responsibilities:
 *   1. Defence-in-depth dedup on `(venue, execId)` via a bounded LRU cache. The
 *      authoritative dedup is the `uq_execution_fill_exec_id` UNIQUE constraint;
 *      this cache short-circuits the round-trip so a venue that aggressively
 *      replays does not pile up rejected DB writes.
 *   2. Reshape [ExecutionReportEvent] into [FIXInboundFillEvent] using `clOrdId`
 *      as the position-service-known order id (the wire schema partitions by
 *      `clOrdId`; `orderId` carries the venue's tag 37 which position-service
 *      does not key by).
 *   3. Skip 35=9 / 35=j REJECTED / BUSINESS_REJECT events for now — the
 *      processor only handles 35=8 ExecTypes today, and rejecting an order on
 *      the back of a venue cancel-reject requires policy beyond the scope of
 *      phase 3 commit 3 (dedicated reject handler lands later).
 *   4. Surface malformed JSON as a metric and skip — the consumer commits the
 *      offset so the bad message does not block progress.
 */
class ExecutionReportDispatcher(
    private val processor: FIXExecutionReportProcessor,
    private val meterRegistry: MeterRegistry,
    private val seenExecIdCacheSize: Int = 100_000,
) {
    private val logger = LoggerFactory.getLogger(ExecutionReportDispatcher::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val seenExecIds: MutableMap<String, Boolean> =
        object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean =
                size > seenExecIdCacheSize
        }

    /** Decode and dispatch a raw Kafka payload. Malformed payloads are counted and dropped. */
    suspend fun dispatchRaw(payload: String, offset: Long, partition: Int, key: String?) {
        val event = try {
            json.decodeFromString(ExecutionReportEvent.serializer(), payload)
        } catch (e: Exception) {
            meterRegistry.counter("execution_report_consumer_malformed_total").increment()
            logger.error(
                "Malformed ExecutionReportEvent on execution.reports: partition={} offset={} key={}",
                partition, offset, key, e,
            )
            return
        }
        dispatch(event)
    }

    suspend fun dispatch(event: ExecutionReportEvent) {
        val venueLabel = event.venue ?: "UNKNOWN"
        val dedupKey = "$venueLabel:${event.execId}"
        if (event.execId.isNotEmpty() && seenExecIds.put(dedupKey, true) != null) {
            meterRegistry.counter("execution_report_consumer_dedup_total", "venue", venueLabel).increment()
            logger.debug(
                "Duplicate execution report short-circuited at consumer: venue={} execId={} clOrdId={}",
                venueLabel, event.execId, event.clOrdId,
            )
            return
        }

        when (event.eventType) {
            ExecutionEventType.FILL,
            ExecutionEventType.PARTIAL_FILL,
            ExecutionEventType.CANCELLED,
            ExecutionEventType.REPLACED -> processor.process(event.toFIXInboundFillEvent())

            ExecutionEventType.REJECTED,
            ExecutionEventType.BUSINESS_REJECT -> {
                meterRegistry.counter(
                    "execution_report_consumer_skipped_total",
                    "event_type", event.eventType.name,
                    "venue", venueLabel,
                ).increment()
                logger.info(
                    "Skipping {} from execution.reports — reject handling is policy-deferred: " +
                        "clOrdId={} execId={} reason={} code={}",
                    event.eventType, event.clOrdId, event.execId,
                    event.rejectReason ?: "", event.rejectCode ?: "",
                )
            }
        }

        meterRegistry.counter(
            "execution_report_consumer_dispatched_total",
            "event_type", event.eventType.name,
            "venue", venueLabel,
        ).increment()
    }

    private fun ExecutionReportEvent.toFIXInboundFillEvent(): FIXInboundFillEvent =
        FIXInboundFillEvent(
            sessionId = sessionId,
            execId = execId,
            // clOrdId is position-service's order id (tag 11, UUID v4).
            // The wire schema's `orderId` carries venue's tag 37 which position-service
            // does not key its execution_orders table by.
            orderId = clOrdId,
            execType = execType,
            lastQty = BigDecimal(lastQty),
            lastPrice = BigDecimal(lastPrice),
            cumulativeQty = BigDecimal(cumulativeQty),
            averagePrice = BigDecimal(averagePrice),
            venue = venue,
        )
}
