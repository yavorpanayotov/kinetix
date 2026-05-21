package com.kinetix.regulatory.submission

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.regulatory.audit.GovernanceAuditPublisher
import com.kinetix.regulatory.metrics.RegulatoryGovernanceMetrics
import org.slf4j.MDC
import java.time.Instant
import java.util.UUID

class SubmissionService(
    private val repository: SubmissionRepository,
    private val auditPublisher: GovernanceAuditPublisher? = null,
    private val governanceMetrics: RegulatoryGovernanceMetrics? = null,
) {

    suspend fun create(reportType: String, preparerId: String, deadline: Instant): RegulatorySubmission {
        val submission = RegulatorySubmission(
            id = UUID.randomUUID().toString(),
            reportType = reportType,
            status = SubmissionStatus.DRAFT,
            preparerId = preparerId,
            approverId = null,
            deadline = deadline,
            submittedAt = null,
            acknowledgedAt = null,
            createdAt = Instant.now(),
        )
        repository.save(submission)
        return submission
    }

    suspend fun submitForReview(id: String): RegulatorySubmission {
        val submission = findOrThrow(id)
        if (submission.status != SubmissionStatus.DRAFT) {
            throw IllegalStateException("Can only submit for review from DRAFT status, current: ${submission.status}")
        }
        val updated = submission.copy(status = SubmissionStatus.PENDING_REVIEW)
        repository.save(updated)
        return updated
    }

    suspend fun approve(id: String, approverId: String): RegulatorySubmission {
        val submission = findOrThrow(id)
        if (submission.status != SubmissionStatus.PENDING_REVIEW) {
            throw IllegalStateException("Can only approve from PENDING_REVIEW status, current: ${submission.status}")
        }
        if (submission.preparerId == approverId) {
            throw IllegalArgumentException("Approver cannot be the same as preparer (four-eyes principle)")
        }
        val updated = submission.copy(
            status = SubmissionStatus.APPROVED,
            approverId = approverId,
        )
        repository.save(updated)
        auditPublisher?.publish(
            GovernanceAuditEvent(
                eventType = AuditEventType.SUBMISSION_APPROVED,
                userId = approverId,
                userRole = "APPROVER",
                submissionId = id,
                details = submission.reportType,
                correlationId = MDC.get("correlationId"),
            )
        )
        return updated
    }

    suspend fun submit(id: String): RegulatorySubmission {
        val submission = findOrThrow(id)
        if (submission.status != SubmissionStatus.APPROVED) {
            throw IllegalStateException("Can only submit from APPROVED status, current: ${submission.status}")
        }
        val updated = submission.copy(
            status = SubmissionStatus.SUBMITTED,
            submittedAt = Instant.now(),
        )
        repository.save(updated)
        governanceMetrics?.recordSubmissionOutcome(
            reportType = submission.reportType,
            outcome = RegulatoryGovernanceMetrics.OUTCOME_SUBMITTED,
        )
        return updated
    }

    suspend fun acknowledge(id: String, acknowledgedAt: Instant): RegulatorySubmission {
        val submission = findOrThrow(id)
        if (submission.status != SubmissionStatus.SUBMITTED) {
            throw IllegalStateException("Can only acknowledge from SUBMITTED status, current: ${submission.status}")
        }
        val updated = submission.copy(
            status = SubmissionStatus.ACKNOWLEDGED,
            acknowledgedAt = acknowledgedAt,
        )
        repository.save(updated)
        governanceMetrics?.recordSubmissionOutcome(
            reportType = submission.reportType,
            outcome = RegulatoryGovernanceMetrics.OUTCOME_ACKNOWLEDGED,
        )
        auditPublisher?.publish(
            GovernanceAuditEvent(
                eventType = AuditEventType.SUBMISSION_ACKNOWLEDGED,
                userId = "SYSTEM",
                userRole = "REGULATOR",
                submissionId = id,
                details = submission.reportType,
                correlationId = MDC.get("correlationId"),
            )
        )
        return updated
    }

    suspend fun listAll(): List<RegulatorySubmission> = repository.findAll()

    suspend fun findById(id: String): RegulatorySubmission? = repository.findById(id)

    private suspend fun findOrThrow(id: String): RegulatorySubmission =
        repository.findById(id) ?: throw NoSuchElementException("Submission not found: $id")
}
