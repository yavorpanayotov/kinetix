package com.kinetix.risk.client

import com.kinetix.common.model.*
import com.kinetix.proto.risk.MarketDataDependenciesServiceGrpcKt.MarketDataDependenciesServiceCoroutineStub
import com.kinetix.proto.risk.RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineStub
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.model.ValuationOutput
import io.grpc.ManagedChannelBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency

private val USD = Currency.getInstance("USD")

/**
 * gRPC contract integration test that runs the real Python risk-engine
 * in a Docker container, then exercises [GrpcRiskEngineClient] against it.
 *
 * This test verifies that:
 * - Kotlin proto stubs and Python servicer agree on field names and numbers
 * - The Python servicer returns correctly populated responses
 * - Enum values serialize and deserialize correctly in both directions
 * - The response mapper produces valid domain objects
 *
 * Requires Docker and a pre-built `kinetix-risk-engine-test` image.
 * Build with: `docker build -t kinetix-risk-engine-test -f deploy/docker/Dockerfile.risk-engine .`
 */
class RiskEngineGrpcContractIntegrationTest : FunSpec({

    val container = GenericContainer(DockerImageName.parse("kinetix-risk-engine-test:latest"))
        .withExposedPorts(50051)
        .withEnv("PYTHONPATH", "/app/src")
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))

    lateinit var client: GrpcRiskEngineClient

    beforeSpec {
        container.start()
        val channel = ManagedChannelBuilder
            .forAddress(container.host, container.getMappedPort(50051))
            .usePlaintext()
            .build()
        val riskStub = RiskCalculationServiceCoroutineStub(channel)
        val depsStub = MarketDataDependenciesServiceCoroutineStub(channel)
        client = GrpcRiskEngineClient(riskStub, depsStub)
    }

    afterSpec {
        container.stop()
    }

    test("calculateVaR returns a valid VaR result for an EQUITY position") {
        val positions = listOf(
            Position(
                bookId = BookId("contract-port-1"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("150.00"), USD),
                marketPrice = Money(BigDecimal("170.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        val request = VaRCalculationRequest(
            bookId = BookId("contract-port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
            timeHorizonDays = 1,
            numSimulations = 10_000,
        )

        val result = client.calculateVaR(request, positions)

        result.bookId shouldBe BookId("contract-port-1")
        result.calculationType shouldBe CalculationType.PARAMETRIC
        result.confidenceLevel shouldBe ConfidenceLevel.CL_95
        result.varValue shouldBeGreaterThan 0.0
        result.expectedShortfall shouldBeGreaterThan 0.0
        result.componentBreakdown.shouldNotBeEmpty()
        result.componentBreakdown[0].assetClass shouldBe AssetClass.EQUITY
    }

    test("calculateVaR returns a valid result for HISTORICAL calculation type") {
        val positions = listOf(
            Position(
                bookId = BookId("contract-port-2"),
                instrumentId = InstrumentId("MSFT"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("50"),
                averageCost = Money(BigDecimal("300.00"), USD),
                marketPrice = Money(BigDecimal("320.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        val request = VaRCalculationRequest(
            bookId = BookId("contract-port-2"),
            calculationType = CalculationType.HISTORICAL,
            confidenceLevel = ConfidenceLevel.CL_99,
        )

        val result = client.calculateVaR(request, positions)

        result.bookId shouldBe BookId("contract-port-2")
        result.calculationType shouldBe CalculationType.HISTORICAL
        result.confidenceLevel shouldBe ConfidenceLevel.CL_99
        result.varValue shouldBeGreaterThan 0.0
    }

    test("calculateVaR handles multiple positions across asset classes") {
        val positions = listOf(
            Position(
                bookId = BookId("contract-port-3"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("150.00"), USD),
                marketPrice = Money(BigDecimal("170.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
            Position(
                bookId = BookId("contract-port-3"),
                instrumentId = InstrumentId("UST10Y"),
                assetClass = AssetClass.FIXED_INCOME,
                quantity = BigDecimal("50"),
                averageCost = Money(BigDecimal("9800.00"), USD),
                marketPrice = Money(BigDecimal("10000.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        val request = VaRCalculationRequest(
            bookId = BookId("contract-port-3"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val result = client.calculateVaR(request, positions)

        result.varValue shouldBeGreaterThan 0.0
        result.componentBreakdown.size shouldBe 2
    }

    test("valuate returns VaR and PV when both are requested") {
        val positions = listOf(
            Position(
                bookId = BookId("contract-port-4"),
                instrumentId = InstrumentId("GOOGL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("25"),
                averageCost = Money(BigDecimal("2800.00"), USD),
                marketPrice = Money(BigDecimal("2900.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        val request = VaRCalculationRequest(
            bookId = BookId("contract-port-4"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
            requestedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.PV),
        )

        val result = client.valuate(request, positions)

        result.bookId shouldBe BookId("contract-port-4")
        result.calculationType shouldBe CalculationType.PARAMETRIC
        result.varValue shouldNotBe null
        result.pvValue shouldNotBe null
        result.calculatedAt shouldNotBe null
    }

    test("discoverDependencies returns required market data for positions") {
        val positions = listOf(
            Position(
                bookId = BookId("contract-port-5"),
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("150.00"), USD),
                marketPrice = Money(BigDecimal("170.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )

        val response = client.discoverDependencies(
            positions = positions,
            calculationType = "PARAMETRIC",
            confidenceLevel = "CL_95",
        )

        response shouldNotBe null
    }
})
