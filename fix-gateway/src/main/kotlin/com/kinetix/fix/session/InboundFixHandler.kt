package com.kinetix.fix.session

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import com.kinetix.fix.kafka.ExecutionReportPublisher
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Owns the inbound FIX → Kafka path for fix-gateway (ADR-0035 phase 3 commit 2).
 *
 * Responsibilities:
 *   1. Parse the raw FIX message via [FIXMessageConverter].
 *   2. Drop messages flagged with PossDupFlag (tag 43 = Y) — the FIX session layer is
 *      already replaying them on logon and the venue-side `MessageStore` is the
 *      authoritative dedup boundary. Counter increments so ops can spot venues that
 *      replay aggressively.
 *   3. Surface malformed or missing-ClOrdID messages via Prometheus counters but still
 *      publish what we can — we are NOT the order-state authority; downstream
 *      `position-service` decides whether an unknown ClOrdID is an orphan fill.
 *   4. Publish to `execution.reports` via [ExecutionReportPublisher]. Failures propagate
 *      so the FIX session layer can decide what to do (typically: do not commit, let the
 *      venue resend on next logon).
 *
 * The QuickFIX/J `Application.fromApp` callback wires into [handle] in a follow-on once
 * venue credentials and the Initiator are configured. For now tests drive the handler
 * directly — the wire-format contract is stable.
 */
class InboundFixHandler(
    private val converter: FIXMessageConverter,
    private val publisher: ExecutionReportPublisher,
    private val meterRegistry: MeterRegistry,
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(InboundFixHandler::class.java)

    suspend fun handle(rawMessage: String, sessionId: String, fixVersion: String) {
        val parsed = converter.parseRaw(rawMessage, sessionId, fixVersion, clock)
        if (parsed == null) {
            // Defensive: extract the venue from the raw bytes for the metric label even
            // when the typed parse failed. Falls back to UNKNOWN when nothing is parseable.
            meterRegistry.counter(
                "malformed_inbound_total",
                "venue", extractVenue(rawMessage) ?: "UNKNOWN",
                "defect", "PARSE_FAILED",
            ).increment()
            logger.warn("Inbound FIX message rejected at parse time")
            return
        }

        if (parsed.possDup) {
            meterRegistry.counter(
                "fix_messages_in_total",
                "venue", parsed.event.venue ?: "UNKNOWN",
                "msg_type", "POSS_DUP_DROPPED",
            ).increment()
            logger.info(
                "PossDupFlag=Y inbound dropped: clOrdId={} execId={} eventType={}",
                parsed.event.clOrdId, parsed.event.execId, parsed.event.eventType,
            )
            return
        }

        if (parsed.event.clOrdId.isEmpty()) {
            meterRegistry.counter(
                "malformed_inbound_total",
                "venue", parsed.event.venue ?: "UNKNOWN",
                "defect", "MISSING_CLORD_ID",
            ).increment()
            logger.warn(
                "Inbound 35=8 missing ClOrdID — partition key falls back to venue: execId={}",
                parsed.event.execId,
            )
        }

        meterRegistry.counter(
            "fix_messages_in_total",
            "venue", parsed.event.venue ?: "UNKNOWN",
            "msg_type", msgTypeLabel(parsed.event),
        ).increment()

        publisher.publish(parsed.event)
    }

    private fun msgTypeLabel(event: ExecutionReportEvent): String = when (event.eventType) {
        ExecutionEventType.FILL,
        ExecutionEventType.PARTIAL_FILL,
        ExecutionEventType.CANCELLED,
        ExecutionEventType.REPLACED -> "EXECUTION_REPORT"
        ExecutionEventType.REJECTED -> if (event.execType.isEmpty()) "ORDER_CANCEL_REJECT" else "EXECUTION_REPORT"
        ExecutionEventType.BUSINESS_REJECT -> "BUSINESS_REJECT"
    }

    private fun extractVenue(rawMessage: String): String? {
        val delimiter = if (rawMessage.contains('\u0001')) '\u0001' else '|'
        return rawMessage.split(delimiter)
            .firstOrNull { it.startsWith("30=") }
            ?.substringAfter('=')
    }
}
