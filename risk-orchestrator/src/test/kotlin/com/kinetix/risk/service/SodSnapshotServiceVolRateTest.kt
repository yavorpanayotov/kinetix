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
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RatesServiceClient
import com.kinetix.risk.client.VolatilityServiceClient
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.GreekValues
import com.kinetix.risk.model.GreeksResult
import com.kinetix.risk.model.PositionRisk
import com.kinetix.risk.model.SnapshotType
import com.kinetix.risk.model.ValuationOutput
import com.kinetix.risk.model.ValuationResult
import com.kinetix.risk.persistence.DailyRiskSnapshotRepository
import com.kinetix.risk.persistence.SodBaselineRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

private val BOOK = BookId("book-sod-vol")
private val DATE = LocalDate.of(2025, 6, 15)
private val USD = Currency.getInstance("USD")
private val AAPL = InstrumentId("AAPL")

/**
 * Tests that [SodSnapshotService] captures sodVol and sodRate on each [DailyRiskSnapshot]
 * at snapshot creation time, so that downstream P&L attribution has a baseline to diff against.
 */
class SodSnapshotServiceVolRateTest : FunSpec({

    val sodBaselineRepository = mockk<SodBaselineRepository>()
    val dailyRiskSnapshotRepository = mockk<DailyRiskSnapshotRepository>()
    val varCache = mockk<VaRCache>()
    val varCalculationService = mockk<VaRCalculationService>()
    val positionProvider = mockk<PositionProvider>()
    val volatilityServiceClient = mockk<VolatilityServiceClient>()
    val ratesServiceClient = mockk<RatesServiceClient>()

    fun makeService() = SodSnapshotService(
        sodBaselineRepository = sodBaselineRepository,
        dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
        varCache = varCache,
        varCalculationService = varCalculationService,
        positionProvider = positionProvider,
        volatilityServiceClient = volatilityServiceClient,
        ratesServiceClient = ratesServiceClient,
    )

    fun valuationResult() = ValuationResult(
        bookId = BOOK,
        calculationType = CalculationType.PARAMETRIC,
        confidenceLevel = ConfidenceLevel.CL_95,
        varValue = 100.0,
        expectedShortfall = 120.0,
        componentBreakdown = emptyList(),
        greeks = GreeksResult(
            assetClassGreeks = listOf(
                GreekValues(assetClass = AssetClass.EQUITY, delta = 0.85, gamma = 0.02, vega = 500.0),
            ),
            theta = -10.0,
            rho = 20.0,
        ),
        calculatedAt = Instant.now(),
        computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.GREEKS),
        positionRisk = listOf(
            PositionRisk(
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                marketValue = BigDecimal("15000.00"),
                delta = 0.85,
                gamma = 0.02,
                vega = 500.0,
                varContribution = BigDecimal("100.00"),
                esContribution = BigDecimal("120.00"),
                percentageOfTotal = BigDecimal("100.00"),
            ),
        ),
        jobId = null,
    )

    beforeEach {
        clearMocks(
            sodBaselineRepository, dailyRiskSnapshotRepository, varCache,
            varCalculationService, positionProvider, volatilityServiceClient, ratesServiceClient,
        )
        coEvery { sodBaselineRepository.save(any()) } just Runs
        coEvery { positionProvider.getPositions(BOOK) } returns listOf(
            Position(
                bookId = BOOK,
                instrumentId = AAPL,
                assetClass = AssetClass.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = Money(BigDecimal("150.00"), USD),
                marketPrice = Money(BigDecimal("150.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            ),
        )
    }

    test("captures sodVol on snapshot when vol surface is available") {
        // The vol surface at SOD has impliedVol = 0.22 (22%)
        // The snapshot should store sodVol = 0.22 so intraday attribution can compute volChange
        val surface = VolSurface(
            instrumentId = AAPL,
            asOf = Instant.now(),
            points = listOf(
                VolPoint(BigDecimal("100"), 30, BigDecimal("0.22")),
                VolPoint(BigDecimal("100"), 365, BigDecimal("0.22")),
                VolPoint(BigDecimal("200"), 30, BigDecimal("0.22")),
                VolPoint(BigDecimal("200"), 365, BigDecimal("0.22")),
            ),
            source = VolatilitySource.INTERNAL,
        )
        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.Success(surface)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.NotFound(404)
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs

        makeService().createSnapshot(BOOK, SnapshotType.MANUAL, valuationResult(), DATE)

        coVerify {
            dailyRiskSnapshotRepository.saveAll(withArg { snapshots ->
                snapshots.size shouldBe 1
                snapshots[0].sodVol.shouldNotBeNull()
                snapshots[0].sodVol!! shouldBeGreaterThan 0.0
                // ATM vol on a flat surface at strike~=price is the surface vol
                snapshots[0].sodVol!! shouldBe 0.22
            })
        }
    }

    test("captures sodRate on snapshot when risk-free rate is available") {
        // The risk-free rate at SOD is 4.5%
        // The snapshot should store sodRate = 0.045
        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.NotFound(404)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.Success(
            RiskFreeRate(
                currency = USD,
                tenor = "1Y",
                rate = 0.045,
                asOfDate = Instant.now(),
                source = RateSource.CENTRAL_BANK,
            ),
        )
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs

        makeService().createSnapshot(BOOK, SnapshotType.MANUAL, valuationResult(), DATE)

        coVerify {
            dailyRiskSnapshotRepository.saveAll(withArg { snapshots ->
                snapshots.size shouldBe 1
                snapshots[0].sodRate.shouldNotBeNull()
                snapshots[0].sodRate!! shouldBe 0.045
            })
        }
    }

    test("stores null sodVol when vol service returns NotFound") {
        // If there is no vol surface at SOD (e.g. equity with no listed options),
        // sodVol must be stored as null — downstream will fall back to zero volChange.
        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.NotFound(404)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.NotFound(404)
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs

        makeService().createSnapshot(BOOK, SnapshotType.MANUAL, valuationResult(), DATE)

        coVerify {
            dailyRiskSnapshotRepository.saveAll(withArg { snapshots ->
                snapshots[0].sodVol.shouldBeNull()
            })
        }
    }

    test("stores null sodRate when rates service returns NotFound") {
        coEvery { volatilityServiceClient.getLatestSurface(AAPL) } returns ClientResponse.NotFound(404)
        coEvery { ratesServiceClient.getLatestRiskFreeRate(USD, "1Y") } returns ClientResponse.NotFound(404)
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs

        makeService().createSnapshot(BOOK, SnapshotType.MANUAL, valuationResult(), DATE)

        coVerify {
            dailyRiskSnapshotRepository.saveAll(withArg { snapshots ->
                snapshots[0].sodRate.shouldBeNull()
            })
        }
    }
})
