package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.model.*
import com.kinetix.risk.persistence.DailyRiskSnapshotRepository
import com.kinetix.risk.persistence.PnlAttributionRepository
import com.kinetix.risk.persistence.SodBaselineRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

private val PORTFOLIO = BookId("port-1")
private val TODAY = LocalDate.of(2025, 1, 15)

class PnlComputationServiceTest : FunSpec({

    val sodSnapshotService = mockk<SodSnapshotService>()
    val dailyRiskSnapshotRepository = mockk<DailyRiskSnapshotRepository>()
    val pnlAttributionService = PnlAttributionService()
    val pnlAttributionRepository = mockk<PnlAttributionRepository>()
    val varCache = mockk<VaRCache>()
    val positionProvider = mockk<PositionProvider>()

    val service = PnlComputationService(
        sodSnapshotService = sodSnapshotService,
        dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
        pnlAttributionService = pnlAttributionService,
        pnlAttributionRepository = pnlAttributionRepository,
        varCache = varCache,
        positionProvider = positionProvider,
    )

    beforeEach {
        clearMocks(sodSnapshotService, dailyRiskSnapshotRepository, pnlAttributionRepository, varCache, positionProvider)
    }

    test("throws NoSodBaselineException when no baseline exists") {
        coEvery { sodSnapshotService.getBaselineStatus(PORTFOLIO, TODAY) } returns SodBaselineStatus(exists = false)

        shouldThrow<NoSodBaselineException> {
            service.compute(PORTFOLIO, TODAY)
        }
    }

    test("computes P&L attribution using SOD snapshots and current positions") {
        coEvery { sodSnapshotService.getBaselineStatus(PORTFOLIO, TODAY) } returns SodBaselineStatus(
            exists = true,
            baselineDate = TODAY.toString(),
            snapshotType = SnapshotType.MANUAL,
            createdAt = Instant.parse("2025-01-15T08:00:00Z"),
        )

        val sodSnapshots = listOf(
            DailyRiskSnapshot(
                bookId = PORTFOLIO,
                snapshotDate = TODAY,
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                marketPrice = BigDecimal("150.00"),
                delta = 0.85,
                gamma = 0.02,
                vega = 1500.0,
                theta = -50.0,
                rho = 30.0,
            ),
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns sodSnapshots

        val currentPositions = listOf(
            Position(
                bookId = PORTFOLIO,
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("145.00"), Currency.getInstance("USD")),
                marketPrice = Money(BigDecimal("155.00"), Currency.getInstance("USD")),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns currentPositions
        coEvery { pnlAttributionRepository.save(any()) } just Runs

        val result = service.compute(PORTFOLIO, TODAY)

        result.bookId shouldBe PORTFOLIO
        result.date shouldBe TODAY
        coVerify { pnlAttributionRepository.save(any()) }
    }

    test("returns saved P&L attribution result") {
        coEvery { sodSnapshotService.getBaselineStatus(PORTFOLIO, TODAY) } returns SodBaselineStatus(
            exists = true,
            baselineDate = TODAY.toString(),
            snapshotType = SnapshotType.AUTO,
            createdAt = Instant.parse("2025-01-15T08:00:00Z"),
        )

        val sodSnapshots = listOf(
            DailyRiskSnapshot(
                bookId = PORTFOLIO,
                snapshotDate = TODAY,
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                marketPrice = BigDecimal("150.00"),
                delta = 0.85,
                gamma = 0.02,
                vega = 1500.0,
                theta = -50.0,
                rho = 30.0,
            ),
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns sodSnapshots

        val currentPositions = listOf(
            Position(
                bookId = PORTFOLIO,
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("145.00"), Currency.getInstance("USD")),
                marketPrice = Money(BigDecimal("155.00"), Currency.getInstance("USD")),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns currentPositions
        coEvery { pnlAttributionRepository.save(any()) } just Runs

        val result = service.compute(PORTFOLIO, TODAY)

        result.positionAttributions.size shouldBe 1
        result.positionAttributions[0].instrumentId shouldBe InstrumentId("AAPL")
    }
})
