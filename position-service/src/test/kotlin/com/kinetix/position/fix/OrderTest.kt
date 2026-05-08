package com.kinetix.position.fix

import com.kinetix.common.model.Side
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import java.math.BigDecimal
import java.time.Instant

class OrderTest : FunSpec({

    val baseOrder = Order(
        orderId = "ord-1",
        bookId = "book-1",
        instrumentId = "AAPL",
        side = Side.BUY,
        quantity = BigDecimal("100"),
        orderType = "LIMIT",
        limitPrice = BigDecimal("150.00"),
        arrivalPrice = BigDecimal("149.80"),
        submittedAt = Instant.parse("2026-03-24T10:00:00Z"),
        status = OrderStatus.PENDING_RISK_CHECK,
        riskCheckResult = null,
        riskCheckDetails = null,
        fixSessionId = "SESSION-1",
        instrumentType = "CASH_EQUITY",
    )

    test("pending_risk_check is not a terminal status") {
        baseOrder.isTerminal shouldBe false
    }

    test("approved is not a terminal status") {
        baseOrder.copy(status = OrderStatus.APPROVED).isTerminal shouldBe false
    }

    test("sent is not a terminal status") {
        baseOrder.copy(status = OrderStatus.SENT).isTerminal shouldBe false
    }

    test("partial is not a terminal status") {
        baseOrder.copy(status = OrderStatus.PARTIAL).isTerminal shouldBe false
    }

    test("filled is a terminal status") {
        baseOrder.copy(status = OrderStatus.FILLED).isTerminal shouldBe true
    }

    test("cancelled is a terminal status") {
        baseOrder.copy(status = OrderStatus.CANCELLED).isTerminal shouldBe true
    }

    test("expired is a terminal status") {
        baseOrder.copy(status = OrderStatus.EXPIRED).isTerminal shouldBe true
    }

    test("rejected is a terminal status") {
        baseOrder.copy(status = OrderStatus.REJECTED).isTerminal shouldBe true
    }

    test("filledQuantity is zero when there are no fills") {
        baseOrder.filledQuantity.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("filledQuantity sums all fill quantities") {
        val fill1 = makePartialFill("fill-1", "ord-1", BigDecimal("40"), BigDecimal("149.90"))
        val fill2 = makePartialFill("fill-2", "ord-1", BigDecimal("60"), BigDecimal("150.10"))
        val order = baseOrder.copy(fills = listOf(fill1, fill2))
        order.filledQuantity.compareTo(BigDecimal("100")) shouldBe 0
    }

    test("averageFillPrice is null when there are no fills") {
        baseOrder.averageFillPrice.shouldBeNull()
    }

    test("averageFillPrice is the weighted average of fill prices") {
        val fill1 = makePartialFill("fill-1", "ord-1", BigDecimal("40"), BigDecimal("149.90"))
        val fill2 = makePartialFill("fill-2", "ord-1", BigDecimal("60"), BigDecimal("150.10"))
        val order = baseOrder.copy(fills = listOf(fill1, fill2))
        // (40 * 149.90 + 60 * 150.10) / 100 = (5996 + 9006) / 100 = 150.02
        val avgPrice = order.averageFillPrice
        avgPrice.shouldNotBeNull()
        avgPrice.compareTo(BigDecimal("150.02")) shouldBe 0
    }

    test("averageFillPrice for a single fill equals that fill price") {
        val fill = makePartialFill("fill-1", "ord-1", BigDecimal("100"), BigDecimal("150.50"))
        val order = baseOrder.copy(fills = listOf(fill))
        order.averageFillPrice?.compareTo(BigDecimal("150.50")) shouldBe 0
    }
})

private fun makePartialFill(
    fillId: String,
    orderId: String,
    qty: BigDecimal,
    price: BigDecimal,
): ExecutionFill = ExecutionFill(
    fillId = fillId,
    orderId = orderId,
    bookId = "book-1",
    instrumentId = "AAPL",
    fillTime = Instant.parse("2026-03-24T10:01:00Z"),
    fillQty = qty,
    fillPrice = price,
    fillType = FillType.PARTIAL,
    venue = "NYSE",
    cumulativeQty = qty,
    averagePrice = price,
    fixExecId = "exec-$fillId",
)
