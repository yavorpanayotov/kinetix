package com.kinetix.risk.service

import com.kinetix.risk.model.ChartBucketRow
import com.kinetix.risk.model.JobPhaseName
import com.kinetix.risk.model.RunLabel
import com.kinetix.risk.model.ValuationJob
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class NoOpValuationJobRecorder : ValuationJobRecorder {
    override suspend fun save(job: ValuationJob) {}
    override suspend fun update(job: ValuationJob) {}
    override suspend fun updateCurrentPhase(jobId: UUID, phase: JobPhaseName) {}
    override suspend fun findByBookId(bookId: String, limit: Int, offset: Int, from: Instant?, to: Instant?, valuationDate: LocalDate?, runLabel: RunLabel?): List<ValuationJob> = emptyList()
    override suspend fun countByBookId(bookId: String, from: Instant?, to: Instant?, valuationDate: LocalDate?, runLabel: RunLabel?): Long = 0
    override suspend fun findByJobId(jobId: UUID): ValuationJob? = null
    override suspend fun findDistinctBookIds(): List<String> = emptyList()
    override suspend fun findLatestCompletedByDate(bookId: String, valuationDate: LocalDate): ValuationJob? = null
    override suspend fun findLatestCompleted(bookId: String): ValuationJob? = null
    override suspend fun findLatestCompletedBeforeDate(bookId: String, beforeDate: LocalDate): ValuationJob? = null
    override suspend fun findOfficialEodByDate(bookId: String, valuationDate: LocalDate): ValuationJob? = null
    override suspend fun findOfficialEodRange(bookId: String, from: LocalDate, to: LocalDate): List<ValuationJob> = emptyList()
    override suspend fun promoteToOfficialEod(jobId: UUID, promotedBy: String, promotedAt: Instant): ValuationJob =
        throw UnsupportedOperationException("No-op recorder does not support EOD promotion")
    override suspend fun demoteOfficialEod(jobId: UUID): ValuationJob =
        throw UnsupportedOperationException("No-op recorder does not support EOD demotion")
    override suspend fun supersedeOfficialEod(jobId: UUID): ValuationJob =
        throw UnsupportedOperationException("No-op recorder does not support EOD supersession")
    override suspend fun findChartData(bookId: String, from: Instant, to: Instant, bucketInterval: String): List<ChartBucketRow> = emptyList()
    override suspend fun resetOrphanedRunningJobs(): Int = 0
    override suspend fun findByTriggeredBy(triggeredBy: String, limit: Int): List<ValuationJob> = emptyList()
    override suspend fun deleteByTriggeredBy(triggeredBy: String): Int = 0
    override suspend fun deleteOfficialEodDesignationsByPromotedBy(promotedBy: String): Int = 0
}
