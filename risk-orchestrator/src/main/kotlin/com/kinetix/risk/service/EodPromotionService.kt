package com.kinetix.risk.service

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.risk.kafka.GovernanceAuditPublisher
import com.kinetix.risk.model.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class EodPromotionService(
    private val jobRecorder: ValuationJobRecorder,
    private val eventPublisher: OfficialEodEventPublisher,
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    private val riskAuditPublisher: RiskAuditEventPublisher? = null,
    private val matViewRefresher: (suspend () -> Unit)? = null,
    private val matViewRefreshRetryDelayMs: Long = 5_000L,
    private val manifestRepository: RunManifestRepository? = null,
    private val governanceAuditPublisher: GovernanceAuditPublisher? = null,
) {
    private val logger = LoggerFactory.getLogger(EodPromotionService::class.java)
    private val lastPromotionTimestamps = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    suspend fun promoteToOfficialEod(jobId: UUID, promotedBy: String, force: Boolean = false): ValuationJob {
        val sample = Timer.start(meterRegistry)
        try {
            val job = jobRecorder.findByJobId(jobId)
                ?: throw EodPromotionException.JobNotFound(jobId)

            if (job.triggeredBy == promotedBy) {
                throw EodPromotionException.SelfPromotion(promotedBy)
            }

            return promoteCore(jobId, job, promotedBy, force, sample)
        } catch (e: Exception) {
            sample.stop(meterRegistry.timer("eod.promotion.duration"))
            meterRegistry.counter("eod.promotion.requests", "result", "error").increment()
            throw e
        }
    }

    suspend fun promoteToOfficialEodAutomatically(jobId: UUID): ValuationJob {
        val sample = Timer.start(meterRegistry)
        try {
            val job = jobRecorder.findByJobId(jobId)
                ?: throw EodPromotionException.JobNotFound(jobId)

            return promoteCore(jobId, job, promotedBy = "AUTO_CLOSE", force = false, sample)
        } catch (e: Exception) {
            sample.stop(meterRegistry.timer("eod.promotion.duration"))
            meterRegistry.counter("eod.promotion.requests", "result", "error").increment()
            throw e
        }
    }

    private suspend fun promoteCore(jobId: UUID, job: ValuationJob, promotedBy: String, force: Boolean, sample: Timer.Sample): ValuationJob {
        if (job.status != RunStatus.COMPLETED) {
            throw EodPromotionException.JobNotCompleted(jobId)
        }

            if (job.promotedAt != null) {
                throw EodPromotionException.AlreadyPromoted(jobId)
            }

            // Market data completeness gate: reject promotion when any market data
            // entry is MISSING, unless the caller explicitly sets force=true.
            if (manifestRepository != null && job.manifestId != null) {
                val marketDataRefs = manifestRepository.findMarketDataRefs(job.manifestId)
                val incompleteRefs = marketDataRefs.filter { it.status == MarketDataSnapshotStatus.MISSING }
                if (incompleteRefs.isNotEmpty()) {
                    if (!force) {
                        val summary = incompleteRefs
                            .take(5)
                            .joinToString { "${it.dataType}/${it.instrumentId}" }
                        throw IncompleteMarketDataException(
                            "EOD promotion rejected: ${incompleteRefs.size} market data entries are MISSING for job $jobId ($summary)"
                        )
                    }
                    logger.warn(
                        "Force-promoting job {} with {} incomplete market data entries: {}",
                        jobId,
                        incompleteRefs.size,
                        incompleteRefs.take(5).joinToString { "${it.dataType}/${it.instrumentId}" },
                    )
                }
            }

            // Supersede existing Official EOD for same portfolio/date if one exists
            val existingEod = jobRecorder.findOfficialEodByDate(job.bookId, job.valuationDate)
            if (existingEod != null && existingEod.jobId != jobId) {
                jobRecorder.supersedeOfficialEod(existingEod.jobId)
            }

            val promoted = jobRecorder.promoteToOfficialEod(jobId, promotedBy, Instant.now())

            eventPublisher.publish(
                OfficialEodPromotedEvent(
                    jobId = promoted.jobId.toString(),
                    bookId = promoted.bookId,
                    valuationDate = promoted.valuationDate.toString(),
                    promotedBy = promotedBy,
                    promotedAt = promoted.promotedAt.toString(),
                    varValue = promoted.varValue,
                    expectedShortfall = promoted.expectedShortfall,
                )
            )

            // Emit risk audit event for EOD promotion
            if (riskAuditPublisher != null && promoted.manifestId != null) {
                try {
                    riskAuditPublisher.publish(
                        EodPromotedAuditEvent(
                            jobId = promoted.jobId.toString(),
                            bookId = promoted.bookId,
                            valuationDate = promoted.valuationDate.toString(),
                            manifestId = promoted.manifestId.toString(),
                            promotedBy = promotedBy,
                            promotedAt = promoted.promotedAt.toString(),
                            varValue = promoted.varValue,
                            expectedShortfall = promoted.expectedShortfall,
                        )
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to publish RISK_RUN_EOD_PROMOTED audit event for job {}", promoted.jobId, e)
                }
            }

            governanceAuditPublisher?.publish(
                GovernanceAuditEvent(
                    eventType = AuditEventType.EOD_PROMOTED,
                    userId = promotedBy,
                    userRole = if (promotedBy == "AUTO_CLOSE") "AUTO_CLOSE" else "EOD_OPERATOR",
                    bookId = promoted.bookId,
                    details = "jobId=${promoted.jobId},valuationDate=${promoted.valuationDate}",
                    correlationId = MDC.get("correlationId"),
                )
            )

            if (matViewRefresher != null) {
                refreshMatViewWithRetry(promoted.jobId)
            }

            sample.stop(meterRegistry.timer("eod.promotion.duration"))
            meterRegistry.counter("eod.promotion.requests", "result", "success").increment()
            val lastTs = lastPromotionTimestamps.computeIfAbsent(promoted.bookId) { bookId ->
                val ref = java.util.concurrent.atomic.AtomicLong()
                meterRegistry.gauge(
                    "eod.promotion.last_timestamp",
                    listOf(io.micrometer.core.instrument.Tag.of("book_id", bookId)),
                    ref,
                ) { it.get().toDouble() }
                ref
            }
            lastTs.set(promoted.promotedAt!!.epochSecond)
            return promoted
    }

    suspend fun demoteFromOfficialEod(jobId: UUID, demotedBy: String): ValuationJob {
        val job = jobRecorder.findByJobId(jobId)
            ?: throw EodPromotionException.JobNotFound(jobId)

        if (job.runLabel != RunLabel.OFFICIAL_EOD || job.promotedAt == null) {
            throw EodPromotionException.NotPromoted(jobId)
        }

        val demoted = jobRecorder.demoteOfficialEod(jobId)
        logger.warn(
            "eod_demotion_performed job_id={} book_id={} demoted_by={}",
            jobId,
            demoted.bookId,
            demotedBy,
        )
        return demoted
    }

    suspend fun findOfficialEod(bookId: String, valuationDate: LocalDate): ValuationJob? {
        return jobRecorder.findOfficialEodByDate(bookId, valuationDate)
    }

    private suspend fun refreshMatViewWithRetry(jobId: UUID) {
        val maxAttempts = 2
        for (attempt in 1..maxAttempts) {
            try {
                matViewRefresher!!.invoke()
                return
            } catch (e: Exception) {
                if (attempt < maxAttempts) {
                    logger.warn(
                        "Mat view refresh attempt {}/{} failed for job {}, retrying in {}ms",
                        attempt, maxAttempts, jobId, matViewRefreshRetryDelayMs, e,
                    )
                    delay(matViewRefreshRetryDelayMs)
                } else {
                    logger.warn(
                        "Failed to refresh daily_official_eod_summary materialized view after {} attempts for job {}",
                        maxAttempts, jobId, e,
                    )
                    meterRegistry.counter("eod.matview.refresh.failures").increment()
                }
            }
        }
    }
}
