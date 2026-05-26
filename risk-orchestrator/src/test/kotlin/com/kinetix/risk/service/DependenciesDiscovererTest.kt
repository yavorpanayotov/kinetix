package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.proto.risk.DataDependenciesResponse
import com.kinetix.proto.risk.MarketDataDependency
import com.kinetix.proto.risk.MarketDataType
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.model.DiscoveredDependency
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun position(
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
) = Position(
    bookId = BookId("port-1"),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal("100"),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal("170.00"), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun dependency(
    dataType: MarketDataType,
    instrumentId: String = "AAPL",
    assetClass: String = "EQUITY",
    parameters: Map<String, String> = emptyMap(),
    required: Boolean = true,
) = MarketDataDependency.newBuilder()
    .setDataType(dataType)
    .setInstrumentId(instrumentId)
    .setAssetClass(assetClass)
    .putAllParameters(parameters)
    .setRequired(required)
    .build()

class DependenciesDiscovererTest : FunSpec({

    val riskEngineClient = mockk<RiskEngineClient>()
    val discoverer = DependenciesDiscoverer(riskEngineClient)

    beforeEach {
        clearMocks(riskEngineClient)
    }

    test("discovers dependencies from risk engine and returns domain objects") {
        val positions = listOf(position())
        val depsResponse = DataDependenciesResponse.newBuilder()
            .addDependencies(dependency(MarketDataType.SPOT_PRICE, parameters = mapOf("key" to "val")))
            .addDependencies(dependency(MarketDataType.HISTORICAL_PRICES, parameters = mapOf("lookbackDays" to "252")))
            .build()

        coEvery { riskEngineClient.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns depsResponse

        val result = discoverer.discover(positions, "PARAMETRIC", "CL_95")

        // 2 from engine + 1 IDX-SPX HISTORICAL_PRICES injected for equity positions
        result shouldHaveSize 3
        result shouldContainExactlyInAnyOrder listOf(
            DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY", mapOf("key" to "val")),
            DiscoveredDependency("HISTORICAL_PRICES", "AAPL", "EQUITY", mapOf("lookbackDays" to "252")),
            DiscoveredDependency("HISTORICAL_PRICES", "IDX-SPX", "EQUITY", mapOf("lookbackDays" to "300")),
        )
    }

    test("returns only IDX-SPX dependency when no other dependencies discovered for equity positions") {
        val positions = listOf(position())
        val depsResponse = DataDependenciesResponse.newBuilder().build()

        coEvery { riskEngineClient.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns depsResponse

        val result = discoverer.discover(positions, "PARAMETRIC", "CL_95")

        result shouldHaveSize 1
        result.single() shouldBe DiscoveredDependency(
            dataType = "HISTORICAL_PRICES",
            instrumentId = "IDX-SPX",
            assetClass = "EQUITY",
            parameters = mapOf("lookbackDays" to "300"),
        )
    }

    test("returns empty list when discovery fails") {
        val positions = listOf(position())
        coEvery { riskEngineClient.discoverDependencies(any(), any(), any()) } throws RuntimeException("gRPC unavailable")

        val result = discoverer.discover(positions, "PARAMETRIC", "CL_95")

        result.shouldBeEmpty()
    }

    test("deduplicates by data type, instrument id, and parameters") {
        val positions = listOf(position())
        val depsResponse = DataDependenciesResponse.newBuilder()
            .addDependencies(dependency(MarketDataType.SPOT_PRICE))
            .addDependencies(dependency(MarketDataType.SPOT_PRICE))
            .addDependencies(dependency(MarketDataType.SPOT_PRICE, instrumentId = "MSFT"))
            .build()

        coEvery { riskEngineClient.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns depsResponse

        val result = discoverer.discover(positions, "PARAMETRIC", "CL_95")

        // 2 deduplicated SPOT_PRICE + 1 IDX-SPX injected for equity positions
        result shouldHaveSize 3
        result.map { it.instrumentId } shouldContainExactlyInAnyOrder listOf("AAPL", "MSFT", "IDX-SPX")
    }

    test("emits HISTORICAL_PRICES for IDX-SPX when equity positions are present") {
        val positions = listOf(position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY))
        val depsResponse = DataDependenciesResponse.newBuilder().build()

        coEvery { riskEngineClient.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns depsResponse

        val result = discoverer.discover(positions, "PARAMETRIC", "CL_95")

        val spxDep = result.find {
            it.instrumentId == "IDX-SPX" && it.dataType == "HISTORICAL_PRICES"
        }
        spxDep shouldBe DiscoveredDependency(
            dataType = "HISTORICAL_PRICES",
            instrumentId = "IDX-SPX",
            assetClass = "EQUITY",
            parameters = mapOf("lookbackDays" to "300"),
        )
    }

    test("does not emit IDX-SPX HISTORICAL_PRICES when no equity positions") {
        val positions = listOf(position(instrumentId = "US10Y", assetClass = AssetClass.FIXED_INCOME))
        val depsResponse = DataDependenciesResponse.newBuilder().build()

        coEvery { riskEngineClient.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns depsResponse

        val result = discoverer.discover(positions, "PARAMETRIC", "CL_95")

        result.none { it.instrumentId == "IDX-SPX" } shouldBe true
    }

    test("does not duplicate IDX-SPX dependency if risk engine already emits it") {
        val positions = listOf(position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY))
        val depsResponse = DataDependenciesResponse.newBuilder()
            .addDependencies(
                dependency(MarketDataType.HISTORICAL_PRICES, instrumentId = "IDX-SPX", assetClass = "EQUITY",
                    parameters = mapOf("lookbackDays" to "300"))
            )
            .build()

        coEvery { riskEngineClient.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns depsResponse

        val result = discoverer.discover(positions, "PARAMETRIC", "CL_95")

        result.count { it.instrumentId == "IDX-SPX" } shouldBe 1
    }

    test("preserves dependencies with same data type but different parameters") {
        val positions = listOf(position(assetClass = AssetClass.FIXED_INCOME))
        val depsResponse = DataDependenciesResponse.newBuilder()
            .addDependencies(dependency(MarketDataType.YIELD_CURVE, instrumentId = "", parameters = mapOf("curveId" to "USD")))
            .addDependencies(dependency(MarketDataType.YIELD_CURVE, instrumentId = "", parameters = mapOf("curveId" to "EUR")))
            .build()

        coEvery { riskEngineClient.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns depsResponse

        val result = discoverer.discover(positions, "PARAMETRIC", "CL_95")

        result shouldHaveSize 2
        result.map { it.parameters } shouldContainExactlyInAnyOrder listOf(
            mapOf("curveId" to "USD"),
            mapOf("curveId" to "EUR"),
        )
    }
})
