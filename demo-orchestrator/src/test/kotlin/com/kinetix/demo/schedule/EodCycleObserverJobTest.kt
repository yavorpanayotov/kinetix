package com.kinetix.demo.schedule

import com.kinetix.demo.client.RegulatoryServiceClient
import com.kinetix.demo.client.dtos.BacktestRequest
import com.kinetix.demo.client.dtos.BacktestResult
import com.kinetix.demo.client.dtos.CreateSubmissionRequest
import com.kinetix.demo.client.dtos.SubmissionRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import java.time.Duration

/**
 * Unit tests for [EodCycleObserverJob].
 *
 * Drives `processEvent` directly with a fake [OfficialEodPromotedEvent] and a
 * mocked [RegulatoryServiceClient] + [BacktestInputProvider]. Uses [runTest]
 * so the 30s post-event delay is virtual and the tests run instantly.
 */
class EodCycleObserverJobTest : FunSpec({

    val sampleEvent = OfficialEodPromotedEvent(
        jobId = "job-123",
        bookId = "alpha",
        valuationDate = "2026-05-18",
        promotedBy = "promoter",
        promotedAt = "2026-05-18T22:00:00Z",
        varValue = 1_234.5,
        expectedShortfall = 1_500.0,
    )

    val stubBacktestRequest = BacktestRequest(
        dailyVarPredictions = List(30) { 1_000.0 },
        dailyPnl = List(30) { i -> ((i % 5) - 2) * 250.0 },
        confidenceLevel = 0.99,
        calculationType = "PARAMETRIC",
    )

    val sampleBacktestResult = BacktestResult(
        violationCount = 1,
        kupiecPass = true,
        trafficLightZone = "GREEN",
    )

    val sampleSubmissionRef = SubmissionRef(
        id = "submission-1",
        reportType = "DAILY_RISK_SUMMARY",
        status = "DRAFT",
    )

    fun newProvider(): BacktestInputProvider {
        val provider = mockk<BacktestInputProvider>()
        coEvery { provider.fetchFor(any()) } returns stubBacktestRequest
        return provider
    }

    fun newClient(): RegulatoryServiceClient {
        val client = mockk<RegulatoryServiceClient>()
        coEvery { client.runBacktest(any(), any()) } returns sampleBacktestResult
        coEvery { client.createSubmission(any()) } returns sampleSubmissionRef
        return client
    }

    fun newJob(
        client: RegulatoryServiceClient,
        provider: BacktestInputProvider,
        postEventDelay: Duration = Duration.ZERO,
    ): EodCycleObserverJob = EodCycleObserverJob(
        regulatoryClient = client,
        backtestInputProvider = provider,
        postEventDelay = postEventDelay,
    )

    test("happy path calls runBacktest and createSubmission exactly once each") {
        val client = newClient()
        val provider = newProvider()
        val job = newJob(client, provider)

        val backtestSlot = slot<BacktestRequest>()
        coEvery { client.runBacktest("alpha", capture(backtestSlot)) } returns sampleBacktestResult

        val submissionSlot = slot<CreateSubmissionRequest>()
        coEvery { client.createSubmission(capture(submissionSlot)) } returns sampleSubmissionRef

        runTest { job.processEvent(sampleEvent) }

        coVerify(exactly = 1) { provider.fetchFor("alpha") }
        coVerify(exactly = 1) { client.runBacktest("alpha", any()) }
        coVerify(exactly = 1) { client.createSubmission(any()) }

        backtestSlot.captured shouldBe stubBacktestRequest

        submissionSlot.captured.reportType shouldBe "DAILY_RISK_SUMMARY"
        submissionSlot.captured.preparerId shouldBe "demo-orchestrator"
        submissionSlot.captured.deadline shouldBe "2026-05-19T17:00:00Z"
    }

    test("submission deadline rolls over month-end when valuationDate is 2026-12-31") {
        val client = newClient()
        val provider = newProvider()
        val job = newJob(client, provider)

        val submissionSlot = slot<CreateSubmissionRequest>()
        coEvery { client.createSubmission(capture(submissionSlot)) } returns sampleSubmissionRef

        val yearEndEvent = sampleEvent.copy(valuationDate = "2026-12-31")
        runTest { job.processEvent(yearEndEvent) }

        submissionSlot.captured.deadline shouldBe "2027-01-01T17:00:00Z"
    }

    test("submission is still attempted when backtest input provider throws") {
        val client = newClient()
        val provider = mockk<BacktestInputProvider>()
        coEvery { provider.fetchFor(any()) } throws RuntimeException("snapshot store down")

        val job = newJob(client, provider)

        runTest { job.processEvent(sampleEvent) }

        coVerify(exactly = 0) { client.runBacktest(any(), any()) }
        coVerify(exactly = 1) { client.createSubmission(any()) }
    }

    test("submission is still attempted when runBacktest throws") {
        val client = mockk<RegulatoryServiceClient>()
        coEvery { client.runBacktest(any(), any()) } throws RuntimeException("regulatory backtest 500")
        coEvery { client.createSubmission(any()) } returns sampleSubmissionRef

        val provider = newProvider()
        val job = newJob(client, provider)

        runTest { job.processEvent(sampleEvent) }

        coVerify(exactly = 1) { client.runBacktest(any(), any()) }
        coVerify(exactly = 1) { client.createSubmission(any()) }
    }

    test("processEvent does not propagate exceptions when createSubmission throws") {
        val client = mockk<RegulatoryServiceClient>()
        coEvery { client.runBacktest(any(), any()) } returns sampleBacktestResult
        coEvery { client.createSubmission(any()) } throws RuntimeException("submissions 500")

        val provider = newProvider()
        val job = newJob(client, provider)

        runTest { job.processEvent(sampleEvent) } // no exception escapes

        coVerify(exactly = 1) { client.runBacktest(any(), any()) }
        coVerify(exactly = 1) { client.createSubmission(any()) }
    }

    test("ZERO post-event delay completes promptly under runTest") {
        val client = newClient()
        val provider = newProvider()
        val job = newJob(client, provider, postEventDelay = Duration.ZERO)

        runTest { job.processEvent(sampleEvent) }

        coVerify(exactly = 1) { client.runBacktest("alpha", any()) }
        coVerify(exactly = 1) { client.createSubmission(any()) }
    }

    test("backtest request sent to regulatory client is the provider's output") {
        val client = newClient()
        val provider = mockk<BacktestInputProvider>()
        val customRequest = stubBacktestRequest.copy(confidenceLevel = 0.975)
        coEvery { provider.fetchFor("alpha") } returns customRequest

        val backtestSlot = slot<BacktestRequest>()
        coEvery { client.runBacktest("alpha", capture(backtestSlot)) } returns sampleBacktestResult

        val job = newJob(client, provider)
        runTest { job.processEvent(sampleEvent) }

        backtestSlot.captured shouldBe customRequest
    }

})
