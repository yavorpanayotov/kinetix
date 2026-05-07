package com.kinetix.position.fix

import com.kinetix.common.kafka.events.RiskBreakEvent
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.Side
import com.kinetix.position.kafka.RiskBreakPublisher
import com.kinetix.position.service.TradeBookingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Ghost-fill detection in FIXExecutionReportProcessor (ADR-0035 phase 2).
 * Inbound 35=8 fills against EXPIRED / CANCELLED / REJECTED orders are
 * persisted to ghost_fills, surface as a CRITICAL RiskBreak on `risk.breaks`,
 * and do NOT auto-update the Position.
 */
class GhostFillDetectionTest : FunSpec({

    val orderRepository = mockk<ExecutionOrderRepository>()
    val fillRepository = mockk<ExecutionFillRepository>()
    val tradeBookingService = mockk<TradeBookingService>()
    val executionCostService = mockk<ExecutionCostService>()
    val executionCostRepository = mockk<ExecutionCostRepository>()
    val ghostFillRepository = RecordingGhostFillRepository()
    val riskBreakPublisher = RecordingRiskBreakPublisher()

    fun processor() = FIXExecutionReportProcessor(
        orderRepository = orderRepository,
        fillRepository = fillRepository,
        tradeBookingService = tradeBookingService,
        executionCostService = executionCostService,
        executionCostRepository = executionCostRepository,
        ghostFillRepository = ghostFillRepository,
        riskBreakPublisher = riskBreakPublisher,
        clock = { Instant.parse("2026-05-04T20:30:00Z") },
    )

    fun terminalOrder(status: OrderStatus): Order = Order(
        orderId = "ord-ghost-1", bookId = "book-1", instrumentId = "AAPL",
        side = Side.BUY, quantity = BigDecimal("100"), orderType = "LIMIT",
        limitPrice = BigDecimal("150.00"), arrivalPrice = BigDecimal("149.90"),
        submittedAt = Instant.parse("2026-05-04T13:30:00Z"),
        status = status, riskCheckResult = "APPROVED", riskCheckDetails = null,
        fixSessionId = "SESSION-1", assetClass = AssetClass.EQUITY,
        currency = Currency.getInstance("USD"),
    )

    fun fill() = FIXInboundFillEvent(
        sessionId = "SESSION-1", execId = "ghost-exec-1", orderId = "ord-ghost-1",
        execType = "F", lastQty = BigDecimal("50"), lastPrice = BigDecimal("150.00"),
        cumulativeQty = BigDecimal("50"), averagePrice = BigDecimal("150.00"),
        venue = "NYSE",
    )

    beforeEach {
        clearMocks(orderRepository, fillRepository, tradeBookingService)
        ghostFillRepository.saved.clear()
        riskBreakPublisher.published.clear()
        coEvery { fillRepository.existsByFixExecId(any()) } returns false
    }

    listOf(OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.REJECTED).forEach { priorStatus ->
        test("fill against $priorStatus order persists ghost fill + publishes CRITICAL RiskBreak; Position is NOT updated") {
            coEvery { orderRepository.findById("ord-ghost-1") } returns terminalOrder(priorStatus)
            coEvery { orderRepository.updateStatus(any(), any()) } just runs

            processor().process(fill())

            ghostFillRepository.saved shouldHaveSize 1
            ghostFillRepository.saved[0].priorStatus shouldBe priorStatus
            ghostFillRepository.saved[0].fixExecId shouldBe "ghost-exec-1"
            ghostFillRepository.saved[0].fillQty shouldBe BigDecimal("50")
            ghostFillRepository.saved[0].venue shouldBe "NYSE"

            riskBreakPublisher.published shouldHaveSize 1
            riskBreakPublisher.published[0].breakType shouldBe "GHOST_FILL"
            riskBreakPublisher.published[0].severity shouldBe "CRITICAL"
            riskBreakPublisher.published[0].orderId shouldBe "ord-ghost-1"
            riskBreakPublisher.published[0].attributes["priorStatus"] shouldBe priorStatus.name

            // Position invariants: NO trade booking, NO position-update fill row, NO order-status mutation
            coVerify(exactly = 0) { fillRepository.save(any()) }
            coVerify(exactly = 0) { tradeBookingService.handle(any()) }
            coVerify(exactly = 0) { orderRepository.updateStatus(any(), any()) }
        }
    }

    test("fill against FILLED order is treated as overfill, NOT as a ghost fill") {
        coEvery { orderRepository.findById("ord-ghost-1") } returns terminalOrder(OrderStatus.FILLED)

        processor().process(fill())

        // Overfill path — no ghost fill recorded, no RiskBreak published.
        ghostFillRepository.saved shouldHaveSize 0
        riskBreakPublisher.published shouldHaveSize 0
    }
})

private class RecordingGhostFillRepository : GhostFillRepository {
    val saved = mutableListOf<GhostFill>()
    override suspend fun save(fill: GhostFill) {
        saved += fill
    }
    override suspend fun findByOrderId(orderId: String): List<GhostFill> =
        saved.filter { it.orderId == orderId }
}

private class RecordingRiskBreakPublisher : RiskBreakPublisher {
    val published = mutableListOf<RiskBreakEvent>()
    override suspend fun publish(event: RiskBreakEvent) {
        published += event
    }
}
