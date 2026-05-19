package com.kinetix.risk.service

import com.kinetix.risk.model.ChartBucketRow
import com.kinetix.risk.model.JobPhaseName
import com.kinetix.risk.model.RunLabel
import com.kinetix.risk.model.ValuationJob
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ValuationJobRecorder {
    suspend fun save(job: ValuationJob)
    suspend fun update(job: ValuationJob)
    suspend fun updateCurrentPhase(jobId: UUID, phase: JobPhaseName)
    suspend fun findByBookId(
        bookId: String,
        limit: Int = 50,
        offset: Int = 0,
        from: Instant? = null,
        to: Instant? = null,
        valuationDate: LocalDate? = null,
        runLabel: RunLabel? = null,
    ): List<ValuationJob>
    suspend fun countByBookId(
        bookId: String,
        from: Instant? = null,
        to: Instant? = null,
        valuationDate: LocalDate? = null,
        runLabel: RunLabel? = null,
    ): Long
    suspend fun findByJobId(jobId: UUID): ValuationJob?
    suspend fun findDistinctBookIds(): List<String>
    suspend fun findLatestCompletedByDate(bookId: String, valuationDate: LocalDate): ValuationJob?
    suspend fun findLatestCompleted(bookId: String): ValuationJob?
    suspend fun findLatestCompletedBeforeDate(bookId: String, beforeDate: LocalDate): ValuationJob?
    suspend fun findOfficialEodByDate(bookId: String, valuationDate: LocalDate): ValuationJob?
    suspend fun findOfficialEodRange(bookId: String, from: LocalDate, to: LocalDate): List<ValuationJob>
    suspend fun promoteToOfficialEod(jobId: UUID, promotedBy: String, promotedAt: Instant): ValuationJob
    suspend fun demoteOfficialEod(jobId: UUID): ValuationJob
    suspend fun supersedeOfficialEod(jobId: UUID): ValuationJob
    suspend fun findChartData(bookId: String, from: Instant, to: Instant, bucketInterval: String): List<ChartBucketRow>
    suspend fun resetOrphanedRunningJobs(): Int
    suspend fun findByTriggeredBy(triggeredBy: String, limit: Int = 1): List<ValuationJob>
    suspend fun deleteByTriggeredBy(triggeredBy: String): Int
    suspend fun deleteOfficialEodDesignationsByPromotedBy(promotedBy: String): Int
}
