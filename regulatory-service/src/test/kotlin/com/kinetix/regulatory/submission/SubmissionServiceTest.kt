package com.kinetix.regulatory.submission

import com.kinetix.regulatory.metrics.RegulatoryGovernanceMetrics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

class SubmissionServiceTest : FunSpec({

    val repository = mockk<SubmissionRepository>()
    val service = SubmissionService(repository)

    test("creates a submission in DRAFT status") {
        coEvery { repository.save(any()) } returns Unit

        val result = service.create(
            reportType = "FRTB_SBM",
            preparerId = "analyst-1",
            deadline = Instant.parse("2026-03-31T23:59:59Z"),
        )

        result.reportType shouldBe "FRTB_SBM"
        result.status shouldBe SubmissionStatus.DRAFT
        result.preparerId shouldBe "analyst-1"
        result.approverId shouldBe null
        result.deadline shouldBe Instant.parse("2026-03-31T23:59:59Z")
        result.submittedAt shouldBe null
        result.acknowledgedAt shouldBe null

        coVerify(exactly = 1) { repository.save(any()) }
    }

    test("transitions from DRAFT to PENDING_REVIEW") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.DRAFT)
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        val result = service.submitForReview(id)

        result.status shouldBe SubmissionStatus.PENDING_REVIEW
    }

    test("approver cannot be the same as preparer (four-eyes)") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(
            id = id,
            status = SubmissionStatus.PENDING_REVIEW,
            preparerId = "analyst-1",
        )
        coEvery { repository.findById(id) } returns submission

        shouldThrow<IllegalArgumentException> {
            service.approve(id, approverId = "analyst-1")
        }.message shouldBe "Approver cannot be the same as preparer (four-eyes principle)"
    }

    test("approves a submission with different approver") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(
            id = id,
            status = SubmissionStatus.PENDING_REVIEW,
            preparerId = "analyst-1",
        )
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        val result = service.approve(id, approverId = "manager-1")

        result.status shouldBe SubmissionStatus.APPROVED
        result.approverId shouldBe "manager-1"
    }

    test("tracks submission deadline") {
        coEvery { repository.save(any()) } returns Unit
        val deadline = Instant.parse("2026-06-30T23:59:59Z")

        val result = service.create(
            reportType = "FRTB_DRC",
            preparerId = "analyst-2",
            deadline = deadline,
        )

        result.deadline shouldBe deadline
    }

    test("transitions to SUBMITTED when approved") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.APPROVED)
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        val result = service.submit(id)

        result.status shouldBe SubmissionStatus.SUBMITTED
        result.submittedAt shouldNotBe null
    }

    test("rejects submission for review when not in DRAFT status") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.SUBMITTED)
        coEvery { repository.findById(id) } returns submission

        shouldThrow<IllegalStateException> {
            service.submitForReview(id)
        }
    }

    test("rejects approval when not in PENDING_REVIEW status") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.DRAFT)
        coEvery { repository.findById(id) } returns submission

        shouldThrow<IllegalStateException> {
            service.approve(id, approverId = "manager-1")
        }
    }

    test("rejects final submit when not in APPROVED status") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.PENDING_REVIEW)
        coEvery { repository.findById(id) } returns submission

        shouldThrow<IllegalStateException> {
            service.submit(id)
        }
    }

    test("transitions from SUBMITTED to ACKNOWLEDGED with regulator-supplied timestamp") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.SUBMITTED)
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        val regulatorTimestamp = Instant.parse("2026-04-15T09:30:00Z")
        val result = service.acknowledge(id, acknowledgedAt = regulatorTimestamp)

        result.status shouldBe SubmissionStatus.ACKNOWLEDGED
        result.acknowledgedAt shouldBe regulatorTimestamp
    }

    test("preserves regulator timestamp distinct from system clock") {
        // The regulator's clock and the receiving service's clock differ; the
        // regulator-supplied value must be persisted verbatim per spec
        // AcknowledgeSubmission (regulatory.allium:351-356).
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.SUBMITTED)
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        val pastTimestamp = Instant.parse("2026-04-10T08:00:00Z")
        val result = service.acknowledge(id, acknowledgedAt = pastTimestamp)

        result.acknowledgedAt shouldBe pastTimestamp
        result.acknowledgedAt shouldNotBe null
    }

    test("rejects acknowledgement when not in SUBMITTED status") {
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.APPROVED)
        coEvery { repository.findById(id) } returns submission

        shouldThrow<IllegalStateException> {
            service.acknowledge(id, acknowledgedAt = Instant.parse("2026-04-15T09:30:00Z"))
        }.message shouldBe "Can only acknowledge from SUBMITTED status, current: APPROVED"
    }

    test("lists all submissions") {
        val submissions = listOf(
            aSubmission(reportType = "FRTB_SBM"),
            aSubmission(reportType = "FRTB_DRC"),
        )
        coEvery { repository.findAll() } returns submissions

        val result = service.listAll()

        result.size shouldBe 2
    }

    test("final submit records a SUBMITTED governance metric outcome") {
        val meterRegistry = SimpleMeterRegistry()
        val instrumentedService = SubmissionService(
            repository = repository,
            governanceMetrics = RegulatoryGovernanceMetrics(meterRegistry),
        )
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.APPROVED, reportType = "FRTB_SBM")
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        instrumentedService.submit(id)

        meterRegistry.counter(
            "regulatory_submission_outcomes_total",
            "report_type", "FRTB_SBM", "outcome", "SUBMITTED",
        ).count() shouldBe 1.0
    }

    test("acknowledgement records an ACKNOWLEDGED governance metric outcome") {
        val meterRegistry = SimpleMeterRegistry()
        val instrumentedService = SubmissionService(
            repository = repository,
            governanceMetrics = RegulatoryGovernanceMetrics(meterRegistry),
        )
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.SUBMITTED, reportType = "FRTB_DRC")
        coEvery { repository.findById(id) } returns submission
        coEvery { repository.save(any()) } returns Unit

        instrumentedService.acknowledge(id, acknowledgedAt = Instant.parse("2026-04-15T09:30:00Z"))

        meterRegistry.counter(
            "regulatory_submission_outcomes_total",
            "report_type", "FRTB_DRC", "outcome", "ACKNOWLEDGED",
        ).count() shouldBe 1.0
    }

    test("a rejected final submit does not record a SUBMITTED outcome") {
        val meterRegistry = SimpleMeterRegistry()
        val instrumentedService = SubmissionService(
            repository = repository,
            governanceMetrics = RegulatoryGovernanceMetrics(meterRegistry),
        )
        val id = UUID.randomUUID().toString()
        val submission = aSubmission(id = id, status = SubmissionStatus.PENDING_REVIEW)
        coEvery { repository.findById(id) } returns submission

        shouldThrow<IllegalStateException> {
            instrumentedService.submit(id)
        }

        meterRegistry.find("regulatory_submission_outcomes_total").counter() shouldBe null
    }
})

private fun aSubmission(
    id: String = UUID.randomUUID().toString(),
    reportType: String = "FRTB_SBM",
    status: SubmissionStatus = SubmissionStatus.DRAFT,
    preparerId: String = "analyst-1",
    approverId: String? = null,
    deadline: Instant = Instant.parse("2026-03-31T23:59:59Z"),
    submittedAt: Instant? = null,
    acknowledgedAt: Instant? = null,
    createdAt: Instant = Instant.now(),
) = RegulatorySubmission(
    id = id,
    reportType = reportType,
    status = status,
    preparerId = preparerId,
    approverId = approverId,
    deadline = deadline,
    submittedAt = submittedAt,
    acknowledgedAt = acknowledgedAt,
    createdAt = createdAt,
)
