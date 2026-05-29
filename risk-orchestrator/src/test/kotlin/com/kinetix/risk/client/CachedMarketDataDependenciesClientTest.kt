package com.kinetix.risk.client

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.proto.risk.DataDependenciesResponse
import com.kinetix.proto.risk.MarketDataDependency
import com.kinetix.proto.risk.MarketDataType
import com.kinetix.risk.model.FactorDecompositionSnapshot
import com.kinetix.risk.model.MarketDataValue
import com.kinetix.risk.model.TimeSeriesMarketData
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.model.VaRResult
import com.kinetix.risk.model.ValuationResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun position(instrumentId: String = "AAPL") = Position(
    bookId = BookId("port-1"),
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal("100"),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal("170.00"), USD),
    instrumentType = InstrumentTypeCode.CASH_EQUITY,
)

private fun dependenciesResponse(
    version: String = "1.0.0",
    vararg dataTypes: MarketDataType = arrayOf(MarketDataType.SPOT_PRICE),
): DataDependenciesResponse =
    DataDependenciesResponse.newBuilder()
        .setEngineVersion(version)
        .apply {
            dataTypes.forEach { dt ->
                addDependencies(
                    MarketDataDependency.newBuilder()
                        .setDataType(dt)
                        .setInstrumentId("AAPL")
                        .setAssetClass("EQUITY")
                        .build()
                )
            }
        }
        .build()

class CachedMarketDataDependenciesClientTest : FunSpec({

    val delegate = mockk<RiskEngineClient>()
    val cachedClient = CachedMarketDataDependenciesClient(delegate)

    val positions = listOf(position())

    beforeEach {
        clearMocks(delegate)
        cachedClient.invalidateAll()
    }

    test("first call invokes the delegate gRPC client") {
        val response = dependenciesResponse("1.0.0")
        coEvery { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns response

        cachedClient.discoverDependencies(positions, "PARAMETRIC", "CL_95")

        coVerify(exactly = 1) { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") }
    }

    test("second call with same (calculationType, confidenceLevel) key returns cached result without invoking delegate") {
        val response = dependenciesResponse("1.0.0")
        coEvery { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns response

        val first = cachedClient.discoverDependencies(positions, "PARAMETRIC", "CL_95")
        val second = cachedClient.discoverDependencies(positions, "PARAMETRIC", "CL_95")

        first shouldBe second
        coVerify(exactly = 1) { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") }
    }

    test("calls with different (calculationType, confidenceLevel) keys are each independently cached") {
        val response95 = dependenciesResponse("1.0.0", MarketDataType.SPOT_PRICE)
        val response99 = dependenciesResponse("1.0.0", MarketDataType.HISTORICAL_PRICES)
        coEvery { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns response95
        coEvery { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_99") } returns response99

        repeat(3) { cachedClient.discoverDependencies(positions, "PARAMETRIC", "CL_95") }
        repeat(3) { cachedClient.discoverDependencies(positions, "PARAMETRIC", "CL_99") }

        coVerify(exactly = 1) { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") }
        coVerify(exactly = 1) { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_99") }
    }

    test("when the cached entry carries version v1 and the next probe returns v2 the cache is invalidated and delegate is re-invoked") {
        val v1 = dependenciesResponse("1.0.0")
        val v2 = dependenciesResponse("2.0.0")
        coEvery { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returnsMany listOf(v1, v2)

        // Populate cache with v1
        cachedClient.discoverDependencies(positions, "PARAMETRIC", "CL_95")
        // Trigger a version-aware probe — e.g. during a periodic refresh cycle
        cachedClient.probeAndRefreshIfVersionChanged(positions, "PARAMETRIC", "CL_95")

        // Third call returns v2 from cache (no delegate call)
        val third = cachedClient.discoverDependencies(positions, "PARAMETRIC", "CL_95")

        third.engineVersion shouldBe "2.0.0"
        coVerify(exactly = 2) { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") }
    }

    test("probeAndRefreshIfVersionChanged does not update cache when version is unchanged") {
        val response = dependenciesResponse("1.0.0")
        coEvery { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") } returns response

        cachedClient.discoverDependencies(positions, "PARAMETRIC", "CL_95")
        cachedClient.probeAndRefreshIfVersionChanged(positions, "PARAMETRIC", "CL_95")
        val third = cachedClient.discoverDependencies(positions, "PARAMETRIC", "CL_95")

        third.engineVersion shouldBe "1.0.0"
        // Initial miss + one probe = 2 delegate calls; third is served from cache
        coVerify(exactly = 2) { delegate.discoverDependencies(positions, "PARAMETRIC", "CL_95") }
    }

    test("delegates calculateVaR to underlying client unchanged") {
        val request = mockk<VaRCalculationRequest>()
        val varResult = mockk<VaRResult>()
        coEvery { delegate.calculateVaR(request, positions) } returns varResult

        val result = cachedClient.calculateVaR(request, positions)

        result shouldBe varResult
    }

    test("delegates valuate to underlying client unchanged") {
        val request = mockk<VaRCalculationRequest>()
        val valuationResult = mockk<ValuationResult>()
        coEvery { delegate.valuate(request, positions) } returns valuationResult

        val result = cachedClient.valuate(request, positions)

        result shouldBe valuationResult
    }

    test("delegates decomposeFactorRisk to underlying client unchanged") {
        val bookId = BookId("port-1")
        val marketData = emptyMap<String, TimeSeriesMarketData>()
        val snapshot = mockk<FactorDecompositionSnapshot>()
        coEvery { delegate.decomposeFactorRisk(bookId, positions, marketData, 10000.0) } returns snapshot

        val result = cachedClient.decomposeFactorRisk(bookId, positions, marketData, 10000.0)

        result shouldBe snapshot
    }
})
