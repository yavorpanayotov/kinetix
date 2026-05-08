package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun position(
    bookId: String = "port-1",
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    averageCost: String = "150.00",
    marketPrice: String = "170.00",
) = Position(
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = Money(BigDecimal(averageCost), USD),
    marketPrice = Money(BigDecimal(marketPrice), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun hypotheticalTrade(
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    side: Side = Side.BUY,
    quantity: String = "50",
    price: String = "175.00",
) = HypotheticalTrade(
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    side = side,
    quantity = BigDecimal(quantity),
    price = Money(BigDecimal(price), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun valuationResult(
    bookId: String = "port-1",
    varValue: Double = 5000.0,
    expectedShortfall: Double = 6250.0,
    greeks: GreeksResult? = null,
    positionRisk: List<PositionRisk> = emptyList(),
) = ValuationResult(
    bookId = BookId(bookId),
    calculationType = CalculationType.PARAMETRIC,
    confidenceLevel = ConfidenceLevel.CL_95,
    varValue = varValue,
    expectedShortfall = expectedShortfall,
    componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, varValue, 100.0)),
    greeks = greeks,
    calculatedAt = Instant.now(),
    computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
    positionRisk = positionRisk,
)

class WhatIfAnalysisServiceTest : FunSpec({

    val positionProvider = mockk<PositionProvider>()
    val riskEngineClient = mockk<RiskEngineClient>()
    val service = WhatIfAnalysisService(positionProvider, riskEngineClient)

    beforeEach {
        clearMocks(positionProvider, riskEngineClient)
    }

    context("applyHypotheticalTrades") {

        test("increases quantity and recalculates weighted average cost when buying an existing position") {
            val positions = listOf(position(quantity = "100", averageCost = "150.00", marketPrice = "170.00"))
            val trades = listOf(hypotheticalTrade(side = Side.BUY, quantity = "50", price = "180.00"))

            val result = service.applyHypotheticalTrades(positions, trades)

            result shouldHaveSize 1
            val pos = result[0]
            pos.instrumentId shouldBe InstrumentId("AAPL")
            pos.quantity shouldBe BigDecimal("150")
            // Weighted avg: (150 * 100 + 180 * 50) / 150 = 24000 / 150 = 160.00
            pos.averageCost.amount.setScale(2) shouldBe BigDecimal("160.00")
        }

        test("decreases quantity when selling part of an existing position") {
            val positions = listOf(position(quantity = "100", averageCost = "150.00"))
            val trades = listOf(hypotheticalTrade(side = Side.SELL, quantity = "30", price = "175.00"))

            val result = service.applyHypotheticalTrades(positions, trades)

            result shouldHaveSize 1
            val pos = result[0]
            pos.quantity shouldBe BigDecimal("70")
            // Average cost stays the same when reducing position
            pos.averageCost.amount.setScale(2) shouldBe BigDecimal("150.00")
        }

        test("creates a short position when selling more than held") {
            val positions = listOf(position(quantity = "100", averageCost = "150.00"))
            val trades = listOf(hypotheticalTrade(side = Side.SELL, quantity = "150", price = "175.00"))

            val result = service.applyHypotheticalTrades(positions, trades)

            result shouldHaveSize 1
            val pos = result[0]
            pos.quantity shouldBe BigDecimal("-50")
            // Crossing zero resets avg cost to the trade price
            pos.averageCost.amount.setScale(2) shouldBe BigDecimal("175.00")
        }

        test("creates a new position for an instrument not in the portfolio") {
            val positions = listOf(position(instrumentId = "AAPL"))
            val trades = listOf(hypotheticalTrade(instrumentId = "TSLA", side = Side.BUY, quantity = "200", price = "250.00"))

            val result = service.applyHypotheticalTrades(positions, trades)

            result shouldHaveSize 2
            val tsla = result.first { it.instrumentId == InstrumentId("TSLA") }
            tsla.quantity shouldBe BigDecimal("200")
            tsla.averageCost.amount.setScale(2) shouldBe BigDecimal("250.00")
            tsla.marketPrice.amount.setScale(2) shouldBe BigDecimal("250.00")
            tsla.assetClass shouldBe AssetClass.EQUITY
            tsla.bookId shouldBe BookId("port-1")
        }

        test("removes position when trade exactly closes it to zero quantity") {
            val positions = listOf(position(quantity = "100", averageCost = "150.00"))
            val trades = listOf(hypotheticalTrade(side = Side.SELL, quantity = "100", price = "175.00"))

            val result = service.applyHypotheticalTrades(positions, trades)

            result shouldHaveSize 0
        }

        test("applies multiple hypothetical trades sequentially") {
            val positions = listOf(
                position(instrumentId = "AAPL", quantity = "100", averageCost = "150.00"),
                position(instrumentId = "TSLA", quantity = "50", averageCost = "200.00", marketPrice = "220.00"),
            )
            val trades = listOf(
                hypotheticalTrade(instrumentId = "AAPL", side = Side.BUY, quantity = "50", price = "180.00"),
                hypotheticalTrade(instrumentId = "TSLA", side = Side.SELL, quantity = "30", price = "230.00"),
                hypotheticalTrade(instrumentId = "GOOG", assetClass = AssetClass.EQUITY, side = Side.BUY, quantity = "25", price = "140.00"),
            )

            val result = service.applyHypotheticalTrades(positions, trades)

            result shouldHaveSize 3

            val aapl = result.first { it.instrumentId == InstrumentId("AAPL") }
            aapl.quantity shouldBe BigDecimal("150")

            val tsla = result.first { it.instrumentId == InstrumentId("TSLA") }
            tsla.quantity shouldBe BigDecimal("20")

            val goog = result.first { it.instrumentId == InstrumentId("GOOG") }
            goog.quantity shouldBe BigDecimal("25")
            goog.averageCost.amount.setScale(2) shouldBe BigDecimal("140.00")
        }

        test("sells a new instrument to create a short position from scratch") {
            val positions = listOf(position(instrumentId = "AAPL"))
            val trades = listOf(hypotheticalTrade(instrumentId = "TSLA", side = Side.SELL, quantity = "100", price = "250.00"))

            val result = service.applyHypotheticalTrades(positions, trades)

            result shouldHaveSize 2
            val tsla = result.first { it.instrumentId == InstrumentId("TSLA") }
            tsla.quantity shouldBe BigDecimal("-100")
            tsla.averageCost.amount.setScale(2) shouldBe BigDecimal("250.00")
        }
    }

    context("analyzeWhatIf") {

        test("fetches positions, runs base and hypothetical VaR, and returns comparison result") {
            val positions = listOf(position(quantity = "100", averageCost = "150.00", marketPrice = "170.00"))
            val trades = listOf(hypotheticalTrade(side = Side.BUY, quantity = "50", price = "180.00"))

            val baseResult = valuationResult(varValue = 5000.0, expectedShortfall = 6250.0)
            val hypotheticalResult = valuationResult(varValue = 7000.0, expectedShortfall = 8750.0)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), eq(positions), any()) } returns baseResult
            coEvery { riskEngineClient.valuate(any(), neq(positions), any()) } returns hypotheticalResult

            val result = service.analyzeWhatIf(
                bookId = BookId("port-1"),
                hypotheticalTrades = trades,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            result.baseVaR shouldBeExactly 5000.0
            result.baseExpectedShortfall shouldBeExactly 6250.0
            result.hypotheticalVaR shouldBeExactly 7000.0
            result.hypotheticalExpectedShortfall shouldBeExactly 8750.0
            result.varChange shouldBeExactly 2000.0
            result.esChange shouldBeExactly 2500.0

            coVerify { positionProvider.getPositions(BookId("port-1")) }
            coVerify(exactly = 2) { riskEngineClient.valuate(any(), any(), any()) }
        }

        test("returns negative varChange when hypothetical trade reduces risk") {
            val positions = listOf(
                position(instrumentId = "AAPL", quantity = "100", averageCost = "150.00", marketPrice = "170.00"),
            )
            // Selling reduces exposure
            val trades = listOf(hypotheticalTrade(instrumentId = "AAPL", side = Side.SELL, quantity = "50", price = "170.00"))

            val baseResult = valuationResult(varValue = 5000.0, expectedShortfall = 6250.0)
            val hypotheticalResult = valuationResult(varValue = 2500.0, expectedShortfall = 3125.0)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), eq(positions), any()) } returns baseResult
            coEvery { riskEngineClient.valuate(any(), neq(positions), any()) } returns hypotheticalResult

            val result = service.analyzeWhatIf(
                bookId = BookId("port-1"),
                hypotheticalTrades = trades,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            result.varChange shouldBeExactly -2500.0
            result.esChange shouldBeExactly -3125.0
        }

        test("passes correct calculation type and confidence level to risk engine") {
            val positions = listOf(position())
            val trades = listOf(hypotheticalTrade())

            val baseResult = valuationResult(varValue = 5000.0, expectedShortfall = 6250.0)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), any(), any()) } returns baseResult

            service.analyzeWhatIf(
                bookId = BookId("port-1"),
                hypotheticalTrades = trades,
                calculationType = CalculationType.MONTE_CARLO,
                confidenceLevel = ConfidenceLevel.CL_99,
            )

            val requestSlots = mutableListOf<VaRCalculationRequest>()
            coVerify(exactly = 2) { riskEngineClient.valuate(capture(requestSlots), any(), any()) }

            requestSlots.forEach { req ->
                req.calculationType shouldBe CalculationType.MONTE_CARLO
                req.confidenceLevel shouldBe ConfidenceLevel.CL_99
            }
        }
    }
})
