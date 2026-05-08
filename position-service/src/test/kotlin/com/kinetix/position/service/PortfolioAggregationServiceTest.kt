package com.kinetix.position.service

import com.kinetix.common.model.*
import com.kinetix.position.model.CurrencyExposure
import com.kinetix.position.model.PortfolioSummary
import com.kinetix.position.persistence.PositionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val EUR = Currency.getInstance("EUR")
private val GBP = Currency.getInstance("GBP")
private val PORTFOLIO = BookId("port-1")

private fun money(amount: String, currency: Currency) = Money(BigDecimal(amount), currency)

private fun position(
    instrumentId: String,
    currency: Currency,
    quantity: String = "100",
    averageCost: String = "150.00",
    marketPrice: String = "155.00",
) = Position(
    bookId = PORTFOLIO,
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal(quantity),
    averageCost = money(averageCost, currency),
    marketPrice = money(marketPrice, currency),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class PortfolioAggregationServiceTest : FunSpec({

    val positionRepo = mockk<PositionRepository>()
    val fxRateProvider = mockk<FxRateProvider>()
    val service = PortfolioAggregationService(positionRepo, fxRateProvider)

    beforeEach { clearMocks(positionRepo, fxRateProvider) }

    test("should aggregate positions in same currency without conversion") {
        val positions = listOf(
            position("AAPL", USD, quantity = "100", averageCost = "150.00", marketPrice = "155.00"),
            position("MSFT", USD, quantity = "50", averageCost = "300.00", marketPrice = "310.00"),
        )
        coEvery { positionRepo.findByBookId(PORTFOLIO) } returns positions
        coEvery { fxRateProvider.getRate(USD, USD) } returns BigDecimal.ONE

        val summary = service.aggregate(PORTFOLIO, USD)

        // AAPL market value = 100 * 155 = 15500, MSFT market value = 50 * 310 = 15500
        summary.totalNav shouldBe money("31000.00", USD)
        // AAPL pnl = (155 - 150) * 100 = 500, MSFT pnl = (310 - 300) * 50 = 500
        summary.totalUnrealizedPnl shouldBe money("1000.00", USD)
        summary.currencyBreakdown shouldHaveSize 1
        summary.currencyBreakdown[0].currency shouldBe USD
        summary.currencyBreakdown[0].fxRate shouldBe BigDecimal.ONE
    }

    test("should convert positions to base currency using FX rates") {
        val positions = listOf(
            position("AAPL", USD, quantity = "100", averageCost = "150.00", marketPrice = "155.00"),
            position("BMW", EUR, quantity = "50", averageCost = "80.00", marketPrice = "85.00"),
        )
        coEvery { positionRepo.findByBookId(PORTFOLIO) } returns positions
        coEvery { fxRateProvider.getRate(USD, USD) } returns BigDecimal.ONE
        coEvery { fxRateProvider.getRate(EUR, USD) } returns BigDecimal("1.10")

        val summary = service.aggregate(PORTFOLIO, USD)

        // AAPL: 100 * 155 = 15500 USD
        // BMW: 50 * 85 = 4250 EUR -> 4250 * 1.10 = 4675 USD
        summary.totalNav shouldBe money("20175.00", USD)
        summary.currencyBreakdown shouldHaveSize 2
    }

    test("should compute total NAV across all currencies") {
        val positions = listOf(
            position("AAPL", USD, quantity = "100", averageCost = "150.00", marketPrice = "160.00"),
            position("BMW", EUR, quantity = "200", averageCost = "80.00", marketPrice = "90.00"),
            position("BP", GBP, quantity = "300", averageCost = "5.00", marketPrice = "6.00"),
        )
        coEvery { positionRepo.findByBookId(PORTFOLIO) } returns positions
        coEvery { fxRateProvider.getRate(USD, USD) } returns BigDecimal.ONE
        coEvery { fxRateProvider.getRate(EUR, USD) } returns BigDecimal("1.10")
        coEvery { fxRateProvider.getRate(GBP, USD) } returns BigDecimal("1.25")

        val summary = service.aggregate(PORTFOLIO, USD)

        // AAPL: 100 * 160 = 16000 USD
        // BMW: 200 * 90 = 18000 EUR * 1.10 = 19800 USD
        // BP: 300 * 6 = 1800 GBP * 1.25 = 2250 USD
        summary.totalNav shouldBe money("38050.00", USD)
        summary.baseCurrency shouldBe USD
        summary.bookId shouldBe PORTFOLIO
    }

    test("should return per-currency breakdown") {
        val positions = listOf(
            position("AAPL", USD, quantity = "100", averageCost = "150.00", marketPrice = "160.00"),
            position("GOOGL", USD, quantity = "50", averageCost = "100.00", marketPrice = "110.00"),
            position("BMW", EUR, quantity = "200", averageCost = "80.00", marketPrice = "90.00"),
        )
        coEvery { positionRepo.findByBookId(PORTFOLIO) } returns positions
        coEvery { fxRateProvider.getRate(USD, USD) } returns BigDecimal.ONE
        coEvery { fxRateProvider.getRate(EUR, USD) } returns BigDecimal("1.10")

        val summary = service.aggregate(PORTFOLIO, USD)

        summary.currencyBreakdown shouldHaveSize 2

        val usdExposure = summary.currencyBreakdown.first { it.currency == USD }
        // AAPL: 100 * 160 = 16000, GOOGL: 50 * 110 = 5500 => total = 21500
        usdExposure.localValue shouldBe money("21500.00", USD)
        usdExposure.baseValue shouldBe money("21500.00", USD)
        usdExposure.fxRate shouldBe BigDecimal.ONE

        val eurExposure = summary.currencyBreakdown.first { it.currency == EUR }
        // BMW: 200 * 90 = 18000 EUR
        eurExposure.localValue shouldBe money("18000.00", EUR)
        eurExposure.baseValue shouldBe money("19800.00", USD)
        eurExposure.fxRate shouldBe BigDecimal("1.10")
    }

    test("should use USD as default base currency") {
        val positions = listOf(
            position("AAPL", USD, quantity = "10", averageCost = "100.00", marketPrice = "100.00"),
        )
        coEvery { positionRepo.findByBookId(PORTFOLIO) } returns positions
        coEvery { fxRateProvider.getRate(USD, USD) } returns BigDecimal.ONE

        val summary = service.aggregate(PORTFOLIO)

        summary.baseCurrency shouldBe USD
    }
})
