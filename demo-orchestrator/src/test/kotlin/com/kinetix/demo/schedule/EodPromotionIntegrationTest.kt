package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorHttpClient
import com.kinetix.demo.profile.DemoBookProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Integration test for [EodPromotionJob] wired against a real local Ktor
 * stub of `risk-orchestrator`.
 *
 * Per CLAUDE.md (Project Conventions / Acceptance tests), the HTTP boundary
 * to another Kinetix service is exercised over a real localhost HTTP/1.1
 * channel — never via `MockEngine`. The stub binds to port 0 and the
 * [RiskOrchestratorHttpClient] is pointed at the resolved port, so
 * serialisation, content negotiation, and the HTTP wire are all real.
 *
 * The fake `risk-orchestrator` mirrors only the four routes the EOD demo
 * flow needs:
 *  - `POST /api/v1/risk/var/{bookId}` — records a new valuation job
 *  - `GET /api/v1/risk/jobs/{bookId}` — paginated job history
 *  - `GET /api/v1/risk/jobs/{bookId}/official-eod` — idempotency probe
 *  - `PATCH /api/v1/risk/jobs/{jobId}/label` — promotes a job to
 *    `OFFICIAL_EOD` and writes through to the in-memory EOD designation
 *    store so subsequent `GET /api/v1/risk/eod-timeline/{bookId}` calls
 *    return the promoted entry. Mirrors
 *    `EodPromotionService.promoteCore` + `KafkaOfficialEodPublisher`.
 *  - `GET /api/v1/risk/eod-timeline/{bookId}` — the read path the live UI's
 *    EOD History tab consumes; the assertion in this test that drives
 *    checkbox 5.1 of `docs/plans/ui-fix-v1.md`.
 *
 * No Postgres / Kafka Testcontainers here: demo-orchestrator has no
 * database of its own, and the `OfficialEodPromotedEvent` Kafka
 * publication is owned by `risk-orchestrator`'s
 * `KafkaOfficialEodPublisher` (out of scope to modify). We assert
 * end-to-end via the upstream's `GET /api/v1/risk/eod-timeline/{bookId}`
 * read path — the contract the EOD History tab actually consumes.
 */
class EodPromotionIntegrationTest : FunSpec({

    test("runOnce promotes a fresh EOD designation for every book and the eod-timeline read path returns it") {
        val state = FakeRiskOrchestratorState()
        val stubServer = startFakeRiskOrchestrator(state)
        val port = runBlocking { stubServer.engine.resolvedConnectors().first().port }
        val baseUrl = "http://localhost:$port"

        val httpClient = HttpClient(CIO) {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        try {
            val client = RiskOrchestratorHttpClient(
                httpClient = httpClient,
                baseUrl = baseUrl,
            )

            val balancedIncome = DemoBookProfile(
                bookId = "balanced-income",
                tradeProbability = 0.0,
                instrumentIds = listOf("JNJ"),
                notionalRangeUsd = 1L..2L,
                assetClass = "EQUITY",
            )
            val derivatives = DemoBookProfile(
                bookId = "derivatives-book",
                tradeProbability = 0.0,
                instrumentIds = listOf("SPX-OPT-5000C"),
                notionalRangeUsd = 1L..2L,
                assetClass = "DERIVATIVE",
            )

            // Pin the clock past the configured trading-hours-end (16:30 UTC).
            // 2026-05-18 is a Monday and matches the documented audit date in
            // docs/plans/ui-fix-v1.md.
            val closeInstant = LocalDate.of(2026, 5, 18)
                .atTime(17, 0)
                .atZone(ZoneOffset.UTC)
                .toInstant()
            val fixedClock = Clock.fixed(closeInstant, ZoneOffset.UTC)

            val job = EodPromotionJob(
                client = client,
                books = listOf(balancedIncome, derivatives),
                clock = fixedClock,
            )

            val promoted = runBlocking { job.runOnce() }
            promoted shouldBe 2

            // Both books should now have an Official EOD designation.
            state.officialEodDesignations.keys shouldHaveSize 2
            state.varCalculationCount["balanced-income"] shouldBe 1
            state.varCalculationCount["derivatives-book"] shouldBe 1

            // The EOD-timeline read path returns the promoted entry. This is
            // the contract the live UI's EOD History tab consumes.
            val timeline = runBlocking {
                client.eodTimeline(
                    bookId = "balanced-income",
                    from = LocalDate.of(2026, 4, 19),
                    to = LocalDate.of(2026, 5, 19),
                )
            }
            timeline.bookId shouldBe "balanced-income"
            timeline.entries.size shouldBeGreaterThan 0
            timeline.entries.first().valuationDate shouldBe "2026-05-18"
            timeline.entries.first().varValue.shouldNotBeNull()
        } finally {
            httpClient.close()
            stubServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }

    test("re-running on the same simulated day is idempotent — no second VaR calculation is triggered") {
        val state = FakeRiskOrchestratorState()
        val stubServer = startFakeRiskOrchestrator(state)
        val port = runBlocking { stubServer.engine.resolvedConnectors().first().port }
        val baseUrl = "http://localhost:$port"

        val httpClient = HttpClient(CIO) {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        try {
            val client = RiskOrchestratorHttpClient(
                httpClient = httpClient,
                baseUrl = baseUrl,
            )

            val balancedIncome = DemoBookProfile(
                bookId = "balanced-income",
                tradeProbability = 0.0,
                instrumentIds = listOf("JNJ"),
                notionalRangeUsd = 1L..2L,
                assetClass = "EQUITY",
            )

            val fixedClock = Clock.fixed(
                LocalDate.of(2026, 5, 18)
                    .atTime(17, 0)
                    .atZone(ZoneOffset.UTC)
                    .toInstant(),
                ZoneOffset.UTC,
            )

            val job = EodPromotionJob(
                client = client,
                books = listOf(balancedIncome),
                clock = fixedClock,
            )

            val firstPromoted = runBlocking { job.runOnce() }
            firstPromoted shouldBe 1
            state.varCalculationCount["balanced-income"] shouldBe 1

            // Re-run on the same simulated day — should be a no-op.
            val secondPromoted = runBlocking { job.runOnce() }
            secondPromoted shouldBe 0
            state.varCalculationCount["balanced-income"] shouldBe 1
            state.officialEodDesignations.size shouldBe 1
        } finally {
            httpClient.close()
            stubServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }
})

// region — fake risk-orchestrator

@Serializable
private data class StubJobSummary(
    val jobId: String,
    val bookId: String,
    val triggerType: String = "API",
    val status: String = "COMPLETED",
    val startedAt: String,
    val completedAt: String? = null,
    val valuationDate: String? = null,
    val varValue: Double? = null,
    val expectedShortfall: Double? = null,
    val pvValue: Double? = null,
    val runLabel: String? = null,
    val promotedAt: String? = null,
    val promotedBy: String? = null,
)

@Serializable
private data class StubPaginatedJobsResponse(
    val items: List<StubJobSummary>,
    val totalCount: Long,
)

@Serializable
private data class StubVaRRequest(
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val timeHorizonDays: String? = null,
    val numSimulations: String? = null,
    val requestedOutputs: List<String>? = null,
)

@Serializable
private data class StubVaRResponse(
    val bookId: String,
    val calculationType: String,
    val confidenceLevel: String,
    val varValue: String,
    val expectedShortfall: String,
)

@Serializable
private data class StubPromoteRequest(
    val label: String,
    val promotedBy: String,
)

@Serializable
private data class StubPromotionResponse(
    val jobId: String,
    val bookId: String,
    val valuationDate: String,
    val runLabel: String,
    val promotedAt: String?,
    val promotedBy: String?,
)

@Serializable
private data class StubEodTimelineEntry(
    val valuationDate: String,
    val jobId: String,
    val varValue: Double?,
    val expectedShortfall: Double?,
    val pvValue: Double?,
)

@Serializable
private data class StubEodTimelineResponse(
    val bookId: String,
    val from: String,
    val to: String,
    val entries: List<StubEodTimelineEntry>,
)

private class FakeRiskOrchestratorState {
    val jobs: MutableList<StubJobSummary> = mutableListOf()
    val varCalculationCount: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    /** Key: "{bookId}|{valuationDate}" → promoted job summary. */
    val officialEodDesignations: ConcurrentHashMap<String, StubJobSummary> = ConcurrentHashMap()

    fun recordVarCalculation(bookId: String): StubJobSummary {
        val count = varCalculationCount.merge(bookId, 1) { existing, _ -> existing + 1 } ?: 1
        val job = StubJobSummary(
            jobId = UUID.randomUUID().toString(),
            bookId = bookId,
            status = "COMPLETED",
            startedAt = "2026-05-18T16:30:00Z",
            completedAt = "2026-05-18T16:30:01Z",
            valuationDate = "2026-05-18",
            varValue = 150_000.0 + count,
            expectedShortfall = 200_000.0 + count,
            pvValue = 1_000_000.0,
            runLabel = "ADHOC",
        )
        jobs.add(0, job) // most recent first
        return job
    }

    fun latestJobForBook(bookId: String): StubJobSummary? = jobs.firstOrNull { it.bookId == bookId }

    fun findJobById(jobId: String): StubJobSummary? = jobs.firstOrNull { it.jobId == jobId }

    fun promoteJob(jobId: String, promotedBy: String): StubJobSummary? {
        val idx = jobs.indexOfFirst { it.jobId == jobId }
        if (idx < 0) return null
        val current = jobs[idx]
        val valuationDate = current.valuationDate ?: return null
        val promoted = current.copy(
            runLabel = "OFFICIAL_EOD",
            promotedAt = "2026-05-18T17:00:00Z",
            promotedBy = promotedBy,
        )
        jobs[idx] = promoted
        officialEodDesignations["${current.bookId}|$valuationDate"] = promoted
        return promoted
    }

    fun findOfficialEod(bookId: String, valuationDate: String): StubJobSummary? =
        officialEodDesignations["$bookId|$valuationDate"]

    fun timelineFor(bookId: String, from: LocalDate, to: LocalDate): List<StubJobSummary> {
        return officialEodDesignations.entries
            .filter { it.key.startsWith("$bookId|") }
            .mapNotNull { it.value }
            .filter {
                val vd = it.valuationDate ?: return@filter false
                val parsed = LocalDate.parse(vd)
                !parsed.isBefore(from) && !parsed.isAfter(to)
            }
            .sortedBy { it.valuationDate }
    }
}

private fun startFakeRiskOrchestrator(state: FakeRiskOrchestratorState) = embeddedServer(Netty, port = 0) {
    install(ContentNegotiation) {
        // `encodeDefaults = true` so default-valued fields like
        // `StubJobSummary.status` ("COMPLETED") show up on the wire — the
        // downstream client expects them as required fields, matching
        // production `ValuationJobSummaryResponse`.
        json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
    }
    routing {
        post("/api/v1/risk/var/{bookId}") {
            val bookId = call.parameters["bookId"].orEmpty()
            // We deserialize the body to assert the contract is honoured —
            // the value is ignored beyond that.
            call.receive<StubVaRRequest>()
            val job = state.recordVarCalculation(bookId)
            call.respond(
                StubVaRResponse(
                    bookId = bookId,
                    calculationType = "PARAMETRIC",
                    confidenceLevel = "CL_95",
                    varValue = "%.2f".format(job.varValue ?: 0.0),
                    expectedShortfall = "%.2f".format(job.expectedShortfall ?: 0.0),
                ),
            )
        }

        get("/api/v1/risk/jobs/{bookId}") {
            val bookId = call.parameters["bookId"].orEmpty()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val items = state.jobs.filter { it.bookId == bookId }.take(limit)
            call.respond(
                StubPaginatedJobsResponse(
                    items = items,
                    totalCount = items.size.toLong(),
                ),
            )
        }

        get("/api/v1/risk/jobs/{bookId}/official-eod") {
            val bookId = call.parameters["bookId"].orEmpty()
            val date = call.request.queryParameters["date"]
            if (date.isNullOrBlank()) {
                call.respondText(
                    text = """{"error":"date parameter is required"}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
            val designation = state.findOfficialEod(bookId, date)
            if (designation == null) {
                call.respondText(
                    text = """{"error":"No Official EOD designation for $bookId on $date"}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.NotFound,
                )
            } else {
                call.respond(
                    StubPromotionResponse(
                        jobId = designation.jobId,
                        bookId = designation.bookId,
                        valuationDate = designation.valuationDate ?: date,
                        runLabel = designation.runLabel ?: "OFFICIAL_EOD",
                        promotedAt = designation.promotedAt,
                        promotedBy = designation.promotedBy,
                    ),
                )
            }
        }

        patch("/api/v1/risk/jobs/{jobId}/label") {
            val jobId = call.parameters["jobId"].orEmpty()
            val body = call.receive<StubPromoteRequest>()
            if (body.label != "OFFICIAL_EOD") {
                call.respondText(
                    text = """{"error":"only OFFICIAL_EOD is supported in this fake"}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
                return@patch
            }
            val promoted = state.promoteJob(jobId, body.promotedBy)
            if (promoted == null) {
                call.respondText(
                    text = """{"error":"Job $jobId not found"}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.NotFound,
                )
            } else {
                call.respond(
                    StubPromotionResponse(
                        jobId = promoted.jobId,
                        bookId = promoted.bookId,
                        valuationDate = promoted.valuationDate.orEmpty(),
                        runLabel = promoted.runLabel ?: "OFFICIAL_EOD",
                        promotedAt = promoted.promotedAt,
                        promotedBy = promoted.promotedBy,
                    ),
                )
            }
        }

        get("/api/v1/risk/eod-timeline/{bookId}") {
            val bookId = call.parameters["bookId"].orEmpty()
            val fromStr = call.request.queryParameters["from"].orEmpty()
            val toStr = call.request.queryParameters["to"].orEmpty()
            val from = LocalDate.parse(fromStr)
            val to = LocalDate.parse(toStr)
            val items = state.timelineFor(bookId, from, to)
            call.respond(
                StubEodTimelineResponse(
                    bookId = bookId,
                    from = fromStr,
                    to = toStr,
                    entries = items.map {
                        StubEodTimelineEntry(
                            valuationDate = it.valuationDate.orEmpty(),
                            jobId = it.jobId,
                            varValue = it.varValue,
                            expectedShortfall = it.expectedShortfall,
                            pvValue = it.pvValue,
                        )
                    },
                ),
            )
        }
    }
}.start(wait = false)

// endregion
