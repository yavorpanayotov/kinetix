package com.kinetix.fix.session

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.fix.kafka.RecordingExecutionReportPublisher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant

/**
 * Drives [InboundFixHandler] with raw FIX messages and asserts:
 *   - 35=8 / 35=9 / 35=j all flow to the publisher with the right event type
 *   - PossDupFlag=Y messages are dropped (dedup) and the counter increments
 *   - Missing ClOrdID still publishes (no event dropped) and the counter increments
 *   - Malformed messages do not propagate
 */
class InboundFixHandlerTest : FunSpec({

    val sessionId = "FIX.4.4:KINETIX->NYSE"
    val fixVersion = "FIX.4.4"
    val fixedClock = { Instant.parse("2026-05-07T10:00:00Z") }

    fun newHandler(): Triple<InboundFixHandler, RecordingExecutionReportPublisher, SimpleMeterRegistry> {
        val publisher = RecordingExecutionReportPublisher()
        val meterRegistry = SimpleMeterRegistry()
        val handler = InboundFixHandler(
            converter = FIXMessageConverter(),
            publisher = publisher,
            meterRegistry = meterRegistry,
            clock = fixedClock,
        )
        return Triple(handler, publisher, meterRegistry)
    }

    test("35=8 Fill is published as FILL") {
        val (handler, publisher, _) = newHandler()
        val msg = fixMessage(
            "35" to "8", "11" to "clord-1", "17" to "exec-1", "37" to "ord-1",
            "150" to "F", "32" to "100", "31" to "150.25", "14" to "100", "6" to "150.25",
            "30" to "NYSE", "38" to "100",
        )
        handler.handle(msg, sessionId, fixVersion)
        publisher.published.size shouldBe 1
        publisher.published[0].eventType shouldBe ExecutionEventType.FILL
        publisher.published[0].clOrdId shouldBe "clord-1"
    }

    test("35=8 partial fill is published as PARTIAL_FILL") {
        val (handler, publisher, _) = newHandler()
        val msg = fixMessage(
            "35" to "8", "11" to "clord-2", "17" to "exec-2", "37" to "ord-2",
            "150" to "F", "32" to "30", "31" to "150.20", "14" to "30", "6" to "150.20",
            "30" to "NYSE", "38" to "100",
        )
        handler.handle(msg, sessionId, fixVersion)
        publisher.published[0].eventType shouldBe ExecutionEventType.PARTIAL_FILL
    }

    test("35=9 OrderCancelReject is published as REJECTED keyed by OrigClOrdID") {
        val (handler, publisher, _) = newHandler()
        val msg = fixMessage(
            "35" to "9", "11" to "clord-cxl", "37" to "ord-3", "41" to "clord-orig",
            "434" to "1", "102" to "0", "58" to "Too late to cancel", "30" to "NYSE",
        )
        handler.handle(msg, sessionId, fixVersion)
        publisher.published.size shouldBe 1
        publisher.published[0].eventType shouldBe ExecutionEventType.REJECTED
        publisher.published[0].clOrdId shouldBe "clord-orig"
        publisher.published[0].rejectReason shouldBe "Too late to cancel"
    }

    test("35=j BusinessMessageReject is published as BUSINESS_REJECT") {
        val (handler, publisher, _) = newHandler()
        val msg = fixMessage(
            "35" to "j", "45" to "5", "372" to "D",
            "380" to "5", "58" to "Unknown instrument", "30" to "NYSE",
        )
        handler.handle(msg, sessionId, fixVersion)
        publisher.published.size shouldBe 1
        publisher.published[0].eventType shouldBe ExecutionEventType.BUSINESS_REJECT
    }

    test("PossDupFlag=Y messages are NOT published — dedup at the session layer") {
        val (handler, publisher, registry) = newHandler()
        val msg = fixMessage(
            "35" to "8", "11" to "clord-dup", "17" to "exec-dup", "37" to "ord-dup",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
            "30" to "NYSE", "38" to "100", "43" to "Y",
        )
        handler.handle(msg, sessionId, fixVersion)
        publisher.published.size shouldBe 0
        registry.counter("fix_messages_in_total", "venue", "NYSE", "msg_type", "POSS_DUP_DROPPED").count() shouldBe 1.0
    }

    test("missing ClOrdID is still published, counter increments") {
        val (handler, publisher, registry) = newHandler()
        val msg = fixMessage(
            "35" to "8", "17" to "exec-no-clord", "37" to "ord-no-clord",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
            "30" to "LSE", "38" to "100",
        )
        handler.handle(msg, sessionId, fixVersion)
        publisher.published.size shouldBe 1
        publisher.published[0].clOrdId shouldBe ""
        registry.counter("malformed_inbound_total", "venue", "LSE", "defect", "MISSING_CLORD_ID").count() shouldBe 1.0
    }

    test("malformed message (duplicate tags) does not propagate") {
        val (handler, publisher, registry) = newHandler()
        val msg = "35=D|35=8|11=ord-1|17=exec-1|37=ord-1|150=F|32=100|31=150.00|14=100|6=150.00"
        handler.handle(msg, sessionId, fixVersion)
        publisher.published.size shouldBe 0
        registry.counter("malformed_inbound_total", "venue", "UNKNOWN", "defect", "PARSE_FAILED").count() shouldBe 1.0
    }

    test("inbound counter increments per published 35=8 by msg_type") {
        val (handler, publisher, registry) = newHandler()
        val msg = fixMessage(
            "35" to "8", "11" to "clord-c", "17" to "exec-c", "37" to "ord-c",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
            "30" to "NYSE", "38" to "100",
        )
        handler.handle(msg, sessionId, fixVersion)
        publisher.published.size shouldBe 1
        registry.counter("fix_messages_in_total", "venue", "NYSE", "msg_type", "EXECUTION_REPORT").count() shouldBe 1.0
    }

    test("publisher failure is not silently swallowed") {
        var thrown = false
        val publisher = RecordingExecutionReportPublisher { throw RuntimeException("kafka down") }
        val registry = SimpleMeterRegistry()
        val handler = InboundFixHandler(FIXMessageConverter(), publisher, registry, fixedClock)
        val msg = fixMessage(
            "35" to "8", "11" to "clord-1", "17" to "exec-1", "37" to "ord-1",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
            "30" to "NYSE", "38" to "100",
        )
        try {
            handler.handle(msg, sessionId, fixVersion)
        } catch (e: RuntimeException) {
            thrown = true
        }
        thrown shouldBe true
    }
})

private fun fixMessage(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("|") { (tag, value) -> "$tag=$value" }
