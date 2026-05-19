package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.kafka.RiskResultPublisher
import com.kinetix.risk.model.AdaptiveVaRParameters
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ComponentBreakdown
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.ChartBucketRow
import com.kinetix.risk.model.JobPhaseName
import com.kinetix.risk.model.MarketRegime
import com.kinetix.risk.model.RegimeSignals
import com.kinetix.risk.model.RegimeState
import com.kinetix.risk.model.RunLabel
import com.kinetix.risk.model.RunStatus
import com.kinetix.risk.model.TriggerType
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.model.ValuationJob
import com.kinetix.risk.model.ValuationOutput
import com.kinetix.risk.model.ValuationResult
import com.kinetix.risk.service.ValuationJobRecorder
import java.time.LocalDate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun position() = Position(
    bookId = BookId("book-1"),
    instrumentId = InstrumentId("AAPL"),
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal("100"),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal("170.00"), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun valuationResult(calculationType: CalculationType = CalculationType.PARAMETRIC) = ValuationResult(
    bookId = BookId("book-1"),
    calculationType = calculationType,
    confidenceLevel = ConfidenceLevel.CL_95,
    varValue = 5000.0,
    expectedShortfall = 6250.0,
    componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0)),
    greeks = null,
    calculatedAt = Instant.now(),
    computedOutputs = setOf(ValuationOutput.VAR),
)

private fun crisisState() = RegimeState(
    regime = MarketRegime.CRISIS,
    detectedAt = Instant.now(),
    confidence = 0.85,
    signals = RegimeSignals(realisedVol20d = 0.30, crossAssetCorrelation = 0.82),
    varParameters = AdaptiveVaRParameters(
        calculationType = CalculationType.MONTE_CARLO,
        confidenceLevel = ConfidenceLevel.CL_99,
        timeHorizonDays = 5,
        correlationMethod = "stressed",
        numSimulations = 50_000,
    ),
    consecutiveObservations = 3,
    isConfirmed = true,
    degradedInputs = false,
)

class VaRCalculationServiceRegimeOverrideTest : FunSpec({

    fun makeService(
        regimeStateProvider: (() -> RegimeState?)? = null,
    ): Pair<VaRCalculationService, RiskEngineClient> {
        val positionProvider = mockk<PositionProvider>()
        val riskEngineClient = mockk<RiskEngineClient>()
        val resultPublisher = mockk<RiskResultPublisher>()

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { resultPublisher.publish(any(), any()) } returns Unit
        coEvery { riskEngineClient.valuate(any(), any(), any(), any()) } answers {
            val req: VaRCalculationRequest = firstArg()
            valuationResult(req.calculationType)
        }

        val service = VaRCalculationService(
            positionProvider = positionProvider,
            riskEngineClient = riskEngineClient,
            resultPublisher = resultPublisher,
            meterRegistry = SimpleMeterRegistry(),
            activeRegimeProvider = regimeStateProvider,
        )
        return service to riskEngineClient
    }

    test("CRISIS regime overrides PARAMETRIC to MONTE_CARLO for scheduled calculations") {
        val (service, client) = makeService(regimeStateProvider = { crisisState() })

        val capturedRequest = slot<VaRCalculationRequest>()
        coEvery { client.valuate(capture(capturedRequest), any(), any(), any()) } returns
            valuationResult(CalculationType.MONTE_CARLO)

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            ),
            triggerType = TriggerType.SCHEDULED,
            triggeredBy = "SYSTEM",
        )

        capturedRequest.captured.calculationType shouldBe CalculationType.MONTE_CARLO
        capturedRequest.captured.confidenceLevel shouldBe ConfidenceLevel.CL_99
        capturedRequest.captured.timeHorizonDays shouldBe 5
        capturedRequest.captured.numSimulations shouldBe 50_000
    }

    test("CRISIS regime overrides parameters for event-driven calculations") {
        val (service, client) = makeService(regimeStateProvider = { crisisState() })

        val capturedRequest = slot<VaRCalculationRequest>()
        coEvery { client.valuate(capture(capturedRequest), any(), any(), any()) } returns
            valuationResult(CalculationType.MONTE_CARLO)

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            ),
            triggerType = TriggerType.PRICE_EVENT,
            triggeredBy = "SYSTEM",
        )

        capturedRequest.captured.calculationType shouldBe CalculationType.MONTE_CARLO
    }

    test("regime override does NOT apply to on-demand user calculations") {
        val (service, client) = makeService(regimeStateProvider = { crisisState() })

        val capturedRequest = slot<VaRCalculationRequest>()
        coEvery { client.valuate(capture(capturedRequest), any(), any(), any()) } returns
            valuationResult(CalculationType.PARAMETRIC)

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            ),
            triggerType = TriggerType.ON_DEMAND,
            triggeredBy = "alice",
        )

        // On-demand: requested params are used unchanged
        capturedRequest.captured.calculationType shouldBe CalculationType.PARAMETRIC
        capturedRequest.captured.confidenceLevel shouldBe ConfidenceLevel.CL_95
    }

    test("NORMAL regime does not override scheduled calculation parameters") {
        val normalState = RegimeState(
            regime = MarketRegime.NORMAL,
            detectedAt = Instant.now(),
            confidence = 0.92,
            signals = RegimeSignals(realisedVol20d = 0.10, crossAssetCorrelation = 0.40),
            varParameters = AdaptiveVaRParameters(
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
                timeHorizonDays = 1,
                correlationMethod = "standard",
                numSimulations = null,
            ),
            consecutiveObservations = 0,
            isConfirmed = true,
            degradedInputs = false,
        )

        val (service, client) = makeService(regimeStateProvider = { normalState })

        val capturedRequest = slot<VaRCalculationRequest>()
        coEvery { client.valuate(capture(capturedRequest), any(), any(), any()) } returns
            valuationResult(CalculationType.PARAMETRIC)

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            ),
            triggerType = TriggerType.SCHEDULED,
            triggeredBy = "SYSTEM",
        )

        capturedRequest.captured.calculationType shouldBe CalculationType.PARAMETRIC
        capturedRequest.captured.confidenceLevel shouldBe ConfidenceLevel.CL_95
    }

    test("no regime provider means no override — system behaves as before") {
        val (service, client) = makeService(regimeStateProvider = null)

        val capturedRequest = slot<VaRCalculationRequest>()
        coEvery { client.valuate(capture(capturedRequest), any(), any(), any()) } returns
            valuationResult(CalculationType.PARAMETRIC)

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            ),
            triggerType = TriggerType.SCHEDULED,
            triggeredBy = "SYSTEM",
        )

        capturedRequest.captured.calculationType shouldBe CalculationType.PARAMETRIC
    }

    test("unconfirmed regime does not override parameters") {
        val pendingCrisis = crisisState().copy(isConfirmed = false)
        val (service, client) = makeService(regimeStateProvider = { pendingCrisis })

        val capturedRequest = slot<VaRCalculationRequest>()
        coEvery { client.valuate(capture(capturedRequest), any(), any(), any()) } returns
            valuationResult(CalculationType.PARAMETRIC)

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            ),
            triggerType = TriggerType.SCHEDULED,
            triggeredBy = "SYSTEM",
        )

        capturedRequest.captured.calculationType shouldBe CalculationType.PARAMETRIC
    }

    test("regime override records both requested and effective parameters on the job") {
        val capturedJobs = mutableListOf<ValuationJob>()
        val capturingRecorder = capturingJobRecorder(capturedJobs)

        val positionProvider = mockk<PositionProvider>()
        val riskEngineClient = mockk<RiskEngineClient>()
        val resultPublisher = mockk<RiskResultPublisher>()

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { resultPublisher.publish(any(), any()) } returns Unit
        coEvery { riskEngineClient.valuate(any(), any(), any(), any()) } returns
            valuationResult(CalculationType.MONTE_CARLO)

        val service = VaRCalculationService(
            positionProvider = positionProvider,
            riskEngineClient = riskEngineClient,
            resultPublisher = resultPublisher,
            meterRegistry = SimpleMeterRegistry(),
            activeRegimeProvider = { crisisState() },
            jobRecorder = capturingRecorder,
        )

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
                timeHorizonDays = 1,
            ),
            triggerType = TriggerType.SCHEDULED,
            triggeredBy = "SYSTEM",
        )

        val completedJob = capturedJobs.last { it.status == RunStatus.COMPLETED }
        // Effective (regime-overridden) params
        completedJob.calculationType shouldBe CalculationType.MONTE_CARLO.name
        completedJob.confidenceLevel shouldBe ConfidenceLevel.CL_99.name
        completedJob.timeHorizonDays shouldBe 5
        // Requested (original) params — must differ from effective
        completedJob.requestedCalculationType shouldBe CalculationType.PARAMETRIC.name
        completedJob.requestedConfidenceLevel shouldBe ConfidenceLevel.CL_95.name
        completedJob.requestedTimeHorizonDays shouldBe 1
    }

    test("no regime override means requested params equal effective params on the job") {
        val capturedJobs = mutableListOf<ValuationJob>()
        val capturingRecorder = capturingJobRecorder(capturedJobs)

        val positionProvider = mockk<PositionProvider>()
        val riskEngineClient = mockk<RiskEngineClient>()
        val resultPublisher = mockk<RiskResultPublisher>()

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { resultPublisher.publish(any(), any()) } returns Unit
        coEvery { riskEngineClient.valuate(any(), any(), any(), any()) } returns
            valuationResult(CalculationType.PARAMETRIC)

        val service = VaRCalculationService(
            positionProvider = positionProvider,
            riskEngineClient = riskEngineClient,
            resultPublisher = resultPublisher,
            meterRegistry = SimpleMeterRegistry(),
            activeRegimeProvider = null,
            jobRecorder = capturingRecorder,
        )

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            ),
            triggerType = TriggerType.SCHEDULED,
            triggeredBy = "SYSTEM",
        )

        val completedJob = capturedJobs.last { it.status == RunStatus.COMPLETED }
        // Requested params are always recorded — when no override is active they
        // mirror the effective params, making the audit trail unambiguous.
        completedJob.requestedCalculationType shouldBe CalculationType.PARAMETRIC.name
        completedJob.requestedConfidenceLevel shouldBe ConfidenceLevel.CL_95.name
        // timeHorizonDays defaults to 1 in VaRCalculationRequest
        completedJob.requestedTimeHorizonDays shouldBe 1
    }
})

private fun capturingJobRecorder(sink: MutableList<ValuationJob>): ValuationJobRecorder =
    object : ValuationJobRecorder {
        override suspend fun save(job: ValuationJob) { sink.add(job) }
        override suspend fun update(job: ValuationJob) { sink.add(job) }
        override suspend fun updateCurrentPhase(jobId: java.util.UUID, phase: JobPhaseName) {}
        override suspend fun findByBookId(bookId: String, limit: Int, offset: Int, from: java.time.Instant?, to: java.time.Instant?, valuationDate: LocalDate?, runLabel: RunLabel?): List<ValuationJob> = emptyList()
        override suspend fun countByBookId(bookId: String, from: java.time.Instant?, to: java.time.Instant?, valuationDate: LocalDate?, runLabel: RunLabel?): Long = 0L
        override suspend fun findByJobId(jobId: java.util.UUID): ValuationJob? = null
        override suspend fun findDistinctBookIds(): List<String> = emptyList()
        override suspend fun findLatestCompletedByDate(bookId: String, valuationDate: LocalDate): ValuationJob? = null
        override suspend fun findLatestCompleted(bookId: String): ValuationJob? = null
        override suspend fun findLatestCompletedBeforeDate(bookId: String, beforeDate: LocalDate): ValuationJob? = null
        override suspend fun findOfficialEodByDate(bookId: String, valuationDate: LocalDate): ValuationJob? = null
        override suspend fun findOfficialEodRange(bookId: String, from: LocalDate, to: LocalDate): List<ValuationJob> = emptyList()
        override suspend fun promoteToOfficialEod(jobId: java.util.UUID, promotedBy: String, promotedAt: java.time.Instant): ValuationJob = throw UnsupportedOperationException()
        override suspend fun demoteOfficialEod(jobId: java.util.UUID): ValuationJob = throw UnsupportedOperationException()
        override suspend fun supersedeOfficialEod(jobId: java.util.UUID): ValuationJob = throw UnsupportedOperationException()
        override suspend fun findChartData(bookId: String, from: java.time.Instant, to: java.time.Instant, bucketInterval: String): List<ChartBucketRow> = emptyList()
        override suspend fun resetOrphanedRunningJobs(): Int = 0
        override suspend fun findByTriggeredBy(triggeredBy: String, limit: Int): List<ValuationJob> = emptyList()
        override suspend fun deleteByTriggeredBy(triggeredBy: String): Int = 0
        override suspend fun deleteOfficialEodDesignationsByPromotedBy(promotedBy: String): Int = 0
    }
