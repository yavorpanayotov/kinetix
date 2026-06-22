package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.kafka.CrossBookRiskResultPublisher
import com.kinetix.risk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
    marketPrice: String = "170.00",
) = Position(
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal(marketPrice), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun varResult(
    bookId: String = "port-1",
    calculationType: CalculationType = CalculationType.PARAMETRIC,
    varValue: Double = 5000.0,
    componentBreakdown: List<ComponentBreakdown> = listOf(
        ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0),
    ),
) = ValuationResult(
    bookId = BookId(bookId),
    calculationType = calculationType,
    confidenceLevel = ConfidenceLevel.CL_95,
    varValue = varValue,
    expectedShortfall = varValue * 1.25,
    componentBreakdown = componentBreakdown,
    greeks = null,
    calculatedAt = Instant.now(),
    computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
)

class CrossBookVaRCalculationServiceTest : FunSpec({

    val positionProvider = mockk<PositionProvider>()
    val riskEngineClient = mockk<RiskEngineClient>()
    val resultPublisher = mockk<CrossBookRiskResultPublisher>()
    val varCache = mockk<VaRCache>()
    val service = CrossBookVaRCalculationService(
        positionProvider, riskEngineClient, resultPublisher, varCache,
    )

    beforeEach {
        clearMocks(positionProvider, riskEngineClient, resultPublisher, varCache)
    }

    test("fetches positions for each book and calls risk engine with merged position list") {
        val bookAPositions = listOf(position(bookId = "book-A", instrumentId = "AAPL"))
        val bookBPositions = listOf(position(bookId = "book-B", instrumentId = "TSLA"))
        val mergedPositions = bookAPositions + bookBPositions

        coEvery { positionProvider.getPositions(BookId("book-A")) } returns bookAPositions
        coEvery { positionProvider.getPositions(BookId("book-B")) } returns bookBPositions
        coEvery { varCache.get("book-A") } returns varResult(bookId = "book-A", varValue = 3000.0)
        coEvery { varCache.get("book-B") } returns varResult(bookId = "book-B", varValue = 2000.0)
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns varResult(
            varValue = 4000.0,
            componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 4000.0, 100.0)),
        )
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        val request = CrossBookVaRRequest(
            bookIds = listOf(BookId("book-A"), BookId("book-B")),
            portfolioGroupId = "group-1",
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val result = service.calculate(request)

        result.shouldNotBeNull()

        coVerify(exactly = 1) { riskEngineClient.valuate(any(), match { it.size == 2 }, any()) }
        coVerify { positionProvider.getPositions(BookId("book-A")) }
        coVerify { positionProvider.getPositions(BookId("book-B")) }
    }

    test("returns null when all books have empty positions") {
        coEvery { positionProvider.getPositions(BookId("book-A")) } returns emptyList()
        coEvery { positionProvider.getPositions(BookId("book-B")) } returns emptyList()

        val request = CrossBookVaRRequest(
            bookIds = listOf(BookId("book-A"), BookId("book-B")),
            portfolioGroupId = "group-1",
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val result = service.calculate(request)

        result.shouldBeNull()

        coVerify(exactly = 0) { riskEngineClient.valuate(any(), any(), any()) }
        coVerify(exactly = 0) { resultPublisher.publish(any(), any()) }
    }

    test("proceeds with available books when one returns empty") {
        val bookAPositions = listOf(position(bookId = "book-A", instrumentId = "AAPL"))

        coEvery { positionProvider.getPositions(BookId("book-A")) } returns bookAPositions
        coEvery { positionProvider.getPositions(BookId("book-B")) } returns emptyList()
        coEvery { varCache.get("book-A") } returns varResult(bookId = "book-A", varValue = 3000.0)
        coEvery { varCache.get("book-B") } returns null
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns varResult(
            varValue = 3000.0,
            componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 3000.0, 100.0)),
        )
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        val request = CrossBookVaRRequest(
            bookIds = listOf(BookId("book-A"), BookId("book-B")),
            portfolioGroupId = "group-1",
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val result = service.calculate(request)

        result.shouldNotBeNull()

        coVerify(exactly = 1) { riskEngineClient.valuate(any(), match { it.size == 1 }, any()) }
    }

    test("result includes diversification benefit computed from standalone VaRs") {
        val bookAPositions = listOf(position(bookId = "book-A", instrumentId = "AAPL", marketPrice = "200.00"))
        val bookBPositions = listOf(position(bookId = "book-B", instrumentId = "TSLA", marketPrice = "100.00"))

        coEvery { positionProvider.getPositions(BookId("book-A")) } returns bookAPositions
        coEvery { positionProvider.getPositions(BookId("book-B")) } returns bookBPositions
        coEvery { varCache.get("book-A") } returns varResult(bookId = "book-A", varValue = 3000.0)
        coEvery { varCache.get("book-B") } returns varResult(bookId = "book-B", varValue = 2000.0)
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns varResult(
            varValue = 4000.0,
            componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 4000.0, 100.0)),
        )
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        val request = CrossBookVaRRequest(
            bookIds = listOf(BookId("book-A"), BookId("book-B")),
            portfolioGroupId = "group-1",
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val result = service.calculate(request)

        result.shouldNotBeNull()
        result.totalStandaloneVar shouldBe 5000.0
        result.diversificationBenefit shouldBeGreaterThan 0.0
        result.diversificationBenefit shouldBe 1000.0
    }

    test("publishes result to Kafka") {
        val bookAPositions = listOf(position(bookId = "book-A", instrumentId = "AAPL"))

        coEvery { positionProvider.getPositions(BookId("book-A")) } returns bookAPositions
        coEvery { varCache.get("book-A") } returns varResult(bookId = "book-A", varValue = 5000.0)
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns varResult(
            varValue = 5000.0,
            componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0)),
        )
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        val request = CrossBookVaRRequest(
            bookIds = listOf(BookId("book-A")),
            portfolioGroupId = "group-1",
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        service.calculate(request)

        coVerify(exactly = 1) {
            resultPublisher.publish(match { it.portfolioGroupId == "group-1" }, any())
        }
    }

    test("single book produces zero diversification benefit") {
        val bookAPositions = listOf(position(bookId = "book-A", instrumentId = "AAPL"))

        coEvery { positionProvider.getPositions(BookId("book-A")) } returns bookAPositions
        coEvery { varCache.get("book-A") } returns varResult(bookId = "book-A", varValue = 5000.0)
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns varResult(
            varValue = 5000.0,
            componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0)),
        )
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        val request = CrossBookVaRRequest(
            bookIds = listOf(BookId("book-A")),
            portfolioGroupId = "group-1",
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val result = service.calculate(request)

        result.shouldNotBeNull()
        result.totalStandaloneVar shouldBe 5000.0
        result.diversificationBenefit shouldBeExactly 0.0
    }

    test("diversification benefit is clamped to zero when cross-book VaR exceeds sum of standalones") {
        val bookAPositions = listOf(position(bookId = "book-A", instrumentId = "AAPL", marketPrice = "200.00"))
        val bookBPositions = listOf(position(bookId = "book-B", instrumentId = "TSLA", marketPrice = "100.00"))

        coEvery { positionProvider.getPositions(BookId("book-A")) } returns bookAPositions
        coEvery { positionProvider.getPositions(BookId("book-B")) } returns bookBPositions
        // Standalone VaRs sum to 3000
        coEvery { varCache.get("book-A") } returns varResult(bookId = "book-A", varValue = 2000.0)
        coEvery { varCache.get("book-B") } returns varResult(bookId = "book-B", varValue = 1000.0)
        // Cross-book VaR exceeds the sum of standalones (can happen with super-additive correlations)
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns varResult(
            varValue = 4000.0,
            componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 4000.0, 100.0)),
        )
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        val request = CrossBookVaRRequest(
            bookIds = listOf(BookId("book-A"), BookId("book-B")),
            portfolioGroupId = "group-1",
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val result = service.calculate(request)

        result.shouldNotBeNull()
        result.totalStandaloneVar shouldBe 3000.0
        result.diversificationBenefit shouldBeExactly 0.0
    }

    test("requests GREEKS output and surfaces the firm-level aggregated greeks from the risk engine") {
        val bookAPositions = listOf(position(bookId = "book-A", instrumentId = "AAPL"))
        val bookBPositions = listOf(position(bookId = "book-B", instrumentId = "TSLA"))

        coEvery { positionProvider.getPositions(BookId("book-A")) } returns bookAPositions
        coEvery { positionProvider.getPositions(BookId("book-B")) } returns bookBPositions
        coEvery { varCache.get(any()) } returns null

        // The risk engine, valuing the merged firm portfolio in one call,
        // returns aggregate asset-class greeks. The cross-book result must
        // surface them so the firm-level dashboard renders sensitivities.
        val firmGreeks = GreeksResult(
            assetClassGreeks = listOf(
                GreekValues(AssetClass.EQUITY, delta = 1234.5, gamma = 67.8, vega = 90.1),
            ),
            theta = -12.3,
            rho = 4.5,
        )
        val requestSlot = slot<VaRCalculationRequest>()
        coEvery { riskEngineClient.valuate(capture(requestSlot), any(), any()) } returns
            varResult(varValue = 4000.0).copy(greeks = firmGreeks)
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        val request = CrossBookVaRRequest(
            bookIds = listOf(BookId("book-A"), BookId("book-B")),
            portfolioGroupId = "group-1",
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val result = service.calculate(request)

        requestSlot.captured.requestedOutputs shouldContain ValuationOutput.GREEKS
        result.shouldNotBeNull()
        result.greeks shouldBe firmGreeks
    }
})
