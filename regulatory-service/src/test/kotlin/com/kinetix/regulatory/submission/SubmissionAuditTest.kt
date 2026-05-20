package com.kinetix.regulatory.submission

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.regulatory.audit.GovernanceAuditPublisher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.RecordMetadata
import org.slf4j.MDC
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Future

class SubmissionAuditTest : FunSpec({

    val repository = mockk<SubmissionRepository>()
    val producer = mockk<KafkaProducer<String, String>>()
    val publisher = GovernanceAuditPublisher(producer, topic = "governance.audit")
    val service = SubmissionService(repository, auditPublisher = publisher)

    fun givenFuture(): Future<RecordMetadata> = mockk(relaxed = true)

    beforeEach {
        clearMocks(repository, producer)
        MDC.clear()
    }

    afterEach {
        MDC.clear()
    }

    test("publishes SUBMISSION_APPROVED when submission is approved") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.PENDING_REVIEW, preparerId = "preparer-1")
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        service.approve(id, approverId = "approver-1")

        verify(exactly = 1) { producer.send(any()) }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.eventType shouldBe AuditEventType.SUBMISSION_APPROVED
        decoded.submissionId shouldBe id
        decoded.userId shouldBe "approver-1"
    }

    test("publishes SUBMISSION_ACKNOWLEDGED when submission is acknowledged") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.SUBMITTED)
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        service.acknowledge(id, acknowledgedAt = Instant.parse("2026-04-15T09:30:00Z"))

        verify(exactly = 1) { producer.send(any()) }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.eventType shouldBe AuditEventType.SUBMISSION_ACKNOWLEDGED
        decoded.submissionId shouldBe id
    }

    test("carries the correlationId from the MDC on the published governance event") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.PENDING_REVIEW, preparerId = "preparer-1")
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        MDC.put("correlationId", "corr-submission-321")
        service.approve(id, approverId = "approver-1")

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.correlationId shouldBe "corr-submission-321"
    }

    test("publishes a null correlationId when the MDC has no correlation id") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.PENDING_REVIEW, preparerId = "preparer-1")
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        service.approve(id, approverId = "approver-1")

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.correlationId shouldBe null
    }

    test("does not publish audit event when submission approval is rejected due to same preparer and approver") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.PENDING_REVIEW, preparerId = "user-1")
        coEvery { repository.findById(id) } returns submission

        try {
            service.approve(id, approverId = "user-1")
        } catch (_: IllegalArgumentException) { }

        verify(exactly = 0) { producer.send(any()) }
    }
})

private fun aSubmission(
    id: String = UUID.randomUUID().toString(),
    status: SubmissionStatus = SubmissionStatus.DRAFT,
    preparerId: String = "preparer-1",
) = RegulatorySubmission(
    id = id,
    reportType = "FRTB_SA",
    status = status,
    preparerId = preparerId,
    approverId = null,
    deadline = Instant.now().plusSeconds(86400),
    submittedAt = null,
    acknowledgedAt = null,
    createdAt = Instant.now(),
)
