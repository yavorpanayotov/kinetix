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

    test("does not produce a billions-scale gamma artefact from a VaR-bump snapshot gamma") {
        // kx-gla6: when only a VaR-bump-scale gamma is available on the DailyRiskSnapshot
        // (a VaR sensitivity per fractional bump, ~ -1.0e9) and there is no closed-form
        // pricing gamma, the Taylor gammaPnl term = 0.5 * gamma * priceChange^2 must NOT
        // explode into a multi-billion artefact mirrored by unexplainedPnl.
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
                // VaR-bump second-order sensitivity (per fractional bump) — NOT a per-unit pricing gamma.
                gamma = -1.0e9,
                vega = 1500.0,
                theta = -50.0,
                rho = 30.0,
            ),
        )
        coEvery { dailyRiskSnapshotRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns sodSnapshots

        // Small absolute price move of a few dollars.
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

        // priceChange = 5, totalPnl = 5 * 100 = 500. With the VaR-bump gamma fallback the
        // gammaPnl would be 0.5 * -1.0e9 * 5^2 = -1.25e10 and unexplained would mirror it.
        result.totalPnl shouldBe BigDecimal("500.00")
        // gammaPnl must not be the billions-scale artefact: bound it by |totalPnl|.
        (result.gammaPnl.abs() <= result.totalPnl.abs()) shouldBe true
        // Specifically, with no pricing gamma available, gamma must fall back to zero.
        result.gammaPnl.compareTo(BigDecimal.ZERO) shouldBe 0
        // Unexplained must not mirror a multi-billion gamma term.
        (result.unexplainedPnl.abs() <= result.totalPnl.abs()) shouldBe true
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
