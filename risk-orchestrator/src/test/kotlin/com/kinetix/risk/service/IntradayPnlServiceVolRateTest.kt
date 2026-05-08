package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.RateSource
import com.kinetix.common.model.RiskFreeRate
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import com.kinetix.common.model.VolatilitySource
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.RatesServiceClient
import com.kinetix.risk.client.VolatilityServiceClient
import com.kinetix.risk.model.DailyRiskSnapshot
import com.kinetix.risk.model.PnlTrigger
import com.kinetix.risk.model.SodBaseline
import com.kinetix.risk.model.SnapshotType
import com.kinetix.risk.persistence.DailyRiskSnapshotRepository
import com.kinetix.risk.persistence.IntradayPnlRepository
import com.kinetix.risk.persistence.SodBaselineRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val USD = Currency.getInstance("USD")
private val BOOK = BookId("book-vol-1")
private val AAPL = InstrumentId("AAPL")
private val DATE = LocalDate.now()

/**
 * Tests that [IntradayPnlService] uses actual vol and rate deltas in attribution,
 * rather than hardcoding zero.
 */
class IntradayPnlServiceVolRateTest : FunSpec({

    fun sodBaseline() = SodBaseline(
        bookId = BOOK,
        baselineDate = DATE,
        snapshotType = SnapshotType.AUTO,
        createdAt = Instant.now(),
        sourceJobId = UUID.randomUUID(),
        calculationType = "PARAMETRIC",
        varValue = null,
        expectedShortfall = null,
    )

    fun position(
        instrumentId: InstrumentId = AAPL,
        marketPrice: String = "160.00",
        avgCost: String = "150.00",
        quantity: String = "100",
    ) = Position(
        bookId = BOOK,
        instrumentId = instrumentId,
        assetClass = AssetClass.EQUITY,
        quantity = BigDecimal(quantity),
        averageCost = Money(BigDecimal(avgCost), USD),
        marketPrice = Money(BigDecimal(marketPrice), USD),
        instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
    )

    fun sodSnapshot(
        instrumentId: InstrumentId = AAPL,
        marketPrice: String = "150.00",
        vega: Double = 0.0,
        rho: Double = 0.0,
        sodVol: Double? = null,
        sodRate: Double? = null,
    ) = DailyRiskSnapshot(
        bookId = BOOK,
        snapshotDate = DATE,
        instrumentId = instrumentId,
        assetClass = AssetClass.EQUITY,
        quantity = BigDecimal("100"),
        marketPrice = BigDecimal(marketPrice),
        delta = 0.7,
        gamma = 0.02,
        vega = vega,
        theta = -5.0,
        rho = rho,
        sodVol = sodVol,
        sodRate = sodRate,
    )

    fun volSurface(impliedVol: String) = VolSurface(
        instrumentId = AAPL,
        asOf = Instant.now(),
        points = listOf(
            VolPoint(BigDecimal("100"), 30, BigDecimal(impliedVol)),
            VolPoint(BigDecimal("100"), 365, BigDecimal(impliedVol)),
            VolPoint(BigDecimal("200"), 30, BigDecimal(impliedVol)),
            VolPoint(BigDecimal("200"), 365, BigDecimal(impliedVol)),
        ),
        source = VolatilitySource.INTERNAL,
    )

    fun riskFreeRate(rate: Double) = RiskFreeRate(
        currency = USD,
        tenor = "1Y",
        rate = rate,
        asOfDate = Instant.now(),
        source = RateSource.CENTRAL_BANK,
    )

    fun makeService(
        sodBaselineRepo: SodBaselineRepository,
        dailyRiskSnapshotRepo: DailyRiskSnapshotRepository,
        positionProvider: com.kinetix.risk.client.PositionProvider,
        volClient: VolatilityServiceClient,
        ratesClient: RatesServiceClient,
    ): IntradayPnlService {
        val intradayPnlRepo = mockk<IntradayPnlRepository>(relaxed = true)
        val publisher = mockk<IntradayPnlPublisher>(relaxed = true)
        coEvery { intradayPnlRepo.findLatest(any()) } returns null
        return IntradayPnlService(
            sodBaselineRepository = sodBaselineRepo,
            dailyRiskSnapshotRepository = dailyRiskSnapshotRepo,
            intradayPnlRepository = intradayPnlRepo,
            positionProvider = positionProvider,
            pnlAttributionService = PnlAttributionService(),
            publisher = publisher,
            volatilityServiceClient = volClient,
            ratesServiceClient = ratesClient,
        )
    }

    test("intraday vegaPnl is non-zero when vol has moved since SOD") {
        // SOD vol was 0.20, current vol is 0.25 — volChange = 0.05
        // vega = 400, so vegaPnl = 400 * 0.05 = 20.0
        val sodBaselineRepo = mockk<SodBaselineRepository>()
        val dailyRiskSnapshotRepo = mockk<DailyRiskSnapshotRepository>()
        val positionProvider = mockk<com.kinetix.risk.client.PositionProvider>()
        val volClient = mockk<VolatilityServiceClient>()
        val ratesClient = mockk<RatesServiceClient>()

        coEvery { sodBaselineRepo.findByBookIdAndDate(BOOK, any()) } returns sodBaseline()
        coEvery { dailyRiskSnapshotRepo.findByBookIdAndDate(BOOK, any()) } returns listOf(
            sodSnapshot(vega = 400.0, sodVol = 0.20),
        )
        coEvery { positionProvider.getPositions(BOOK) } returns listOf(position())
        coEvery { volClient.getLatestSurface(AAPL) } returns ClientResponse.Success(volSurface("0.25"))
        coEvery { ratesClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.NotFound(404)

        val service = makeService(sodBaselineRepo, dailyRiskSnapshotRepo, positionProvider, volClient, ratesClient)
        val snapshot = service.recompute(BOOK, PnlTrigger.POSITION_CHANGE, correlationId = null, date = DATE)

        snapshot.shouldNotBeNull()
        snapshot.vegaPnl.compareTo(BigDecimal.ZERO) shouldBeGreaterThan 0
        snapshot.vegaPnl.setScale(4, RoundingMode.HALF_UP) shouldBe BigDecimal("20.0000")
    }

    test("intraday rhoPnl is non-zero when rate has moved since SOD") {
        // SOD rate was 0.03, current rate is 0.05 — rateChange = 0.02
        // rho = 50, so rhoPnl = 50 * 0.02 = 1.0
        val sodBaselineRepo = mockk<SodBaselineRepository>()
        val dailyRiskSnapshotRepo = mockk<DailyRiskSnapshotRepository>()
        val positionProvider = mockk<com.kinetix.risk.client.PositionProvider>()
        val volClient = mockk<VolatilityServiceClient>()
        val ratesClient = mockk<RatesServiceClient>()

        coEvery { sodBaselineRepo.findByBookIdAndDate(BOOK, any()) } returns sodBaseline()
        coEvery { dailyRiskSnapshotRepo.findByBookIdAndDate(BOOK, any()) } returns listOf(
            sodSnapshot(rho = 50.0, sodRate = 0.03),
        )
        coEvery { positionProvider.getPositions(BOOK) } returns listOf(position())
        coEvery { volClient.getLatestSurface(AAPL) } returns ClientResponse.NotFound(404)
        coEvery { ratesClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.Success(riskFreeRate(0.05))

        val service = makeService(sodBaselineRepo, dailyRiskSnapshotRepo, positionProvider, volClient, ratesClient)
        val snapshot = service.recompute(BOOK, PnlTrigger.POSITION_CHANGE, correlationId = null, date = DATE)

        snapshot.shouldNotBeNull()
        snapshot.rhoPnl.compareTo(BigDecimal.ZERO) shouldBeGreaterThan 0
        snapshot.rhoPnl.setScale(4, RoundingMode.HALF_UP) shouldBe BigDecimal("1.0000")
    }

    test("intraday vegaPnl is negative when vol has fallen since SOD") {
        // SOD vol was 0.30, current vol is 0.20 — volChange = -0.10
        // vega = 200, so vegaPnl = 200 * (-0.10) = -20.0
        val sodBaselineRepo = mockk<SodBaselineRepository>()
        val dailyRiskSnapshotRepo = mockk<DailyRiskSnapshotRepository>()
        val positionProvider = mockk<com.kinetix.risk.client.PositionProvider>()
        val volClient = mockk<VolatilityServiceClient>()
        val ratesClient = mockk<RatesServiceClient>()

        coEvery { sodBaselineRepo.findByBookIdAndDate(BOOK, any()) } returns sodBaseline()
        coEvery { dailyRiskSnapshotRepo.findByBookIdAndDate(BOOK, any()) } returns listOf(
            sodSnapshot(vega = 200.0, sodVol = 0.30),
        )
        coEvery { positionProvider.getPositions(BOOK) } returns listOf(position())
        coEvery { volClient.getLatestSurface(AAPL) } returns ClientResponse.Success(volSurface("0.20"))
        coEvery { ratesClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.NotFound(404)

        val service = makeService(sodBaselineRepo, dailyRiskSnapshotRepo, positionProvider, volClient, ratesClient)
        val snapshot = service.recompute(BOOK, PnlTrigger.POSITION_CHANGE, correlationId = null, date = DATE)

        snapshot.shouldNotBeNull()
        snapshot.vegaPnl.compareTo(BigDecimal.ZERO) shouldBeLessThan 0
        snapshot.vegaPnl.setScale(4, RoundingMode.HALF_UP) shouldBe BigDecimal("-20.0000")
    }

    test("intraday attribution identity holds with real vol and rate inputs") {
        val sodBaselineRepo = mockk<SodBaselineRepository>()
        val dailyRiskSnapshotRepo = mockk<DailyRiskSnapshotRepository>()
        val positionProvider = mockk<com.kinetix.risk.client.PositionProvider>()
        val volClient = mockk<VolatilityServiceClient>()
        val ratesClient = mockk<RatesServiceClient>()

        coEvery { sodBaselineRepo.findByBookIdAndDate(BOOK, any()) } returns sodBaseline()
        coEvery { dailyRiskSnapshotRepo.findByBookIdAndDate(BOOK, any()) } returns listOf(
            sodSnapshot(vega = 300.0, rho = 40.0, sodVol = 0.18, sodRate = 0.035),
        )
        coEvery { positionProvider.getPositions(BOOK) } returns listOf(position())
        coEvery { volClient.getLatestSurface(AAPL) } returns ClientResponse.Success(volSurface("0.22"))
        coEvery { ratesClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.Success(riskFreeRate(0.045))

        val service = makeService(sodBaselineRepo, dailyRiskSnapshotRepo, positionProvider, volClient, ratesClient)
        val snapshot = service.recompute(BOOK, PnlTrigger.POSITION_CHANGE, correlationId = null, date = DATE)

        snapshot.shouldNotBeNull()
        // Attribution identity: delta + gamma + vega + theta + rho + unexplained == total
        val sumOfComponents = snapshot.deltaPnl + snapshot.gammaPnl + snapshot.vegaPnl +
            snapshot.thetaPnl + snapshot.rhoPnl + snapshot.unexplainedPnl
        sumOfComponents.setScale(6, RoundingMode.HALF_UP) shouldBe
            snapshot.totalPnl.setScale(6, RoundingMode.HALF_UP)
    }

    test("intraday unexplained is smaller when vol and rate are populated vs zero") {
        // When vol and rate changes are real (non-zero), unexplained should be smaller
        // than when they are zero — more of the P&L is attributed.
        val sodBaselineRepo = mockk<SodBaselineRepository>()
        val dailyRiskSnapshotRepo = mockk<DailyRiskSnapshotRepository>()
        val positionProvider = mockk<com.kinetix.risk.client.PositionProvider>()
        val volClient = mockk<VolatilityServiceClient>()
        val ratesClient = mockk<RatesServiceClient>()

        // sodVol = null and sodRate = null -> can't compute vol/rate changes -> unexplained is larger
        coEvery { sodBaselineRepo.findByBookIdAndDate(BOOK, any()) } returns sodBaseline()
        coEvery { dailyRiskSnapshotRepo.findByBookIdAndDate(BOOK, any()) } returns listOf(
            sodSnapshot(vega = 300.0, rho = 40.0, sodVol = null, sodRate = null),
        )
        coEvery { positionProvider.getPositions(BOOK) } returns listOf(position())
        coEvery { volClient.getLatestSurface(AAPL) } returns ClientResponse.Success(volSurface("0.22"))
        coEvery { ratesClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.Success(riskFreeRate(0.045))

        val serviceNoBaseline = makeService(sodBaselineRepo, dailyRiskSnapshotRepo, positionProvider, volClient, ratesClient)
        val snapshotNoBaseline = serviceNoBaseline.recompute(BOOK, PnlTrigger.POSITION_CHANGE, correlationId = null, date = DATE)

        // Now with sodVol and sodRate captured
        val sodBaselineRepo2 = mockk<SodBaselineRepository>()
        val dailyRiskSnapshotRepo2 = mockk<DailyRiskSnapshotRepository>()
        val positionProvider2 = mockk<com.kinetix.risk.client.PositionProvider>()
        val volClient2 = mockk<VolatilityServiceClient>()
        val ratesClient2 = mockk<RatesServiceClient>()

        coEvery { sodBaselineRepo2.findByBookIdAndDate(BOOK, any()) } returns sodBaseline()
        coEvery { dailyRiskSnapshotRepo2.findByBookIdAndDate(BOOK, any()) } returns listOf(
            sodSnapshot(vega = 300.0, rho = 40.0, sodVol = 0.18, sodRate = 0.035),
        )
        coEvery { positionProvider2.getPositions(BOOK) } returns listOf(position())
        coEvery { volClient2.getLatestSurface(AAPL) } returns ClientResponse.Success(volSurface("0.22"))
        coEvery { ratesClient2.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.Success(riskFreeRate(0.045))

        val serviceWithBaseline = makeService(sodBaselineRepo2, dailyRiskSnapshotRepo2, positionProvider2, volClient2, ratesClient2)
        val snapshotWithBaseline = serviceWithBaseline.recompute(BOOK, PnlTrigger.POSITION_CHANGE, correlationId = null, date = DATE)

        snapshotNoBaseline.shouldNotBeNull()
        snapshotWithBaseline.shouldNotBeNull()

        // The absolute unexplained should be smaller (or equal) when vol/rate data is available
        snapshotWithBaseline.unexplainedPnl.abs() shouldBeLessThan snapshotNoBaseline.unexplainedPnl.abs()
    }
})
