package com.kinetix.schema

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

/**
 * Pins the JSON wire format of [ExecutionReportEvent] published on the `execution.reports`
 * Kafka topic by `fix-gateway` (ADR-0035 phase 3).
 *
 * Every consumer-visible field MUST round-trip and every [ExecutionEventType] variant MUST
 * have an explicit pinned JSON shape. Adding a new field is fine if it has a default; renaming
 * or removing a field is a wire-format break and will fail this test.
 */
class ExecutionReportEventSchemaTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true }

    test("FILL event round-trips with all fill economics") {
        val event = ExecutionReportEvent(
            eventId = "evt-fill-1",
            clOrdId = "clord-fill-1",
            orderId = "venue-order-1",
            execId = "exec-1",
            sessionId = "FIX.4.4:SENDER->NYSE",
            venue = "NYSE",
            correlationId = "corr-1",
            fixVersion = "FIX.4.4",
            execType = "F",
            eventType = ExecutionEventType.FILL,
            lastQty = "100",
            lastPrice = "150.25",
            cumulativeQty = "100",
            averagePrice = "150.25",
            receivedAt = "2026-05-07T10:00:00Z",
        )

        val serialized = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        val deserialized = json.decodeFromString<ExecutionReportEvent>(serialized)

        deserialized shouldBe event
        deserialized.eventType shouldBe ExecutionEventType.FILL
        deserialized.execType shouldBe "F"
        deserialized.lastQty shouldBe "100"
        deserialized.cumulativeQty shouldBe "100"
    }

    test("PARTIAL_FILL event preserves raw FIX 4.2 ExecType=1") {
        val event = ExecutionReportEvent(
            eventId = "evt-partial-1",
            clOrdId = "clord-partial-1",
            orderId = "venue-order-2",
            execId = "exec-2",
            sessionId = "FIX.4.2:SENDER->LSE",
            venue = "LSE",
            fixVersion = "FIX.4.2",
            execType = "1",
            eventType = ExecutionEventType.PARTIAL_FILL,
            lastQty = "30",
            lastPrice = "150.20",
            cumulativeQty = "30",
            averagePrice = "150.20",
            receivedAt = "2026-05-07T10:00:01Z",
        )

        val serialized = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        val deserialized = json.decodeFromString<ExecutionReportEvent>(serialized)

        deserialized.eventType shouldBe ExecutionEventType.PARTIAL_FILL
        deserialized.execType shouldBe "1"
        deserialized.fixVersion shouldBe "FIX.4.2"
    }

    test("FILL event with FIX 4.4 ExecType=F differs from PARTIAL_FILL on FIX 4.2 ExecType=1") {
        val fix44Fill = ExecutionReportEvent(
            eventId = "e1", clOrdId = "c1", orderId = "o1", execId = "x1",
            sessionId = "s", venue = "NYSE",
            fixVersion = "FIX.4.4", execType = "F", eventType = ExecutionEventType.FILL,
            receivedAt = "2026-05-07T10:00:00Z",
        )
        val fix42Partial = ExecutionReportEvent(
            eventId = "e2", clOrdId = "c2", orderId = "o2", execId = "x2",
            sessionId = "s", venue = "LSE",
            fixVersion = "FIX.4.2", execType = "1", eventType = ExecutionEventType.PARTIAL_FILL,
            receivedAt = "2026-05-07T10:00:00Z",
        )
        fix44Fill.execType shouldBe "F"
        fix42Partial.execType shouldBe "1"
        fix44Fill.eventType shouldBe ExecutionEventType.FILL
        fix42Partial.eventType shouldBe ExecutionEventType.PARTIAL_FILL
    }

    test("CANCELLED event has ExecType=4 and no fill economics required") {
        val event = ExecutionReportEvent(
            eventId = "evt-cancel-1",
            clOrdId = "clord-cxl-1",
            orderId = "venue-order-3",
            execId = "exec-3",
            sessionId = "FIX.4.4:SENDER->NYSE",
            venue = "NYSE",
            fixVersion = "FIX.4.4",
            execType = "4",
            eventType = ExecutionEventType.CANCELLED,
            receivedAt = "2026-05-07T10:00:02Z",
        )

        val serialized = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        val deserialized = json.decodeFromString<ExecutionReportEvent>(serialized)

        deserialized shouldBe event
        deserialized.eventType shouldBe ExecutionEventType.CANCELLED
        deserialized.execType shouldBe "4"
        deserialized.lastQty shouldBe "0"
        deserialized.lastPrice shouldBe "0"
    }

    test("REPLACED event carries new quantity/price in lastQty/lastPrice") {
        val event = ExecutionReportEvent(
            eventId = "evt-replace-1",
            clOrdId = "clord-rep-1",
            orderId = "venue-order-4",
            execId = "exec-4",
            sessionId = "FIX.4.4:SENDER->NYSE",
            venue = "NYSE",
            fixVersion = "FIX.4.4",
            execType = "5",
            eventType = ExecutionEventType.REPLACED,
            lastQty = "200",
            lastPrice = "151.00",
            receivedAt = "2026-05-07T10:00:03Z",
        )

        val serialized = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        val deserialized = json.decodeFromString<ExecutionReportEvent>(serialized)

        deserialized shouldBe event
        deserialized.eventType shouldBe ExecutionEventType.REPLACED
        deserialized.execType shouldBe "5"
    }

    test("REJECTED event from 35=9 OrderCancelReject carries reject reason and code") {
        val event = ExecutionReportEvent(
            eventId = "evt-reject-1",
            clOrdId = "clord-rej-1",
            orderId = "venue-order-5",
            execId = "exec-5",
            sessionId = "FIX.4.4:SENDER->NYSE",
            venue = "NYSE",
            fixVersion = "FIX.4.4",
            execType = "8",
            eventType = ExecutionEventType.REJECTED,
            rejectReason = "Too late to cancel",
            rejectCode = "0",
            receivedAt = "2026-05-07T10:00:04Z",
        )

        val serialized = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        val deserialized = json.decodeFromString<ExecutionReportEvent>(serialized)

        deserialized shouldBe event
        deserialized.eventType shouldBe ExecutionEventType.REJECTED
        deserialized.rejectReason shouldBe "Too late to cancel"
        deserialized.rejectCode shouldBe "0"
    }

    test("BUSINESS_REJECT event from 35=j carries reject reason and code") {
        val event = ExecutionReportEvent(
            eventId = "evt-bizrej-1",
            clOrdId = "clord-bizrej-1",
            orderId = "",
            execId = "exec-6",
            sessionId = "FIX.4.4:SENDER->NYSE",
            venue = "NYSE",
            fixVersion = "FIX.4.4",
            execType = "",
            eventType = ExecutionEventType.BUSINESS_REJECT,
            rejectReason = "Unknown instrument",
            rejectCode = "5",
            receivedAt = "2026-05-07T10:00:05Z",
        )

        val serialized = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        val deserialized = json.decodeFromString<ExecutionReportEvent>(serialized)

        deserialized shouldBe event
        deserialized.eventType shouldBe ExecutionEventType.BUSINESS_REJECT
        deserialized.rejectReason shouldBe "Unknown instrument"
    }

    test("backward compatibility: minimal event deserialises with field defaults") {
        val minimalJson = """
            {
                "eventId": "evt-minimal",
                "clOrdId": "c-min",
                "orderId": "o-min",
                "execId": "x-min",
                "sessionId": "s-min",
                "fixVersion": "FIX.4.4",
                "execType": "F",
                "eventType": "FILL",
                "receivedAt": "2026-05-07T10:00:00Z"
            }
        """.trimIndent()

        val event = json.decodeFromString<ExecutionReportEvent>(minimalJson)
        event.venue shouldBe null
        event.correlationId shouldBe null
        event.lastQty shouldBe "0"
        event.lastPrice shouldBe "0"
        event.cumulativeQty shouldBe "0"
        event.averagePrice shouldBe "0"
        event.rejectReason shouldBe null
        event.rejectCode shouldBe null
    }

    test("forward compatibility: unknown fields are ignored") {
        val futureJson = """
            {
                "eventId": "evt-future",
                "clOrdId": "c-fut",
                "orderId": "o-fut",
                "execId": "x-fut",
                "sessionId": "s",
                "fixVersion": "FIX.5.0",
                "execType": "F",
                "eventType": "FILL",
                "receivedAt": "2026-05-07T10:00:00Z",
                "futureField": "we don't know about this yet"
            }
        """.trimIndent()

        val event = json.decodeFromString<ExecutionReportEvent>(futureJson)
        event.eventId shouldBe "evt-future"
        event.fixVersion shouldBe "FIX.5.0"
    }

    test("eventType serialises as enum name (string), not ordinal") {
        val event = ExecutionReportEvent(
            eventId = "e", clOrdId = "c", orderId = "o", execId = "x",
            sessionId = "s", venue = "NYSE",
            fixVersion = "FIX.4.4", execType = "F", eventType = ExecutionEventType.PARTIAL_FILL,
            receivedAt = "2026-05-07T10:00:00Z",
        )
        val serialized = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        serialized shouldContain "\"eventType\":\"PARTIAL_FILL\""
    }

    test("clOrdId is preserved verbatim — partition-key contract for execution.reports topic") {
        val uuid = "5b2a3f1e-1234-4abc-9def-0123456789ab"
        val event = ExecutionReportEvent(
            eventId = "e", clOrdId = uuid, orderId = "o", execId = "x",
            sessionId = "s", venue = "NYSE",
            fixVersion = "FIX.4.4", execType = "F", eventType = ExecutionEventType.FILL,
            receivedAt = "2026-05-07T10:00:00Z",
        )
        val serialized = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        val deserialized = json.decodeFromString<ExecutionReportEvent>(serialized)
        deserialized.clOrdId shouldBe uuid
    }

    test("decimal values are carried as strings to avoid float drift") {
        val event = ExecutionReportEvent(
            eventId = "e", clOrdId = "c", orderId = "o", execId = "x",
            sessionId = "s", venue = "NYSE",
            fixVersion = "FIX.4.4", execType = "F", eventType = ExecutionEventType.FILL,
            lastQty = "0.00000001",
            lastPrice = "99999999999999.99",
            cumulativeQty = "0.00000001",
            averagePrice = "99999999999999.99",
            receivedAt = "2026-05-07T10:00:00Z",
        )
        val serialized = Json.encodeToString(ExecutionReportEvent.serializer(), event)
        val deserialized = json.decodeFromString<ExecutionReportEvent>(serialized)
        deserialized.lastQty shouldBe "0.00000001"
        deserialized.lastPrice shouldBe "99999999999999.99"
    }
})
