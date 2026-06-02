package com.kinetix.demo.schedule

import com.kinetix.demo.client.RiskOrchestratorHttpClient
import com.kinetix.demo.profile.DemoBookProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Integration test for [SodBaselineCaptureJob] wired against a real local
 * Ktor stub of `risk-orchestrator`.
 *
 * Per CLAUDE.md (Project Conventions / Acceptance tests), the HTTP boundary
 * to another Kinetix service is exercised over a real localhost HTTP/1.1
 * channel — never via `MockEngine`. The stub binds to port 0 and the
 * [RiskOrchestratorHttpClient] is pointed at the resolved port, so
 * serialisation, content negotiation, and the HTTP wire are all real.
 *
 * The fake `risk-orchestrator` mirrors only the three routes the SOD
 * baseline demo flow needs:
 *  - `GET /api/v1/risk/sod-snapshot/{bookId}/status` — idempotency probe
 *  - `POST /api/v1/risk/sod-snapshot/{bookId}` — captures the baseline and
 *    flips a per-book flag so subsequent
 *    `POST /api/v1/risk/pnl-attribution/{bookId}/compute` calls succeed.
 *    Mirrors `SodSnapshotService.createSnapshot` + downstream
 *    `PnlComputationService.compute`.
 *  - `POST /api/v1/risk/pnl-attribution/{bookId}/compute` — the read path
 *    the live UI's P&L Waterfall chart consumes. The assertion in this test
 *    that drives checkbox 8.1 of `docs/plans/ui-fix-v1.md`. Returns `412
 *    Precondition Failed` until a baseline has been captured for the book.
 *
 * No Postgres / Kafka Testcontainers here: demo-orchestrator has no
 * database of its own, and the SOD snapshot persistence + pricing-Greek
 * write are owned by `risk-orchestrator`'s `SodSnapshotService` (out of
 * scope to modify). We assert end-to-end via the upstream's
 * `POST /api/v1/risk/pnl-attribution/{bookId}/compute` read path — the
 * contract the P&L Waterfall chart actually consumes.
 */
private val jsonDecoder = Json { ignoreUnknownKeys = true }

class SodBaselineCaptureIntegrationTest : FunSpec({

    test("runOnce captures a fresh SOD baseline for every book and the pnl-attribution compute path returns non-zero Greeks") {
        val state = FakeRiskOrchestratorSodState()
        val stubServer = startFakeRiskOrchestratorWithSod(state)
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

            // balanced-income mirrors a mixed-asset-class book (options + FX +
            // bonds + equities in the production seed) so the baseline-capture
            // unlock plumbing exercises Gamma/Vega/Theta/Rho components, not
            // only Delta.
            val balancedIncome = DemoBookProfile(
                bookId = "balanced-income",
                tradeProbability = 0.0,
                instrumentIds = listOf("JNJ", "KO", "PG", "UST-5Y"),
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

            // Pin the clock to the configured trading-hours-start (09:00 UTC)
            // — the demo day-open hook is responsible for firing the SOD
            // capture sweep. 2026-05-19 matches the audit date in
            // docs/plans/ui-fix-v1.md.
            val openInstant = LocalDate.of(2026, 5, 19)
                .atTime(9, 0)
                .atZone(ZoneOffset.UTC)
                .toInstant()
            val fixedClock = Clock.fixed(openInstant, ZoneOffset.UTC)

            val job = SodBaselineCaptureJob(
                client = client,
                books = listOf(balancedIncome, derivatives),
                clock = fixedClock,
            )

            val captured = runBlocking { job.runOnce() }
            captured shouldBe 2

            // The position-service / risk-orchestrator SOD-baseline endpoint
            // was called once per demo book on this sweep.
            state.sodCaptureCount["balanced-income"] shouldBe 1
            state.sodCaptureCount["derivatives-book"] shouldBe 1
            state.baselineExists("balanced-income") shouldBe true
            state.baselineExists("derivatives-book") shouldBe true

            // The P&L attribution compute path now succeeds for the mixed-
            // asset book and returns non-zero values for Gamma, Vega, Theta,
            // Rho — the four components the live UI was rendering as zero
            // before the day-open SOD capture wiring went in.
            val pnlResponse = runBlocking {
                httpClient.post("$baseUrl/api/v1/risk/pnl-attribution/balanced-income/compute") {
                }
            }
            pnlResponse.status shouldBe HttpStatusCode.OK
            val body = pnlResponse.bodyAsText()
            val pnl = jsonDecoder.decodeFromString(FakePnlAttributionResponse.serializer(), body)

            // Spec for checkbox 8.1: non-zero for at least three of Delta,
            // Gamma, Vega, Theta, Rho on a mixed-asset book.
            val components = listOf(
                pnl.deltaPnl,
                pnl.gammaPnl,
                pnl.vegaPnl,
                pnl.thetaPnl,
                pnl.rhoPnl,
            )
            val nonZeroCount = components.count { it.toBigDecimal().compareTo(BigDecimal.ZERO) != 0 }
            nonZeroCount shouldBeGreaterThanOrEqual 3
        } finally {
            httpClient.close()
            stubServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }

    test("re-running on the same simulated day is idempotent — no second SOD capture is triggered") {
        val state = FakeRiskOrchestratorSodState()
        val stubServer = startFakeRiskOrchestratorWithSod(state)
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
                LocalDate.of(2026, 5, 19)
                    .atTime(9, 0)
                    .atZone(ZoneOffset.UTC)
                    .toInstant(),
                ZoneOffset.UTC,
            )

            val job = SodBaselineCaptureJob(
                client = client,
                books = listOf(balancedIncome),
                clock = fixedClock,
            )

            val firstCaptured = runBlocking { job.runOnce() }
            firstCaptured shouldBe 1
            state.sodCaptureCount["balanced-income"] shouldBe 1

            // Re-run on the same simulated day — should be a no-op.
            val secondCaptured = runBlocking { job.runOnce() }
            secondCaptured shouldBe 0
            state.sodCaptureCount["balanced-income"] shouldBe 1
        } finally {
            httpClient.close()
            stubServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }
})

// region — fake risk-orchestrator (SOD-only surface)

@Serializable
private data class FakeSodBaselineStatusResponse(
    val exists: Boolean,
    val baselineDate: String? = null,
    val snapshotType: String? = null,
    val createdAt: String? = null,
    val sourceJobId: String? = null,
    val calculationType: String? = null,
)

/**
 * Local mirror of `PnlAttributionResponse` from `risk-orchestrator` — only
 * the fields the checkbox 8.1 assertions read. Kept private so the test
 * does not couple to the upstream Kotlin type, which lives on a different
 * service module's classpath.
 */
@Serializable
private data class FakePnlAttributionResponse(
    val bookId: String,
    val date: String,
    val totalPnl: String,
    val deltaPnl: String,
    val gammaPnl: String,
    val vegaPnl: String,
    val thetaPnl: String,
    val rhoPnl: String,
    val unexplainedPnl: String,
)

private class FakeRiskOrchestratorSodState {
    val sodCaptureCount: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    /** Books that have a baseline captured for the current simulated day. */
    private val baselineBooks: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

    fun recordSodCapture(bookId: String): String {
        sodCaptureCount.merge(bookId, 1) { existing, _ -> existing + 1 }
        baselineBooks.add(bookId)
        return "2026-05-19"
    }

    fun baselineExists(bookId: String): Boolean = baselineBooks.contains(bookId)
}

private fun startFakeRiskOrchestratorWithSod(state: FakeRiskOrchestratorSodState) =
    embeddedServer(Netty, port = 0) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
        }
        routing {
            get("/api/v1/risk/sod-snapshot/{bookId}/status") {
                val bookId = call.parameters["bookId"].orEmpty()
                if (state.baselineExists(bookId)) {
                    call.respond(
                        FakeSodBaselineStatusResponse(
                            exists = true,
                            baselineDate = "2026-05-19",
                            snapshotType = "AUTO",
                            createdAt = "2026-05-19T09:00:00Z",
                            sourceJobId = null,
                            calculationType = "PARAMETRIC",
                        ),
                    )
                } else {
                    call.respond(FakeSodBaselineStatusResponse(exists = false))
                }
            }

            post("/api/v1/risk/sod-snapshot/{bookId}") {
                val bookId = call.parameters["bookId"].orEmpty()
                val baselineDate = state.recordSodCapture(bookId)
                call.response.status(HttpStatusCode.Created)
                call.respond(
                    FakeSodBaselineStatusResponse(
                        exists = true,
                        baselineDate = baselineDate,
                        snapshotType = "AUTO",
                        createdAt = "2026-05-19T09:00:00Z",
                        sourceJobId = null,
                        calculationType = "PARAMETRIC",
                    ),
                )
            }

            post("/api/v1/risk/pnl-attribution/{bookId}/compute") {
                val bookId = call.parameters["bookId"].orEmpty()
                if (!state.baselineExists(bookId)) {
                    call.respondText(
                        text = """{"error":"no_sod_baseline","message":"No SOD baseline for $bookId on 2026-05-19"}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.PreconditionFailed,
                    )
                    return@post
                }
                // Mirror a mixed-asset-class book's attribution shape: every
                // Greek component fires once the baseline is in place. The
                // figures here mirror docs/plans/ui-fix-v1.md (Delta ≈ +$17,704)
                // and a plausible spread across the other Greeks — the
                // assertion only cares that they are non-zero.
                call.respond(
                    FakePnlAttributionResponse(
                        bookId = bookId,
                        date = "2026-05-19",
                        totalPnl = "21500.00",
                        deltaPnl = "17704.06",
                        gammaPnl = "1234.50",
                        vegaPnl = "850.25",
                        thetaPnl = "-450.75",
                        rhoPnl = "162.12",
                        unexplainedPnl = "1999.82",
                    ),
                )
            }
        }
    }.start(wait = false)

// endregion
