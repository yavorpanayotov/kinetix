package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.model.AttributionDataQuality
import com.kinetix.risk.model.DailyRiskSnapshot
import com.kinetix.risk.model.SnapshotType
import com.kinetix.risk.model.SodBaselineStatus
import com.kinetix.risk.model.SodGreekSnapshot
import com.kinetix.risk.persistence.DailyRiskSnapshotRepository
import com.kinetix.risk.persistence.PnlAttributionRepository
import com.kinetix.risk.persistence.SodGreekSnapshotRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

private val BOOK = BookId("book-cross-1")
private val TODAY = LocalDate.of(2025, 6, 15)
private val USD = Currency.getInstance("USD")
private val AAPL = InstrumentId("AAPL")

class PnlComputationServiceCrossGreekTest : FunSpec({

    val sodSnapshotService = mockk<SodSnapshotService>()
    val dailyRiskSnapshotRepository = mockk<DailyRiskSnapshotRepository>()
    val pnlAttributionService = PnlAttributionService()
    val pnlAttributionRepository = mockk<PnlAttributionRepository>()
    val varCache = mockk<VaRCache>()
    val positionProvider = mockk<PositionProvider>()
    val sodGreekSnapshotRepository = mockk<SodGreekSnapshotRepository>()

    fun makeService() = PnlComputationService(
        sodSnapshotService = sodSnapshotService,
        dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
        pnlAttributionService = pnlAttributionService,
        pnlAttributionRepository = pnlAttributionRepository,
        varCache = varCache,
        positionProvider = positionProvider,
        sodGreekSnapshotRepository = sodGreekSnapshotRepository,
    )

    beforeEach {
        clearMocks(
            sodSnapshotService, dailyRiskSnapshotRepository, pnlAttributionRepository,
            varCache, positionProvider, sodGreekSnapshotRepository,
        )
        coEvery { sodSnapshotService.getBaselineStatus(BOOK, TODAY) } returns SodBaselineStatus(
            exists = true,
            baselineDate = TODAY.toString(),
            snapshotType = SnapshotType.AUTO,
            createdAt = Instant.parse("2025-06-15T08:00:00Z"),
        )
        coEvery { pnlAttributionRepository.save(any()) } just Runs
    }

    test("uses pricing Greeks from SodGreekSnapshot including vanna, volga, charm") {
        val sodSnapshot = DailyRiskSnapshot(
            bookId = BOOK,
            snapshotDate = TODAY,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            marketPrice = BigDecimal("150.00"),
            // VaR Greeks — should NOT be used for attribution when pricing Greeks exist
            delta = 0.5,
            gamma = 0.1,
            vega = 1000.0,
            theta = -30.0,
            rho = 20.0,
            sodVol = 0.20,
            sodRate = 0.05,
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(BOOK, TODAY) } returns listOf(sodSnapshot)

        // Pricing Greeks — these should take precedence
        val greekSnapshot = SodGreekSnapshot(
            bookId = BOOK,
            snapshotDate = TODAY,
            instrumentId = AAPL,
            sodPrice = BigDecimal("150.00"),
            sodVol = 0.20,
            sodRate = 0.05,
            delta = 0.6,
            gamma = 0.12,
            vega = 1200.0,
            theta = -35.0,
            rho = 22.0,
            vanna = 0.5,
            volga = 200.0,
            charm = -0.05,
            isLocked = true,
            lockedAt = Instant.parse("2025-06-15T07:55:00Z"),
            lockedBy = "SYSTEM",
            createdAt = Instant.parse("2025-06-15T07:55:00Z"),
        )
        coEvery { sodGreekSnapshotRepository.findByBookIdAndDate(BOOK, TODAY) } returns listOf(greekSnapshot)

        val currentPositions = listOf(
            Position(
                bookId = BOOK,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("148.00"), USD),
                marketPrice = Money(BigDecimal("160.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        coEvery { positionProvider.getPositions(BOOK) } returns currentPositions

        val result = makeService().compute(BOOK, TODAY)

        // deltaPnl should use pricing delta (0.6), not VaR delta (0.5)
        // priceChange = 160 - 150 = 10
        // deltaPnl = 0.6 * 10 = 6.0
        val pos = result.positionAttributions[0]
        pos.deltaPnl.setScale(4, java.math.RoundingMode.HALF_UP) shouldBe BigDecimal("6.0000")

        // vannaPnl = 0.5 * 10 * 0 = 0 (no vol change since no vol service wired)
        // but vanna should be taken from greekSnapshot, not default zero
        // This test verifies the input was wired from the greek snapshot.
        // With no vol change, vannaPnl = 0, but the attribution should still show FULL_ATTRIBUTION
        // because vanna != 0 in the input.
        result.dataQualityFlag shouldBe AttributionDataQuality.FULL_ATTRIBUTION
    }

    test("falls back to PRICE_ONLY when no SodGreekSnapshot exists") {
        val sodSnapshot = DailyRiskSnapshot(
            bookId = BOOK,
            snapshotDate = TODAY,
            instrumentId = AAPL,
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            marketPrice = BigDecimal("150.00"),
            delta = 0.5,
            gamma = 0.1,
            vega = 1000.0,
            theta = -30.0,
            rho = 20.0,
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(BOOK, TODAY) } returns listOf(sodSnapshot)
        coEvery { sodGreekSnapshotRepository.findByBookIdAndDate(BOOK, TODAY) } returns emptyList()

        val currentPositions = listOf(
            Position(
                bookId = BOOK,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("148.00"), USD),
                marketPrice = Money(BigDecimal("155.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        coEvery { positionProvider.getPositions(BOOK) } returns currentPositions

        val result = makeService().compute(BOOK, TODAY)

        result.dataQualityFlag shouldBe AttributionDataQuality.PRICE_ONLY
    }
})
