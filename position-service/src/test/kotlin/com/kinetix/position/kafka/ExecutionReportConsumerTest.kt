package com.kinetix.position.kafka

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import com.kinetix.position.fix.FIXExecutionReportProcessor
import com.kinetix.position.fix.FIXInboundFillEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal

private fun event(
    clOrdId: String = "ord-1",
    execId: String = "exec-1",
    venue: String? = "NYSE",
    eventType: ExecutionEventType = ExecutionEventType.FILL,
    execType: String = "F",
    fixVersion: String = "FIX.4.4",
    lastQty: String = "100",
    lastPrice: String = "150.00",
    cumulativeQty: String = "100",
    averagePrice: String = "150.00",
    sessionId: String = "FIX.4.4:SENDER->TARGET",
    receivedAt: String = "2026-05-07T10:00:00Z",
    orderId: String = "venue-37-1",
    rejectReason: String? = null,
    rejectCode: String? = null,
) = ExecutionReportEvent(
    eventId = "evt-$execId",
    clOrdId = clOrdId,
    orderId = orderId,
    execId = execId,
    sessionId = sessionId,
    venue = venue,
    fixVersion = fixVersion,
    execType = execType,
    eventType = eventType,
    lastQty = lastQty,
    lastPrice = lastPrice,
    cumulativeQty = cumulativeQty,
    averagePrice = averagePrice,
    rejectReason = rejectReason,
    rejectCode = rejectCode,
    receivedAt = receivedAt,
)

class ExecutionReportConsumerTest : FunSpec({

    val processor = mockk<FIXExecutionReportProcessor>()

    beforeEach {
        clearMocks(processor)
        coEvery { processor.process(any()) } just runs
    }

    test("FILL event dispatches to processor with clOrdId as orderId and raw execType") {
        val dispatcher = ExecutionReportDispatcher(processor, SimpleMeterRegistry())
        val captured = slot<FIXInboundFillEvent>()
        coEvery { processor.process(capture(captured)) } just runs

        dispatcher.dispatch(
            event(clOrdId = "ord-fill-1", execId = "exec-fill-1", execType = "F"),
        )

        coVerify(exactly = 1) { processor.process(any()) }
        captured.captured.orderId shouldBe "ord-fill-1"
        captured.captured.execType shouldBe "F"
        captured.captured.execId shouldBe "exec-fill-1"
        captured.captured.lastQty.compareTo(BigDecimal("100")) shouldBe 0
    }

    test("PARTIAL_FILL preserves raw execType so the processor branches identically to the in-process path") {
        val dispatcher = ExecutionReportDispatcher(processor, SimpleMeterRegistry())
        val captured = slot<FIXInboundFillEvent>()
        coEvery { processor.process(capture(captured)) } just runs

        dispatcher.dispatch(
            event(
                clOrdId = "ord-partial-1",
                execId = "exec-partial-1",
                eventType = ExecutionEventType.PARTIAL_FILL,
                execType = "1",
                fixVersion = "FIX.4.2",
                lastQty = "40",
                cumulativeQty = "40",
            ),
        )

        captured.captured.execType shouldBe "1"
        captured.captured.lastQty.compareTo(BigDecimal("40")) shouldBe 0
        captured.captured.cumulativeQty.compareTo(BigDecimal("40")) shouldBe 0
    }

    test("CANCELLED event maps with execType=4 so processor cancel branch fires") {
        val dispatcher = ExecutionReportDispatcher(processor, SimpleMeterRegistry())
        val captured = slot<FIXInboundFillEvent>()
        coEvery { processor.process(capture(captured)) } just runs

        dispatcher.dispatch(
            event(
                clOrdId = "ord-cxl-1",
                execId = "exec-cxl-1",
                eventType = ExecutionEventType.CANCELLED,
                execType = "4",
                lastQty = "0",
                lastPrice = "0",
                cumulativeQty = "0",
                averagePrice = "0",
            ),
        )

        captured.captured.execType shouldBe "4"
    }

    test("REPLACED event maps with execType=5 so processor replace branch fires") {
        val dispatcher = ExecutionReportDispatcher(processor, SimpleMeterRegistry())
        val captured = slot<FIXInboundFillEvent>()
        coEvery { processor.process(capture(captured)) } just runs

        dispatcher.dispatch(
            event(
                clOrdId = "ord-rpl-1",
                execId = "exec-rpl-1",
                eventType = ExecutionEventType.REPLACED,
                execType = "5",
                lastQty = "80",
                lastPrice = "155.00",
            ),
        )

        captured.captured.execType shouldBe "5"
        captured.captured.lastQty.compareTo(BigDecimal("80")) shouldBe 0
        captured.captured.lastPrice.compareTo(BigDecimal("155.00")) shouldBe 0
    }

    test("REJECTED 35=9 OrderCancelReject is logged but not dispatched (processor only handles 35=8 ExecTypes)") {
        val registry = SimpleMeterRegistry()
        val dispatcher = ExecutionReportDispatcher(processor, registry)

        dispatcher.dispatch(
            event(
                clOrdId = "ord-rej-1",
                execId = "exec-rej-1",
                eventType = ExecutionEventType.REJECTED,
                execType = "",
                rejectReason = "Too late to cancel",
                rejectCode = "0",
                lastQty = "0",
                lastPrice = "0",
                cumulativeQty = "0",
                averagePrice = "0",
            ),
        )

        coVerify(exactly = 0) { processor.process(any()) }
        registry.find("execution_report_consumer_skipped_total")
            .tag("event_type", "REJECTED")
            .counter()!!.count() shouldBe 1.0
    }

    test("BUSINESS_REJECT 35=j is logged but not dispatched") {
        val registry = SimpleMeterRegistry()
        val dispatcher = ExecutionReportDispatcher(processor, registry)

        dispatcher.dispatch(
            event(
                clOrdId = "",
                execId = "",
                eventType = ExecutionEventType.BUSINESS_REJECT,
                execType = "",
                rejectReason = "Unknown order",
                rejectCode = "1",
                lastQty = "0",
                lastPrice = "0",
                cumulativeQty = "0",
                averagePrice = "0",
            ),
        )

        coVerify(exactly = 0) { processor.process(any()) }
        registry.find("execution_report_consumer_skipped_total")
            .tag("event_type", "BUSINESS_REJECT")
            .counter()!!.count() shouldBe 1.0
    }

    test("duplicate (venue, execId) within LRU window is skipped — defence-in-depth over DB unique constraint") {
        val registry = SimpleMeterRegistry()
        val dispatcher = ExecutionReportDispatcher(processor, registry)

        val first = event(clOrdId = "ord-dup-1", execId = "exec-dup-1", venue = "NYSE")
        val second = event(clOrdId = "ord-dup-1", execId = "exec-dup-1", venue = "NYSE")

        dispatcher.dispatch(first)
        dispatcher.dispatch(second)

        coVerify(exactly = 1) { processor.process(any()) }
        registry.find("execution_report_consumer_dedup_total")
            .tag("venue", "NYSE")
            .counter()!!.count() shouldBe 1.0
    }

    test("same execId from two different venues is NOT dedup'd — venue is part of the dedup key") {
        val dispatcher = ExecutionReportDispatcher(processor, SimpleMeterRegistry())

        dispatcher.dispatch(event(clOrdId = "ord-x", execId = "exec-1", venue = "NYSE"))
        dispatcher.dispatch(event(clOrdId = "ord-y", execId = "exec-1", venue = "LSE"))

        coVerify(exactly = 2) { processor.process(any()) }
    }

    test("processor exception bubbles up so the Kafka consumer can withhold the offset commit") {
        val dispatcher = ExecutionReportDispatcher(processor, SimpleMeterRegistry())
        coEvery { processor.process(any()) } throws RuntimeException("DB down")

        try {
            dispatcher.dispatch(event(clOrdId = "ord-err", execId = "exec-err"))
            error("expected exception")
        } catch (e: RuntimeException) {
            e.message shouldBe "DB down"
        }
    }

    test("malformed JSON payload is logged and skipped — does not block consumer progress") {
        val registry = SimpleMeterRegistry()
        val dispatcher = ExecutionReportDispatcher(processor, registry)

        dispatcher.dispatchRaw("not-json", offset = 42, partition = 0, key = "ord-bad")

        coVerify(exactly = 0) { processor.process(any()) }
        registry.find("execution_report_consumer_malformed_total").counter()!!.count() shouldBe 1.0
    }

    test("LRU cache evicts the oldest (venue,execId) pair when capacity is exceeded") {
        val registry = SimpleMeterRegistry()
        val dispatcher = ExecutionReportDispatcher(
            processor,
            registry,
            seenExecIdCacheSize = 2,
        )

        // Cache: [exec-1]
        dispatcher.dispatch(event(clOrdId = "ord-1", execId = "exec-1", venue = "NYSE"))
        // Cache: [exec-1, exec-2]
        dispatcher.dispatch(event(clOrdId = "ord-2", execId = "exec-2", venue = "NYSE"))
        // Re-arrival of exec-1 → dedup'd, but access-order moves exec-1 to MRU.
        // Cache: [exec-2, exec-1]
        dispatcher.dispatch(event(clOrdId = "ord-1", execId = "exec-1", venue = "NYSE"))
        // exec-3 inserts → exec-2 (eldest) evicted. Cache: [exec-1, exec-3]
        dispatcher.dispatch(event(clOrdId = "ord-3", execId = "exec-3", venue = "NYSE"))
        // Re-arrival of exec-2 → was evicted — dispatched again. The DB UNIQUE
        // constraint on (fix_exec_id) is the final guard.
        // Cache: [exec-3, exec-2]
        dispatcher.dispatch(event(clOrdId = "ord-2", execId = "exec-2", venue = "NYSE"))

        coVerify(exactly = 4) { processor.process(any()) }
        registry.find("execution_report_consumer_dedup_total")
            .tag("venue", "NYSE")
            .counter()!!.count() shouldBe 1.0
    }

    test("consumer JSON contract: deserialises wire payload identical to fix-gateway output") {
        val payload = Json.encodeToString(
            event(
                clOrdId = "ord-wire",
                execId = "exec-wire",
                eventType = ExecutionEventType.PARTIAL_FILL,
                execType = "1",
                fixVersion = "FIX.4.2",
                lastQty = "25.5",
                cumulativeQty = "25.5",
            )
        )
        val dispatcher = ExecutionReportDispatcher(processor, SimpleMeterRegistry())
        val captured = slot<FIXInboundFillEvent>()
        coEvery { processor.process(capture(captured)) } just runs

        dispatcher.dispatchRaw(payload, offset = 0, partition = 0, key = "ord-wire")

        captured.captured.execType shouldBe "1"
        captured.captured.lastQty.compareTo(BigDecimal("25.5")) shouldBe 0
    }
})
