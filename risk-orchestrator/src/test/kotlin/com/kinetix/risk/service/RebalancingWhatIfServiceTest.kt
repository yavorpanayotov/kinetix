package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
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

private fun rebalancingTrade(
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    side: Side = Side.BUY,
    quantity: String = "50",
    price: String = "175.00",
    bidAskSpreadBps: Double = 5.0,
) = RebalancingTrade(
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    side = side,
    quantity = BigDecimal(quantity),
    price = Money(BigDecimal(price), USD),
    bidAskSpreadBps = bidAskSpreadBps,
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

class RebalancingWhatIfServiceTest : FunSpec({

    val positionProvider = mockk<PositionProvider>()
    val riskEngineClient = mockk<RiskEngineClient>()
    val whatIfAnalysisService = WhatIfAnalysisService(positionProvider, riskEngineClient)
    val service = RebalancingWhatIfService(positionProvider, riskEngineClient, whatIfAnalysisService)

    beforeEach {
        clearMocks(positionProvider, riskEngineClient)
    }

    context("analyzeRebalancing with empty trade list") {

        test("returns base unchanged when no trades are submitted") {
            val positions = listOf(position(quantity = "100", marketPrice = "170.00"))
            val baseResult = valuationResult(varValue = 5000.0, expectedShortfall = 6250.0)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), any(), any()) } returns baseResult

            val result = service.analyzeRebalancing(
                bookId = BookId("port-1"),
                trades = emptyList(),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            result.baseVar shouldBeExactly 5000.0
            result.rebalancedVar shouldBeExactly 5000.0
            result.varChange shouldBeExactly 0.0
            result.varChangePct shouldBeExactly 0.0
            result.tradeContributions shouldHaveSize 0
            result.estimatedExecutionCost shouldBeExactly 0.0
        }
    }

    context("analyzeRebalancing with single trade") {

        test("computes VaR change and pct when trade increases risk") {
            val positions = listOf(position(quantity = "100", marketPrice = "170.00"))
            val trades = listOf(rebalancingTrade(side = Side.BUY, quantity = "50", price = "175.00"))

            val baseResult = valuationResult(varValue = 5000.0, expectedShortfall = 6250.0)
            val rebalancedResult = valuationResult(varValue = 7000.0, expectedShortfall = 8750.0)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), eq(positions), any()) } returns baseResult
            coEvery { riskEngineClient.valuate(any(), neq(positions), any()) } returns rebalancedResult

            val result = service.analyzeRebalancing(
                bookId = BookId("port-1"),
                trades = trades,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            result.baseVar shouldBeExactly 5000.0
            result.rebalancedVar shouldBeExactly 7000.0
            result.varChange shouldBeExactly 2000.0
            result.varChangePct shouldBe (40.0 plusOrMinus 0.001)
        }

        test("computes negative VaR change when trade reduces risk") {
            val positions = listOf(position(quantity = "100", marketPrice = "170.00"))
            val trades = listOf(rebalancingTrade(side = Side.SELL, quantity = "50", price = "175.00"))

            val baseResult = valuationResult(varValue = 5000.0, expectedShortfall = 6250.0)
            val rebalancedResult = valuationResult(varValue = 2500.0, expectedShortfall = 3125.0)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), eq(positions), any()) } returns baseResult
            coEvery { riskEngineClient.valuate(any(), neq(positions), any()) } returns rebalancedResult

            val result = service.analyzeRebalancing(
                bookId = BookId("port-1"),
                trades = trades,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            result.varChange shouldBeExactly -2500.0
            result.varChangePct shouldBe (-50.0 plusOrMinus 0.001)
        }

        test("computes estimated execution cost as notional times spread") {
            val positions = listOf(position(quantity = "100", marketPrice = "170.00"))
            // 50 shares at 200.00, 5 bps spread -> cost = 50 * 200 * 0.0005 = 5.00
            val trades = listOf(
                rebalancingTrade(quantity = "50", price = "200.00", bidAskSpreadBps = 5.0),
            )

            val baseResult = valuationResult(varValue = 5000.0, expectedShortfall = 6250.0)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), any(), any()) } returns baseResult

            val result = service.analyzeRebalancing(
                bookId = BookId("port-1"),
                trades = trades,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            // notional = 50 * 200 = 10000, spread = 5 bps = 0.0005 -> cost = 5.00
            result.estimatedExecutionCost shouldBe (5.0 plusOrMinus 0.001)
        }
    }

    context("analyzeRebalancing with multiple trades") {

        test("computes per-trade marginal VaR contributions sequentially") {
            val positions = listOf(
                position(instrumentId = "AAPL", quantity = "100", marketPrice = "170.00"),
                position(instrumentId = "TSLA", quantity = "50", marketPrice = "220.00"),
            )
            val trades = listOf(
                rebalancingTrade(instrumentId = "AAPL", side = Side.SELL, quantity = "50", price = "170.00"),
                rebalancingTrade(instrumentId = "TSLA", side = Side.BUY, quantity = "25", price = "220.00"),
            )

            val baseResult = valuationResult(varValue = 5000.0, expectedShortfall = 6250.0)
            val afterTrade1 = valuationResult(varValue = 3500.0, expectedShortfall = 4375.0)
            val afterTrade2 = valuationResult(varValue = 4200.0, expectedShortfall = 5250.0)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            // Base uses original positions
            coEvery { riskEngineClient.valuate(any(), eq(positions), any()) } returns baseResult
            // Each incremental step will hit with a different list
            coEvery { riskEngineClient.valuate(any(), any(), any()) } returnsMany listOf(
                baseResult,
                afterTrade1,
                afterTrade2,
                afterTrade2, // final rebalanced call
            )

            val result = service.analyzeRebalancing(
                bookId = BookId("port-1"),
                trades = trades,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            result.tradeContributions shouldHaveSize 2
            // First trade: 3500 - 5000 = -1500
            result.tradeContributions[0].instrumentId shouldBe "AAPL"
            result.tradeContributions[0].marginalVarImpact shouldBe (-1500.0 plusOrMinus 0.001)
            // Second trade: 4200 - 3500 = +700
            result.tradeContributions[1].instrumentId shouldBe "TSLA"
            result.tradeContributions[1].marginalVarImpact shouldBe (700.0 plusOrMinus 0.001)
        }

        test("sums execution costs across all trades") {
            val positions = listOf(position(quantity = "100", marketPrice = "170.00"))
            // Trade 1: 50 shares at 200, 5 bps -> 50 * 200 * 0.0005 = 5.00
            // Trade 2: 100 shares at 100, 10 bps -> 100 * 100 * 0.001 = 10.00
            val trades = listOf(
                rebalancingTrade(instrumentId = "AAPL", quantity = "50", price = "200.00", bidAskSpreadBps = 5.0),
                rebalancingTrade(instrumentId = "TSLA", quantity = "100", price = "100.00", bidAskSpreadBps = 10.0),
            )

            val baseResult = valuationResult(varValue = 5000.0)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), any(), any()) } returns baseResult

            val result = service.analyzeRebalancing(
                bookId = BookId("port-1"),
                trades = trades,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            result.estimatedExecutionCost shouldBe (15.0 plusOrMinus 0.001)
        }

        test("greek changes reflect base vs final rebalanced portfolio") {
            val positions = listOf(position(quantity = "100", marketPrice = "170.00"))
            val trades = listOf(rebalancingTrade(side = Side.BUY, quantity = "50", price = "175.00"))

            val baseGreeks = GreeksResult(
                assetClassGreeks = listOf(GreekValues(AssetClass.EQUITY, delta = 100.0, gamma = 5.0, vega = 200.0)),
                theta = -30.0,
                rho = 50.0,
            )
            val rebalancedGreeks = GreeksResult(
                assetClassGreeks = listOf(GreekValues(AssetClass.EQUITY, delta = 150.0, gamma = 7.5, vega = 300.0)),
                theta = -45.0,
                rho = 75.0,
            )

            val baseResult = valuationResult(varValue = 5000.0, greeks = baseGreeks)
            val rebalancedResult = valuationResult(varValue = 7000.0, greeks = rebalancedGreeks)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), eq(positions), any()) } returns baseResult
            coEvery { riskEngineClient.valuate(any(), neq(positions), any()) } returns rebalancedResult

            val result = service.analyzeRebalancing(
                bookId = BookId("port-1"),
                trades = trades,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            result.baseGreeks shouldBe baseGreeks
            result.rebalancedGreeks shouldBe rebalancedGreeks
            result.greeksChange.deltaChange shouldBe (50.0 plusOrMinus 0.001)
            result.greeksChange.gammaChange shouldBe (2.5 plusOrMinus 0.001)
            result.greeksChange.vegaChange shouldBe (100.0 plusOrMinus 0.001)
            result.greeksChange.thetaChange shouldBe (-15.0 plusOrMinus 0.001)
            result.greeksChange.rhoChange shouldBe (25.0 plusOrMinus 0.001)
        }
    }
})
