package com.kinetix.position.fix

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.Side
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.BookTradeResult
import com.kinetix.position.service.TradeBookingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private fun makeOrder(
    orderId: String,
    side: Side = Side.BUY,
    quantity: BigDecimal = BigDecimal("100"),
    status: OrderStatus = OrderStatus.SENT,
    assetClass: AssetClass = AssetClass.EQUITY,
    currency: Currency = Currency.getInstance("USD"),
    instrumentType: String? = "CASH_EQUITY",
) = Order(
    orderId = orderId,
    bookId = "book-1",
    instrumentId = "AAPL",
    side = side,
    quantity = quantity,
    orderType = "LIMIT",
    limitPrice = BigDecimal("150.00"),
    arrivalPrice = BigDecimal("149.90"),
    submittedAt = Instant.parse("2026-03-25T09:00:00Z"),
    status = status,
    riskCheckResult = "APPROVED",
    riskCheckDetails = null,
    fixSessionId = "FIX-01",
    assetClass = assetClass,
    currency = currency,
    instrumentType = instrumentType,
)

private fun fillEvent(
    orderId: String,
    execId: String,
    execType: String,
    lastQty: BigDecimal = BigDecimal("100"),
    lastPrice: BigDecimal = BigDecimal("150.00"),
    cumulativeQty: BigDecimal = BigDecimal("100"),
    averagePrice: BigDecimal = BigDecimal("150.00"),
    sessionId: String = "SESSION-1",
) = FIXInboundFillEvent(
    sessionId = sessionId,
    execId = execId,
    orderId = orderId,
    execType = execType,
    lastQty = lastQty,
    lastPrice = lastPrice,
    cumulativeQty = cumulativeQty,
    averagePrice = averagePrice,
    venue = "NYSE",
)

class FIXExecutionReportProcessorTest : FunSpec({

    val orderRepository = mockk<ExecutionOrderRepository>()
    val fillRepository = mockk<ExecutionFillRepository>()
    val tradeBookingService = mockk<TradeBookingService>()
    val executionCostService = mockk<ExecutionCostService>()
    val executionCostRepository = mockk<ExecutionCostRepository>()

    val processor = FIXExecutionReportProcessor(
        orderRepository,
        fillRepository,
        tradeBookingService,
        executionCostService,
        executionCostRepository,
    )

    beforeEach {
        clearMocks(orderRepository, fillRepository, tradeBookingService, executionCostService, executionCostRepository)
        coEvery { fillRepository.save(any()) } just runs
        coEvery { orderRepository.updateStatus(any(), any(), any(), any()) } just runs
        coEvery { tradeBookingService.handle(any()) } returns mockk(relaxed = true)
        coEvery { executionCostService.compute(any(), any()) } returns mockk(relaxed = true)
        coEvery { executionCostRepository.save(any()) } just runs
    }

    test("full fill (ExecType=F) saves fill, advances order to FILLED, and triggers trade booking") {
        val order = makeOrder("ord-1", quantity = BigDecimal("100"))
        coEvery { orderRepository.findById("ord-1") } returns order
        coEvery { fillRepository.existsByFixExecId("exec-F-1") } returns false
        coEvery { fillRepository.findByOrderId("ord-1") } returns emptyList()

        processor.process(fillEvent("ord-1", "exec-F-1", "F",
            lastQty = BigDecimal("100"),
            cumulativeQty = BigDecimal("100"),
        ))

        coVerify(exactly = 1) { fillRepository.save(match { it.fillType == FillType.FULL && it.fillQty.compareTo(BigDecimal("100")) == 0 }) }
        coVerify(exactly = 1) { orderRepository.updateStatus("ord-1", OrderStatus.FILLED) }
        coVerify(exactly = 1) { tradeBookingService.handle(any<BookTradeCommand>()) }
    }

    test("partial fill (ExecType=1) saves fill, advances order to PARTIAL, and triggers trade booking") {
        val order = makeOrder("ord-2", quantity = BigDecimal("100"))
        coEvery { orderRepository.findById("ord-2") } returns order
        coEvery { fillRepository.existsByFixExecId("exec-1-1") } returns false
        coEvery { fillRepository.findByOrderId("ord-2") } returns emptyList()

        processor.process(fillEvent("ord-2", "exec-1-1", "1",
            lastQty = BigDecimal("40"),
            cumulativeQty = BigDecimal("40"),
        ))

        coVerify(exactly = 1) { fillRepository.save(match { it.fillType == FillType.PARTIAL && it.fillQty.compareTo(BigDecimal("40")) == 0 }) }
        coVerify(exactly = 1) { orderRepository.updateStatus("ord-2", OrderStatus.PARTIAL) }
        coVerify(exactly = 1) { tradeBookingService.handle(match { it.quantity.compareTo(BigDecimal("40")) == 0 }) }
    }

    test("duplicate fill is ignored when fix_exec_id already exists") {
        coEvery { fillRepository.existsByFixExecId("exec-dup") } returns true

        processor.process(fillEvent("ord-3", "exec-dup", "F"))

        coVerify(exactly = 0) { fillRepository.save(any()) }
        coVerify(exactly = 0) { orderRepository.updateStatus(any(), any(), any(), any()) }
        coVerify(exactly = 0) { tradeBookingService.handle(any()) }
    }

    test("cancel (ExecType=4) advances SENT order to CANCELLED") {
        val order = makeOrder("ord-4", status = OrderStatus.SENT)
        coEvery { orderRepository.findById("ord-4") } returns order

        processor.process(fillEvent("ord-4", "exec-cancel-1", "4",
            lastQty = BigDecimal.ZERO,
            lastPrice = BigDecimal.ZERO,
        ))

        coVerify(exactly = 1) { orderRepository.updateStatus("ord-4", OrderStatus.CANCELLED) }
        coVerify(exactly = 0) { tradeBookingService.handle(any()) }
    }

    test("cancel (ExecType=4) advances PARTIAL order to CANCELLED") {
        val order = makeOrder("ord-5", status = OrderStatus.PARTIAL)
        coEvery { orderRepository.findById("ord-5") } returns order

        processor.process(fillEvent("ord-5", "exec-cancel-2", "4",
            lastQty = BigDecimal.ZERO,
            lastPrice = BigDecimal.ZERO,
        ))

        coVerify(exactly = 1) { orderRepository.updateStatus("ord-5", OrderStatus.CANCELLED) }
    }

    test("cancel is ignored for an already FILLED order") {
        val order = makeOrder("ord-6", status = OrderStatus.FILLED)
        coEvery { orderRepository.findById("ord-6") } returns order

        processor.process(fillEvent("ord-6", "exec-cancel-3", "4",
            lastQty = BigDecimal.ZERO,
            lastPrice = BigDecimal.ZERO,
        ))

        coVerify(exactly = 0) { orderRepository.updateStatus(any(), any(), any(), any()) }
    }

    test("overfill guard rejects fill that would exceed order quantity") {
        val order = makeOrder("ord-7", quantity = BigDecimal("100"))
        val existingFill = ExecutionFill(
            fillId = "fill-existing",
            orderId = "ord-7",
            bookId = "book-1",
            instrumentId = "AAPL",
            fillTime = Instant.now(),
            fillQty = BigDecimal("90"),
            fillPrice = BigDecimal("150.00"),
            fillType = FillType.PARTIAL,
            venue = null,
            cumulativeQty = BigDecimal("90"),
            averagePrice = BigDecimal("150.00"),
            fixExecId = "exec-prev",
        )
        coEvery { orderRepository.findById("ord-7") } returns order
        coEvery { fillRepository.existsByFixExecId("exec-overfill") } returns false
        coEvery { fillRepository.findByOrderId("ord-7") } returns listOf(existingFill)

        // 90 already filled + 20 incoming = 110 > 100 → reject
        processor.process(fillEvent("ord-7", "exec-overfill", "F",
            lastQty = BigDecimal("20"),
            cumulativeQty = BigDecimal("110"),
        ))

        coVerify(exactly = 0) { fillRepository.save(any()) }
        coVerify(exactly = 0) { tradeBookingService.handle(any()) }
    }

    test("replace (ExecType=5) updates order quantity and limit price") {
        val order = makeOrder("ord-8", quantity = BigDecimal("100"))
        coEvery { orderRepository.findById("ord-8") } returns order
        coEvery { fillRepository.findByOrderId("ord-8") } returns emptyList()
        coEvery { orderRepository.updateQuantityAndPrice(any(), any(), any()) } just runs

        processor.process(fillEvent("ord-8", "exec-replace-1", "5",
            lastQty = BigDecimal("80"),
            lastPrice = BigDecimal("155.00"),
        ))

        coVerify(exactly = 1) { orderRepository.updateQuantityAndPrice("ord-8", BigDecimal("80"), BigDecimal("155.00")) }
        coVerify(exactly = 0) { tradeBookingService.handle(any()) }
    }

    test("fill books trade with instrumentType from the order, so the position carries a non-null type") {
        val order = makeOrder(
            "ord-9-it",
            assetClass = AssetClass.DERIVATIVE,
            instrumentType = "EQUITY_OPTION",
        )
        coEvery { orderRepository.findById("ord-9-it") } returns order
        coEvery { fillRepository.existsByFixExecId("exec-it-1") } returns false
        coEvery { fillRepository.findByOrderId("ord-9-it") } returns emptyList()

        processor.process(fillEvent("ord-9-it", "exec-it-1", "F",
            lastQty = BigDecimal("100"),
            cumulativeQty = BigDecimal("100"),
        ))

        coVerify(exactly = 1) {
            tradeBookingService.handle(match { it.instrumentType == "EQUITY_OPTION" })
        }
    }

    test("fill books trade with asset class from the order, not hardcoded EQUITY") {
        val order = makeOrder("ord-9", assetClass = AssetClass.FIXED_INCOME, currency = Currency.getInstance("USD"))
        coEvery { orderRepository.findById("ord-9") } returns order
        coEvery { fillRepository.existsByFixExecId("exec-fi-1") } returns false
        coEvery { fillRepository.findByOrderId("ord-9") } returns emptyList()

        processor.process(fillEvent("ord-9", "exec-fi-1", "F",
            lastQty = BigDecimal("100"),
            cumulativeQty = BigDecimal("100"),
        ))

        coVerify(exactly = 1) {
            tradeBookingService.handle(match { it.assetClass == AssetClass.FIXED_INCOME })
        }
    }

    test("fill books trade with currency from the order, not hardcoded USD") {
        val eur = Currency.getInstance("EUR")
        val order = makeOrder("ord-10", assetClass = AssetClass.FX, currency = eur)
        coEvery { orderRepository.findById("ord-10") } returns order
        coEvery { fillRepository.existsByFixExecId("exec-fx-1") } returns false
        coEvery { fillRepository.findByOrderId("ord-10") } returns emptyList()

        processor.process(fillEvent("ord-10", "exec-fx-1", "F",
            lastQty = BigDecimal("100"),
            cumulativeQty = BigDecimal("100"),
        ))

        coVerify(exactly = 1) {
            tradeBookingService.handle(match { it.assetClass == AssetClass.FX && it.price.currency == eur })
        }
    }

    test("computes and persists execution cost after a full fill") {
        val order = makeOrder("ord-11", quantity = BigDecimal("100"))
        coEvery { orderRepository.findById("ord-11") } returns order
        coEvery { fillRepository.existsByFixExecId("exec-full-1") } returns false
        coEvery { fillRepository.findByOrderId("ord-11") } returns emptyList()
        val costAnalysis = mockk<ExecutionCostAnalysis>(relaxed = true)
        coEvery { executionCostService.compute(any(), any()) } returns costAnalysis

        processor.process(fillEvent("ord-11", "exec-full-1", "F",
            lastQty = BigDecimal("100"),
            lastPrice = BigDecimal("150.10"),
            cumulativeQty = BigDecimal("100"),
            averagePrice = BigDecimal("150.10"),
        ))

        coVerify(exactly = 1) {
            executionCostService.compute(
                match { enrichedOrder ->
                    enrichedOrder.orderId == "ord-11" &&
                        enrichedOrder.fills.size == 1 &&
                        enrichedOrder.fills[0].fillQty.compareTo(BigDecimal("100")) == 0
                },
                any(),
            )
        }
        coVerify(exactly = 1) { executionCostRepository.save(costAnalysis) }
    }

    test("replace (ExecType=5) rejected when newQty is less than cumulative fills") {
        val order = makeOrder("ord-replace-guard", quantity = BigDecimal("100"))
        coEvery { orderRepository.findById("ord-replace-guard") } returns order
        coEvery { fillRepository.findByOrderId("ord-replace-guard") } returns listOf(
            ExecutionFill(
                fillId = "fill-1",
                orderId = "ord-replace-guard",
                bookId = "book-1",
                instrumentId = "AAPL",
                fillTime = java.time.Instant.now(),
                fillQty = BigDecimal("80"),
                fillPrice = BigDecimal("150.00"),
                fillType = FillType.FULL,
                venue = null,
                cumulativeQty = BigDecimal("80"),
                averagePrice = BigDecimal("150.00"),
                fixExecId = "exec-f1",
            ),
        )

        // Replace with newQty=50, which is less than 80 already filled → reject
        processor.process(fillEvent("ord-replace-guard", "exec-replace-bad", "5",
            lastQty = BigDecimal("50"),
            lastPrice = BigDecimal("155.00"),
        ))

        // Should NOT update — the replace was rejected
        coVerify(exactly = 0) { orderRepository.updateQuantityAndPrice(any(), any(), any()) }
    }

    test("does not compute execution cost after a partial fill") {
        val order = makeOrder("ord-12", quantity = BigDecimal("100"))
        coEvery { orderRepository.findById("ord-12") } returns order
        coEvery { fillRepository.existsByFixExecId("exec-partial-1") } returns false
        coEvery { fillRepository.findByOrderId("ord-12") } returns emptyList()

        processor.process(fillEvent("ord-12", "exec-partial-1", "1",
            lastQty = BigDecimal("60"),
            cumulativeQty = BigDecimal("60"),
        ))

        coVerify(exactly = 0) { executionCostService.compute(any(), any()) }
        coVerify(exactly = 0) { executionCostRepository.save(any()) }
    }

    // ── FIX correlation chain (audit A-16, spec execution.allium:328) ─────────

    test("fill stamps BookTradeCommand.correlationId with the originating order id") {
        val order = makeOrder("ord-corr-1", quantity = BigDecimal("100"))
        coEvery { orderRepository.findById("ord-corr-1") } returns order
        coEvery { fillRepository.existsByFixExecId("exec-corr-1") } returns false
        coEvery { fillRepository.findByOrderId("ord-corr-1") } returns emptyList()

        processor.process(fillEvent("ord-corr-1", "exec-corr-1", "F",
            lastQty = BigDecimal("100"),
            cumulativeQty = BigDecimal("100"),
        ))

        coVerify(exactly = 1) {
            tradeBookingService.handle(match<BookTradeCommand> { it.correlationId == "ord-corr-1" })
        }
    }
})
