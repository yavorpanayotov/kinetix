package com.kinetix.position.service

import com.kinetix.common.model.*
import com.kinetix.position.kafka.TradeEventPublisher
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.persistence.TradeEventRepository
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

private fun command(
    tradeId: String = "t-1",
    bookId: BookId = PORTFOLIO,
    instrumentId: InstrumentId = AAPL,
    assetClass: AssetClass = AssetClass.EQUITY,
    side: Side = Side.BUY,
    quantity: String = "100",
    price: String = "150.00",
    tradedAt: Instant = Instant.parse("2025-01-15T10:00:00Z"),
) = BookTradeCommand(
    tradeId = TradeId(tradeId),
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = assetClass,
    side = side,
    quantity = BigDecimal(quantity),
    price = usd(price),
    tradedAt = tradedAt,
    instrumentType = "CASH_EQUITY",
)

private fun position(
    bookId: BookId = PORTFOLIO,
    instrumentId: InstrumentId = AAPL,
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    averageCost: String = "150.00",
    marketPrice: String = "155.00",
) = Position(
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = usd(averageCost),
    marketPrice = usd(marketPrice),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private val noOpTransaction = object : TransactionalRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class TradeBookingServiceTest : FunSpec({

    val tradeRepo = mockk<TradeEventRepository>()
    val positionRepo = mockk<PositionRepository>()
    val publisher = mockk<TradeEventPublisher>()
    val service = TradeBookingService(tradeRepo, positionRepo, noOpTransaction, publisher)

    beforeEach {
        clearMocks(tradeRepo, positionRepo, publisher)
        coEvery { publisher.publish(any()) } just runs
    }

    test("books a new trade and creates position from empty") {
        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns null
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns null
        coEvery { positionRepo.save(any()) } just runs

        val result = service.handle(command())

        result.trade.tradeId shouldBe TradeId("t-1")
        result.trade.side shouldBe Side.BUY
        result.position.quantity.compareTo(BigDecimal("100")) shouldBe 0
        result.position.averageCost shouldBe usd("150.00")

        coVerify(exactly = 1) { tradeRepo.save(any()) }
        coVerify(exactly = 1) { positionRepo.save(any()) }
    }

    test("books a trade and updates existing position with weighted average cost") {
        val existing = position(quantity = "100", averageCost = "140.00", marketPrice = "155.00")

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns null
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existing
        coEvery { positionRepo.save(any()) } just runs

        val result = service.handle(command())

        // 100 @ 140 + 100 @ 150 = 200 @ 145
        result.position.quantity.compareTo(BigDecimal("200")) shouldBe 0
        result.position.averageCost.amount.compareTo(BigDecimal("145")) shouldBe 0
    }

    test("duplicate tradeId returns existing state without re-saving") {
        val existingTrade = Trade(
            tradeId = TradeId("t-1"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            price = usd("150.00"),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val existingPosition = position()

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns existingTrade
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existingPosition

        val result = service.handle(command())

        result.trade shouldBe existingTrade
        result.position shouldBe existingPosition

        coVerify(exactly = 0) { tradeRepo.save(any()) }
        coVerify(exactly = 0) { positionRepo.save(any()) }
    }

    test("SELL trade reduces existing position") {
        val existing = position(quantity = "100", averageCost = "150.00", marketPrice = "155.00")

        coEvery { tradeRepo.findByTradeId(any()) } returns null
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns existing
        coEvery { positionRepo.save(any()) } just runs

        val result = service.handle(command(side = Side.SELL, quantity = "30"))

        result.position.quantity.compareTo(BigDecimal("70")) shouldBe 0
        result.position.averageCost shouldBe usd("150.00") // cost unchanged on partial close
    }

    test("saves the position returned by applyTrade") {
        coEvery { tradeRepo.findByTradeId(any()) } returns null
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { positionRepo.findByKey(any(), any()) } returns null
        val savedPosition = slot<Position>()
        coEvery { positionRepo.save(capture(savedPosition)) } just runs

        service.handle(command(quantity = "50", price = "200.00"))

        savedPosition.captured.quantity.compareTo(BigDecimal("50")) shouldBe 0
        savedPosition.captured.averageCost shouldBe usd("200.00")
    }

    test("publishes trade event for new trade") {
        coEvery { tradeRepo.findByTradeId(any()) } returns null
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { positionRepo.findByKey(any(), any()) } returns null
        coEvery { positionRepo.save(any()) } just runs

        service.handle(command())

        coVerify(exactly = 1) { publisher.publish(match { it.trade.tradeId == TradeId("t-1") }) }
    }

    test("publishes userId and userRole from command on the trade event") {
        coEvery { tradeRepo.findByTradeId(any()) } returns null
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { positionRepo.findByKey(any(), any()) } returns null
        coEvery { positionRepo.save(any()) } just runs

        val capturedEvent = slot<com.kinetix.common.model.TradeEvent>()
        coEvery { publisher.publish(capture(capturedEvent)) } just runs

        service.handle(command().copy(userId = "alice", userRole = "TRADER"))

        capturedEvent.captured.userId shouldBe "alice"
        capturedEvent.captured.userRole shouldBe "TRADER"
    }

    test("does NOT publish trade event for duplicate trade") {
        val existingTrade = Trade(
            tradeId = TradeId("t-1"),
            bookId = PORTFOLIO,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            side = Side.BUY,
            quantity = BigDecimal("100"),
            price = usd("150.00"),
            tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )

        coEvery { tradeRepo.findByTradeId(TradeId("t-1")) } returns existingTrade
        coEvery { positionRepo.findByKey(PORTFOLIO, AAPL) } returns position()

        service.handle(command())

        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    test("validates traderId via TraderValidator when present") {
        val validator = mockk<com.kinetix.position.trader.TraderValidator>()
        every { validator.validate(TraderId("tr-eg-001")) } just runs

        val validatingService = TradeBookingService(
            tradeRepo, positionRepo, noOpTransaction, publisher, traderValidator = validator,
        )

        coEvery { tradeRepo.findByTradeId(any()) } returns null
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { positionRepo.findByKey(any(), any()) } returns null
        coEvery { positionRepo.save(any()) } just runs

        validatingService.handle(command().copy(traderId = TraderId("tr-eg-001")))

        verify(exactly = 1) { validator.validate(TraderId("tr-eg-001")) }
    }

    test("UnknownTraderException from validator stops the booking flow") {
        val validator = mockk<com.kinetix.position.trader.TraderValidator>()
        every { validator.validate(TraderId("ghost")) } throws
            com.kinetix.position.trader.UnknownTraderException(TraderId("ghost"))

        val validatingService = TradeBookingService(
            tradeRepo, positionRepo, noOpTransaction, publisher, traderValidator = validator,
        )

        io.kotest.assertions.throwables.shouldThrow<com.kinetix.position.trader.UnknownTraderException> {
            validatingService.handle(command().copy(traderId = TraderId("ghost")))
        }

        coVerify(exactly = 0) { tradeRepo.save(any()) }
        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    test("validator is not invoked when traderId is null") {
        val validator = mockk<com.kinetix.position.trader.TraderValidator>()
        val validatingService = TradeBookingService(
            tradeRepo, positionRepo, noOpTransaction, publisher, traderValidator = validator,
        )

        coEvery { tradeRepo.findByTradeId(any()) } returns null
        coEvery { tradeRepo.save(any()) } just runs
        coEvery { positionRepo.findByKey(any(), any()) } returns null
        coEvery { positionRepo.save(any()) } just runs

        validatingService.handle(command())

        verify(exactly = 0) { validator.validate(any()) }
    }
})
