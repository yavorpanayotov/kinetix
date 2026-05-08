package com.kinetix.position.service

import com.kinetix.common.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val PORTFOLIO = BookId("port-1")
private val AAPL = InstrumentId("AAPL")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)

private fun emptyPosition() = Position(
    bookId = PORTFOLIO,
    instrumentId = AAPL,
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal.ZERO,
    averageCost = Money.zero(USD),
    marketPrice = Money.zero(USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun buyTrade(
    quantity: String,
    price: String,
    tradeId: String = "t-buy",
) = Trade(
    tradeId = TradeId(tradeId),
    bookId = PORTFOLIO,
    instrumentId = AAPL,
    assetClass = AssetClass.EQUITY,
    side = Side.BUY,
    quantity = BigDecimal(quantity),
    price = usd(price),
    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun sellTrade(
    quantity: String,
    price: String,
    tradeId: String = "t-sell",
) = Trade(
    tradeId = TradeId(tradeId),
    bookId = PORTFOLIO,
    instrumentId = AAPL,
    assetClass = AssetClass.EQUITY,
    side = Side.SELL,
    quantity = BigDecimal(quantity),
    price = usd(price),
    tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class RealizedPnlTest : FunSpec({

    test("selling reduces position and records realized PnL") {
        val position = emptyPosition()
            .applyTrade(buyTrade("100", "150.00"))

        position.realizedPnl.amount.compareTo(BigDecimal.ZERO) shouldBe 0

        val afterSell = position.applyTrade(sellTrade("60", "170.00"))

        // Sold 60 @ 170, avg cost was 150. Realized = (170-150) * 60 = 1200
        afterSell.quantity.compareTo(BigDecimal("40")) shouldBe 0
        afterSell.realizedPnl.amount.compareTo(BigDecimal("1200")) shouldBe 0
    }

    test("realized PnL equals (sell price - avg cost) * sold quantity") {
        val position = emptyPosition()
            .applyTrade(buyTrade("200", "50.00"))
            .applyTrade(sellTrade("100", "75.00"))

        // (75 - 50) * 100 = 2500
        position.realizedPnl.amount.compareTo(BigDecimal("2500")) shouldBe 0
    }

    test("position tracks cumulative realized PnL") {
        val position = emptyPosition()
            .applyTrade(buyTrade("200", "50.00", "t-1"))
            .applyTrade(sellTrade("50", "60.00", "t-2"))
            .applyTrade(sellTrade("50", "70.00", "t-3"))

        // First sell: (60-50)*50 = 500
        // Second sell: (70-50)*50 = 1000
        // Total: 1500
        position.realizedPnl.amount.compareTo(BigDecimal("1500")) shouldBe 0
        position.quantity.compareTo(BigDecimal("100")) shouldBe 0
    }

    test("no realized PnL when increasing position") {
        val position = emptyPosition()
            .applyTrade(buyTrade("100", "50.00", "t-1"))
            .applyTrade(buyTrade("100", "60.00", "t-2"))

        position.realizedPnl.amount.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("realized PnL on full close") {
        val position = emptyPosition()
            .applyTrade(buyTrade("100", "50.00"))
            .applyTrade(sellTrade("100", "80.00"))

        // (80-50)*100 = 3000
        position.realizedPnl.amount.compareTo(BigDecimal("3000")) shouldBe 0
        position.quantity.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("position flip computes realized PnL only on the closed portion") {
        val position = emptyPosition()
            .applyTrade(buyTrade("100", "50.00"))
            .applyTrade(sellTrade("150", "55.00"))

        // Closed 100 units of the long at (55 - 50) * 100 = 500 realized P&L
        // New short of 50 units does not contribute to realized P&L
        position.realizedPnl.amount.compareTo(BigDecimal("500")) shouldBe 0
        position.quantity.compareTo(BigDecimal("-50")) shouldBe 0
        position.averageCost shouldBe usd("55.00")
    }

    test("realized PnL on short position covering") {
        // Start with a short by selling first
        val position = emptyPosition()
            .applyTrade(sellTrade("100", "80.00"))

        position.quantity.compareTo(BigDecimal("-100")) shouldBe 0
        position.realizedPnl.amount.compareTo(BigDecimal.ZERO) shouldBe 0

        // Cover the short by buying
        val covered = position.applyTrade(buyTrade("100", "60.00"))

        // Short sold at 80, bought back at 60: profit = (80-60)*100 on short side
        // For short: realized = (tradePrice - avgCost) * closedQty * sign(quantity)
        // = (60 - 80) * 100 * (-1) = 2000
        covered.realizedPnl.amount.compareTo(BigDecimal("2000")) shouldBe 0
        covered.quantity.compareTo(BigDecimal.ZERO) shouldBe 0
    }
})
