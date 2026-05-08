package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.client.RiskEngineClient
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

class ReplayServiceTest : FunSpec({

    val manifestRepo = mockk<RunManifestRepository>()
    val blobStore = mockk<MarketDataBlobStore>()
    val riskEngineClient = mockk<RiskEngineClient>()
    val jobRecorder = mockk<ValuationJobRecorder>()
    val auditPublisher = mockk<RiskAuditEventPublisher>()
    val replayRunRepo = mockk<ReplayRunRepository>()

    val replayService = ReplayService(manifestRepo, blobStore, riskEngineClient, jobRecorder, auditPublisher, replayRunRepo)

    val testManifest = RunManifest(
        manifestId = UUID.randomUUID(),
        jobId = UUID.randomUUID(),
        bookId = "port-1",
        valuationDate = LocalDate.of(2026, 3, 13),
        capturedAt = Instant.now(),
        modelVersion = "0.1.0-abc12345",
        calculationType = "PARAMETRIC",
        confidenceLevel = "CL_95",
        timeHorizonDays = 1,
        numSimulations = 10_000,
        monteCarloSeed = 0,
        positionCount = 1,
        positionDigest = "abc",
        marketDataDigest = "def",
        inputDigest = "ghi",
        status = ManifestStatus.COMPLETE,
        varValue = 5000.0,
        expectedShortfall = 6250.0,
    )

    val testPositionEntries = listOf(
        PositionSnapshotEntry(
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            quantity = BigDecimal("100"),
            averageCostAmount = BigDecimal("150.00"),
            marketPriceAmount = BigDecimal("170.00"),
            currency = "USD",
            marketValueAmount = BigDecimal("17000.00"),
            unrealizedPnlAmount = BigDecimal("2000.00"),
            instrumentType = "CASH_EQUITY",
        )
    )

    val testValuationResult = ValuationResult(
        bookId = BookId("port-1"),
        calculationType = CalculationType.PARAMETRIC,
        confidenceLevel = ConfidenceLevel.CL_95,
        varValue = 5000.0,
        expectedShortfall = 6250.0,
        componentBreakdown = listOf(ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0)),
        greeks = null,
        calculatedAt = Instant.now(),
        computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
    )

    beforeEach {
        clearMocks(manifestRepo, blobStore, riskEngineClient, jobRecorder, auditPublisher, replayRunRepo)
        coEvery { auditPublisher.publish(any()) } just Runs
        coEvery { replayRunRepo.save(any()) } just Runs
    }

    test("returns ManifestNotFound when no manifest exists for job") {
        val jobId = UUID.randomUUID()
        coEvery { manifestRepo.findByJobId(jobId) } returns null

        val result = replayService.replay(jobId)
        result.shouldBeInstanceOf<ReplayResult.ManifestNotFound>()
    }

    test("returns Error when position snapshot is empty") {
        val jobId = testManifest.jobId
        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest
        coEvery { manifestRepo.findPositionSnapshot(testManifest.manifestId) } returns emptyList()

        val result = replayService.replay(jobId)
        result.shouldBeInstanceOf<ReplayResult.Error>()
    }

    test("returns BlobMissing when a FETCHED market data blob is not found in the blob store") {
        val jobId = testManifest.jobId
        val contentHash = "abcdef1234567890"
        val refs = listOf(
            MarketDataRef(
                dataType = "SPOT_PRICE",
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                contentHash = contentHash,
                status = MarketDataSnapshotStatus.FETCHED,
                sourceService = "price-service",
                sourcedAt = Instant.now(),
            )
        )

        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest
        coEvery { manifestRepo.findPositionSnapshot(testManifest.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(testManifest.manifestId) } returns refs
        coEvery { blobStore.get(contentHash) } returns null

        val result = replayService.replay(jobId)
        result.shouldBeInstanceOf<ReplayResult.BlobMissing>()
        result.contentHash shouldBe contentHash
        result.dataType shouldBe "SPOT_PRICE"
        result.instrumentId shouldBe "AAPL"
    }

    test("replays a valuation using snapshot positions and no market data") {
        val jobId = testManifest.jobId

        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest
        coEvery { manifestRepo.findPositionSnapshot(testManifest.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(testManifest.manifestId) } returns emptyList()
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns testValuationResult
        coEvery { jobRecorder.findByJobId(jobId) } returns null

        val result = replayService.replay(jobId)
        result.shouldBeInstanceOf<ReplayResult.Success>()

        result.manifest shouldBe testManifest
        result.replayResult.varValue shouldBe 5000.0
        result.replayResult.expectedShortfall shouldBe 6250.0
        // Original values come from manifest when job record is null
        result.originalVarValue shouldBe 5000.0
        result.originalExpectedShortfall shouldBe 6250.0
    }

    test("replay result includes original VaR and ES from job record when manifest has no outputs") {
        val manifestNoOutputs = testManifest.copy(varValue = null, expectedShortfall = null)
        val jobId = manifestNoOutputs.jobId

        val jobWithValues = ValuationJob(
            jobId = jobId,
            bookId = "port-1",
            triggerType = TriggerType.ON_DEMAND,
            status = RunStatus.COMPLETED,
            startedAt = Instant.now(),
            valuationDate = LocalDate.now(),
            varValue = 4800.0,
            expectedShortfall = 6000.0,
            triggeredBy = "user-a",
        )

        coEvery { manifestRepo.findByJobId(jobId) } returns manifestNoOutputs
        coEvery { manifestRepo.findPositionSnapshot(manifestNoOutputs.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(manifestNoOutputs.manifestId) } returns emptyList()
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns testValuationResult
        coEvery { jobRecorder.findByJobId(jobId) } returns jobWithValues

        val result = replayService.replay(jobId)
        result.shouldBeInstanceOf<ReplayResult.Success>()
        result.originalVarValue shouldBe 4800.0
        result.originalExpectedShortfall shouldBe 6000.0
    }

    test("replays with market data blobs resolved from blob store") {
        val jobId = testManifest.jobId
        val contentHash = "abcdef1234567890"
        val blob = """{"dataType":"SPOT_PRICE","instrumentId":"AAPL","assetClass":"EQUITY","value":170.5}"""

        val refs = listOf(
            MarketDataRef(
                dataType = "SPOT_PRICE",
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                contentHash = contentHash,
                status = MarketDataSnapshotStatus.FETCHED,
                sourceService = "price-service",
                sourcedAt = Instant.now(),
            )
        )

        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest
        coEvery { manifestRepo.findPositionSnapshot(testManifest.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(testManifest.manifestId) } returns refs
        coEvery { blobStore.get(contentHash) } returns blob
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns testValuationResult
        coEvery { jobRecorder.findByJobId(jobId) } returns null

        val result = replayService.replay(jobId)
        result.shouldBeInstanceOf<ReplayResult.Success>()

        // Verify market data was passed to the risk engine
        val marketDataSlot = slot<List<MarketDataValue>>()
        coVerify { riskEngineClient.valuate(any(), any(), capture(marketDataSlot)) }
        marketDataSlot.captured.size shouldBe 1
        val scalar = marketDataSlot.captured[0]
        scalar.shouldBeInstanceOf<ScalarMarketData>()
        scalar.value shouldBe 170.5
    }

    test("skips MISSING market data refs without calling blob store") {
        val jobId = testManifest.jobId
        val refs = listOf(
            MarketDataRef(
                dataType = "YIELD_CURVE",
                instrumentId = "USD_SOFR",
                assetClass = "RATES",
                contentHash = "",
                status = MarketDataSnapshotStatus.MISSING,
                sourceService = "rates-service",
                sourcedAt = Instant.now(),
            )
        )

        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest
        coEvery { manifestRepo.findPositionSnapshot(testManifest.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(testManifest.manifestId) } returns refs
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns testValuationResult
        coEvery { jobRecorder.findByJobId(jobId) } returns null

        val result = replayService.replay(jobId)
        result.shouldBeInstanceOf<ReplayResult.Success>()

        coVerify(exactly = 0) { blobStore.get(any()) }
    }

    test("getManifest returns manifest when it exists") {
        val jobId = testManifest.jobId
        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest

        val manifest = replayService.getManifest(jobId)
        manifest shouldBe testManifest
    }

    test("getManifest returns null when no manifest exists") {
        val jobId = UUID.randomUUID()
        coEvery { manifestRepo.findByJobId(jobId) } returns null

        val manifest = replayService.getManifest(jobId)
        manifest shouldBe null
    }

    test("replay request uses original manifest parameters") {
        val jobId = testManifest.jobId
        val manifest = testManifest.copy(
            calculationType = "MONTE_CARLO",
            numSimulations = 50_000,
            monteCarloSeed = 42,
            timeHorizonDays = 10,
        )

        coEvery { manifestRepo.findByJobId(jobId) } returns manifest
        coEvery { manifestRepo.findPositionSnapshot(manifest.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(manifest.manifestId) } returns emptyList()
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns testValuationResult.copy(
            calculationType = CalculationType.MONTE_CARLO,
        )
        coEvery { jobRecorder.findByJobId(jobId) } returns null

        replayService.replay(jobId)

        val requestSlot = slot<VaRCalculationRequest>()
        coVerify { riskEngineClient.valuate(capture(requestSlot), any(), any()) }
        requestSlot.captured.calculationType shouldBe CalculationType.MONTE_CARLO
        requestSlot.captured.numSimulations shouldBe 50_000
        requestSlot.captured.monteCarloSeed shouldBe 42
        requestSlot.captured.timeHorizonDays shouldBe 10
    }

    test("successful replay publishes RISK_RUN_REPLAYED audit event") {
        val jobId = testManifest.jobId

        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest
        coEvery { manifestRepo.findPositionSnapshot(testManifest.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(testManifest.manifestId) } returns emptyList()
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns testValuationResult
        coEvery { jobRecorder.findByJobId(jobId) } returns null

        replayService.replay(jobId)

        val eventSlot = slot<RiskAuditEvent>()
        coVerify { auditPublisher.publish(capture(eventSlot)) }
        val event = eventSlot.captured
        event.shouldBeInstanceOf<RunReplayedAuditEvent>()
        event.eventType shouldBe "RISK_RUN_REPLAYED"
        event.bookId shouldBe "port-1"
        event.manifestId shouldBe testManifest.manifestId.toString()
        event.replayVarValue shouldBe 5000.0
        event.originalVarValue shouldBe 5000.0
    }

    test("replay succeeds even when audit publishing fails") {
        val jobId = testManifest.jobId

        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest
        coEvery { manifestRepo.findPositionSnapshot(testManifest.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(testManifest.manifestId) } returns emptyList()
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns testValuationResult
        coEvery { jobRecorder.findByJobId(jobId) } returns null
        coEvery { auditPublisher.publish(any()) } throws RuntimeException("Kafka unavailable")

        val result = replayService.replay(jobId)
        result.shouldBeInstanceOf<ReplayResult.Success>()
        result.replayResult.varValue shouldBe 5000.0
    }

    test("does not publish audit event when manifest is not found") {
        val jobId = UUID.randomUUID()
        coEvery { manifestRepo.findByJobId(jobId) } returns null

        replayService.replay(jobId)

        coVerify(exactly = 0) { auditPublisher.publish(any()) }
    }

    test("persists replay run on successful replay") {
        val jobId = testManifest.jobId

        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest
        coEvery { manifestRepo.findPositionSnapshot(testManifest.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(testManifest.manifestId) } returns emptyList()
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns testValuationResult
        coEvery { jobRecorder.findByJobId(jobId) } returns null

        replayService.replay(jobId)

        val replayRunSlot = slot<ReplayRun>()
        coVerify { replayRunRepo.save(capture(replayRunSlot)) }
        val saved = replayRunSlot.captured
        saved.manifestId shouldBe testManifest.manifestId
        saved.originalJobId shouldBe jobId
        saved.replayVarValue shouldBe 5000.0
        saved.replayExpectedShortfall shouldBe 6250.0
        saved.originalVarValue shouldBe 5000.0
        saved.originalExpectedShortfall shouldBe 6250.0
    }

    test("replay succeeds even when replay run persistence fails") {
        val jobId = testManifest.jobId

        coEvery { manifestRepo.findByJobId(jobId) } returns testManifest
        coEvery { manifestRepo.findPositionSnapshot(testManifest.manifestId) } returns testPositionEntries
        coEvery { manifestRepo.findMarketDataRefs(testManifest.manifestId) } returns emptyList()
        coEvery { riskEngineClient.valuate(any(), any(), any()) } returns testValuationResult
        coEvery { jobRecorder.findByJobId(jobId) } returns null
        coEvery { replayRunRepo.save(any()) } throws RuntimeException("DB down")

        val result = replayService.replay(jobId)
        result.shouldBeInstanceOf<ReplayResult.Success>()
    }
})
