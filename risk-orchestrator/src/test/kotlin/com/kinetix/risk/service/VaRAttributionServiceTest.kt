package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ChangeMagnitude
import com.kinetix.risk.model.ComponentBreakdown
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.InputChangeSummary
import com.kinetix.risk.model.MarketDataInputChange
import com.kinetix.risk.model.MarketDataInputChangeType
import com.kinetix.risk.model.PositionInputChange
import com.kinetix.risk.model.PositionInputChangeType
import com.kinetix.risk.model.RunStatus
import com.kinetix.risk.model.TriggerType
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.model.ValuationJob
import com.kinetix.risk.model.ValuationOutput
import com.kinetix.risk.model.ValuationResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val USD = Currency.getInstance("USD")

private fun position(instrumentId: String = "AAPL", quantity: String = "100") = Position(
    bookId = BookId("port-1"),
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal(quantity),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal("170.00"), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun job(
    jobId: UUID = UUID.randomUUID(),
    varValue: Double = 5000.0,
    theta: Double = -50.0,
    bookId: String = "port-1",
    valuationDate: LocalDate = LocalDate.of(2025, 1, 15),
) = ValuationJob(
    jobId = jobId,
    bookId = bookId,
    triggerType = TriggerType.ON_DEMAND,
    status = RunStatus.COMPLETED,
    startedAt = Instant.now(),
    valuationDate = valuationDate,
    completedAt = Instant.now(),
    varValue = varValue,
    expectedShortfall = varValue * 1.25,
    theta = theta,
    calculationType = "PARAMETRIC",
    confidenceLevel = "CL_95",
    triggeredBy = "user-a",
)

private fun valuationResult(varValue: Double = 5000.0) = ValuationResult(
    bookId = BookId("port-1"),
    calculationType = CalculationType.PARAMETRIC,
    confidenceLevel = ConfidenceLevel.CL_95,
    varValue = varValue,
    expectedShortfall = varValue * 1.25,
    componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, varValue, 100.0)),
    greeks = null,
    calculatedAt = Instant.now(),
    computedOutputs = setOf(ValuationOutput.VAR),
)

class VaRAttributionServiceTest : FunSpec({

    val riskEngineClient = mockk<RiskEngineClient>()
    val positionProvider = mockk<PositionProvider>()
    val service = VaRAttributionService(riskEngineClient, positionProvider)

    beforeEach { clearMocks(riskEngineClient, positionProvider) }

    test("computes position effect by swapping positions with constant market data") {
        val baseJob = job(varValue = 5000.0, theta = -50.0)
        val targetJob = job(varValue = 7000.0, theta = -50.0, valuationDate = LocalDate.of(2025, 1, 16))

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns listOf(position("AAPL", "150"))
        // VaR re-run with current positions against base market params = 6000
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(6000.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob)

        result.totalChange shouldBeExactly 2000.0
        // positionEffect = VaR_with_new_positions - baseVaR = 6000 - 5000 = 1000
        result.positionEffect shouldBeExactly 1000.0
    }

    test("computes unexplained as residual after all known effects") {
        val baseJob = job(varValue = 5000.0, theta = -50.0)
        val targetJob = job(varValue = 7000.0, theta = -50.0, valuationDate = LocalDate.of(2025, 1, 16))

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        // varWithNewPositions = 5500, so positionEffect = 500
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(5500.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob)

        val explained = result.positionEffect + (result.volEffect ?: 0.0) + (result.corrEffect ?: 0.0) + result.timeDecayEffect
        val expectedUnexplained = result.totalChange - explained
        result.unexplained shouldBe expectedUnexplained.plusOrMinus(0.01)
    }

    test("returns zero attribution when runs are identical") {
        val baseJob = job(varValue = 5000.0, theta = -50.0)
        val targetJob = job(varValue = 5000.0, theta = -50.0)

        // identical runs: early-exit path, no calls expected
        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(5000.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob)

        result.totalChange shouldBeExactly 0.0
        result.positionEffect shouldBeExactly 0.0
        result.timeDecayEffect shouldBeExactly 0.0
        result.unexplained shouldBeExactly 0.0
    }

    test("computes time decay effect from theta over one calendar day") {
        val baseJob = job(varValue = 5000.0, theta = -100.0, valuationDate = LocalDate.of(2025, 1, 15))
        val targetJob = job(varValue = 4900.0, theta = -100.0, valuationDate = LocalDate.of(2025, 1, 16))

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(5000.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob)

        // 1 calendar day elapsed; time decay = theta * 1 = -100
        result.timeDecayEffect shouldBe (-100.0).plusOrMinus(1.0)
    }

    test("passes base calculation type and confidence level to the risk engine") {
        val baseJob = job(varValue = 5000.0)
        val targetJob = job(varValue = 6000.0, valuationDate = LocalDate.of(2025, 1, 16))

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(5500.0)

        service.attributeVaRChange(BookId("port-1"), baseJob, targetJob)

        val requestSlot = slot<VaRCalculationRequest>()
        coVerify { riskEngineClient.valuate(capture(requestSlot), any(), any()) }

        requestSlot.captured.calculationType shouldBe CalculationType.PARAMETRIC
        requestSlot.captured.confidenceLevel shouldBe ConfidenceLevel.CL_95
    }

    test("includes effect magnitudes in attribution result") {
        val baseJob = job(varValue = 5000.0, theta = -50.0).copy(pvValue = 1_000_000.0)
        val targetJob = job(varValue = 7000.0, theta = -50.0, valuationDate = LocalDate.of(2025, 1, 16))

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(6000.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob)

        result.effectMagnitudes shouldContainKey "position"
        result.effectMagnitudes shouldContainKey "timeDecay"
        result.effectMagnitudes shouldContainKey "unexplained"
    }

    test("classifies large position effect as LARGE magnitude") {
        // totalChange = 2000, positionEffect = 1500 → denominator = max(2000, 50) = 2000
        // share = 1500/2000 = 0.75 → LARGE (>40%)
        val baseJob = job(varValue = 5000.0, theta = 0.0).copy(pvValue = 10_000.0)
        val targetJob = job(varValue = 7000.0, theta = 0.0)

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(6500.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob)

        result.effectMagnitudes["position"] shouldBe ChangeMagnitude.LARGE
    }

    test("estimates vol effect from input changes with vol surface magnitude") {
        val baseJob = job(varValue = 5000.0, theta = 0.0).copy(vega = 100.0, pvValue = 1_000_000.0)
        val targetJob = job(varValue = 6000.0, theta = 0.0)

        val inputChanges = InputChangeSummary(
            positionsChanged = false,
            marketDataChanged = true,
            modelVersionChanged = false,
            baseModelVersion = "1.0.0",
            targetModelVersion = "1.0.0",
            positionChanges = emptyList(),
            marketDataChanges = listOf(
                MarketDataInputChange(
                    dataType = "VOLATILITY_SURFACE",
                    instrumentId = "AAPL",
                    assetClass = "EQUITY",
                    changeType = MarketDataInputChangeType.CHANGED,
                    baseContentHash = "hash-a",
                    targetContentHash = "hash-b",
                    magnitude = ChangeMagnitude.LARGE,
                ),
            ),
        )

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(5000.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob, inputChanges)

        // volEffect ≈ vega * 0.10 (LARGE avg) = 100 * 0.10 = 10
        result.volEffect shouldNotBe null
        result.volEffect!! shouldBe 10.0.plusOrMinus(0.01)
    }

    test("adds Monte Carlo caveat when calculation type is MONTE_CARLO") {
        val baseJob = job(varValue = 5000.0).copy(calculationType = "MONTE_CARLO", pvValue = 1_000_000.0)
        val targetJob = job(varValue = 6000.0, valuationDate = LocalDate.of(2025, 1, 16))

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(5500.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob)

        result.caveats shouldContain "Monte Carlo simulation: sampling variance may contribute to observed differences"
    }

    test("adds Historical VaR caveat when calculation type is HISTORICAL") {
        val baseJob = job(varValue = 5000.0).copy(calculationType = "HISTORICAL", pvValue = 1_000_000.0)
        val targetJob = job(varValue = 6000.0, valuationDate = LocalDate.of(2025, 1, 16))

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(5500.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob)

        result.caveats shouldContain "Historical VaR is not a differentiable function of inputs; attribution is scenario-based, not sensitivity-based"
    }

    test("adds model version changed caveat when model versions differ") {
        val baseJob = job(varValue = 5000.0).copy(pvValue = 1_000_000.0)
        val targetJob = job(varValue = 6000.0, valuationDate = LocalDate.of(2025, 1, 16))

        val inputChanges = InputChangeSummary(
            positionsChanged = false,
            marketDataChanged = false,
            modelVersionChanged = true,
            baseModelVersion = "1.0.0",
            targetModelVersion = "2.0.0",
            positionChanges = emptyList(),
            marketDataChanges = emptyList(),
        )

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(5500.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob, inputChanges)

        result.caveats shouldContain "Model version changed between runs (1.0.0 → 2.0.0); model effect cannot be decomposed further"
    }

    test("adds large gamma caveat when second-order effects are significant") {
        val baseJob = job(varValue = 5000.0).copy(
            delta = 0.5,
            gamma = 2.0,
            pvValue = 1_000_000.0,
        )
        val targetJob = job(varValue = 6000.0, valuationDate = LocalDate.of(2025, 1, 16))

        val inputChanges = InputChangeSummary(
            positionsChanged = true,
            marketDataChanged = false,
            modelVersionChanged = false,
            baseModelVersion = "1.0.0",
            targetModelVersion = "1.0.0",
            positionChanges = listOf(
                PositionInputChange(
                    instrumentId = "AAPL",
                    assetClass = "EQUITY",
                    changeType = PositionInputChangeType.BOTH_CHANGED,
                    baseQuantity = BigDecimal("100"),
                    targetQuantity = BigDecimal("100"),
                    quantityDelta = BigDecimal.ZERO,
                    baseMarketPrice = BigDecimal("100.00"),
                    targetMarketPrice = BigDecimal("150.00"),
                    priceDelta = BigDecimal("50.00"),
                    currency = "USD",
                ),
            ),
            marketDataChanges = emptyList(),
        )

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns valuationResult(5500.0)

        val result = service.attributeVaRChange(BookId("port-1"), baseJob, targetJob, inputChanges)

        result.caveats shouldContain "Large gamma positions detected; first-order attribution may understate second-order effects"
    }
})
