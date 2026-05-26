package com.kinetix.demo

import com.kinetix.demo.client.RiskOrchestratorHttpClient
import com.kinetix.demo.profile.DemoBookProfiles
import com.kinetix.demo.routes.bootstrapStatusRoutes
import com.kinetix.demo.schedule.BootstrapState
import com.kinetix.demo.schedule.BootstrapStateHolder
import com.kinetix.demo.schedule.DemoVaRBootstrapJob
import com.kinetix.demo.schedule.SodBaselineCaptureJob
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * Acceptance test for [DemoVaRBootstrapJob] wired against a real local Ktor
 * stub of `risk-orchestrator` and the [BootstrapStatusRoutes] HTTP endpoint.
 *
 * Per CLAUDE.md (Project Conventions / Acceptance tests), the HTTP boundary
 * to another Kinetix service is exercised over a real localhost HTTP channel
 * — never via MockEngine. The stub binds to port 0 so the [RiskOrchestratorHttpClient]
 * is pointed at the resolved port.
 *
 * Assertions:
 *  1. Every distinct book ID from [DemoBookProfiles] appears exactly once in
 *     the recorded VaR POST calls to the stub.
 *  2. GET /demo/bootstrap-status transitions NOT_STARTED → IN_PROGRESS → READY
 *     within 30 seconds.
 *  3. The final payload reports state=READY, successCount=8, sodSuccessCount=8.
 */
class DemoVaRBootstrapAcceptanceTest : FunSpec({

    test("all 8 books VaR-posted exactly once and bootstrap-status reaches READY") {
        val varPostCalls = java.util.concurrent.ConcurrentLinkedQueue<RecordedBootstrapCall>()
        val sodStatusCalls = java.util.concurrent.ConcurrentLinkedQueue<RecordedBootstrapCall>()
        val sodCreateCalls = java.util.concurrent.ConcurrentLinkedQueue<RecordedBootstrapCall>()

        // Stub risk-orchestrator: records VaR POSTs and SOD calls, returns 200 OK
        val stubServer = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/v1/risk/var/{bookId}") {
                    val bookId = call.parameters["bookId"].orEmpty()
                    val body = call.receiveText()
                    varPostCalls.add(RecordedBootstrapCall(bookId = bookId, body = body))
                    call.respondText(
                        text = """{"jobId":"stub-job-$bookId","status":"COMPLETED"}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )
                }
                get("/api/v1/risk/sod-snapshot/{bookId}/status") {
                    val bookId = call.parameters["bookId"].orEmpty()
                    sodStatusCalls.add(RecordedBootstrapCall(bookId = bookId, body = ""))
                    call.respondText(
                        text = """{"exists":false,"bookId":"$bookId","baselineDate":null,"snapshotType":null}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )
                }
                post("/api/v1/risk/sod-snapshot/{bookId}") {
                    val bookId = call.parameters["bookId"].orEmpty()
                    val body = call.receiveText()
                    sodCreateCalls.add(RecordedBootstrapCall(bookId = bookId, body = body))
                    call.respondText(
                        text = """{"exists":true,"bookId":"$bookId","baselineDate":"2026-05-26","snapshotType":"DEMO"}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )
                }
            }
        }.start(wait = false)

        val stubPort = runBlocking { stubServer.engine.resolvedConnectors().first().port }
        val stubBaseUrl = "http://localhost:$stubPort"

        val httpClient = HttpClient(CIO) {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        // The status holder and route server that the test probes
        val stateHolder = BootstrapStateHolder()

        // Route server exposing GET /demo/bootstrap-status
        val routeServer = embeddedServer(Netty, port = 0) {
            install(ContentNegotiation) { json() }
            routing { bootstrapStatusRoutes(stateHolder) }
        }.start(wait = false)

        val routePort = runBlocking { routeServer.engine.resolvedConnectors().first().port }
        val routeBaseUrl = "http://localhost:$routePort"

        val probeClient = HttpClient(CIO)

        try {
            val riskClient = RiskOrchestratorHttpClient(
                httpClient = httpClient,
                baseUrl = stubBaseUrl,
            )
            val sodJob = SodBaselineCaptureJob(client = riskClient)
            val bootstrapJob = DemoVaRBootstrapJob(
                riskOrchestratorClient = riskClient,
                retryDelayMillis = 0L,
                sodJob = sodJob,
            )

            // Verify initial state is NOT_STARTED
            val initialResp = probeClient.get("$routeBaseUrl/demo/bootstrap-status")
            initialResp.status shouldBe HttpStatusCode.OK
            initialResp.bodyAsText() shouldContain "\"state\":\"NOT_STARTED\""

            // Transition to IN_PROGRESS before launching the job
            stateHolder.setInProgress()

            val inProgressResp = probeClient.get("$routeBaseUrl/demo/bootstrap-status")
            inProgressResp.status shouldBe HttpStatusCode.OK
            inProgressResp.bodyAsText() shouldContain "\"state\":\"IN_PROGRESS\""

            // Run the bootstrap job and update state
            val result = runBlocking {
                withTimeout(30.seconds) {
                    bootstrapJob.runOnce()
                }
            }
            stateHolder.setReady(result)

            // 1. Verify READY state
            val readyResp = probeClient.get("$routeBaseUrl/demo/bootstrap-status")
            readyResp.status shouldBe HttpStatusCode.OK
            val finalBody = readyResp.bodyAsText()
            finalBody shouldContain "\"state\":\"READY\""

            // 2. Each distinct book ID appears exactly once in VaR POSTs
            val varBookIds = varPostCalls.map { it.bookId }
            val expectedBookIds = DemoBookProfiles.all().map { it.bookId }

            withClue("VaR POSTs should contain all 8 expected book IDs") {
                varBookIds shouldContainAll expectedBookIds
            }
            for (bookId in expectedBookIds) {
                withClue("Book $bookId should appear exactly once in VaR POSTs, got: $varBookIds") {
                    varBookIds.count { it == bookId } shouldBe 1
                }
            }

            // 3. Final payload has correct counts
            val json = Json.parseToJsonElement(finalBody).jsonObject
            withClue("successCount should be 8 in: $finalBody") {
                json["successCount"]?.jsonPrimitive?.content shouldBe "8"
            }
            withClue("sodSuccessCount should be 8 in: $finalBody") {
                json["sodSuccessCount"]?.jsonPrimitive?.content shouldBe "8"
            }

            // 4. State holder reports READY
            stateHolder.get() shouldBe BootstrapState.READY
            stateHolder.getResult()!!.successCount shouldBe 8
            stateHolder.getResult()!!.sodSuccessCount shouldBe 8
        } finally {
            probeClient.close()
            httpClient.close()
            routeServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            stubServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }
})

private data class RecordedBootstrapCall(val bookId: String, val body: String)
