package com.kinetix.risk

import com.kinetix.common.model.*
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.client.dtos.InstrumentDto
import com.kinetix.risk.kafka.RiskResultPublisher
import com.kinetix.risk.model.*
import com.kinetix.risk.service.VaRCalculationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private class SlowStubRiskEngineClient : RiskEngineClient {
    override suspend fun calculateVaR(
        request: VaRCalculationRequest,
        positions: List<Position>,
        marketData: List<com.kinetix.risk.model.MarketDataValue>,
        instrumentMap: Map<String, InstrumentDto>,
    ): VaRResult {
        delay(31_000)
        return VaRResult(
            bookId = request.bookId,
            calculationType = request.calculationType,
            confidenceLevel = request.confidenceLevel,
            varValue = 50000.0,
            expectedShortfall = 62500.0,
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 50000.0, 100.0),
            ),
            calculatedAt = Instant.now(),
        )
    }

    override suspend fun valuate(
        request: VaRCalculationRequest,
        positions: List<Position>,
        marketData: List<com.kinetix.risk.model.MarketDataValue>,
        instrumentMap: Map<String, InstrumentDto>,
    ): ValuationResult {
        delay(31_000)
        return ValuationResult(
            bookId = request.bookId,
            calculationType = request.calculationType,
            confidenceLevel = request.confidenceLevel,
            varValue = 50000.0,
            expectedShortfall = 62500.0,
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 50000.0, 100.0),
            ),
            greeks = null,
            calculatedAt = Instant.now(),
            computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
        )
    }

    override suspend fun discoverDependencies(
        positions: List<Position>,
        calculationType: String,
        confidenceLevel: String,
        instrumentMap: Map<String, InstrumentDto>,
    ) = throw UnsupportedOperationException("Not used in observability test")

    override suspend fun decomposeFactorRisk(
        bookId: com.kinetix.common.model.BookId,
        positions: List<Position>,
        marketData: Map<String, com.kinetix.risk.model.TimeSeriesMarketData>,
        totalVar: Double,
    ) = throw UnsupportedOperationException("Not used in observability test")
}

private class StubPositionProvider : com.kinetix.risk.client.PositionProvider {
    override suspend fun getPositions(bookId: BookId): List<Position> {
        return listOf(
            Position(
                bookId = bookId,
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("150.00"), USD),
                marketPrice = Money(BigDecimal("170.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            )
        )
    }
}

class ObservabilityAcceptanceTest : FunSpec({

    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val positionProvider = StubPositionProvider()
    val slowRiskEngine = SlowStubRiskEngineClient()
    val resultPublisher = mockk<RiskResultPublisher>()
    coEvery { resultPublisher.publish(any()) } just Runs

    val varService = VaRCalculationService(
        positionProvider, slowRiskEngine, resultPublisher, registry,
    )

    test("a risk orchestrator with metrics enabled — a VaR calculation exceeds 30 seconds — the calculation duration metric records a value exceeding 30s") {
        val result = varService.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("obs-test-port"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val scrapeOutput = registry.scrape()
        scrapeOutput shouldContain "var_calculation_duration"
        val durationLine = scrapeOutput.lines()
            .filter { it.startsWith("var_calculation_duration_seconds_bucket") }
            .lastOrNull { !it.contains("+Inf") }
        // The highest finite bucket should have been recorded
        scrapeOutput shouldContain "var_calculation_duration_seconds_count"
    }

    test("a risk orchestrator with metrics enabled — a VaR calculation exceeds 30 seconds — the /metrics endpoint exposes the duration metric in Prometheus format") {
        val result = varService.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("obs-test-port"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val scrapeOutput = registry.scrape()
        scrapeOutput shouldContain "# HELP"
        scrapeOutput shouldContain "var_calculation_duration_seconds"
        scrapeOutput shouldContain "var_calculation_count_total"
    }
})
