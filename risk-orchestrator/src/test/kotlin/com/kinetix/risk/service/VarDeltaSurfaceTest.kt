package com.kinetix.risk.service

import com.kinetix.risk.model.JobPhaseName
import com.kinetix.risk.model.RunLabel
import com.kinetix.risk.model.RunStatus
import com.kinetix.risk.model.TriggerType
import com.kinetix.risk.model.ValuationJob
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Trader-review P1 #14 — the Risk-tab header was showing
 * `VaR 95% $190.1K  ↑ $0.00 (+0.0%)` despite 5,000+ live trades and a
 * constant stream of TRADE_EVENT-driven valuations. The "delta since last"
 * indicator was stuck at zero.
 *
 * The orchestrator must surface a `varDelta` (and percentage) computed
 * against the **previous distinct completed valuation run** — not against
 * the current run itself, which would tautologically be zero.
 *
 * Distinguish three states:
 *  1. Two or more distinct completed runs exist → delta is the signed
 *     difference and the percentage is `(current - previous) / previous * 100`.
 *  2. Exactly one completed run exists → delta and percentage are **null**
 *     ("no prior") — *not* zero, so the UI can render `—` instead of a
 *     misleading `$0.00`.
 *  3. No completed runs → surface is null.
 */
class VarDeltaSurfaceTest : FunSpec({

    val bookId = "port-1"
    val valuationDate = LocalDate.of(2026, 5, 28)
    val baseTime = Instant.parse("2026-05-28T10:00:00Z")

    fun completedJob(
        varValue: Double,
        startedAt: Instant,
    ) = ValuationJob(
        jobId = UUID.randomUUID(),
        bookId = bookId,
        triggerType = TriggerType.TRADE_EVENT,
        status = RunStatus.COMPLETED,
        startedAt = startedAt,
        valuationDate = valuationDate,
        completedAt = startedAt.plusMillis(500),
        durationMs = 500,
        calculationType = "PARAMETRIC",
        confidenceLevel = "CL_95",
        varValue = varValue,
        expectedShortfall = varValue * 1.25,
        triggeredBy = "TRADE_EVENT",
    )

    test("delta is the signed difference between the latest two distinct completed runs") {
        val previous = completedJob(varValue = 1_000_000.0, startedAt = baseTime)
        val current = completedJob(varValue = 1_200_000.0, startedAt = baseTime.plusSeconds(60))
        val recorder = FakeRecorder(listOf(previous, current))

        val service = VarDeltaSurfaceService(recorder)
        val surface = service.surface(bookId)

        surface.shouldNotBeNull()
        surface.currentVar shouldBe 1_200_000.0
        surface.previousVar shouldBe 1_000_000.0
        surface.varDelta.shouldNotBeNull()
        surface.varDelta!! shouldBe (200_000.0 plusOrMinus 1e-6)
        surface.varDeltaPct.shouldNotBeNull()
        surface.varDeltaPct!! shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("delta is negative when VaR fell since the previous run") {
        val previous = completedJob(varValue = 500_000.0, startedAt = baseTime)
        val current = completedJob(varValue = 400_000.0, startedAt = baseTime.plusSeconds(120))
        val recorder = FakeRecorder(listOf(previous, current))

        val surface = VarDeltaSurfaceService(recorder).surface(bookId)

        surface.shouldNotBeNull()
        surface.varDelta.shouldNotBeNull()
        surface.varDelta!! shouldBe (-100_000.0 plusOrMinus 1e-6)
        surface.varDeltaPct.shouldNotBeNull()
        surface.varDeltaPct!! shouldBe (-20.0 plusOrMinus 1e-6)
    }

    test("delta and percentage are null when only one completed run exists") {
        val only = completedJob(varValue = 750_000.0, startedAt = baseTime)
        val recorder = FakeRecorder(listOf(only))

        val surface = VarDeltaSurfaceService(recorder).surface(bookId)

        surface.shouldNotBeNull()
        surface.currentVar shouldBe 750_000.0
        surface.previousVar.shouldBeNull()
        surface.varDelta.shouldBeNull()
        surface.varDeltaPct.shouldBeNull()
    }

    test("surface is null when no completed runs exist") {
        val recorder = FakeRecorder(emptyList())

        val surface = VarDeltaSurfaceService(recorder).surface(bookId)

        surface.shouldBeNull()
    }

    test("ignores non-completed runs when picking previous and current") {
        // Latest in time is a RUNNING job — must be skipped; current is the
        // most recent COMPLETED, previous is the COMPLETED before that.
        val previousCompleted = completedJob(varValue = 800_000.0, startedAt = baseTime)
        val currentCompleted = completedJob(varValue = 900_000.0, startedAt = baseTime.plusSeconds(30))
        val laterRunning = completedJob(varValue = 999_999.0, startedAt = baseTime.plusSeconds(60))
            .copy(status = RunStatus.RUNNING, varValue = null)
        val recorder = FakeRecorder(listOf(previousCompleted, currentCompleted, laterRunning))

        val surface = VarDeltaSurfaceService(recorder).surface(bookId)

        surface.shouldNotBeNull()
        surface.currentVar shouldBe 900_000.0
        surface.previousVar shouldBe 800_000.0
        surface.varDelta!! shouldBe (100_000.0 plusOrMinus 1e-6)
    }
})

private class FakeRecorder(private val jobs: List<ValuationJob>) : ValuationJobRecorder {
    override suspend fun save(job: ValuationJob) {}
    override suspend fun update(job: ValuationJob) {}
    override suspend fun updateCurrentPhase(jobId: UUID, phase: JobPhaseName) {}
    override suspend fun findByBookId(
        bookId: String,
        limit: Int,
        offset: Int,
        from: Instant?,
        to: Instant?,
        valuationDate: LocalDate?,
        runLabel: RunLabel?,
    ): List<ValuationJob> =
        jobs.filter { it.bookId == bookId }
            .sortedByDescending { it.startedAt }
            .drop(offset)
            .take(limit)

    override suspend fun countByBookId(
        bookId: String,
        from: Instant?,
        to: Instant?,
        valuationDate: LocalDate?,
        runLabel: RunLabel?,
    ): Long = jobs.count { it.bookId == bookId }.toLong()

    override suspend fun findByJobId(jobId: UUID): ValuationJob? = jobs.firstOrNull { it.jobId == jobId }
    override suspend fun findDistinctBookIds(): List<String> = jobs.map { it.bookId }.distinct()
    override suspend fun findLatestCompletedByDate(bookId: String, valuationDate: LocalDate): ValuationJob? = null
    override suspend fun findLatestCompleted(bookId: String): ValuationJob? =
        jobs.filter { it.bookId == bookId && it.status == RunStatus.COMPLETED }
            .maxByOrNull { it.startedAt }
    override suspend fun findLatestCompletedBeforeDate(bookId: String, beforeDate: LocalDate): ValuationJob? = null
    override suspend fun findOfficialEodByDate(bookId: String, valuationDate: LocalDate): ValuationJob? = null
    override suspend fun findOfficialEodRange(bookId: String, from: LocalDate, to: LocalDate): List<ValuationJob> = emptyList()
    override suspend fun promoteToOfficialEod(jobId: UUID, promotedBy: String, promotedAt: Instant): ValuationJob =
        throw UnsupportedOperationException()
    override suspend fun demoteOfficialEod(jobId: UUID): ValuationJob = throw UnsupportedOperationException()
    override suspend fun supersedeOfficialEod(jobId: UUID): ValuationJob = throw UnsupportedOperationException()
    override suspend fun findChartData(
        bookId: String,
        from: Instant,
        to: Instant,
        bucketInterval: String,
    ): List<com.kinetix.risk.model.ChartBucketRow> = emptyList()
    override suspend fun resetOrphanedRunningJobs(): Int = 0
    override suspend fun findByTriggeredBy(triggeredBy: String, limit: Int): List<ValuationJob> = emptyList()
    override suspend fun deleteByTriggeredBy(triggeredBy: String): Int = 0
    override suspend fun deleteOfficialEodDesignationsByPromotedBy(promotedBy: String): Int = 0
}
