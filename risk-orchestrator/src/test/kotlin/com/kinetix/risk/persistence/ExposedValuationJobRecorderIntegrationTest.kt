package com.kinetix.risk.persistence

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private fun startedJob(
    bookId: String = "port-1",
    triggerType: TriggerType = TriggerType.ON_DEMAND,
    startedAt: Instant = Instant.parse("2025-01-15T10:00:00Z"),
    valuationDate: LocalDate = LocalDate.of(2025, 1, 15),
) = ValuationJob(
    jobId = UUID.randomUUID(),
    bookId = bookId,
    triggerType = triggerType,
    status = RunStatus.RUNNING,
    startedAt = startedAt,
    valuationDate = valuationDate,
    calculationType = "PARAMETRIC",
    confidenceLevel = "CL_95",
    triggeredBy = "SYSTEM",
)

private fun completedJob(
    bookId: String = "port-1",
    triggerType: TriggerType = TriggerType.ON_DEMAND,
    startedAt: Instant = Instant.parse("2025-01-15T10:00:00Z"),
    varValue: Double = 5000.0,
    valuationDate: LocalDate = LocalDate.of(2025, 1, 15),
) = ValuationJob(
    jobId = UUID.randomUUID(),
    bookId = bookId,
    triggerType = triggerType,
    status = RunStatus.COMPLETED,
    startedAt = startedAt,
    valuationDate = valuationDate,
    completedAt = startedAt.plusMillis(150),
    durationMs = 150,
    calculationType = "PARAMETRIC",
    confidenceLevel = "CL_95",
    varValue = varValue,
    expectedShortfall = varValue * 1.25,
    pvValue = 1_800_000.0,
    phases = listOf(
        JobPhase(
            name = JobPhaseName.FETCH_POSITIONS,
            status = RunStatus.COMPLETED,
            startedAt = startedAt,
            completedAt = startedAt.plusMillis(20),
            durationMs = 20,
            details = mapOf("positionCount" to 5),
        ),
        JobPhase(
            name = JobPhaseName.DISCOVER_DEPENDENCIES,
            status = RunStatus.COMPLETED,
            startedAt = startedAt.plusMillis(20),
            completedAt = startedAt.plusMillis(50),
            durationMs = 30,
            details = mapOf("dependencyCount" to 3, "dataTypes" to "SPOT_PRICE,YIELD_CURVE"),
        ),
        JobPhase(
            name = JobPhaseName.FETCH_MARKET_DATA,
            status = RunStatus.COMPLETED,
            startedAt = startedAt.plusMillis(50),
            completedAt = startedAt.plusMillis(80),
            durationMs = 30,
            details = mapOf("requested" to 3, "fetched" to 2),
        ),
        JobPhase(
            name = JobPhaseName.VALUATION,
            status = RunStatus.COMPLETED,
            startedAt = startedAt.plusMillis(80),
            completedAt = startedAt.plusMillis(130),
            durationMs = 50,
            details = mapOf("varValue" to 5000.0, "expectedShortfall" to 6250.0),
        ),
        JobPhase(
            name = JobPhaseName.PUBLISH_RESULT,
            status = RunStatus.COMPLETED,
            startedAt = startedAt.plusMillis(130),
            completedAt = startedAt.plusMillis(150),
            durationMs = 20,
            details = mapOf("topic" to "risk.results"),
        ),
    ),
    triggeredBy = "user-a",
)

class ExposedValuationJobRecorderIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val recorder = ExposedValuationJobRecorder(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            ValuationJobsTable.deleteAll()
            OfficialEodDesignationsTable.deleteAll()
        }
    }

    test("saves and retrieves a completed valuation job") {
        val job = completedJob()
        recorder.save(job)

        val found = recorder.findByJobId(job.jobId)
        found.shouldNotBeNull()
        found.jobId shouldBe job.jobId
        found.bookId shouldBe "port-1"
        found.triggerType shouldBe TriggerType.ON_DEMAND
        found.status shouldBe RunStatus.COMPLETED
        found.calculationType shouldBe "PARAMETRIC"
        found.confidenceLevel shouldBe "CL_95"
        found.varValue shouldBe 5000.0
        found.expectedShortfall shouldBe 6250.0
        found.pvValue shouldBe 1_800_000.0
        found.durationMs shouldBe 150
        found.error shouldBe null
        found.phases shouldHaveSize 5
        found.phases[0].name shouldBe JobPhaseName.FETCH_POSITIONS
        found.phases[0].details["positionCount"] shouldBe "5"
        found.phases[4].name shouldBe JobPhaseName.PUBLISH_RESULT
    }

    test("lists jobs ordered by started_at descending") {
        val job1 = completedJob(startedAt = Instant.parse("2025-01-15T10:00:00Z"))
        val job2 = completedJob(startedAt = Instant.parse("2025-01-15T11:00:00Z"))
        val job3 = completedJob(startedAt = Instant.parse("2025-01-15T09:00:00Z"))

        recorder.save(job1)
        recorder.save(job2)
        recorder.save(job3)

        val jobs = recorder.findByBookId("port-1")
        jobs shouldHaveSize 3
        jobs[0].jobId shouldBe job2.jobId
        jobs[1].jobId shouldBe job1.jobId
        jobs[2].jobId shouldBe job3.jobId
    }

    test("returns null for unknown job ID") {
        recorder.findByJobId(UUID.randomUUID()).shouldBeNull()
    }

    test("filters jobs by time range") {
        val job1 = completedJob(startedAt = Instant.parse("2025-01-15T08:00:00Z"))
        val job2 = completedJob(startedAt = Instant.parse("2025-01-15T10:00:00Z"))
        val job3 = completedJob(startedAt = Instant.parse("2025-01-15T12:00:00Z"))

        recorder.save(job1)
        recorder.save(job2)
        recorder.save(job3)

        // from only
        val afterNine = recorder.findByBookId(
            "port-1",
            from = Instant.parse("2025-01-15T09:00:00Z"),
        )
        afterNine shouldHaveSize 2
        afterNine[0].jobId shouldBe job3.jobId
        afterNine[1].jobId shouldBe job2.jobId

        // to only
        val beforeEleven = recorder.findByBookId(
            "port-1",
            to = Instant.parse("2025-01-15T11:00:00Z"),
        )
        beforeEleven shouldHaveSize 2
        beforeEleven[0].jobId shouldBe job2.jobId
        beforeEleven[1].jobId shouldBe job1.jobId

        // from and to
        val range = recorder.findByBookId(
            "port-1",
            from = Instant.parse("2025-01-15T09:00:00Z"),
            to = Instant.parse("2025-01-15T11:00:00Z"),
        )
        range shouldHaveSize 1
        range[0].jobId shouldBe job2.jobId

        // no filter returns all
        val all = recorder.findByBookId("port-1")
        all shouldHaveSize 3
    }

    test("updates a RUNNING job to COMPLETED with all fields") {
        val job = startedJob()
        recorder.save(job)

        val found = recorder.findByJobId(job.jobId)
        found.shouldNotBeNull()
        found.status shouldBe RunStatus.RUNNING
        found.completedAt.shouldBeNull()
        found.varValue.shouldBeNull()

        val completedAt = job.startedAt.plusMillis(200)
        val updatedJob = job.copy(
            status = RunStatus.COMPLETED,
            completedAt = completedAt,
            durationMs = 200,
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            pvValue = 1_800_000.0,
            phases = listOf(
                JobPhase(
                    name = JobPhaseName.FETCH_POSITIONS,
                    status = RunStatus.COMPLETED,
                    startedAt = job.startedAt,
                    completedAt = job.startedAt.plusMillis(20),
                    durationMs = 20,
                    details = mapOf("positionCount" to 5),
                ),
            ),
        )
        recorder.update(updatedJob)

        val updated = recorder.findByJobId(job.jobId)
        updated.shouldNotBeNull()
        updated.status shouldBe RunStatus.COMPLETED
        updated.completedAt shouldBe completedAt
        updated.durationMs shouldBe 200
        updated.varValue shouldBe 5000.0
        updated.expectedShortfall shouldBe 6250.0
        updated.pvValue shouldBe 1_800_000.0
        updated.phases shouldHaveSize 1
        updated.phases[0].name shouldBe JobPhaseName.FETCH_POSITIONS
        updated.error shouldBe null
    }

    test("updates a RUNNING job to FAILED with error") {
        val job = startedJob()
        recorder.save(job)

        val completedAt = job.startedAt.plusMillis(50)
        val failedJob = job.copy(
            status = RunStatus.FAILED,
            completedAt = completedAt,
            durationMs = 50,
            error = "Engine down",
        )
        recorder.update(failedJob)

        val updated = recorder.findByJobId(job.jobId)
        updated.shouldNotBeNull()
        updated.status shouldBe RunStatus.FAILED
        updated.error shouldBe "Engine down"
        updated.durationMs shouldBe 50
    }

    test("respects limit and offset") {
        for (i in 0 until 5) {
            recorder.save(completedJob(startedAt = Instant.parse("2025-01-15T${10 + i}:00:00Z")))
        }

        val page1 = recorder.findByBookId("port-1", limit = 2, offset = 0)
        page1 shouldHaveSize 2

        val page2 = recorder.findByBookId("port-1", limit = 2, offset = 2)
        page2 shouldHaveSize 2

        val page3 = recorder.findByBookId("port-1", limit = 2, offset = 4)
        page3 shouldHaveSize 1

        // No overlap between pages
        val allIds = (page1 + page2 + page3).map { it.jobId }.toSet()
        allIds shouldHaveSize 5
    }

    test("persists and retrieves position risk JSONB snapshot on update") {
        val job = startedJob()
        recorder.save(job)

        val positionRisk = listOf(
            PositionRisk(
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                marketValue = BigDecimal("17000.00"),
                delta = 0.85,
                gamma = 0.02,
                vega = null,
                varContribution = BigDecimal("3000.00"),
                esContribution = BigDecimal("3750.00"),
                percentageOfTotal = BigDecimal("60.00"),
            ),
            PositionRisk(
                instrumentId = InstrumentId("GOOGL"),
                assetClass = AssetClass.EQUITY,
                marketValue = BigDecimal("25000.00"),
                delta = 1.15,
                gamma = 0.03,
                vega = 500.0,
                varContribution = BigDecimal("2000.00"),
                esContribution = BigDecimal("2500.00"),
                percentageOfTotal = BigDecimal("40.00"),
            ),
        )
        val componentBreakdown = listOf(
            ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0),
        )
        val assetClassGreeks = listOf(
            GreekValues(AssetClass.EQUITY, 2.0, 0.05, 500.0),
        )
        val computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL, ValuationOutput.GREEKS)

        val updatedJob = job.copy(
            status = RunStatus.COMPLETED,
            completedAt = job.startedAt.plusMillis(200),
            durationMs = 200,
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            positionRiskSnapshot = positionRisk,
            componentBreakdownSnapshot = componentBreakdown,
            computedOutputsSnapshot = computedOutputs,
            assetClassGreeksSnapshot = assetClassGreeks,
        )
        recorder.update(updatedJob)

        val found = recorder.findByJobId(job.jobId)
        found.shouldNotBeNull()
        found.positionRiskSnapshot shouldHaveSize 2
        found.positionRiskSnapshot[0].instrumentId shouldBe InstrumentId("AAPL")
        found.positionRiskSnapshot[0].marketValue.compareTo(BigDecimal("17000.00")) shouldBe 0
        found.positionRiskSnapshot[0].delta shouldBe 0.85
        found.positionRiskSnapshot[0].vega shouldBe null
        found.positionRiskSnapshot[1].instrumentId shouldBe InstrumentId("GOOGL")
        found.positionRiskSnapshot[1].vega shouldBe 500.0

        found.componentBreakdownSnapshot shouldHaveSize 1
        found.componentBreakdownSnapshot[0].assetClass shouldBe AssetClass.EQUITY
        found.componentBreakdownSnapshot[0].varContribution shouldBe 5000.0

        found.assetClassGreeksSnapshot shouldHaveSize 1
        found.assetClassGreeksSnapshot[0].delta shouldBe 2.0

        found.computedOutputsSnapshot shouldBe computedOutputs
    }

    test("returns empty snapshot lists for jobs saved without JSONB data") {
        val job = completedJob()
        recorder.save(job)

        val found = recorder.findByJobId(job.jobId)
        found.shouldNotBeNull()
        found.positionRiskSnapshot shouldHaveSize 0
        found.componentBreakdownSnapshot shouldHaveSize 0
        found.assetClassGreeksSnapshot shouldHaveSize 0
        found.computedOutputsSnapshot shouldBe emptySet()
    }

    test("findDistinctBookIds returns all unique portfolio IDs") {
        recorder.save(completedJob(bookId = "port-1", startedAt = Instant.parse("2025-01-15T10:00:00Z")))
        recorder.save(completedJob(bookId = "port-2", startedAt = Instant.parse("2025-01-15T11:00:00Z")))
        recorder.save(completedJob(bookId = "port-1", startedAt = Instant.parse("2025-01-15T12:00:00Z")))

        val portfolios = recorder.findDistinctBookIds()
        portfolios shouldHaveSize 2
        portfolios shouldContainExactlyInAnyOrder listOf("port-1", "port-2")
    }

    test("findLatestCompleted returns most recent COMPLETED job") {
        val olderJob = completedJob(startedAt = Instant.parse("2025-01-15T10:00:00Z"))
        recorder.save(olderJob)

        val newerJob = completedJob(startedAt = Instant.parse("2025-01-15T12:00:00Z"), varValue = 7000.0)
        recorder.save(newerJob)

        // Also save a RUNNING job (should be skipped)
        recorder.save(startedJob(startedAt = Instant.parse("2025-01-15T13:00:00Z")))

        val latest = recorder.findLatestCompleted("port-1")
        latest.shouldNotBeNull()
        latest.jobId shouldBe newerJob.jobId
        latest.varValue shouldBe 7000.0
    }

    test("findLatestCompleted returns completed job even without position risk data") {
        recorder.save(completedJob())
        recorder.save(startedJob(startedAt = Instant.parse("2025-01-15T11:00:00Z")))

        val latest = recorder.findLatestCompleted("port-1")
        latest.shouldNotBeNull()
        latest.status shouldBe RunStatus.COMPLETED
        latest.positionRiskSnapshot shouldHaveSize 0
    }

    test("findLatestCompleted returns null for unknown portfolio") {
        recorder.save(completedJob())
        recorder.findLatestCompleted("unknown-portfolio").shouldBeNull()
    }

    test("saves and reads back valuationDate correctly") {
        val date = LocalDate.of(2025, 3, 10)
        val job = completedJob(valuationDate = date)
        recorder.save(job)

        val found = recorder.findByJobId(job.jobId)
        found.shouldNotBeNull()
        found.valuationDate shouldBe date
    }

    test("update does NOT change valuationDate — prevents midnight-crossing bug") {
        val originalDate = LocalDate.of(2025, 1, 15)
        val job = startedJob(
            startedAt = Instant.parse("2025-01-15T23:59:00Z"),
            valuationDate = originalDate,
        )
        recorder.save(job)

        val updatedJob = job.copy(
            status = RunStatus.COMPLETED,
            completedAt = Instant.parse("2025-01-16T00:01:00Z"),
            durationMs = 120_000,
            valuationDate = LocalDate.of(2025, 1, 16), // caller might wrongly pass new date
        )
        recorder.update(updatedJob)

        val found = recorder.findByJobId(job.jobId)
        found.shouldNotBeNull()
        found.valuationDate shouldBe originalDate // date stays at original
    }

    test("findLatestCompletedByDate returns the job with latest startedAt for that date") {
        val date = LocalDate.of(2025, 3, 10)
        val olderJob = completedJob(
            startedAt = Instant.parse("2025-03-10T06:00:00Z"),
            valuationDate = date,
            varValue = 4000.0,
        )
        val newerJob = completedJob(
            startedAt = Instant.parse("2025-03-10T14:00:00Z"),
            valuationDate = date,
            varValue = 5000.0,
        )
        recorder.save(olderJob)
        recorder.save(newerJob)

        val found = recorder.findLatestCompletedByDate("port-1", date)
        found.shouldNotBeNull()
        found.jobId shouldBe newerJob.jobId
        found.varValue shouldBe 5000.0
    }

    test("findLatestCompletedByDate returns null when only RUNNING jobs exist for date") {
        val date = LocalDate.of(2025, 3, 10)
        recorder.save(startedJob(
            startedAt = Instant.parse("2025-03-10T10:00:00Z"),
            valuationDate = date,
        ))

        recorder.findLatestCompletedByDate("port-1", date).shouldBeNull()
    }

    test("findLatestCompletedByDate returns null when only FAILED jobs exist for date") {
        val date = LocalDate.of(2025, 3, 10)
        val job = startedJob(
            startedAt = Instant.parse("2025-03-10T10:00:00Z"),
            valuationDate = date,
        )
        recorder.save(job)
        recorder.update(job.copy(
            status = RunStatus.FAILED,
            completedAt = job.startedAt.plusMillis(50),
            durationMs = 50,
            error = "Engine down",
        ))

        recorder.findLatestCompletedByDate("port-1", date).shouldBeNull()
    }

    test("findLatestCompletedByDate returns null for wrong date even if other dates have completions") {
        val date10 = LocalDate.of(2025, 3, 10)
        val date11 = LocalDate.of(2025, 3, 11)
        recorder.save(completedJob(
            startedAt = Instant.parse("2025-03-10T10:00:00Z"),
            valuationDate = date10,
        ))

        recorder.findLatestCompletedByDate("port-1", date11).shouldBeNull()
    }

    test("findLatestCompletedByDate returns null for unknown portfolio") {
        val date = LocalDate.of(2025, 3, 10)
        recorder.save(completedJob(
            startedAt = Instant.parse("2025-03-10T10:00:00Z"),
            valuationDate = date,
        ))

        recorder.findLatestCompletedByDate("unknown-portfolio", date).shouldBeNull()
    }

    test("findByBookId with valuationDate filter returns only jobs for that date") {
        val date10 = LocalDate.of(2025, 3, 10)
        val date11 = LocalDate.of(2025, 3, 11)
        val job10 = completedJob(
            startedAt = Instant.parse("2025-03-10T10:00:00Z"),
            valuationDate = date10,
        )
        val job11 = completedJob(
            startedAt = Instant.parse("2025-03-11T10:00:00Z"),
            valuationDate = date11,
        )
        recorder.save(job10)
        recorder.save(job11)

        val filtered = recorder.findByBookId("port-1", valuationDate = date10)
        filtered shouldHaveSize 1
        filtered[0].jobId shouldBe job10.jobId

        val all = recorder.findByBookId("port-1")
        all shouldHaveSize 2
    }

    test("promoteToOfficialEod sets runLabel, promotedAt, promotedBy and creates designation") {
        val job = completedJob(valuationDate = LocalDate.of(2025, 3, 10))
        recorder.save(job)

        val promoted = recorder.promoteToOfficialEod(job.jobId, "risk-mgr", Instant.parse("2025-03-10T18:00:00Z"))

        promoted.runLabel shouldBe RunLabel.OFFICIAL_EOD
        promoted.promotedBy shouldBe "risk-mgr"
        promoted.promotedAt shouldBe Instant.parse("2025-03-10T18:00:00Z")

        val found = recorder.findOfficialEodByDate("port-1", LocalDate.of(2025, 3, 10))
        found.shouldNotBeNull()
        found.jobId shouldBe job.jobId
    }

    test("promoteToOfficialEod rejects duplicate designation for same portfolio and date") {
        val date = LocalDate.of(2025, 3, 10)
        val job1 = completedJob(startedAt = Instant.parse("2025-03-10T10:00:00Z"), valuationDate = date)
        val job2 = completedJob(startedAt = Instant.parse("2025-03-10T14:00:00Z"), valuationDate = date)
        recorder.save(job1)
        recorder.save(job2)

        recorder.promoteToOfficialEod(job1.jobId, "risk-mgr", Instant.parse("2025-03-10T18:00:00Z"))

        shouldThrow<EodPromotionException.ConflictingOfficialEod> {
            withContext(Dispatchers.IO) {
                recorder.promoteToOfficialEod(job2.jobId, "risk-mgr", Instant.parse("2025-03-10T18:30:00Z"))
            }
        }
    }

    test("findOfficialEodByDate returns null when no designation exists") {
        recorder.save(completedJob(valuationDate = LocalDate.of(2025, 3, 10)))

        recorder.findOfficialEodByDate("port-1", LocalDate.of(2025, 3, 10)).shouldBeNull()
    }

    test("demoteOfficialEod clears designation and resets runLabel") {
        val date = LocalDate.of(2025, 3, 10)
        val job = completedJob(valuationDate = date)
        recorder.save(job)
        recorder.promoteToOfficialEod(job.jobId, "risk-mgr", Instant.parse("2025-03-10T18:00:00Z"))

        val demoted = recorder.demoteOfficialEod(job.jobId)

        demoted.runLabel.shouldBeNull()
        demoted.promotedAt.shouldBeNull()
        demoted.promotedBy.shouldBeNull()
        recorder.findOfficialEodByDate("port-1", date).shouldBeNull()
    }

    test("supersedeOfficialEod marks job as SUPERSEDED_EOD and removes designation") {
        val date = LocalDate.of(2025, 3, 10)
        val job = completedJob(valuationDate = date)
        recorder.save(job)
        recorder.promoteToOfficialEod(job.jobId, "risk-mgr", Instant.parse("2025-03-10T18:00:00Z"))

        val superseded = recorder.supersedeOfficialEod(job.jobId)

        superseded.runLabel shouldBe RunLabel.SUPERSEDED_EOD
        superseded.promotedAt.shouldBeNull()
        superseded.promotedBy.shouldBeNull()
        recorder.findOfficialEodByDate("port-1", date).shouldBeNull()
    }

    test("update rejects modification of promoted Official EOD job") {
        val date = LocalDate.of(2025, 3, 10)
        val job = completedJob(valuationDate = date)
        recorder.save(job)
        recorder.promoteToOfficialEod(job.jobId, "risk-mgr", Instant.parse("2025-03-10T18:00:00Z"))

        val modified = job.copy(varValue = 9999.0)
        val ex = shouldThrow<IllegalStateException> {
            withContext(Dispatchers.IO) {
                recorder.update(modified)
            }
        }
        ex.message shouldBe "Cannot modify promoted Official EOD job ${job.jobId}"
    }

    test("findByBookId filters by runLabel") {
        val date = LocalDate.of(2025, 3, 10)
        val job1 = completedJob(startedAt = Instant.parse("2025-03-10T10:00:00Z"), valuationDate = date)
        val job2 = completedJob(startedAt = Instant.parse("2025-03-10T14:00:00Z"), valuationDate = date)
        recorder.save(job1)
        recorder.save(job2)
        recorder.promoteToOfficialEod(job1.jobId, "risk-mgr", Instant.parse("2025-03-10T18:00:00Z"))

        val eodJobs = recorder.findByBookId("port-1", runLabel = RunLabel.OFFICIAL_EOD)
        eodJobs shouldHaveSize 1
        eodJobs[0].jobId shouldBe job1.jobId
    }

    test("updateCurrentPhase sets current_phase column") {
        val job = startedJob()
        recorder.save(job)

        recorder.updateCurrentPhase(job.jobId, JobPhaseName.FETCH_MARKET_DATA)

        val found = recorder.findByJobId(job.jobId)
        found.shouldNotBeNull()
        found.currentPhase shouldBe JobPhaseName.FETCH_MARKET_DATA
    }

    test("updateCurrentPhase on promoted EOD job throws") {
        val date = LocalDate.of(2025, 3, 10)
        val job = completedJob(valuationDate = date)
        recorder.save(job)
        recorder.promoteToOfficialEod(job.jobId, "risk-mgr", Instant.parse("2025-03-10T18:00:00Z"))

        val ex = shouldThrow<IllegalStateException> {
            withContext(Dispatchers.IO) {
                recorder.updateCurrentPhase(job.jobId, JobPhaseName.VALUATION)
            }
        }
        ex.message shouldBe "Cannot modify promoted Official EOD job ${job.jobId}"
    }

    test("update clears currentPhase on COMPLETED job") {
        val job = startedJob()
        recorder.save(job)
        recorder.updateCurrentPhase(job.jobId, JobPhaseName.VALUATION)

        val updatedJob = job.copy(
            status = RunStatus.COMPLETED,
            completedAt = job.startedAt.plusMillis(200),
            durationMs = 200,
            varValue = 5000.0,
        )
        recorder.update(updatedJob)

        val found = recorder.findByJobId(job.jobId)
        found.shouldNotBeNull()
        found.currentPhase.shouldBeNull()
    }

    test("deleteOfficialEodDesignationsByPromotedBy removes matching designations only") {
        val seedJob1 = completedJob(startedAt = Instant.parse("2025-03-10T10:00:00Z"), valuationDate = LocalDate.of(2025, 3, 10))
        val seedJob2 = completedJob(bookId = "port-2", startedAt = Instant.parse("2025-03-11T10:00:00Z"), valuationDate = LocalDate.of(2025, 3, 11))
        val mgrJob = completedJob(bookId = "port-3", startedAt = Instant.parse("2025-03-12T10:00:00Z"), valuationDate = LocalDate.of(2025, 3, 12))
        recorder.save(seedJob1)
        recorder.save(seedJob2)
        recorder.save(mgrJob)
        recorder.promoteToOfficialEod(seedJob1.jobId, "SEED", Instant.parse("2025-03-10T18:00:00Z"))
        recorder.promoteToOfficialEod(seedJob2.jobId, "SEED", Instant.parse("2025-03-11T18:00:00Z"))
        recorder.promoteToOfficialEod(mgrJob.jobId, "risk-mgr", Instant.parse("2025-03-12T18:00:00Z"))

        val deleted = recorder.deleteOfficialEodDesignationsByPromotedBy("SEED")

        deleted shouldBe 2
        recorder.findOfficialEodByDate("port-1", LocalDate.of(2025, 3, 10)).shouldBeNull()
        recorder.findOfficialEodByDate("port-2", LocalDate.of(2025, 3, 11)).shouldBeNull()
        recorder.findOfficialEodByDate("port-3", LocalDate.of(2025, 3, 12)).shouldNotBeNull()
    }

    test("deleteOfficialEodDesignationsByPromotedBy returns 0 when no matches") {
        val deleted = recorder.deleteOfficialEodDesignationsByPromotedBy("SEED")
        deleted shouldBe 0
    }
})
