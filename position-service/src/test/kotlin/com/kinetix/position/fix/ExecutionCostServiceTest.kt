package com.kinetix.position.fix

import com.kinetix.common.model.Side
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class ExecutionCostServiceTest : FunSpec({

    val service = ExecutionCostService()

    val filledAt = Instant.parse("2026-03-24T10:05:00Z")

    fun makeOrder(
        side: Side,
        arrivalPrice: BigDecimal,
        fills: List<ExecutionFill>,
    ) = Order(
        orderId = "ord-test",
        bookId = "book-1",
        instrumentId = "AAPL",
        side = side,
        quantity = fills.fold(BigDecimal.ZERO) { acc, f -> acc + f.fillQty },
        orderType = "LIMIT",
        limitPrice = null,
        arrivalPrice = arrivalPrice,
        submittedAt = Instant.parse("2026-03-24T10:00:00Z"),
        status = OrderStatus.FILLED,
        riskCheckResult = "APPROVED",
        riskCheckDetails = null,
        fixSessionId = "SESSION-1",
        fills = fills,
        instrumentType = "CASH_EQUITY",
    )

    fun makeFill(qty: BigDecimal, price: BigDecimal) = ExecutionFill(
        fillId = "fill-${qty.toPlainString()}",
        orderId = "ord-test",
        bookId = "book-1",
        instrumentId = "AAPL",
        fillTime = filledAt,
        fillQty = qty,
        fillPrice = price,
        fillType = FillType.FULL,
        venue = "NYSE",
        cumulativeQty = qty,
        averagePrice = price,
        fixExecId = "exec-1",
    )

    test("slippage is zero when average fill price equals arrival price") {
        val order = makeOrder(
            side = Side.BUY,
            arrivalPrice = BigDecimal("150.00"),
            fills = listOf(makeFill(BigDecimal("100"), BigDecimal("150.00"))),
        )
        val analysis = service.compute(order, filledAt)
        analysis.metrics.slippageBps.compareTo(BigDecimal.ZERO) shouldBe 0
        analysis.metrics.totalCostBps.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("positive slippage for a BUY when fill price is above arrival price") {
        // arrivalPrice=100, avgFillPrice=100.10 → slippage = (0.10/100)*10000 = 10 bps
        val order = makeOrder(
            side = Side.BUY,
            arrivalPrice = BigDecimal("100.00"),
            fills = listOf(makeFill(BigDecimal("100"), BigDecimal("100.10"))),
        )
        val analysis = service.compute(order, filledAt)
        analysis.metrics.slippageBps.compareTo(BigDecimal("10.0000000000")) shouldBe 0
    }

    test("negative slippage for a BUY when fill price is below arrival price (savings)") {
        // arrivalPrice=100, avgFillPrice=99.90 → slippage = (-0.10/100)*10000 = -10 bps
        val order = makeOrder(
            side = Side.BUY,
            arrivalPrice = BigDecimal("100.00"),
            fills = listOf(makeFill(BigDecimal("100"), BigDecimal("99.90"))),
        )
        val analysis = service.compute(order, filledAt)
        analysis.metrics.slippageBps.signum() shouldBe -1
    }

    test("positive slippage for a SELL when fill price is below arrival price (paid more than expected)") {
        // For a SELL: slippage = (arrivalPrice - avgFillPrice) / arrivalPrice * 10000
        // arrivalPrice=100, avgFillPrice=99.90 → slippage = (0.10/100)*10000 = 10 bps
        val order = makeOrder(
            side = Side.SELL,
            arrivalPrice = BigDecimal("100.00"),
            fills = listOf(makeFill(BigDecimal("100"), BigDecimal("99.90"))),
        )
        val analysis = service.compute(order, filledAt)
        analysis.metrics.slippageBps.signum() shouldBe 1
    }

    test("slippage is computed from weighted average of multiple partial fills") {
        // fill1: 40 @ 100.10, fill2: 60 @ 100.20
        // avgFill = (40*100.10 + 60*100.20)/100 = (4004 + 6012)/100 = 100.16
        // slippage = (0.16/100)*10000 = 16 bps
        val order = makeOrder(
            side = Side.BUY,
            arrivalPrice = BigDecimal("100.00"),
            fills = listOf(
                makeFill(BigDecimal("40"), BigDecimal("100.10")),
                makeFill(BigDecimal("60"), BigDecimal("100.20")),
            ),
        )
        val analysis = service.compute(order, filledAt)
        analysis.metrics.slippageBps.compareTo(BigDecimal("16.0000000000")) shouldBe 0
    }

    test("analysis records arrival price, average fill price, and total qty") {
        val order = makeOrder(
            side = Side.BUY,
            arrivalPrice = BigDecimal("150.00"),
            fills = listOf(makeFill(BigDecimal("100"), BigDecimal("150.50"))),
        )
        val analysis = service.compute(order, filledAt)
        analysis.arrivalPrice.compareTo(BigDecimal("150.00")) shouldBe 0
        analysis.averageFillPrice.compareTo(BigDecimal("150.50")) shouldBe 0
        analysis.totalQty.compareTo(BigDecimal("100")) shouldBe 0
        analysis.orderId shouldBe "ord-test"
        analysis.bookId shouldBe "book-1"
        analysis.instrumentId shouldBe "AAPL"
    }

    test("market_impact_bps and timing_cost_bps are null (not computed yet)") {
        val order = makeOrder(
            side = Side.BUY,
            arrivalPrice = BigDecimal("100.00"),
            fills = listOf(makeFill(BigDecimal("100"), BigDecimal("100.00"))),
        )
        val analysis = service.compute(order, filledAt)
        analysis.metrics.marketImpactBps shouldBe null
        analysis.metrics.timingCostBps shouldBe null
    }
})
