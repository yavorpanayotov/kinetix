package com.kinetix.position.service

import com.kinetix.common.model.*
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.position.kafka.TradeEventPublisher
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.persistence.TradeEventRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val PORTFOLIO = BookId("port-1")
private val AAPL = InstrumentId("AAPL")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)

private fun trade(
    tradeId: String = "t-1",
    bookId: BookId = PORTFOLIO,
    instrumentId: InstrumentId = AAPL,
    assetClass: AssetClass = AssetClass.EQUITY,
    side: Side = Side.BUY,
    quantity: String = "100",
    price: String = "150.00",
    tradedAt: Instant = Instant.parse("2025-01-15T10:00:00Z"),
    eventType: TradeEventType = TradeEventType.NEW,
    status: TradeStatus = TradeStatus.LIVE,
    originalTradeId: TradeId? = null,
) = Trade(
    tradeId = TradeId(tradeId),
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = assetClass,
    side = side,
    quantity = BigDecimal(quantity),
    price = usd(price),
    tradedAt = tradedAt,
    eventType = eventType,
    status = status,
    originalTradeId = originalTradeId,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun position(
    bookId: BookId = PORTFOLIO,
    instrumentId: InstrumentId = AAPL,
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    averageCost: String = "150.00",
    marketPrice: String = "155.00",
    realizedPnl: String = "0",
) = Position(
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = usd(averageCost),
    marketPrice = usd(marketPrice),
    realizedPnl = usd(realizedPnl),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private val noOpTransaction = object : TransactionalRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class TradeLifecycleServiceTest : FunSpec({

    val tradeRepo = mockk<TradeEventRepository>()
    val positionRepo = mockk<PositionRepository>()
    val publisher = mockk<TradeEventPublisher>()
    val service = TradeLifecycleService(tradeRepo, positionRepo, noOpTransaction, publisher)

    beforeEach {
        clearMocks(tradeRepo, positionRepo, publisher)
        coEvery { publisher.publish(any()) } just runs
    }

    test("amending a trade should update position with new values") {
        val originalTrade = trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00")
        val existingPosition = position(quantity = "100", averageCost = "150.00")

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns originalTrade
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.AMENDED) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition
        coEvery { positionRepo.save(any()) } just runs

        val command = AmendTradeCommand(
            originalTradeId = TradeId("t-1"),
            newTradeId = TradeId("t-1-amend"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("200"),
            price = usd("160.00"),
            tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
            instrumentType = "CASH_EQUITY",
        )

        val result = service.handleAmend(command)

        // Position should reflect: reversed -100 @ 150, then applied +200 @ 160
        // After reversal: qty=0, after new trade: qty=200, avgCost=160
        result.position.quantity.compareTo(BigDecimal("200")) shouldBe 0
        result.position.averageCost shouldBe usd("160.00")
        result.trade.eventType shouldBe TradeEventType.AMEND
        result.trade.originalTradeId shouldBe TradeId("t-1")
    }

    test("amending a trade should mark original trade as AMENDED") {
        val originalTrade = trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00")
        val existingPosition = position(quantity = "100", averageCost = "150.00")

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns originalTrade
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.AMENDED) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition
        coEvery { positionRepo.save(any()) } just runs

        val command = AmendTradeCommand(
            originalTradeId = TradeId("t-1"),
            newTradeId = TradeId("t-1-amend"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("200"),
            price = usd("160.00"),
            tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
            instrumentType = "CASH_EQUITY",
        )

        service.handleAmend(command)

        coVerify { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.AMENDED) }
    }

    test("cancelling a trade should reverse its effect on position") {
        val originalTrade = trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00")
        val existingPosition = position(quantity = "100", averageCost = "150.00")

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns originalTrade
        coEvery { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.CANCELLED) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition
        coEvery { positionRepo.save(any()) } just runs

        val command = CancelTradeCommand(
            tradeId = TradeId("t-1"),
            bookId = PORTFOLIO,
        )

        val result = service.handleCancel(command)

        // Reversing a BUY 100 should leave position at 0
        result.position.quantity.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("cancelling a trade should mark trade as CANCELLED") {
        val originalTrade = trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00")
        val existingPosition = position(quantity = "100", averageCost = "150.00")

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns originalTrade
        coEvery { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.CANCELLED) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition
        coEvery { positionRepo.save(any()) } just runs

        val command = CancelTradeCommand(
            tradeId = TradeId("t-1"),
            bookId = PORTFOLIO,
        )

        service.handleCancel(command)

        coVerify { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.CANCELLED) }
    }

    test("cancelling an already cancelled trade returns existing result idempotently") {
        val cancelledTrade = trade(tradeId = "t-1", status = TradeStatus.CANCELLED)
        val existingPosition = position(quantity = "0", averageCost = "0")

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns cancelledTrade
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition

        val command = CancelTradeCommand(
            tradeId = TradeId("t-1"),
            bookId = PORTFOLIO,
        )

        val result = service.handleCancel(command)
        result.trade.status shouldBe TradeStatus.CANCELLED
        result.position shouldBe existingPosition
        coVerify(exactly = 0) { tradeRepo.updateStatus(any(), any()) }
        coVerify(exactly = 0) { positionRepo.save(any()) }
        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    test("amending an already amended trade with same newTradeId returns existing result idempotently") {
        val amendedOriginal = trade(tradeId = "t-1", status = TradeStatus.AMENDED)
        val existingAmendTrade = trade(tradeId = "t-1-amend", eventType = TradeEventType.AMEND)
        val existingPosition = position(quantity = "200", averageCost = "160.00")

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns amendedOriginal
        coEvery { tradeRepo.findByTradeId(TradeId("t-1-amend")) } returns existingAmendTrade
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition

        val command = AmendTradeCommand(
            originalTradeId = TradeId("t-1"),
            newTradeId = TradeId("t-1-amend"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("200"),
            price = usd("160.00"),
            tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
            instrumentType = "CASH_EQUITY",
        )

        val result = service.handleAmend(command)
        result.trade shouldBe existingAmendTrade
        result.position shouldBe existingPosition
        coVerify(exactly = 0) { tradeRepo.updateStatus(any(), any()) }
        coVerify(exactly = 0) { tradeRepo.save(any()) }
        coVerify(exactly = 0) { positionRepo.save(any()) }
        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    test("amending an already amended trade with different newTradeId should fail") {
        val amendedOriginal = trade(tradeId = "t-1", status = TradeStatus.AMENDED)

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns amendedOriginal
        coEvery { tradeRepo.findByTradeId(TradeId("t-1-different")) } returns null

        val command = AmendTradeCommand(
            originalTradeId = TradeId("t-1"),
            newTradeId = TradeId("t-1-different"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("200"),
            price = usd("160.00"),
            tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
            instrumentType = "CASH_EQUITY",
        )

        shouldThrow<InvalidTradeStateException> {
            service.handleAmend(command)
        }
    }

    test("amending a cancelled trade should fail") {
        val cancelledTrade = trade(tradeId = "t-1", status = TradeStatus.CANCELLED)

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns cancelledTrade
        coEvery { tradeRepo.findByTradeId(TradeId("t-1-amend")) } returns null

        val command = AmendTradeCommand(
            originalTradeId = TradeId("t-1"),
            newTradeId = TradeId("t-1-amend"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("200"),
            price = usd("160.00"),
            tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
            instrumentType = "CASH_EQUITY",
        )

        shouldThrow<InvalidTradeStateException> {
            service.handleAmend(command)
        }
    }

    test("cancelling a non-existent trade throws TradeNotFoundException") {
        coEvery { tradeRepo.findByTradeId(TradeId("unknown")) } returns null

        val command = CancelTradeCommand(
            tradeId = TradeId("unknown"),
            bookId = PORTFOLIO,
        )

        val ex = shouldThrow<TradeNotFoundException> {
            service.handleCancel(command)
        }
        ex.tradeId shouldBe "unknown"
    }

    test("amending a non-existent trade throws TradeNotFoundException") {
        coEvery { tradeRepo.findByTradeId(TradeId("unknown")) } returns null

        val command = AmendTradeCommand(
            originalTradeId = TradeId("unknown"),
            newTradeId = TradeId("unknown-amend"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            price = usd("150.00"),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            instrumentType = "CASH_EQUITY",
        )

        val ex = shouldThrow<TradeNotFoundException> {
            service.handleAmend(command)
        }
        ex.tradeId shouldBe "unknown"
    }

    test("amend publishes trade event") {
        val originalTrade = trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00")
        val existingPosition = position(quantity = "100", averageCost = "150.00")

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns originalTrade
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.AMENDED) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition
        coEvery { positionRepo.save(any()) } just runs

        val command = AmendTradeCommand(
            originalTradeId = TradeId("t-1"),
            newTradeId = TradeId("t-1-amend"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("200"),
            price = usd("160.00"),
            tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
            instrumentType = "CASH_EQUITY",
        )

        service.handleAmend(command)

        coVerify(exactly = 1) { publisher.publish(match { it.trade.eventType == TradeEventType.AMEND }) }
    }

    test("amend trade preserves instrumentType from original trade") {
        val originalTrade = trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00")
            .copy(instrumentType = InstrumentTypeCode.CASH_EQUITY)
        val existingPosition = position(quantity = "100", averageCost = "150.00")
            .copy(instrumentType = InstrumentTypeCode.CASH_EQUITY)

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns originalTrade
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.AMENDED) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition
        coEvery { positionRepo.save(any()) } just runs

        val command = AmendTradeCommand(
            originalTradeId = TradeId("t-1"),
            newTradeId = TradeId("t-1-amend"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("200"),
            price = usd("160.00"),
            tradedAt = Instant.parse("2025-01-15T11:00:00Z"),
            instrumentType = "CASH_EQUITY",
        )

        val result = service.handleAmend(command)

        result.trade.instrumentType shouldBe InstrumentTypeCode.CASH_EQUITY
        result.position.instrumentType shouldBe InstrumentTypeCode.CASH_EQUITY
    }

    test("cancel trade preserves instrumentType on position") {
        val originalTrade = trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00")
            .copy(instrumentType = InstrumentTypeCode.EQUITY_OPTION)
        val existingPosition = position(quantity = "100", averageCost = "150.00")
            .copy(instrumentType = InstrumentTypeCode.EQUITY_OPTION)

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns originalTrade
        coEvery { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.CANCELLED) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition
        coEvery { positionRepo.save(any()) } just runs

        val command = CancelTradeCommand(
            tradeId = TradeId("t-1"),
            bookId = PORTFOLIO,
        )

        val result = service.handleCancel(command)

        result.position.instrumentType shouldBe InstrumentTypeCode.EQUITY_OPTION
    }

    test("cancel publishes trade event for the cancelled trade") {
        val originalTrade = trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00")
        val existingPosition = position(quantity = "100", averageCost = "150.00")

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns originalTrade
        coEvery { tradeRepo.updateStatus(TradeId("t-1"), TradeStatus.CANCELLED) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition
        coEvery { positionRepo.save(any()) } just runs

        val command = CancelTradeCommand(
            tradeId = TradeId("t-1"),
            bookId = PORTFOLIO,
        )

        service.handleCancel(command)

        coVerify(exactly = 1) { publisher.publish(match { it.trade.status == TradeStatus.CANCELLED }) }
    }
})
