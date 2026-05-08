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
import com.kinetix.risk.model.ChartBucketRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/**
 * Verifies the dual-parameter audit trail invariant (REG_D-07):
 * both the user-submitted (requested) parameters and the regime-adjusted
 * (effective) parameters must always be recorded on the completed ValuationJob.
 *
 * Without this guarantee, an auditor looking at a completed job cannot
 * distinguish "no override was active" from "the field was never populated" —
 * both produce null values under the old conditional-assignment approach.
 */
class VaRCalculationServiceAuditTrailTest : FunSpec({

    fun position() = Position(
        bookId = BookId("book-1"),
        instrumentId = InstrumentId("AAPL"),
        assetClass = AssetClass.EQUITY,
        quantity = BigDecimal("100"),
        averageCost = Money(BigDecimal("150.00"), Currency.getInstance("USD")),
        marketPrice = Money(BigDecimal("170.00"), Currency.getInstance("USD")),
        instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
    )

    fun valuationResult(calculationType: CalculationType = CalculationType.PARAMETRIC) = ValuationResult(
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

    fun crisisRegime() = RegimeState(
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

    fun makeService(
        regimeStateProvider: (() -> RegimeState?)? = null,
        jobRecorder: ValuationJobRecorder,
    ): VaRCalculationService {
        val positionProvider = mockk<PositionProvider>()
        val riskEngineClient = mockk<RiskEngineClient>()
        val resultPublisher = mockk<RiskResultPublisher>()

        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { resultPublisher.publish(any(), any()) } returns Unit
        coEvery { riskEngineClient.valuate(any(), any(), any(), any()) } answers {
            val req: VaRCalculationRequest = firstArg()
            valuationResult(req.calculationType)
        }

        return VaRCalculationService(
            positionProvider = positionProvider,
            riskEngineClient = riskEngineClient,
            resultPublisher = resultPublisher,
            meterRegistry = SimpleMeterRegistry(),
            activeRegimeProvider = regimeStateProvider,
            jobRecorder = jobRecorder,
        )
    }

    test("on-demand request records requested parameters even when no regime override is active") {
        val capturedJobs = mutableListOf<ValuationJob>()

        val service = makeService(
            regimeStateProvider = null,
            jobRecorder = auditCapturingRecorder(capturedJobs),
        )

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
                timeHorizonDays = 1,
            ),
            triggerType = TriggerType.ON_DEMAND,
            triggeredBy = "alice",
        )

        val completedJob = capturedJobs.last { it.status == RunStatus.COMPLETED }

        // Effective parameters
        completedJob.calculationType shouldBe CalculationType.PARAMETRIC.name
        completedJob.confidenceLevel shouldBe ConfidenceLevel.CL_95.name
        completedJob.timeHorizonDays shouldBe 1

        // Requested parameters must always be recorded so the audit trail is unambiguous
        completedJob.requestedCalculationType shouldBe CalculationType.PARAMETRIC.name
        completedJob.requestedConfidenceLevel shouldBe ConfidenceLevel.CL_95.name
        completedJob.requestedTimeHorizonDays shouldBe 1
    }

    test("scheduled request with NORMAL regime records requested parameters explicitly") {
        val capturedJobs = mutableListOf<ValuationJob>()

        val normalRegime = RegimeState(
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

        val service = makeService(
            regimeStateProvider = { normalRegime },
            jobRecorder = auditCapturingRecorder(capturedJobs),
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

        // Normal regime causes no override, but requested params must still be recorded
        completedJob.requestedCalculationType shouldBe CalculationType.PARAMETRIC.name
        completedJob.requestedConfidenceLevel shouldBe ConfidenceLevel.CL_95.name
        completedJob.requestedTimeHorizonDays shouldBe 1
    }

    test("regime override records requested params that differ from effective params") {
        val capturedJobs = mutableListOf<ValuationJob>()

        val service = makeService(
            regimeStateProvider = { crisisRegime() },
            jobRecorder = auditCapturingRecorder(capturedJobs),
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

        // Requested (user-submitted) params — must differ from effective
        completedJob.requestedCalculationType shouldBe CalculationType.PARAMETRIC.name
        completedJob.requestedConfidenceLevel shouldBe ConfidenceLevel.CL_95.name
        completedJob.requestedTimeHorizonDays shouldBe 1
    }

    test("requested and effective params are both non-null so audit trail is always complete") {
        val capturedJobs = mutableListOf<ValuationJob>()

        val service = makeService(
            regimeStateProvider = null,
            jobRecorder = auditCapturingRecorder(capturedJobs),
        )

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.HISTORICAL,
                confidenceLevel = ConfidenceLevel.CL_99,
                timeHorizonDays = 10,
            ),
            triggerType = TriggerType.SCHEDULED,
            triggeredBy = "SYSTEM",
        )

        val completedJob = capturedJobs.last { it.status == RunStatus.COMPLETED }

        completedJob.requestedCalculationType shouldNotBe null
        completedJob.requestedConfidenceLevel shouldNotBe null
        completedJob.requestedTimeHorizonDays shouldNotBe null
    }

    test("correlation id from the trigger is recorded on the persisted ValuationJob") {
        val capturedJobs = mutableListOf<ValuationJob>()

        val service = makeService(
            regimeStateProvider = null,
            jobRecorder = auditCapturingRecorder(capturedJobs),
        )

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
                timeHorizonDays = 1,
            ),
            triggerType = TriggerType.ON_DEMAND,
            correlationId = "trace-corr-42",
            triggeredBy = "alice",
        )

        capturedJobs.forAll { it.correlationId shouldBe "trace-corr-42" }
    }

    test("correlation id is null when the caller did not supply one") {
        val capturedJobs = mutableListOf<ValuationJob>()

        val service = makeService(
            regimeStateProvider = null,
            jobRecorder = auditCapturingRecorder(capturedJobs),
        )

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("book-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
                timeHorizonDays = 1,
            ),
            triggerType = TriggerType.ON_DEMAND,
            triggeredBy = "alice",
        )

        capturedJobs.forAll { it.correlationId shouldBe null }
    }
})

private fun auditCapturingRecorder(sink: MutableList<ValuationJob>): ValuationJobRecorder =
    object : ValuationJobRecorder {
        override suspend fun save(job: ValuationJob) { sink.add(job) }
        override suspend fun update(job: ValuationJob) { sink.add(job) }
        override suspend fun updateCurrentPhase(jobId: UUID, phase: JobPhaseName) {}
        override suspend fun findByBookId(bookId: String, limit: Int, offset: Int, from: Instant?, to: Instant?, valuationDate: LocalDate?, runLabel: RunLabel?): List<ValuationJob> = emptyList()
        override suspend fun countByBookId(bookId: String, from: Instant?, to: Instant?, valuationDate: LocalDate?, runLabel: RunLabel?): Long = 0L
        override suspend fun findByJobId(jobId: UUID): ValuationJob? = null
        override suspend fun findDistinctBookIds(): List<String> = emptyList()
        override suspend fun findLatestCompletedByDate(bookId: String, valuationDate: LocalDate): ValuationJob? = null
        override suspend fun findLatestCompleted(bookId: String): ValuationJob? = null
        override suspend fun findLatestCompletedBeforeDate(bookId: String, beforeDate: LocalDate): ValuationJob? = null
        override suspend fun findOfficialEodByDate(bookId: String, valuationDate: LocalDate): ValuationJob? = null
        override suspend fun findOfficialEodRange(bookId: String, from: LocalDate, to: LocalDate): List<ValuationJob> = emptyList()
        override suspend fun promoteToOfficialEod(jobId: UUID, promotedBy: String, promotedAt: Instant): ValuationJob = throw UnsupportedOperationException()
        override suspend fun demoteOfficialEod(jobId: UUID): ValuationJob = throw UnsupportedOperationException()
        override suspend fun supersedeOfficialEod(jobId: UUID): ValuationJob = throw UnsupportedOperationException()
        override suspend fun findChartData(bookId: String, from: Instant, to: Instant, bucketInterval: String): List<ChartBucketRow> = emptyList()
        override suspend fun resetOrphanedRunningJobs(): Int = 0
        override suspend fun findByTriggeredBy(triggeredBy: String, limit: Int): List<ValuationJob> = emptyList()
        override suspend fun deleteByTriggeredBy(triggeredBy: String): Int = 0
    }
