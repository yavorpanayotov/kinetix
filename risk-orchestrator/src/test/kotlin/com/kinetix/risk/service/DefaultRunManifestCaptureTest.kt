package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val USD = Currency.getInstance("USD")

private fun testPosition(
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    marketPrice: String = "170.00",
) = Position(
    bookId = BookId("port-1"),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal(marketPrice), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class DefaultRunManifestCaptureTest : FunSpec({

    val manifestRepo = mockk<RunManifestRepository>()
    val blobStore = mockk<MarketDataBlobStore>()

    val capture = DefaultRunManifestCapture(manifestRepo, blobStore)

    beforeEach {
        clearMocks(manifestRepo, blobStore)
        coEvery { manifestRepo.save(any()) } just Runs
        coEvery { manifestRepo.savePositionSnapshot(any(), any()) } just Runs
        coEvery { manifestRepo.saveMarketDataRefs(any(), any()) } just Runs
        coEvery { blobStore.putIfAbsent(any(), any(), any(), any(), any()) } just Runs
    }

    test("captureInputs sets INPUTS_FROZEN status and empty model version") {
        val positions = listOf(testPosition())
        val request = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
            timeHorizonDays = 1,
            numSimulations = 10_000,
            monteCarloSeed = 42,
        )
        val jobId = UUID.randomUUID()
        val valuationDate = LocalDate.of(2026, 3, 13)

        val result = capture.captureInputs(
            jobId = jobId,
            request = request,
            positions = positions,
            fetchResults = emptyList(),
            valuationDate = valuationDate,
        )

        result.jobId shouldBe jobId
        result.bookId shouldBe "port-1"
        result.calculationType shouldBe "PARAMETRIC"
        result.confidenceLevel shouldBe "CL_95"
        result.timeHorizonDays shouldBe 1
        result.numSimulations shouldBe 10_000
        result.monteCarloSeed shouldBe 42
        result.modelVersion shouldBe ""
        result.valuationDate shouldBe valuationDate
        result.positionCount shouldBe 1
        result.status shouldBe ManifestStatus.INPUTS_FROZEN
        result.varValue shouldBe null
        result.expectedShortfall shouldBe null
        result.outputDigest shouldBe null
        result.positionDigest shouldMatch "[a-f0-9]{64}"
        result.marketDataDigest shouldMatch "[a-f0-9]{64}"
        result.inputDigest shouldMatch "[a-f0-9]{64}"

        coVerify { manifestRepo.save(result) }
    }

    test("saves position snapshot entries for all positions") {
        val positions = listOf(
            testPosition(instrumentId = "AAPL", marketPrice = "170.00"),
            testPosition(instrumentId = "MSFT", marketPrice = "420.00"),
        )
        val request = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val manifest = capture.captureInputs(
            jobId = UUID.randomUUID(),
            request = request,
            positions = positions,
            fetchResults = emptyList(),
            valuationDate = LocalDate.now(),
        )

        val snapshotSlot = slot<List<PositionSnapshotEntry>>()
        coVerify { manifestRepo.savePositionSnapshot(manifest.manifestId, capture(snapshotSlot)) }

        val entries = snapshotSlot.captured
        entries shouldHaveSize 2
        entries[0].instrumentId shouldBe "AAPL"
        entries[0].assetClass shouldBe "EQUITY"
        entries[0].quantity shouldBe BigDecimal("100")
        entries[0].currency shouldBe "USD"
        entries[1].instrumentId shouldBe "MSFT"
    }

    test("stores fetched market data blobs and saves refs with content hash") {
        val positions = listOf(testPosition())
        val dependency = DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY")
        val spotValue = ScalarMarketData("SPOT_PRICE", "AAPL", "EQUITY", 170.5)
        val fetchResults = listOf<FetchResult>(FetchSuccess(dependency, spotValue))

        val request = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val manifest = capture.captureInputs(
            jobId = UUID.randomUUID(),
            request = request,
            positions = positions,
            fetchResults = fetchResults,
            valuationDate = LocalDate.now(),
        )

        coVerify { blobStore.putIfAbsent(any(), "SPOT_PRICE", "AAPL", "EQUITY", any()) }

        val refsSlot = slot<List<MarketDataRef>>()
        coVerify { manifestRepo.saveMarketDataRefs(manifest.manifestId, capture(refsSlot)) }

        val refs = refsSlot.captured
        refs shouldHaveSize 1
        refs[0].dataType shouldBe "SPOT_PRICE"
        refs[0].instrumentId shouldBe "AAPL"
        refs[0].assetClass shouldBe "EQUITY"
        refs[0].status shouldBe MarketDataSnapshotStatus.FETCHED
        refs[0].sourceService shouldBe "price-service"
        refs[0].contentHash shouldMatch "[a-f0-9]{64}"
    }

    test("records MISSING refs for failed fetches without storing blob") {
        val positions = listOf(testPosition())
        val dependency = DiscoveredDependency("YIELD_CURVE", "USD_SOFR", "RATES")
        val fetchResults = listOf<FetchResult>(
            FetchFailure(
                dependency = dependency,
                reason = "NOT_FOUND",
                url = null,
                httpStatus = 404,
                errorMessage = null,
                service = "rates-service",
                timestamp = Instant.now(),
                durationMs = 10,
            )
        )

        val request = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val manifest = capture.captureInputs(
            jobId = UUID.randomUUID(),
            request = request,
            positions = positions,
            fetchResults = fetchResults,
            valuationDate = LocalDate.now(),
        )

        coVerify(exactly = 0) { blobStore.putIfAbsent(any(), any(), any(), any(), any()) }

        manifest.status shouldBe ManifestStatus.PARTIAL

        val refsSlot = slot<List<MarketDataRef>>()
        coVerify { manifestRepo.saveMarketDataRefs(manifest.manifestId, capture(refsSlot)) }

        val refs = refsSlot.captured
        refs shouldHaveSize 1
        refs[0].status shouldBe MarketDataSnapshotStatus.MISSING
        refs[0].contentHash shouldBe ""
        refs[0].sourceService shouldBe "rates-service"
    }

    test("manifest status is INPUTS_FROZEN when all fetches succeed") {
        val positions = listOf(testPosition())
        val dependency = DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY")
        val spotValue = ScalarMarketData("SPOT_PRICE", "AAPL", "EQUITY", 170.5)
        val fetchResults = listOf<FetchResult>(FetchSuccess(dependency, spotValue))

        val request = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val manifest = capture.captureInputs(
            jobId = UUID.randomUUID(),
            request = request,
            positions = positions,
            fetchResults = fetchResults,
            valuationDate = LocalDate.now(),
        )

        manifest.status shouldBe ManifestStatus.INPUTS_FROZEN
    }

    test("captureInputs with no fetch results produces INPUTS_FROZEN status") {
        val positions = listOf(testPosition())
        val request = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        val manifest = capture.captureInputs(
            jobId = UUID.randomUUID(),
            request = request,
            positions = positions,
            fetchResults = emptyList(),
            valuationDate = LocalDate.now(),
        )

        manifest.status shouldBe ManifestStatus.INPUTS_FROZEN
    }

    test("input digest changes when request parameters change") {
        val positions = listOf(testPosition())
        val request1 = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )
        val request2 = request1.copy(calculationType = CalculationType.MONTE_CARLO)

        val manifest1 = capture.captureInputs(
            jobId = UUID.randomUUID(),
            request = request1,
            positions = positions,
            fetchResults = emptyList(),
            valuationDate = LocalDate.now(),
        )

        val manifest2 = capture.captureInputs(
            jobId = UUID.randomUUID(),
            request = request2,
            positions = positions,
            fetchResults = emptyList(),
            valuationDate = LocalDate.now(),
        )

        // Different calculation type should produce different input digest
        (manifest1.inputDigest != manifest2.inputDigest) shouldBe true
        // But same position digest since positions are unchanged
        manifest1.positionDigest shouldBe manifest2.positionDigest
    }

    test("finaliseOutputs updates manifest with model version, outputs, and emits audit event") {
        val auditPublisher = mockk<RiskAuditEventPublisher>()
        coEvery { auditPublisher.publish(any()) } just Runs

        val captureWithAudit = DefaultRunManifestCapture(manifestRepo, blobStore, auditPublisher)

        val manifestId = UUID.randomUUID()
        val existingManifest = RunManifest(
            manifestId = manifestId,
            jobId = UUID.randomUUID(),
            bookId = "port-1",
            valuationDate = LocalDate.of(2026, 3, 13),
            capturedAt = Instant.now(),
            modelVersion = "",
            calculationType = "PARAMETRIC",
            confidenceLevel = "CL_95",
            timeHorizonDays = 1,
            numSimulations = 10_000,
            monteCarloSeed = 0,
            positionCount = 1,
            positionDigest = "abc123",
            marketDataDigest = "def456",
            inputDigest = "old-digest",
            status = ManifestStatus.INPUTS_FROZEN,
        )

        coEvery { manifestRepo.findByManifestId(manifestId) } returns existingManifest
        coEvery { manifestRepo.finaliseManifest(any(), any(), any(), any(), any(), any(), any()) } just Runs

        captureWithAudit.finaliseOutputs(
            manifestId = manifestId,
            modelVersion = "1.2.0-prod",
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            componentBreakdown = emptyList(),
        )

        // Verify manifest was finalised with correct values
        coVerify {
            manifestRepo.finaliseManifest(
                manifestId = manifestId,
                modelVersion = "1.2.0-prod",
                varValue = 5000.0,
                expectedShortfall = 6250.0,
                outputDigest = any(),
                inputDigest = any(),
                status = ManifestStatus.COMPLETE,
            )
        }

        // Verify audit event was emitted with real model version
        val eventSlot = slot<RiskAuditEvent>()
        coVerify { auditPublisher.publish(capture(eventSlot)) }
        val event = eventSlot.captured as ManifestFrozenEvent
        event.eventType shouldBe "RISK_RUN_MANIFEST_FROZEN"
        event.modelVersion shouldBe "1.2.0-prod"
        event.status shouldBe "COMPLETE"
    }

    test("finaliseOutputs preserves PARTIAL status when manifest had missing market data") {
        val captureNoAudit = DefaultRunManifestCapture(manifestRepo, blobStore)

        val manifestId = UUID.randomUUID()
        val existingManifest = RunManifest(
            manifestId = manifestId,
            jobId = UUID.randomUUID(),
            bookId = "port-1",
            valuationDate = LocalDate.of(2026, 3, 13),
            capturedAt = Instant.now(),
            modelVersion = "",
            calculationType = "PARAMETRIC",
            confidenceLevel = "CL_95",
            timeHorizonDays = 1,
            numSimulations = 10_000,
            monteCarloSeed = 0,
            positionCount = 1,
            positionDigest = "abc123",
            marketDataDigest = "def456",
            inputDigest = "old-digest",
            status = ManifestStatus.PARTIAL,
        )

        coEvery { manifestRepo.findByManifestId(manifestId) } returns existingManifest
        coEvery { manifestRepo.finaliseManifest(any(), any(), any(), any(), any(), any(), any()) } just Runs

        captureNoAudit.finaliseOutputs(
            manifestId = manifestId,
            modelVersion = "1.2.0-prod",
            varValue = 5000.0,
            expectedShortfall = 6250.0,
            componentBreakdown = emptyList(),
        )

        coVerify {
            manifestRepo.finaliseManifest(
                manifestId = manifestId,
                modelVersion = "1.2.0-prod",
                varValue = 5000.0,
                expectedShortfall = 6250.0,
                outputDigest = any(),
                inputDigest = any(),
                status = ManifestStatus.PARTIAL,
            )
        }
    }

    test("audit event is not emitted during captureInputs") {
        val auditPublisher = mockk<RiskAuditEventPublisher>()
        val captureWithAudit = DefaultRunManifestCapture(manifestRepo, blobStore, auditPublisher)

        val positions = listOf(testPosition())
        val request = VaRCalculationRequest(
            bookId = BookId("port-1"),
            calculationType = CalculationType.PARAMETRIC,
            confidenceLevel = ConfidenceLevel.CL_95,
        )

        captureWithAudit.captureInputs(
            jobId = UUID.randomUUID(),
            request = request,
            positions = positions,
            fetchResults = emptyList(),
            valuationDate = LocalDate.now(),
        )

        // No audit event should be emitted during input capture
        coVerify(exactly = 0) { auditPublisher.publish(any()) }
    }
})
