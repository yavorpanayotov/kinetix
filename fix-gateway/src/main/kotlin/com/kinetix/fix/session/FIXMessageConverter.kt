package com.kinetix.fix.session

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Translates raw inbound FIX messages (35=8 ExecutionReport, 35=9 OrderCancelReject,
 * 35=j BusinessMessageReject) into the typed [ExecutionReportEvent] wire format that
 * fix-gateway publishes on `execution.reports` (ADR-0035 phase 3 commit 2).
 *
 * The (`fixVersion`, `execType`) → [ExecutionEventType] mapping is owned here. Tests pin
 * every supported combination; downstream consumers branch on the typed [ExecutionEventType]
 * rather than re-deriving from the raw value.
 *
 * Responsibility split versus the legacy `position-service.FIXMessageConverter`:
 *   - This converter targets the wire format published to Kafka.
 *   - The legacy converter (still in position-service for the in-process path) targets
 *     `FIXInboundFillEvent`. It is removed in phase 3 commit 4 once the Kafka path is
 *     soaked.
 *
 * Parsing notes:
 *   - Both SOH (``) and `|` are accepted as field delimiters (mirrors test helpers
 *     and prod wire format).
 *   - Duplicate tags are treated as injection and the whole message is dropped — same
 *     posture as the legacy converter.
 *   - Fill validation (positive LastQty, non-negative LastPx) applies only to FILL/PARTIAL
 *     event types; cancels, replaces, and rejects skip the check.
 */
class FIXMessageConverter {

    private val logger = LoggerFactory.getLogger(FIXMessageConverter::class.java)

    /** Returns the [ExecutionReportEvent] only — discards the parse carrier. */
    fun parse(
        rawMessage: String,
        sessionId: String,
        fixVersion: String,
        clock: () -> Instant = Instant::now,
    ): ExecutionReportEvent? = parseRaw(rawMessage, sessionId, fixVersion, clock)?.event

    /**
     * Parses the raw FIX message and returns a [ParsedInboundFix] — pairs the typed event
     * with session-layer flags such as PossDupFlag that the handler acts on for dedup.
     */
    fun parseRaw(
        rawMessage: String,
        sessionId: String,
        fixVersion: String,
        clock: () -> Instant = Instant::now,
    ): ParsedInboundFix? {
        val tags = parseTags(rawMessage) ?: return null
        val msgType = tags["35"] ?: run {
            logger.warn("FIX message missing MsgType (tag 35)")
            return null
        }
        val possDup = tags["43"] == "Y"
        val receivedAt = clock().toString()
        val eventId = "evt-${UUID.randomUUID()}"

        return when (msgType) {
            "8" -> parseExecutionReport(tags, sessionId, fixVersion, receivedAt, eventId)?.let {
                ParsedInboundFix(it, possDup)
            }
            "9" -> parseOrderCancelReject(tags, sessionId, fixVersion, receivedAt, eventId)?.let {
                ParsedInboundFix(it, possDup)
            }
            "j" -> parseBusinessReject(tags, sessionId, fixVersion, receivedAt, eventId)?.let {
                ParsedInboundFix(it, possDup)
            }
            else -> {
                logger.debug("Skipping unsupported MsgType: {}", msgType)
                null
            }
        }
    }

    private fun parseExecutionReport(
        tags: Map<String, String>,
        sessionId: String,
        fixVersion: String,
        receivedAt: String,
        eventId: String,
    ): ExecutionReportEvent? {
        val execType = tags["150"] ?: run {
            logger.warn("ExecutionReport missing ExecType (tag 150)")
            return null
        }
        val execId = tags["17"] ?: run {
            logger.warn("ExecutionReport missing ExecID (tag 17)")
            return null
        }
        val orderId = tags["37"] ?: run {
            logger.warn("ExecutionReport missing OrderID (tag 37)")
            return null
        }

        val lastQty = tags["32"]?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val lastPrice = tags["31"]?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val cumQty = tags["14"]?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val avgPx = tags["6"]?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val orderQty = tags["38"]?.toBigDecimalOrNull()

        val eventType = mapExecType(fixVersion, execType, cumQty, orderQty) ?: run {
            logger.warn("Unsupported ExecType={} for fixVersion={}", execType, fixVersion)
            return null
        }

        if (eventType in fillEventTypes) {
            if (lastQty.signum() <= 0) {
                logger.error("Rejecting fill with zero/negative LastQty: execId={}, lastQty={}", execId, lastQty)
                return null
            }
            if (lastPrice.signum() < 0) {
                logger.error("Rejecting fill with negative LastPx: execId={}, lastPx={}", execId, lastPrice)
                return null
            }
        }

        return ExecutionReportEvent(
            eventId = eventId,
            clOrdId = tags["11"] ?: "",
            orderId = orderId,
            execId = execId,
            sessionId = sessionId,
            venue = tags["30"],
            correlationId = tags["11"],
            fixVersion = fixVersion,
            execType = execType,
            eventType = eventType,
            lastQty = lastQty.toPlainString(),
            lastPrice = lastPrice.toPlainString(),
            cumulativeQty = cumQty.toPlainString(),
            averagePrice = avgPx.toPlainString(),
            rejectReason = tags["58"],
            rejectCode = tags["103"],
            receivedAt = receivedAt,
        )
    }

    private fun parseOrderCancelReject(
        tags: Map<String, String>,
        sessionId: String,
        fixVersion: String,
        receivedAt: String,
        eventId: String,
    ): ExecutionReportEvent {
        // 35=9 routes against the ORIGINAL ClOrdID (tag 41 OrigClOrdID) so partition
        // ordering follows the order the cancel targeted, not the cancel's own ClOrdID.
        val origClOrdId = tags["41"] ?: tags["11"] ?: ""
        return ExecutionReportEvent(
            eventId = eventId,
            clOrdId = origClOrdId,
            orderId = tags["37"] ?: "",
            execId = tags["11"] ?: "",
            sessionId = sessionId,
            venue = tags["30"],
            correlationId = origClOrdId.takeIf { it.isNotEmpty() },
            fixVersion = fixVersion,
            execType = "",
            eventType = ExecutionEventType.REJECTED,
            rejectReason = tags["58"],
            rejectCode = tags["102"],
            receivedAt = receivedAt,
        )
    }

    private fun parseBusinessReject(
        tags: Map<String, String>,
        sessionId: String,
        fixVersion: String,
        receivedAt: String,
        eventId: String,
    ): ExecutionReportEvent = ExecutionReportEvent(
        eventId = eventId,
        clOrdId = tags["11"] ?: "",
        orderId = "",
        execId = tags["45"] ?: "",
        sessionId = sessionId,
        venue = tags["30"],
        fixVersion = fixVersion,
        execType = "",
        eventType = ExecutionEventType.BUSINESS_REJECT,
        rejectReason = tags["58"],
        rejectCode = tags["380"],
        receivedAt = receivedAt,
    )

    private fun mapExecType(
        fixVersion: String,
        execType: String,
        cumQty: BigDecimal,
        orderQty: BigDecimal?,
    ): ExecutionEventType? = when (fixVersion) {
        "FIX.4.2" -> when (execType) {
            "1" -> ExecutionEventType.PARTIAL_FILL
            "2" -> ExecutionEventType.FILL
            "4" -> ExecutionEventType.CANCELLED
            "5" -> ExecutionEventType.REPLACED
            "8" -> ExecutionEventType.REJECTED
            else -> null
        }
        "FIX.4.4", "FIX.5.0" -> when (execType) {
            "F" -> if (orderQty != null && cumQty < orderQty) ExecutionEventType.PARTIAL_FILL else ExecutionEventType.FILL
            "1" -> ExecutionEventType.PARTIAL_FILL
            "4" -> ExecutionEventType.CANCELLED
            "5" -> ExecutionEventType.REPLACED
            "8" -> ExecutionEventType.REJECTED
            else -> null
        }
        else -> null
    }

    /** Tolerates both SOH (``) and `|` delimiters; rejects messages with duplicate tags. */
    private fun parseTags(rawMessage: String): Map<String, String>? {
        val delimiter = if (rawMessage.contains('\u0001')) '\u0001' else '|'
        val result = mutableMapOf<String, String>()
        for (field in rawMessage.split(delimiter)) {
            val eq = field.indexOf('=')
            if (eq <= 0) continue
            val tag = field.substring(0, eq)
            val value = field.substring(eq + 1)
            if (result.containsKey(tag)) {
                logger.error("Rejecting FIX message with duplicate tag {}: possible tag injection", tag)
                return null
            }
            result[tag] = value
        }
        return result
    }

    companion object {
        private val fillEventTypes = setOf(ExecutionEventType.FILL, ExecutionEventType.PARTIAL_FILL)
    }
}
