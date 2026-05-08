package com.kinetix.position.service

import com.kinetix.common.model.*
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.persistence.TradeEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun trade(
    tradeId: String = "t-1",
    bookId: String = "port-1",
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    side: Side = Side.BUY,
    quantity: String = "100",
    price: String = "150.00",
    counterpartyId: String? = null,
) = Trade(
    tradeId = TradeId(tradeId),
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    side = side,
    quantity = BigDecimal(quantity),
    price = Money(BigDecimal(price), USD),
    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
    counterpartyId = counterpartyId,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class CounterpartyExposureServiceTest : FunSpec({

    val tradeEventRepo = mockk<TradeEventRepository>()
    val service = CounterpartyExposureService(tradeEventRepo)

    beforeEach {
        clearMocks(tradeEventRepo)
    }

    test("aggregates exposure by counterparty") {
        val trades = listOf(
            trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00", counterpartyId = "CP-001"),
            trade(tradeId = "t-2", side = Side.BUY, quantity = "200", price = "150.00", counterpartyId = "CP-001"),
            trade(tradeId = "t-3", side = Side.SELL, quantity = "50", price = "150.00", counterpartyId = "CP-002"),
        )

        coEvery { tradeEventRepo.findByBookId(BookId("port-1")) } returns trades

        val result = service.getExposures(BookId("port-1"))

        result shouldHaveSize 2
        val cp1 = result.first { it.counterpartyId == "CP-001" }
        cp1.grossExposure shouldBe BigDecimal("45000.00")
        cp1.positionCount shouldBe 2

        val cp2 = result.first { it.counterpartyId == "CP-002" }
        cp2.grossExposure shouldBe BigDecimal("7500.00")
        cp2.positionCount shouldBe 1
    }

    test("returns empty for positions without counterparty") {
        val trades = listOf(
            trade(tradeId = "t-1", counterpartyId = null),
            trade(tradeId = "t-2", counterpartyId = null),
        )

        coEvery { tradeEventRepo.findByBookId(BookId("port-1")) } returns trades

        val result = service.getExposures(BookId("port-1"))

        result.shouldBeEmpty()
    }

    test("calculates net and gross exposure") {
        val trades = listOf(
            trade(tradeId = "t-1", side = Side.BUY, quantity = "100", price = "150.00", counterpartyId = "CP-001"),
            trade(tradeId = "t-2", side = Side.SELL, quantity = "60", price = "150.00", counterpartyId = "CP-001"),
        )

        coEvery { tradeEventRepo.findByBookId(BookId("port-1")) } returns trades

        val result = service.getExposures(BookId("port-1"))

        result shouldHaveSize 1
        val cp1 = result[0]
        cp1.counterpartyId shouldBe "CP-001"
        // Net: (100 * 150) - (60 * 150) = 15000 - 9000 = 6000
        cp1.netExposure shouldBe BigDecimal("6000.00")
        // Gross: (100 * 150) + (60 * 150) = 15000 + 9000 = 24000
        cp1.grossExposure shouldBe BigDecimal("24000.00")
        cp1.positionCount shouldBe 2
    }
})
