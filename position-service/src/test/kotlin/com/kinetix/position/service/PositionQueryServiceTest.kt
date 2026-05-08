package com.kinetix.position.service

import com.kinetix.common.model.*
import com.kinetix.position.persistence.PositionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val PORTFOLIO = BookId("port-1")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)

private fun position(
    bookId: BookId = PORTFOLIO,
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    averageCost: String = "150.00",
    marketPrice: String = "155.00",
) = Position(
    bookId = bookId,
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = usd(averageCost),
    marketPrice = usd(marketPrice),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class PositionQueryServiceTest : FunSpec({

    val positionRepo = mockk<PositionRepository>()
    val service = PositionQueryService(positionRepo)

    beforeEach { clearMocks(positionRepo) }

    test("returns positions for portfolio") {
        val positions = listOf(
            position(instrumentId = "AAPL"),
            position(instrumentId = "MSFT", averageCost = "300.00", marketPrice = "310.00"),
        )
        coEvery { positionRepo.findByBookId(PORTFOLIO) } returns positions

        val result = service.handle(GetPositionsQuery(PORTFOLIO))

        result shouldHaveSize 2
        result[0].instrumentId shouldBe InstrumentId("AAPL")
        result[1].instrumentId shouldBe InstrumentId("MSFT")
    }

    test("returns empty list for unknown portfolio") {
        coEvery { positionRepo.findByBookId(BookId("unknown")) } returns emptyList()

        val result = service.handle(GetPositionsQuery(BookId("unknown")))

        result shouldHaveSize 0
    }

    test("delegates to PositionRepository.findByBookId") {
        coEvery { positionRepo.findByBookId(any()) } returns emptyList()

        service.handle(GetPositionsQuery(PORTFOLIO))

        coVerify(exactly = 1) { positionRepo.findByBookId(PORTFOLIO) }
    }

    test("returned positions have computed P&L fields accessible") {
        val positions = listOf(position(quantity = "100", averageCost = "50.00", marketPrice = "55.00"))
        coEvery { positionRepo.findByBookId(any()) } returns positions

        val result = service.handle(GetPositionsQuery(PORTFOLIO))

        result[0].unrealizedPnl shouldBe usd("500.00")
        result[0].marketValue shouldBe usd("5500.00")
    }
})
