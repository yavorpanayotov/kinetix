package com.kinetix.fix.session

import com.kinetix.common.execution.ExecutionEventType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.time.Instant

/**
 * Pins the (fixVersion, execType) -> [ExecutionEventType] mapping table that fix-gateway uses
 * to translate raw FIX inbound messages into typed `ExecutionReportEvent`s for the
 * `execution.reports` Kafka topic (ADR-0035 phase 3 commit 2).
 *
 * The mapping is version-aware: ExecType `1` is Partial Fill in FIX 4.2 but is repurposed in
 * FIX 4.4 where Fill / Partial Fill collapse into `F` and CumQty < OrderQty signals partial.
 *
 * 35=9 OrderCancelReject and 35=j BusinessMessageReject are also handled here so the same
 * converter is the single FIX → wire-event translation point.
 */
class FIXMessageConverterTest : FunSpec({

    val converter = FIXMessageConverter()
    val fixedClock = { Instant.parse("2026-05-07T10:00:00Z") }
    val sessionId = "FIX.4.4:KINETIX->NYSE"

    // ---------------------------------------------------------------------
    // 35=8 ExecutionReport — version-aware mapping
    // ---------------------------------------------------------------------

    test("FIX 4.4 ExecType=F with CumQty == OrderQty -> FILL") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-1", "17" to "exec-1", "37" to "ord-1",
            "150" to "F", "32" to "100", "31" to "150.25", "14" to "100", "6" to "150.25",
            "30" to "NYSE", "38" to "100",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventType shouldBe ExecutionEventType.FILL
        event.execType shouldBe "F"
        event.fixVersion shouldBe "FIX.4.4"
        event.clOrdId shouldBe "clord-1"
        event.orderId shouldBe "ord-1"
        event.execId shouldBe "exec-1"
        event.lastQty shouldBe "100"
        event.lastPrice shouldBe "150.25"
        event.cumulativeQty shouldBe "100"
        event.averagePrice shouldBe "150.25"
        event.venue shouldBe "NYSE"
        event.receivedAt shouldBe "2026-05-07T10:00:00Z"
    }

    test("FIX 4.4 ExecType=F with CumQty < OrderQty -> PARTIAL_FILL") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-2", "17" to "exec-2", "37" to "ord-2",
            "150" to "F", "32" to "30", "31" to "150.20", "14" to "30", "6" to "150.20",
            "30" to "NYSE", "38" to "100",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventType shouldBe ExecutionEventType.PARTIAL_FILL
        event.execType shouldBe "F"
    }

    test("FIX 4.2 ExecType=1 -> PARTIAL_FILL regardless of CumQty") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-3", "17" to "exec-3", "37" to "ord-3",
            "150" to "1", "32" to "40", "31" to "150.00", "14" to "40", "6" to "150.00",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.2", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventType shouldBe ExecutionEventType.PARTIAL_FILL
        event.execType shouldBe "1"
        event.fixVersion shouldBe "FIX.4.2"
    }

    test("FIX 4.2 ExecType=2 -> FILL") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-4", "17" to "exec-4", "37" to "ord-4",
            "150" to "2", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.2", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventType shouldBe ExecutionEventType.FILL
        event.execType shouldBe "2"
    }

    test("ExecType=4 -> CANCELLED") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-5", "17" to "exec-5", "37" to "ord-5",
            "150" to "4", "32" to "0", "31" to "0", "14" to "50", "6" to "149.90",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventType shouldBe ExecutionEventType.CANCELLED
        event.execType shouldBe "4"
    }

    test("ExecType=5 -> REPLACED with new quantity/price in lastQty/lastPrice") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-6", "17" to "exec-6", "37" to "ord-6",
            "150" to "5", "32" to "200", "31" to "151.00", "14" to "0", "6" to "0",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventType shouldBe ExecutionEventType.REPLACED
        event.execType shouldBe "5"
        event.lastQty shouldBe "200"
        event.lastPrice shouldBe "151.00"
    }

    test("ExecType=8 (35=8 Rejected) -> REJECTED with reject reason and code") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-7", "17" to "exec-7", "37" to "",
            "150" to "8", "32" to "0", "31" to "0", "14" to "0", "6" to "0",
            "58" to "Unknown symbol", "103" to "1",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventType shouldBe ExecutionEventType.REJECTED
        event.execType shouldBe "8"
        event.rejectReason shouldBe "Unknown symbol"
        event.rejectCode shouldBe "1"
    }

    // ---------------------------------------------------------------------
    // 35=9 OrderCancelReject
    // ---------------------------------------------------------------------

    test("35=9 OrderCancelReject -> REJECTED with reject reason and code") {
        val msg = fixMessage(
            "35" to "9", "11" to "clord-8-cxl", "37" to "ord-8", "41" to "clord-8",
            "434" to "1", "102" to "0", "58" to "Too late to cancel",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventType shouldBe ExecutionEventType.REJECTED
        event.execType shouldBe ""
        event.rejectReason shouldBe "Too late to cancel"
        event.rejectCode shouldBe "0"
        // Cancel reject targets the original ClOrdID — partition routing must follow OrigClOrdID
        event.clOrdId shouldBe "clord-8"
    }

    // ---------------------------------------------------------------------
    // 35=j BusinessMessageReject
    // ---------------------------------------------------------------------

    test("35=j BusinessMessageReject -> BUSINESS_REJECT") {
        val msg = fixMessage(
            "35" to "j", "45" to "5", "372" to "D",
            "380" to "5", "58" to "Unknown instrument",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventType shouldBe ExecutionEventType.BUSINESS_REJECT
        event.rejectReason shouldBe "Unknown instrument"
        event.rejectCode shouldBe "5"
    }

    // ---------------------------------------------------------------------
    // PossDupFlag detection — handler dedups, but converter must surface it
    // ---------------------------------------------------------------------

    test("converter sets possDup flag when tag 43 is Y") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-dup", "17" to "exec-dup", "37" to "ord-dup",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
            "43" to "Y",
        )
        val parse = converter.parseRaw(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        parse.shouldNotBeNull()
        parse.possDup shouldBe true
        parse.event.eventType shouldBe ExecutionEventType.FILL
    }

    test("converter sets possDup=false when tag 43 absent") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-nodup", "17" to "exec-nodup", "37" to "ord-nodup",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
        )
        val parse = converter.parseRaw(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        parse.shouldNotBeNull()
        parse.possDup shouldBe false
    }

    // ---------------------------------------------------------------------
    // Defects
    // ---------------------------------------------------------------------

    test("returns null for unsupported MsgType") {
        val msg = fixMessage("35" to "D", "11" to "clord-x")
        converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock).shouldBeNull()
    }

    test("missing ClOrdID is preserved as empty — handler decides partition fallback") {
        val msg = fixMessage(
            "35" to "8", "17" to "exec-missing", "37" to "ord-missing",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        event.shouldNotBeNull()
        event.clOrdId shouldBe ""
    }

    test("missing OrderID for 35=8 returns null — venue contract violation") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-no-ord", "17" to "exec-no-ord",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
        )
        converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock).shouldBeNull()
    }

    test("rejects message with duplicate tags (tag injection)") {
        val msg = "35=D|35=8|11=ord-1|17=exec-1|37=ord-1|150=F|32=100|31=150.00|14=100|6=150.00"
        converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock).shouldBeNull()
    }

    test("rejects fill with zero LastQty") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-zero", "17" to "exec-zero", "37" to "ord-zero",
            "150" to "F", "32" to "0", "31" to "150.00", "14" to "0", "6" to "150.00",
        )
        converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock).shouldBeNull()
    }

    test("rejects fill with negative LastPx") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-neg", "17" to "exec-neg", "37" to "ord-neg",
            "150" to "F", "32" to "100", "31" to "-1.00", "14" to "100", "6" to "150.00",
        )
        converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock).shouldBeNull()
    }

    test("eventId is generated and prefixed for traceability") {
        val msg = fixMessage(
            "35" to "8", "11" to "clord-trace", "17" to "exec-trace", "37" to "ord-trace",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
        )
        val event = converter.parse(msg, sessionId = sessionId, fixVersion = "FIX.4.4", clock = fixedClock)
        event.shouldNotBeNull()
        event.eventId shouldStartWith "evt-"
        event.eventId.length shouldBe "evt-".length + 36 // UUID v4 string
    }
})

private fun fixMessage(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("|") { (tag, value) -> "$tag=$value" }
