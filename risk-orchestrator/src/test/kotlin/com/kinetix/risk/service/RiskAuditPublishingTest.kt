package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val USD = Currency.getInstance("USD")

private fun testPosition(instrumentId: String = "AAPL") = Position(
    bookId = BookId("port-1"),
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal("100"),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal("170.00"), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class RiskAuditPublishingTest : FunSpec({

    val manifestRepo = mockk<RunManifestRepository>()
    val blobStore = mockk<MarketDataBlobStore>()
    val auditPublisher = mockk<RiskAuditEventPublisher>()

    beforeEach {
        clearMocks(manifestRepo, blobStore, auditPublisher)
        coEvery { manifestRepo.save(any()) } just Runs
        coEvery { manifestRepo.savePositionSnapshot(any(), any()) } just Runs
        coEvery { manifestRepo.saveMarketDataRefs(any(), any()) } just Runs
        coEvery { blobStore.putIfAbsent(any(), any(), any(), any(), any()) } just Runs
        coEvery { auditPublisher.publish(any()) } just Runs
    }

    test("finaliseOutputs publishes RISK_RUN_MANIFEST_FROZEN audit event with real model version") {
        val capture = DefaultRunManifestCapture(manifestRepo, blobStore, auditPublisher)
        val request = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val manifest = capture.captureInputs(
            jobId = UUID.randomUUID(),
            request = request,
            positions = listOf(testPosition()),
            fetchResults = emptyList(),
            valuationDate = LocalDate.of(2026, 3, 13),
        )

        // No audit event during captureInputs
        coVerify(exactly = 0) { auditPublisher.publish(any()) }

        // Now finalise with real model version
        coEvery { manifestRepo.findByManifestId(manifest.manifestId) } returns manifest
        coEvery { manifestRepo.finaliseManifest(any(), any(), any(), any(), any(), any(), any()) } just Runs

        capture.finaliseOutputs(
            manifestId = manifest.manifestId,
            modelVersion = "0.1.0-dev",
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            componentBreakdown = emptyList(),
        )

        val eventSlot = slot<RiskAuditEvent>()
        coVerify { auditPublisher.publish(capture(eventSlot)) }

        val event = eventSlot.captured
        event.shouldBeInstanceOf<ManifestFrozenEvent>()
        event.eventType shouldBe "RISK_RUN_MANIFEST_FROZEN"
        event.bookId shouldBe "port-1"
        event.valuationDate shouldBe "2026-03-13"
        (event as ManifestFrozenEvent).positionCount shouldBe 1
        event.modelVersion shouldBe "0.1.0-dev"
        event.calculationType shouldBe "PARAMETRIC"
        event.status shouldBe "COMPLETE"
    }

    test("EOD promotion publishes RISK_RUN_EOD_PROMOTED audit event when manifest exists") {
        val jobRecorder = mockk<ValuationJobRecorder>()
        val eventPublisher = mockk<OfficialEodEventPublisher>()
        val service = EodPromotionService(jobRecorder, eventPublisher, riskAuditPublisher = auditPublisher)

        val jobId = UUID.randomUUID()
        val manifestId = UUID.randomUUID()
        val job = ValuationJob(
            jobId = jobId,
            bookId = "port-1",
            triggerType = TriggerType.ON_DEMAND,
            status = RunStatus.COMPLETED,
            startedAt = Instant.parse("2026-03-13T17:00:00Z"),
            valuationDate = LocalDate.of(2026, 3, 13),
            completedAt = Instant.parse("2026-03-13T17:00:30Z"),
            durationMs = 30_000,
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            triggeredBy = "user-a",
            manifestId = manifestId,
        )
        val promoted = job.copy(
            runLabel = RunLabel.OFFICIAL_EOD,
            promotedAt = Instant.parse("2026-03-13T18:00:00Z"),
            promotedBy = "user-b",
        )

        coEvery { jobRecorder.findByJobId(jobId) } returns job
        coEvery { jobRecorder.findOfficialEodByDate("port-1", LocalDate.of(2026, 3, 13)) } returns null
        coEvery { jobRecorder.promoteToOfficialEod(jobId, "user-b", any()) } returns promoted
        coEvery { eventPublisher.publish(any()) } just Runs

        service.promoteToOfficialEod(jobId, "user-b")

        val eventSlot = slot<RiskAuditEvent>()
        coVerify { auditPublisher.publish(capture(eventSlot)) }

        val event = eventSlot.captured
        event.shouldBeInstanceOf<EodPromotedAuditEvent>()
        event.eventType shouldBe "RISK_RUN_EOD_PROMOTED"
        event.bookId shouldBe "port-1"
        event.manifestId shouldBe manifestId.toString()
        (event as EodPromotedAuditEvent).promotedBy shouldBe "user-b"
        event.varValue shouldBe 5000.0
        event.expectedShortfall shouldBe 6250.0
    }

    test("EOD promotion does not publish audit event when no manifestId") {
        val jobRecorder = mockk<ValuationJobRecorder>()
        val eventPublisher = mockk<OfficialEodEventPublisher>()
        val service = EodPromotionService(jobRecorder, eventPublisher, riskAuditPublisher = auditPublisher)

        val jobId = UUID.randomUUID()
        val job = ValuationJob(
            jobId = jobId,
            bookId = "port-1",
            triggerType = TriggerType.ON_DEMAND,
            status = RunStatus.COMPLETED,
            startedAt = Instant.parse("2026-03-13T17:00:00Z"),
            valuationDate = LocalDate.of(2026, 3, 13),
            completedAt = Instant.parse("2026-03-13T17:00:30Z"),
            durationMs = 30_000,
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            triggeredBy = "user-a",
            manifestId = null, // no manifest
        )
        val promoted = job.copy(
            runLabel = RunLabel.OFFICIAL_EOD,
            promotedAt = Instant.parse("2026-03-13T18:00:00Z"),
            promotedBy = "user-b",
        )

        coEvery { jobRecorder.findByJobId(jobId) } returns job
        coEvery { jobRecorder.findOfficialEodByDate("port-1", LocalDate.of(2026, 3, 13)) } returns null
        coEvery { jobRecorder.promoteToOfficialEod(jobId, "user-b", any()) } returns promoted
        coEvery { eventPublisher.publish(any()) } just Runs

        service.promoteToOfficialEod(jobId, "user-b")

        coVerify(exactly = 0) { auditPublisher.publish(any()) }
    }

    test("finaliseOutputs succeeds even when audit publishing fails") {
        val failingPublisher = mockk<RiskAuditEventPublisher>()
        coEvery { failingPublisher.publish(any()) } throws RuntimeException("Kafka unavailable")

        val capture = DefaultRunManifestCapture(manifestRepo, blobStore, failingPublisher)
        val request = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val manifest = capture.captureInputs(
            jobId = UUID.randomUUID(),
            request = request,
            positions = listOf(testPosition()),
            fetchResults = emptyList(),
            valuationDate = LocalDate.of(2026, 3, 13),
        )

        // Manifest is captured successfully
        manifest.bookId shouldBe "port-1"
        manifest.status shouldBe ManifestStatus.INPUTS_FROZEN
        coVerify { manifestRepo.save(manifest) }

        // finaliseOutputs should not throw even if audit publishing fails
        coEvery { manifestRepo.findByManifestId(manifest.manifestId) } returns manifest
        coEvery { manifestRepo.finaliseManifest(any(), any(), any(), any(), any(), any(), any()) } just Runs

        capture.finaliseOutputs(
            manifestId = manifest.manifestId,
            modelVersion = "0.1.0-dev",
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            componentBreakdown = emptyList(),
        )

        // Finalise still persisted despite audit failure
        coVerify { manifestRepo.finaliseManifest(manifest.manifestId, any(), any(), any(), any(), any(), any()) }
    }
})
