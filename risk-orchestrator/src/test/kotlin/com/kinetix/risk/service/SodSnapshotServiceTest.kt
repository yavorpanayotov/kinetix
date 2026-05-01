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
import com.kinetix.risk.persistence.SodBaselineRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val PORTFOLIO = BookId("port-1")
private val TODAY = LocalDate.of(2025, 1, 15)
private val JOB_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

private fun position(
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    price: String = "150.00",
) = Position(
    bookId = PORTFOLIO,
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = Money(BigDecimal(price), Currency.getInstance("USD")),
    marketPrice = Money(BigDecimal(price), Currency.getInstance("USD")),
)

private fun valuationResult(
    bookId: BookId = PORTFOLIO,
    jobId: UUID? = JOB_ID,
    positionRisk: List<PositionRisk> = listOf(
        PositionRisk(
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            marketValue = BigDecimal("15000.00"),
            delta = 0.85,
            gamma = 0.02,
            vega = 1500.0,
            varContribution = BigDecimal("500.00"),
            esContribution = BigDecimal("600.00"),
            percentageOfTotal = BigDecimal("100.00"),
        ),
    ),
) = ValuationResult(
    bookId = bookId,
    calculationType = CalculationType.PARAMETRIC,
    confidenceLevel = ConfidenceLevel.CL_95,
    varValue = 500.0,
    expectedShortfall = 600.0,
    componentBreakdown = emptyList(),
    greeks = GreeksResult(
        assetClassGreeks = listOf(
            GreekValues(assetClass = AssetClass.EQUITY, delta = 0.85, gamma = 0.02, vega = 1500.0),
        ),
        theta = -50.0,
        rho = 30.0,
    ),
    calculatedAt = Instant.parse("2025-01-15T08:00:00Z"),
    computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.GREEKS),
    positionRisk = positionRisk,
    jobId = jobId,
)

class SodSnapshotServiceTest : FunSpec({

    val sodBaselineRepository = mockk<SodBaselineRepository>()
    val dailyRiskSnapshotRepository = mockk<DailyRiskSnapshotRepository>()
    val varCache = mockk<VaRCache>()
    val varCalculationService = mockk<VaRCalculationService>()
    val positionProvider = mockk<PositionProvider>()

    val service = SodSnapshotService(
        sodBaselineRepository = sodBaselineRepository,
        dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
        varCache = varCache,
        varCalculationService = varCalculationService,
        positionProvider = positionProvider,
    )

    beforeEach {
        clearMocks(sodBaselineRepository, dailyRiskSnapshotRepository, varCache, varCalculationService, positionProvider)
    }

    test("creates snapshot from provided ValuationResult and stores baseline metadata") {
        val result = valuationResult()
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)

        coVerify {
            dailyRiskSnapshotRepository.saveAll(withArg { snapshots ->
                snapshots.size shouldBe 1
                snapshots[0].bookId shouldBe PORTFOLIO
                snapshots[0].snapshotDate shouldBe TODAY
                snapshots[0].instrumentId shouldBe InstrumentId("AAPL")
                snapshots[0].quantity shouldBe BigDecimal("100")
                snapshots[0].marketPrice shouldBe BigDecimal("150.00")
                snapshots[0].delta shouldBe 0.85
                snapshots[0].gamma shouldBe 0.02
                snapshots[0].vega shouldBe 1500.0
            })
        }
        coVerify {
            sodBaselineRepository.save(withArg { baseline ->
                baseline.bookId shouldBe PORTFOLIO
                baseline.baselineDate shouldBe TODAY
                baseline.snapshotType shouldBe SnapshotType.MANUAL
                baseline.sourceJobId shouldBe JOB_ID
                baseline.calculationType shouldBe "PARAMETRIC"
            })
        }
    }

    test("creates snapshot using cached VaR result when no ValuationResult provided") {
        val result = valuationResult().copy(calculatedAt = Instant.now().minus(java.time.Duration.ofMinutes(30)))
        coEvery { varCache.get(PORTFOLIO.value) } returns result
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, date = TODAY)

        coVerify { varCache.get(PORTFOLIO.value) }
        coVerify { dailyRiskSnapshotRepository.saveAll(any()) }
        coVerify { sodBaselineRepository.save(any()) }
    }

    test("triggers VaR calculation when no cached result and no ValuationResult provided") {
        val result = valuationResult()
        coEvery { varCache.get(PORTFOLIO.value) } returns null
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { varCalculationService.calculateVaR(any(), any(), runLabel = any()) } returns result
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.AUTO, date = TODAY)

        coVerify { varCalculationService.calculateVaR(any(), any(), runLabel = RunLabel.SOD) }
        coVerify { dailyRiskSnapshotRepository.saveAll(any()) }
    }

    test("replaces existing baseline when creating new snapshot for same date") {
        val result = valuationResult()
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)
        service.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)

        coVerify(exactly = 2) { sodBaselineRepository.save(any()) }
    }

    test("getBaselineStatus returns status with exists=true when baseline exists") {
        val baseline = SodBaseline(
            id = 1,
            bookId = PORTFOLIO,
            baselineDate = TODAY,
            snapshotType = SnapshotType.MANUAL,
            createdAt = Instant.parse("2025-01-15T08:00:00Z"),
            sourceJobId = JOB_ID,
            calculationType = "PARAMETRIC",
        )
        coEvery { sodBaselineRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns baseline

        val status = service.getBaselineStatus(PORTFOLIO, TODAY)

        status.exists shouldBe true
        status.snapshotType shouldBe SnapshotType.MANUAL
        status.createdAt shouldBe Instant.parse("2025-01-15T08:00:00Z")
        status.baselineDate shouldBe "2025-01-15"
        status.sourceJobId shouldBe JOB_ID.toString()
        status.calculationType shouldBe "PARAMETRIC"
    }

    test("getBaselineStatus returns status with exists=false when no baseline") {
        coEvery { sodBaselineRepository.findByBookIdAndDate(PORTFOLIO, TODAY) } returns null

        val status = service.getBaselineStatus(PORTFOLIO, TODAY)

        status.exists shouldBe false
        status.snapshotType shouldBe null
        status.createdAt shouldBe null
        status.baselineDate shouldBe null
    }

    test("resetBaseline deletes baseline and snapshot rows") {
        coEvery { dailyRiskSnapshotRepository.deleteByBookIdAndDate(PORTFOLIO, TODAY) } just Runs
        coEvery { sodBaselineRepository.deleteByBookIdAndDate(PORTFOLIO, TODAY) } just Runs

        service.resetBaseline(PORTFOLIO, TODAY)

        coVerify { dailyRiskSnapshotRepository.deleteByBookIdAndDate(PORTFOLIO, TODAY) }
        coVerify { sodBaselineRepository.deleteByBookIdAndDate(PORTFOLIO, TODAY) }
    }

    test("creates snapshot with null jobId for backward compatibility") {
        val result = valuationResult(jobId = null)
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)

        coVerify {
            sodBaselineRepository.save(withArg { baseline ->
                baseline.sourceJobId shouldBe null
                baseline.calculationType shouldBe "PARAMETRIC"
            })
        }
    }

    test("createSnapshotFromJob creates snapshot from specific completed job") {
        val jobRecorder = mockk<ValuationJobRecorder>()
        val serviceWithRecorder = SodSnapshotService(
            sodBaselineRepository, dailyRiskSnapshotRepository,
            varCache, varCalculationService, positionProvider, jobRecorder,
        )
        val job = ValuationJob(
            jobId = JOB_ID,
            bookId = PORTFOLIO.value,
            triggerType = TriggerType.ON_DEMAND,
            status = RunStatus.COMPLETED,
            startedAt = Instant.parse("2025-01-15T07:00:00Z"),
            valuationDate = TODAY,
            completedAt = Instant.parse("2025-01-15T07:01:00Z"),
            calculationType = "PARAMETRIC",
            confidenceLevel = "CL_95",
            triggeredBy = "user-a",
        )
        coEvery { jobRecorder.findByJobId(JOB_ID) } returns job
        coEvery { varCalculationService.calculateVaR(any(), any()) } returns valuationResult()
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        serviceWithRecorder.createSnapshotFromJob(PORTFOLIO, JOB_ID, TODAY)

        coVerify { varCalculationService.calculateVaR(any(), TriggerType.SCHEDULED) }
        coVerify { sodBaselineRepository.save(any()) }
    }

    test("createSnapshotFromJob throws when job not found") {
        val jobRecorder = mockk<ValuationJobRecorder>()
        val serviceWithRecorder = SodSnapshotService(
            sodBaselineRepository, dailyRiskSnapshotRepository,
            varCache, varCalculationService, positionProvider, jobRecorder,
        )
        coEvery { jobRecorder.findByJobId(JOB_ID) } returns null

        val ex = shouldThrow<IllegalArgumentException> {
            serviceWithRecorder.createSnapshotFromJob(PORTFOLIO, JOB_ID, TODAY)
        }
        ex.message shouldContain "not found"
    }

    test("createSnapshotFromJob throws when job not completed") {
        val jobRecorder = mockk<ValuationJobRecorder>()
        val serviceWithRecorder = SodSnapshotService(
            sodBaselineRepository, dailyRiskSnapshotRepository,
            varCache, varCalculationService, positionProvider, jobRecorder,
        )
        val job = ValuationJob(
            jobId = JOB_ID,
            bookId = PORTFOLIO.value,
            triggerType = TriggerType.ON_DEMAND,
            status = RunStatus.RUNNING,
            startedAt = Instant.parse("2025-01-15T07:00:00Z"),
            valuationDate = TODAY,
            triggeredBy = "user-a",
        )
        coEvery { jobRecorder.findByJobId(JOB_ID) } returns job

        val ex = shouldThrow<IllegalArgumentException> {
            serviceWithRecorder.createSnapshotFromJob(PORTFOLIO, JOB_ID, TODAY)
        }
        ex.message shouldContain "not completed"
    }

    test("createSnapshotFromJob throws when job belongs to different portfolio") {
        val jobRecorder = mockk<ValuationJobRecorder>()
        val serviceWithRecorder = SodSnapshotService(
            sodBaselineRepository, dailyRiskSnapshotRepository,
            varCache, varCalculationService, positionProvider, jobRecorder,
        )
        val job = ValuationJob(
            jobId = JOB_ID,
            bookId = "other-portfolio",
            triggerType = TriggerType.ON_DEMAND,
            status = RunStatus.COMPLETED,
            startedAt = Instant.parse("2025-01-15T07:00:00Z"),
            valuationDate = TODAY,
            triggeredBy = "user-a",
        )
        coEvery { jobRecorder.findByJobId(JOB_ID) } returns job

        val ex = shouldThrow<IllegalArgumentException> {
            serviceWithRecorder.createSnapshotFromJob(PORTFOLIO, JOB_ID, TODAY)
        }
        ex.message shouldContain "belongs to portfolio"
    }

    test("creates snapshot with multiple position risks from ValuationResult") {
        val result = valuationResult(
            positionRisk = listOf(
                PositionRisk(
                    instrumentId = InstrumentId("AAPL"),
                    assetClass = AssetClass.EQUITY,
                    marketValue = BigDecimal("15000.00"),
                    delta = 0.85, gamma = 0.02, vega = 1500.0,
                    varContribution = BigDecimal("300.00"),
                    esContribution = BigDecimal("400.00"),
                    percentageOfTotal = BigDecimal("60.00"),
                ),
                PositionRisk(
                    instrumentId = InstrumentId("MSFT"),
                    assetClass = AssetClass.EQUITY,
                    marketValue = BigDecimal("30000.00"),
                    delta = 0.90, gamma = 0.03, vega = 2000.0,
                    varContribution = BigDecimal("200.00"),
                    esContribution = BigDecimal("250.00"),
                    percentageOfTotal = BigDecimal("40.00"),
                ),
            ),
        )
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(
            position(instrumentId = "AAPL", quantity = "100", price = "150.00"),
            position(instrumentId = "MSFT", quantity = "200", price = "300.00"),
        )
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)

        coVerify {
            dailyRiskSnapshotRepository.saveAll(withArg { snapshots ->
                snapshots.size shouldBe 2
                snapshots[0].instrumentId shouldBe InstrumentId("AAPL")
                snapshots[0].quantity shouldBe BigDecimal("100")
                snapshots[0].marketPrice shouldBe BigDecimal("150.00")
                snapshots[1].instrumentId shouldBe InstrumentId("MSFT")
                snapshots[1].quantity shouldBe BigDecimal("200")
                snapshots[1].marketPrice shouldBe BigDecimal("300.00")
            })
        }
    }

    test("uses fallback values when position not found for an instrument") {
        val result = valuationResult()
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns emptyList()
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)

        coVerify {
            dailyRiskSnapshotRepository.saveAll(withArg { snapshots ->
                snapshots.size shouldBe 1
                snapshots[0].quantity shouldBe BigDecimal.ONE
                snapshots[0].marketPrice shouldBe BigDecimal("15000.00")
            })
        }
    }

    test("calculateFreshVaR passes RunLabel.SOD to calculateVaR") {
        val result = valuationResult()
        coEvery { varCache.get(PORTFOLIO.value) } returns null
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { varCalculationService.calculateVaR(any(), any(), runLabel = any()) } returns result
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.AUTO, date = TODAY)

        coVerify { varCalculationService.calculateVaR(any(), any(), runLabel = RunLabel.SOD) }
    }

    test("rejects stale cached result and recalculates") {
        val staleResult = valuationResult().copy(
            calculatedAt = Instant.now().minus(java.time.Duration.ofHours(3)),
        )
        val freshResult = valuationResult()
        coEvery { varCache.get(PORTFOLIO.value) } returns staleResult
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { varCalculationService.calculateVaR(any(), any(), runLabel = any()) } returns freshResult
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.AUTO, date = TODAY)

        coVerify { varCalculationService.calculateVaR(any(), any(), runLabel = RunLabel.SOD) }
    }

    test("accepts fresh cached result without recalculating") {
        val freshResult = valuationResult().copy(
            calculatedAt = Instant.now().minus(java.time.Duration.ofMinutes(30)),
        )
        coEvery { varCache.get(PORTFOLIO.value) } returns freshResult
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.AUTO, date = TODAY)

        coVerify(exactly = 0) { varCalculationService.calculateVaR(any(), any(), runLabel = any()) }
    }

    test("stores varValue and expectedShortfall on SodBaseline") {
        val result = valuationResult()
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)

        coVerify {
            sodBaselineRepository.save(withArg { baseline ->
                baseline.varValue shouldBe 500.0
                baseline.expectedShortfall shouldBe 600.0
            })
        }
    }

    // ------------------------------------------------------------
    // SOD pricing-Greek population (audit A-3 Phase 2)
    // ------------------------------------------------------------

    test("calls pricingGreeksClient and persists pricing-Greek rows when both are wired") {
        val pricingGreeksClient = mockk<com.kinetix.risk.client.PricingGreeksClient>()
        val sodGreekSnapshotRepo = mockk<com.kinetix.risk.persistence.SodGreekSnapshotRepository>()
        val wiredService = SodSnapshotService(
            sodBaselineRepository = sodBaselineRepository,
            dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
            varCache = varCache,
            varCalculationService = varCalculationService,
            positionProvider = positionProvider,
            pricingGreeksClient = pricingGreeksClient,
            sodGreekSnapshotRepository = sodGreekSnapshotRepo,
        )

        val result = valuationResult()
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs
        coEvery { pricingGreeksClient.calculatePricingGreeks(any()) } returns listOf(
            com.kinetix.risk.client.PricingGreeksResult(
                instrumentId = "AAPL",
                delta = 1.0,
                gamma = 0.0, vega = 0.0, theta = 0.0, rho = 0.0,
                vanna = 0.0, volga = 0.0, charm = 0.0,
                bondDv01 = 0.0, swapDv01 = 0.0,
            ),
        )
        coEvery { sodGreekSnapshotRepo.saveAll(any()) } just Runs

        wiredService.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)

        coVerify(exactly = 1) { pricingGreeksClient.calculatePricingGreeks(any()) }
        coVerify {
            sodGreekSnapshotRepo.saveAll(withArg { rows ->
                rows.size shouldBe 1
                rows[0].instrumentId shouldBe InstrumentId("AAPL")
                rows[0].bookId shouldBe PORTFOLIO
                rows[0].snapshotDate shouldBe TODAY
                rows[0].delta shouldBe 1.0
                // Zero-valued Greeks are stored as null to distinguish "not applicable"
                // from "computed and is zero".
                rows[0].gamma shouldBe null
            })
        }
    }

    test("does not call pricingGreeksClient when client is null (legacy wiring)") {
        // Service is constructed without pricingGreeksClient — must succeed without
        // touching the missing dependency.
        val result = valuationResult()
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs

        service.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)

        // The underlying SOD baseline still persists; the pricing-Greek path is a no-op.
        coVerify { sodBaselineRepository.save(any()) }
    }

    test("does not fail the SOD job when pricingGreeksClient throws") {
        val pricingGreeksClient = mockk<com.kinetix.risk.client.PricingGreeksClient>()
        val sodGreekSnapshotRepo = mockk<com.kinetix.risk.persistence.SodGreekSnapshotRepository>()
        val wiredService = SodSnapshotService(
            sodBaselineRepository = sodBaselineRepository,
            dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
            varCache = varCache,
            varCalculationService = varCalculationService,
            positionProvider = positionProvider,
            pricingGreeksClient = pricingGreeksClient,
            sodGreekSnapshotRepository = sodGreekSnapshotRepo,
        )

        val result = valuationResult()
        coEvery { positionProvider.getPositions(PORTFOLIO) } returns listOf(position())
        coEvery { dailyRiskSnapshotRepository.saveAll(any()) } just Runs
        coEvery { sodBaselineRepository.save(any()) } just Runs
        coEvery { pricingGreeksClient.calculatePricingGreeks(any()) } throws
            RuntimeException("risk-engine unavailable")

        // SOD job must complete successfully — consumers will fall back to VaR Greeks.
        wiredService.createSnapshot(PORTFOLIO, SnapshotType.MANUAL, result, TODAY)

        coVerify { sodBaselineRepository.save(any()) }
        coVerify(exactly = 0) { sodGreekSnapshotRepo.saveAll(any()) }
    }
})
