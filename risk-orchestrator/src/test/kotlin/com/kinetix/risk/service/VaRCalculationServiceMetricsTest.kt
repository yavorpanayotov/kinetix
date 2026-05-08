package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.kafka.RiskResultPublisher
import com.kinetix.risk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

class VaRCalculationServiceMetricsTest : FunSpec({

    val registry = SimpleMeterRegistry()
    val positionProvider = mockk<PositionProvider>()
    val riskEngineClient = mockk<RiskEngineClient>()
    val resultPublisher = mockk<RiskResultPublisher>()
    val service = VaRCalculationService(positionProvider, riskEngineClient, resultPublisher, registry)

    beforeEach {
        clearMocks(positionProvider, riskEngineClient, resultPublisher)
    }

    test("timer var.calculation.duration records after calculateVaR") {
        val positions = listOf(
            Position(
                bookId = BookId("port-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                marketPrice = Money(BigDecimal("170.00"), Currency.getInstance("USD")),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            )
        )
        val result = ValuationResult(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0)),
            greeks = null,
            calculatedAt = Instant.now(),
            computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
        )

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns result
        coEvery { resultPublisher.publish(result) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val timer = registry.find("var.calculation.duration").timer()
        timer!!.count() shouldBe 1
    }

    test("counter var.calculation.count increments tagged by calculationType") {
        val positions = listOf(
            Position(
                bookId = BookId("port-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
                marketPrice = Money(BigDecimal("170.00"), Currency.getInstance("USD")),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            )
        )
        val result = ValuationResult(
            bookId = BookId("port-1"),
            calculationType = CalculationType.HISTORICAL,
            confidenceLevel = ConfidenceLevel.CL_95,
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0)),
            greeks = null,
            calculatedAt = Instant.now(),
            computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
        )

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns result
        coEvery { resultPublisher.publish(result) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.HISTORICAL,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val counter = registry.find("var.calculation.count")
            .tag("calculationType", "HISTORICAL")
            .counter()
        counter!!.count() shouldBe 1.0
    }
})
