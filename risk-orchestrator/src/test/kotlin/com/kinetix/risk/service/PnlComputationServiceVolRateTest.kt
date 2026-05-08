package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import com.kinetix.common.model.VolatilitySource
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RatesServiceClient
import com.kinetix.risk.client.VolatilityServiceClient
import com.kinetix.risk.model.DailyRiskSnapshot
import com.kinetix.risk.model.SnapshotType
import com.kinetix.risk.model.SodBaselineStatus
import com.kinetix.risk.persistence.DailyRiskSnapshotRepository
import com.kinetix.risk.persistence.PnlAttributionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

private val PORTFOLIO = BookId("port-vol-1")
private val TODAY = LocalDate.of(2025, 6, 15)
private val USD = Currency.getInstance("USD")
private val AAPL = InstrumentId("AAPL")

/**
 * Tests that [PnlComputationService] uses actual vol and rate deltas when building
 * attribution inputs, rather than hardcoding zero.
 *
 * These tests drive the fix for the semantic bug where volChange = BigDecimal.ZERO
 * and rateChange = BigDecimal.ZERO caused vega P&L and rho P&L to always be zero.
 */
class PnlComputationServiceVolRateTest : FunSpec({

    val sodSnapshotService = mockk<SodSnapshotService>()
    val dailyRiskSnapshotRepository = mockk<DailyRiskSnapshotRepository>()
    val pnlAttributionService = PnlAttributionService()
    val pnlAttributionRepository = mockk<PnlAttributionRepository>()
    val varCache = mockk<VaRCache>()
    val positionProvider = mockk<PositionProvider>()
    val volatilityServiceClient = mockk<VolatilityServiceClient>()
    val ratesServiceClient = mockk<RatesServiceClient>()

    fun makeService() = PnlComputationService(
        sodSnapshotService = sodSnapshotService,
        dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
        pnlAttributionService = pnlAttributionService,
        pnlAttributionRepository = pnlAttributionRepository,
        varCache = varCache,
        positionProvider = positionProvider,
        volatilityServiceClient = volatilityServiceClient,
        ratesServiceClient = ratesServiceClient,
    )

    beforeEach {
        clearMocks(
            sodSnapshotService, dailyRiskSnapshotRepository, pnlAttributionRepository,
            varCache, positionProvider, volatilityServiceClient, ratesServiceClient,
        )
        coEvery { sodSnapshotService.getBaselineStatus(PORTFOLIO, TODAY) } returns SodBaselineStatus(
            exists = true,
            baselineDate = TODAY.toString(),
            snapshotType = SnapshotType.MANUAL,
            createdAt = Instant.parse("2025-06-15T08:00:00Z"),
        )
        coEvery { pnlAttributionRepository.save(any()) } just Runs
    }

    test("vegaPnl is non-zero when current vol differs from sod vol") {
        // SOD snapshot: sodVol=0.20 (20%), vega=500
        // Current vol surface: impliedVol=0.25 (25%)
        // Expected volChange = 0.25 - 0.20 = 0.05
        // Expected vegaPnl = 500 * 0.05 = 25.0 (non-zero)
        val sodSnapshot = DailyRiskSnapshot(
            bookId = PORTFOLIO,
            snapshotDate = TODAY,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            marketPrice = BigDecimal("150.00"),
            delta = 0.7,
            gamma = 0.02,
            vega = 500.0,
            theta = -10.0,
            rho = 20.0,
            sodVol = 0.20,
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns listOf(sodSnapshot)
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(
            Position(
                bookId = PORTFOLIO,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("145.00"), USD),
                marketPrice = Money(BigDecimal("155.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )

        val currentSurface = VolSurface(
            instrumentId = AAPL,
            asOf = Instant.parse("2025-06-15T14:00:00Z"),
            points = listOf(
                VolPoint(BigDecimal("100"), 30, BigDecimal("0.25")),
                VolPoint(BigDecimal("100"), 365, BigDecimal("0.25")),
                VolPoint(BigDecimal("200"), 30, BigDecimal("0.25")),
                VolPoint(BigDecimal("200"), 365, BigDecimal("0.25")),
            ),
            source = VolatilitySource.INTERNAL,
        )
        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.Success(currentSurface)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.NotFound(404)

        val result = makeService().compute(PORTFOLIO, TODAY)

        // vega P&L must be non-zero: vol moved from 0.20 to 0.25
        result.vegaPnl.compareTo(BigDecimal.ZERO) shouldBeGreaterThan 0
        result.vegaPnl.setScale(4, RoundingMode.HALF_UP) shouldBe BigDecimal("25.0000")
    }

    test("rhoPnl is non-zero when current rate differs from sod rate") {
        // SOD snapshot: sodRate=0.04 (4%), rho=30
        // Current rate: 0.05 (5%)
        // Expected rateChange = 0.05 - 0.04 = 0.01
        // Expected rhoPnl = 30 * 0.01 = 0.30 (non-zero)
        val sodSnapshot = DailyRiskSnapshot(
            bookId = PORTFOLIO,
            snapshotDate = TODAY,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            marketPrice = BigDecimal("150.00"),
            delta = 0.7,
            gamma = 0.02,
            vega = 0.0,
            theta = -10.0,
            rho = 30.0,
            sodRate = 0.04,
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns listOf(sodSnapshot)
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(
            Position(
                bookId = PORTFOLIO,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("145.00"), USD),
                marketPrice = Money(BigDecimal("155.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )

        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.NotFound(404)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.Success(
            com.kinetix.common.model.RiskFreeRate(
                currency = USD,
                tenor = "1Y",
                rate = 0.05,
                asOfDate = Instant.parse("2025-06-15T14:00:00Z"),
                source = com.kinetix.common.model.RateSource.CENTRAL_BANK,
            ),
        )

        val result = makeService().compute(PORTFOLIO, TODAY)

        // rho P&L must be non-zero: rate moved from 0.04 to 0.05
        result.rhoPnl.compareTo(BigDecimal.ZERO) shouldBeGreaterThan 0
        result.rhoPnl.setScale(4, RoundingMode.HALF_UP) shouldBe BigDecimal("0.3000")
    }

    test("falls back to zero volChange when vol service returns NotFound") {
        val sodSnapshot = DailyRiskSnapshot(
            bookId = PORTFOLIO,
            snapshotDate = TODAY,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            marketPrice = BigDecimal("150.00"),
            delta = 0.7,
            gamma = 0.02,
            vega = 500.0,
            theta = -10.0,
            rho = 20.0,
            sodVol = 0.20,
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns listOf(sodSnapshot)
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(
            Position(
                bookId = PORTFOLIO,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("145.00"), USD),
                marketPrice = Money(BigDecimal("155.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )

        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.NotFound(404)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.NotFound(404)

        // Must not throw — graceful degradation
        val result = makeService().compute(PORTFOLIO, TODAY)

        // With no current vol data, volChange = 0, so vegaPnl = 0
        result.vegaPnl.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("falls back to zero rateChange when rates service returns NotFound") {
        val sodSnapshot = DailyRiskSnapshot(
            bookId = PORTFOLIO,
            snapshotDate = TODAY,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            marketPrice = BigDecimal("150.00"),
            delta = 0.7,
            gamma = 0.02,
            vega = 0.0,
            theta = -10.0,
            rho = 30.0,
            sodRate = 0.04,
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns listOf(sodSnapshot)
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(
            Position(
                bookId = PORTFOLIO,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("145.00"), USD),
                marketPrice = Money(BigDecimal("155.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )

        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.NotFound(404)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.NotFound(404)

        val result = makeService().compute(PORTFOLIO, TODAY)

        // With no current rate data, rateChange = 0, so rhoPnl = 0
        result.rhoPnl.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("falls back to zero volChange when sodVol is null on snapshot") {
        // If no SOD vol was captured (e.g. instrument had no vol surface at SOD),
        // volChange must be zero — we cannot compute a meaningful delta.
        val sodSnapshot = DailyRiskSnapshot(
            bookId = PORTFOLIO,
            snapshotDate = TODAY,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            marketPrice = BigDecimal("150.00"),
            delta = 0.7,
            gamma = 0.02,
            vega = 500.0,
            theta = -10.0,
            rho = 20.0,
            sodVol = null, // no vol captured at SOD
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns listOf(sodSnapshot)
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(
            Position(
                bookId = PORTFOLIO,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("145.00"), USD),
                marketPrice = Money(BigDecimal("155.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )

        val currentSurface = VolSurface(
            instrumentId = AAPL,
            asOf = Instant.parse("2025-06-15T14:00:00Z"),
            points = listOf(
                VolPoint(BigDecimal("100"), 30, BigDecimal("0.25")),
                VolPoint(BigDecimal("100"), 365, BigDecimal("0.25")),
                VolPoint(BigDecimal("200"), 30, BigDecimal("0.25")),
                VolPoint(BigDecimal("200"), 365, BigDecimal("0.25")),
            ),
            source = VolatilitySource.INTERNAL,
        )
        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.Success(currentSurface)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.NotFound(404)

        val result = makeService().compute(PORTFOLIO, TODAY)

        // Without SOD vol baseline, we cannot compute volChange, so vegaPnl = 0
        result.vegaPnl.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("P&L attribution identity holds: total equals sum of all components including unexplained") {
        // Verify the accounting identity is preserved even with real vol/rate inputs
        val sodSnapshot = DailyRiskSnapshot(
            bookId = PORTFOLIO,
            snapshotDate = TODAY,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            marketPrice = BigDecimal("150.00"),
            delta = 0.8,
            gamma = 0.03,
            vega = 300.0,
            theta = -15.0,
            rho = 25.0,
            sodVol = 0.22,
            sodRate = 0.045,
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns listOf(sodSnapshot)
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(
            Position(
                bookId = PORTFOLIO,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("145.00"), USD),
                marketPrice = Money(BigDecimal("158.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )

        val currentSurface = VolSurface(
            instrumentId = AAPL,
            asOf = Instant.parse("2025-06-15T14:00:00Z"),
            points = listOf(
                VolPoint(BigDecimal("100"), 30, BigDecimal("0.24")),
                VolPoint(BigDecimal("100"), 365, BigDecimal("0.24")),
                VolPoint(BigDecimal("200"), 30, BigDecimal("0.24")),
                VolPoint(BigDecimal("200"), 365, BigDecimal("0.24")),
            ),
            source = VolatilitySource.INTERNAL,
        )
        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.Success(currentSurface)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.Success(
            com.kinetix.common.model.RiskFreeRate(
                currency = USD,
                tenor = "1Y",
                rate = 0.05,
                asOfDate = Instant.parse("2025-06-15T14:00:00Z"),
                source = com.kinetix.common.model.RateSource.CENTRAL_BANK,
            ),
        )

        val result = makeService().compute(PORTFOLIO, TODAY)

        val explained = result.deltaPnl + result.gammaPnl + result.vegaPnl +
            result.thetaPnl + result.rhoPnl
        val reconstructedTotal = explained + result.unexplainedPnl
        reconstructedTotal.setScale(6, RoundingMode.HALF_UP) shouldBe
            result.totalPnl.setScale(6, RoundingMode.HALF_UP)
    }
})
